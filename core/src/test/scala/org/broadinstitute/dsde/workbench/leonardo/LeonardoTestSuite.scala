package org.broadinstitute.dsde.workbench.leonardo

import cats.syntax.all._
import cats.effect.concurrent.{Deferred, Semaphore}
import cats.effect.{Blocker, ContextShift, IO, Timer}
import io.chrisdavenport.log4cats.StructuredLogger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import org.broadinstitute.dsde.workbench.openTelemetry.FakeOpenTelemetryMetricsInterpreter
import org.scalatest.{Assertion, Assertions}
import fs2.Stream
import org.broadinstitute.dsde.workbench.google2.GKEModels.KubernetesClusterId
import org.scalatest.prop.Configuration
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.global
import org.scalatest.matchers.should.Matchers

trait LeonardoTestSuite extends Matchers {
  implicit val metrics = FakeOpenTelemetryMetricsInterpreter
  implicit val testTimer: Timer[IO] = IO.timer(global)
  implicit val cs: ContextShift[IO] = IO.contextShift(global)
  implicit val loggerIO: StructuredLogger[IO] = Slf4jLogger.getLogger[IO]
  implicit val appContext = AppContext.lift[IO](None).unsafeRunSync()

  val blocker = Blocker.liftExecutionContext(global)
  val semaphore = Semaphore[IO](10).unsafeRunSync()
  val nodepoolLock = KeyLock[IO, KubernetesClusterId](1 minute, 10, blocker)

  def withInfiniteStream(stream: Stream[IO, Unit], validations: IO[Assertion], maxRetry: Int = 30): IO[Assertion] = {
    val process = Stream.eval(Deferred[IO, Assertion]).flatMap { signalToStop =>
      val accumulator = Accumulator(maxRetry, None)
      val signal = Stream.unfoldEval(accumulator) { acc =>
        if (acc.maxRetry < 0)
          signalToStop
            .complete(Assertions.fail(s"time out after ${maxRetry} retries", acc.throwable.orNull))
            .as(None)
        else
          testTimer.sleep(1 seconds) >> validations.attempt.flatMap {
            case Left(e)  => IO.pure(Some(((), Accumulator(acc.maxRetry - 1, Some(e)))))
            case Right(a) => signalToStop.complete(a).as(None)
          }
      }
      val p = Stream(stream.interruptWhen(signalToStop.get.attempt.map(_.map(_ => ()))), signal)
        .parJoin(2)
      p ++ Stream.eval(signalToStop.get)
    }
    process.compile.lastOrError.map(_.asInstanceOf[Assertion])
  }
}

final case class Accumulator(maxRetry: Int, throwable: Option[Throwable])

trait PropertyBasedTesting extends ScalaCheckPropertyChecks with Configuration {
  implicit override val generatorDrivenConfig: PropertyCheckConfiguration = PropertyCheckConfiguration(
    minSuccessful = 3
  )
}
