package org.broadinstitute.dsde.workbench.leonardo.monitor

import akka.actor.{ActorRef, ActorSystem, Terminated}
import akka.testkit.TestKit
import cats.effect.IO
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.leonardo.CommonTestData._
import org.broadinstitute.dsde.workbench.leonardo.dao._
import org.broadinstitute.dsde.workbench.leonardo.db.{DbReference, TestComponent}
import org.broadinstitute.dsde.workbench.leonardo.{GcsPathUtils, RuntimeContainerServiceType, RuntimeStatus}
import org.broadinstitute.dsde.workbench.openTelemetry.OpenTelemetryMetrics
import cats.syntax.all._
import org.broadinstitute.dsde.workbench.leonardo.config.Config.clusterToolMonitorConfig
import org.mockito.ArgumentMatchers
import org.mockito.Mockito._
import org.scalatest.concurrent.Eventually.eventually
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.Try
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.time.{Seconds, Span}

//TODO: running this spec results in lots of match `scala.MatchError: null`, investigate why that is
class ClusterToolMonitorSpec
    extends TestKit(ActorSystem("leonardotest"))
    with AnyFlatSpecLike
    with LazyLogging
    with BeforeAndAfterAll
    with ScalaFutures
    with TestComponent
    with GcsPathUtils
    with MockitoSugar { testKit =>

  implicit override val patienceConfig = PatienceConfig(timeout = scaled(Span(30, Seconds)))

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
    super.afterAll()
  }

  val welderEnabledCluster = makeCluster(1).copy(status = RuntimeStatus.Running,
                                                 welderEnabled = true,
                                                 runtimeImages = Set(jupyterImage, welderImage))
  val welderDisabledCluster =
    makeCluster(2).copy(status = RuntimeStatus.Running, welderEnabled = false, runtimeImages = Set(jupyterImage))
  val notRunningCluster = makeCluster(3).copy(status = RuntimeStatus.Deleted,
                                              welderEnabled = true,
                                              runtimeImages = Set(jupyterImage, welderImage))
  val rstudioCluster = makeCluster(4).copy(status = RuntimeStatus.Running,
                                           welderEnabled = true,
                                           runtimeImages = Set(rstudioImage, welderImage))

  it should "report all services are up normally" in isolatedDbTest { implicit dbRef =>
    welderEnabledCluster.save()
    welderDisabledCluster.save()
    notRunningCluster.save()
    rstudioCluster.save()

    withServiceActor() { (_, mockNewRelic) =>
      Thread.sleep(clusterToolMonitorConfig.pollPeriod.toMillis * 3)
      RuntimeContainerServiceType.values.foreach { service =>
        verify(mockNewRelic, never()).incrementCounter(service.toString + "Down", 1)
      }
    }
  }

  it should "report services are down for a Jupyter image" in isolatedDbTest { implicit dbRef =>
    welderEnabledCluster.save()

    withServiceActor(welderDAO = new MockWelderDAO(false), jupyterDAO = new MockJupyterDAO(false)) { (_, metrics) =>
      eventually(timeout(clusterToolMonitorConfig.pollPeriod * 4)) {
        //the second parameter is needed because of something scala does under the covers that mockito does not like to handle the fact we omit the predefined param count from our incrementCounterIO call.
        //explicitly specifying the count in the incrementCounterIO in the monitor itself does not fix this
        verify(metrics, times(3)).incrementCounter(ArgumentMatchers.eq("JupyterServiceDown"),
                                                   ArgumentMatchers.anyLong(),
                                                   ArgumentMatchers.any[Map[String, String]])
        verify(metrics, times(3)).incrementCounter(ArgumentMatchers.eq("WelderServiceDown"),
                                                   ArgumentMatchers.anyLong(),
                                                   ArgumentMatchers.any[Map[String, String]])
        verify(metrics, never()).incrementCounter(ArgumentMatchers.eq("RStudioServiceDown"),
                                                  ArgumentMatchers.anyLong(),
                                                  ArgumentMatchers.any[Map[String, String]])
      }
    }
  }

  it should "report services are down for a RStudio image" in isolatedDbTest { implicit dbRef =>
    rstudioCluster.save()

    withServiceActor(welderDAO = new MockWelderDAO(false), rstudioDAO = new MockRStudioDAO(false)) {
      (_, mockNewRelic) =>
        eventually(timeout(clusterToolMonitorConfig.pollPeriod * 4)) {
          //the second parameter is needed because of something scala does under the covers that mockito does not like to handle the fact we omit the predefined param count from our incrementCounterIO call.
          //explicitly specifying the count in the incrementCounterIO in the monitor itself does not fix this
          verify(mockNewRelic, times(3)).incrementCounter(ArgumentMatchers.eq("RStudioServiceDown"),
                                                          ArgumentMatchers.anyLong(),
                                                          ArgumentMatchers.any[Map[String, String]])
          verify(mockNewRelic, times(3)).incrementCounter(ArgumentMatchers.eq("WelderServiceDown"),
                                                          ArgumentMatchers.anyLong(),
                                                          ArgumentMatchers.any[Map[String, String]])
          verify(mockNewRelic, never()).incrementCounter(ArgumentMatchers.eq("JupyterServiceDown"),
                                                         ArgumentMatchers.anyLong(),
                                                         ArgumentMatchers.any[Map[String, String]])
        }
    }
  }

  it should "report welder as OK when it is disabled while jupyter is down" in isolatedDbTest { implicit dbRef =>
    welderDisabledCluster.save()

    withServiceActor(welderDAO = new MockWelderDAO(false), jupyterDAO = new MockJupyterDAO(false)) {
      (_, mockNewRelic) =>
        eventually(timeout(clusterToolMonitorConfig.pollPeriod * 4)) {
          verify(mockNewRelic, times(3)).incrementCounter(ArgumentMatchers.eq("JupyterServiceDown"),
                                                          ArgumentMatchers.anyLong(),
                                                          ArgumentMatchers.any[Map[String, String]])
        }

        val res = testTimer.sleep(clusterToolMonitorConfig.pollPeriod) >> IO(
          verify(mockNewRelic, never()).incrementCounter(ArgumentMatchers.eq("WelderServiceDown"),
                                                         ArgumentMatchers.anyLong(),
                                                         ArgumentMatchers.any[Map[String, String]])
        )
        res.unsafeRunSync
    }
  }

  it should "not check a non-active cluster" in isolatedDbTest { implicit dbRef =>
    notRunningCluster.save()

    withServiceActor(welderDAO = new MockWelderDAO(false), jupyterDAO = new MockJupyterDAO(false)) {
      (_, mockNewRelic) =>
        Thread.sleep(clusterToolMonitorConfig.pollPeriod.toMillis * 3)
        verify(mockNewRelic, never()).incrementCounter(ArgumentMatchers.eq("WelderServiceDown"),
                                                       ArgumentMatchers.anyLong(),
                                                       ArgumentMatchers.any[Map[String, String]])
        verify(mockNewRelic, never()).incrementCounter(ArgumentMatchers.eq("JupyterServiceDown"),
                                                       ArgumentMatchers.anyLong(),
                                                       ArgumentMatchers.any[Map[String, String]])
    }
  }

  private def withServiceActor[T](
    metrics: OpenTelemetryMetrics[IO] = mock[OpenTelemetryMetrics[IO]],
    welderDAO: WelderDAO[IO] = new MockWelderDAO(),
    jupyterDAO: JupyterDAO[IO] = new MockJupyterDAO(),
    rstudioDAO: RStudioDAO[IO] = new MockRStudioDAO()
  )(testCode: (ActorRef, OpenTelemetryMetrics[IO]) => T)(implicit dbRef: DbReference[IO]): T = {
    implicit def clusterToolToToolDao = ToolDAO.clusterToolToToolDao(jupyterDAO, welderDAO, rstudioDAO)

    val actor = system.actorOf(
      ClusterToolMonitor.props(clusterToolMonitorConfig, dbRef, metrics)
    )
    val testResult = Try(testCode(actor, metrics))

    // shut down the actor and wait for it to terminate
    testKit watch actor
    system.stop(actor)
    expectMsgClass(5 seconds, classOf[Terminated])
    testResult.get
  }
}
