package org.broadinstitute.dsde.workbench.leonardo.db

import java.time.Instant

import cats.effect.concurrent.Semaphore
import cats.effect.{IO, Resource}
import cats.syntax.all._
import org.broadinstitute.dsde.workbench.leonardo.config.{Config, LiquibaseConfig}
import org.broadinstitute.dsde.workbench.leonardo.{
  App,
  CommonTestData,
  DefaultNodepool,
  GcsPathUtils,
  KubernetesCluster,
  LeonardoTestSuite,
  Nodepool,
  PersistentDisk,
  Runtime,
  RuntimeConfig,
  RuntimeName
}
import org.broadinstitute.dsde.workbench.model.google.{GoogleProject, ServiceAccountKeyId}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Seconds, Span}
import org.scalatest.{BeforeAndAfterAll, TestSuite}
import slick.basic.DatabaseConfig
import slick.dbio.DBIO
import slick.jdbc.JdbcProfile

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

trait TestComponent extends LeonardoTestSuite with ScalaFutures with GcsPathUtils with BeforeAndAfterAll {

  this: TestSuite =>
  implicit override val patienceConfig = PatienceConfig(timeout = scaled(Span(30, Seconds)))

  val defaultServiceAccountKeyId = ServiceAccountKeyId("123")

  val initWithLiquibaseProp = "initLiquibase"
  val liquiBaseConfig =
    LiquibaseConfig("org/broadinstitute/dsde/workbench/leonardo/liquibase/changelog.xml", true)

  // Not using beforeAll because the dbRef is needed before beforeAll is called
  implicit protected lazy val testDbRef: DbRef[IO] = initDbRef.unsafeRunSync()

  override def afterAll(): Unit = {
    testDbRef.close()
    super.afterAll()
  }

  // This is a bit duplicative of DbReference.init but that method returns a Resource[F, DbRef[F]]
  // which doesn't play nicely with ScalaTest BeforeAndAfterAll. This version returns an F[DbRef[F]].
  private def initDbRef: IO[DbRef[IO]] =
    for {
      concurrentPermits <- Semaphore[IO](Config.dbConcurrency)
      dbConfig <- IO(
        DatabaseConfig.forConfig[JdbcProfile]("mysql", Config.config)
      )
      db <- IO(dbConfig.db)
      // init with liquibase if we haven't done it yet
      _ <- if (sys.props.get(initWithLiquibaseProp).isEmpty)
        Resource
          .make(IO(db.source.createConnection()))(conn => IO(conn.close()))
          .use(conn => IO(DbReference.initWithLiquibase(conn, liquiBaseConfig))) >> IO(
          sys.props.put(initWithLiquibaseProp, "done")
        )
      else IO.unit
    } yield new DbRef[IO](dbConfig, db, concurrentPermits, blocker)

  def dbFutureValue[T](f: DBIO[T]): T = testDbRef.inTransaction(f).timeout(30 seconds).unsafeRunSync()
  def dbFailure[T](f: DBIO[T]): Throwable =
    testDbRef.inTransaction(f).attempt.timeout(30 seconds).unsafeRunSync().swap.toOption.get

  // clean up after tests
  def isolatedDbTest[T](testCode: => T): T =
    try {
      dbFutureValue(testDbRef.dataAccess.truncateAll)
      testCode
    } catch {
      case t: Throwable => t.printStackTrace(); throw t
    } finally {
      dbFutureValue(testDbRef.dataAccess.truncateAll)
    }

  protected def getClusterId(getClusterIdRequest: GetClusterKey): Long =
    getClusterId(getClusterIdRequest.googleProject, getClusterIdRequest.clusterName, getClusterIdRequest.destroyedDate)

  protected def getClusterId(googleProject: GoogleProject,
                             clusterName: RuntimeName,
                             destroyedDateOpt: Option[Instant]): Long =
    dbFutureValue(clusterQuery.getIdByUniqueKey(googleProject, clusterName, destroyedDateOpt)).get

  implicit class ClusterExtensions(cluster: Runtime) {
    def save(serviceAccountKeyId: Option[ServiceAccountKeyId] = Some(defaultServiceAccountKeyId)): Runtime =
      dbFutureValue {
        clusterQuery.save(
          SaveCluster(
            cluster,
            Some(gcsPath("gs://bucket" + cluster.runtimeName.asString.takeRight(1))),
            serviceAccountKeyId,
            CommonTestData.defaultDataprocRuntimeConfig,
            Instant.now
          )
        )
      }

    def saveWithRuntimeConfig(
      runtimeConfig: RuntimeConfig,
      serviceAccountKeyId: Option[ServiceAccountKeyId] = Some(defaultServiceAccountKeyId)
    ): Runtime =
      dbFutureValue {
        clusterQuery.save(
          SaveCluster(cluster,
                      Some(gcsPath("gs://bucket" + cluster.runtimeName.asString.takeRight(1))),
                      serviceAccountKeyId,
                      runtimeConfig,
                      Instant.now)
        )
      }
  }

  implicit class DiskExtensions(disk: PersistentDisk) {
    def save(): IO[PersistentDisk] = testDbRef.inTransaction(persistentDiskQuery.save(disk))
  }

  implicit class KubernetesClusterExtensions(c: KubernetesCluster) {
    def save(): KubernetesCluster =
      dbFutureValue {
        kubernetesClusterQuery.save(
          SaveKubernetesCluster(
            c.googleProject,
            c.clusterName,
            c.location,
            c.region,
            c.status,
            c.ingressChart,
            c.auditInfo,
            DefaultNodepool.fromNodepool(
              c.nodepools.headOption
                .getOrElse(throw new Exception("test clusters to be saved must have at least 1 nodepool"))
            )
          )
        )
      }
  }

  implicit class NodepoolExtension(n: Nodepool) {
    def save(): Nodepool =
      dbFutureValue {
        nodepoolQuery.saveForCluster(n)
      }
  }

  implicit class AppExtension(a: App) {
    def save(): App =
      dbFutureValue {
        appQuery.save(
          SaveApp(a)
        )
      }
  }

}
