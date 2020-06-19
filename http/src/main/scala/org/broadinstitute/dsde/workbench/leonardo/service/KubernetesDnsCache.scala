package org.broadinstitute.dsde.workbench.leonardo.service

import java.io.{ByteArrayInputStream, InputStream}
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.UUID
import java.util.concurrent.TimeUnit

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.{ConnectionContext, HttpsConnectionContext}
import akka.http.scaladsl.model.Uri.Host
import cats.effect.implicits._
import cats.effect.{Async, Blocker, ContextShift, Effect}
import com.google.common.cache.{CacheBuilder, CacheLoader, CacheStats}
import com.typesafe.scalalogging.LazyLogging
import javax.net.ssl.{KeyManagerFactory, SSLContext, TrustManagerFactory}
import org.broadinstitute.dsde.workbench.google2.GKEModels.KubernetesClusterId
import org.broadinstitute.dsde.workbench.google2.KubernetesModels.{KubernetesApiServerIp, KubernetesClusterCaCert}
import org.broadinstitute.dsde.workbench.google2.KubernetesSerializableName.ServiceName
import org.broadinstitute.dsde.workbench.leonardo.AppName
import org.broadinstitute.dsde.workbench.leonardo.config.CacheConfig
import org.broadinstitute.dsde.workbench.leonardo.db.{DbReference, GetAppResult, KubernetesServiceDbQueries}
import org.broadinstitute.dsde.workbench.leonardo.service.KubernetesDnsCache.{AppStatusCacheKey, _}
import org.broadinstitute.dsde.workbench.model.google.GoogleProject
import cats.implicits._
import org.broadinstitute.dsde.workbench.google2.{GKEService, autoClosableResourceF}
import org.broadinstitute.dsde.workbench.leonardo.model.LeoException

import scala.concurrent.ExecutionContext

object KubernetesDnsCache {
  sealed trait AppHostStatus
  case object HostAppNotFound extends AppHostStatus
  case object HostServiceNotFound extends AppHostStatus
  case object HostAppNotReady extends AppHostStatus
  case class HostAppReady(hostname: Host, sslContext: HttpsConnectionContext) extends AppHostStatus
  case class AppStatusCacheKey(googleProject: GoogleProject, appName: AppName, serviceName: ServiceName)
  case class ClusterSSLContextCacheKey(kubernetesClusterId: KubernetesClusterId, appKey: AppStatusCacheKey)
}

class KubernetesDnsCache[F[_]: Effect: ContextShift](kubernetesProxyConfig: CacheConfig,
                                                  blocker: Blocker,
                                                     gkeService: GKEService[F])(implicit ec: ExecutionContext, dbRef: DbReference[F], F: Async[F])
  extends LazyLogging {

  def getHostStatus(key: AppStatusCacheKey): F[AppHostStatus] =
    blocker.blockOn(Effect[F].delay(projectAppToHostStatus.get(key)))
  def size: Long = projectAppToHostStatus.size
  def stats: CacheStats = projectAppToHostStatus.stats

  private val projectAppToHostStatus = CacheBuilder
    .newBuilder()
    .expireAfterWrite(kubernetesProxyConfig.cacheExpiryTime.toSeconds, TimeUnit.SECONDS)
    .maximumSize(kubernetesProxyConfig.cacheMaxSize)
    .recordStats
    .build(
      new CacheLoader[AppStatusCacheKey, AppHostStatus] {
        def load(key: AppStatusCacheKey): AppHostStatus = {
          logger.debug(s"DNS Cache miss for ${key.googleProject} / ${key.appName} / ${key.serviceName} ...loading from DB...")
          val getApp = dbRef
            .inTransaction {
              KubernetesServiceDbQueries.getActiveFullAppByName(key.googleProject, key.appName)
            }
            .toIO
            .unsafeRunSync()



          getApp match {
            case Some(app) => {
              hostStatusByAppResult(app, key.serviceName)
            }
            case None =>
              HostAppNotFound
          }
        }
      }
    )

  private val clusterSSLContextCache =
    CacheBuilder
      .newBuilder()
      .expireAfterWrite(kubernetesProxyConfig.cacheExpiryTime.toSeconds, TimeUnit.SECONDS)
      .maximumSize(kubernetesProxyConfig.cacheMaxSize)
      .recordStats
      .build(
        new CacheLoader[ClusterSSLContextCacheKey, HttpsConnectionContext] {
          def load(key: ClusterSSLContextCacheKey): HttpsConnectionContext = {
            logger.debug(s"DNS Cache miss for ${key.kubernetesClusterId.toString}. Loading SSLContext with a google call...")
            performCacheLoad(key)
              .toIO
              .unsafeRunSync
          }
        }
      )

  def performCacheLoad(key: ClusterSSLContextCacheKey): F[HttpsConnectionContext] = {
    for {
      clusterOpt <- gkeService.getCluster(key.kubernetesClusterId)
      cluster <- F.fromOption(clusterOpt, AppIsReadyButClusterNotFound(key.kubernetesClusterId, key.appKey))
      cert = KubernetesClusterCaCert(cluster.getMasterAuth.getClusterCaCertificate)
      cert <- F.fromEither(cert.base64Cert)
      certResource = autoClosableResourceF(new ByteArrayInputStream(cert))
      sslContext <- certResource.use { certStream =>
          getSSLContext(certStream)
      }
    } yield sslContext
  }

  case class AppIsReadyButClusterNotFound(kubernetesClusterId:  KubernetesClusterId, appKey: AppStatusCacheKey) extends LeoException(
    s"App ${appKey.googleProject}/${appKey.appName}/${appKey.serviceName} had a ready status in the database, but cluster ${kubernetesClusterId.toString} was not found in google.",
    StatusCodes.InternalServerError
  )

  def getSSLContext(sslCaCert: InputStream): F[HttpsConnectionContext] = {
    //TODO ??
    val password = null
    // see https://stackoverflow.com/questions/889406/using-multiple-ssl-client-certificates-in-java-with-the-same-host
    for {
      certificateFactory <- F.delay(CertificateFactory.getInstance("X.509"))
      cert <- F.delay(certificateFactory.generateCertificate(sslCaCert))

      keyStore <- F.delay(KeyStore.getInstance(KeyStore.getDefaultType())) //TODO: PKCS12?
      _ <- F.delay(keyStore.load(null, password))
      alias = "ca" + UUID.randomUUID()
      _ <- F.delay(keyStore.setCertificateEntry(alias, cert))

      trustManagerFactory <- F.delay(TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm))
      _ <- F.delay(trustManagerFactory.init(keyStore))

      keyManagerFactory <- F.delay(KeyManagerFactory.getInstance("SunX509"))
      _ <- F.delay(keyManagerFactory.init(keyStore, password))

      sslContext <- F.delay(SSLContext.getInstance("TLS"))
      _ <- F.delay(sslContext.init(keyManagerFactory.getKeyManagers, trustManagerFactory.getTrustManagers, null))
    } yield ConnectionContext.https(sslContext)
  }

  private def readyAppToHost(appResult: GetAppResult, ip: KubernetesApiServerIp, key: AppStatusCacheKey): AppHostStatus =
    appResult.app.getInternalProxyUrls(ip).get(
      key.serviceName
    ).fold[AppHostStatus](HostServiceNotFound)(url => {
      val sslContext = clusterSSLContextCache.get(ClusterSSLContextCacheKey(appResult.cluster.getGkeClusterId, key))
    HostAppReady(
      Host(url.getPath),
      sslContext
    )
    }
    )

  private def hostStatusByAppResult(appResult: GetAppResult, key: AppStatusCacheKey): AppHostStatus =
    appResult.cluster.asyncFields.map(_.apiServerIp)
      .fold[AppHostStatus](HostAppNotReady)(ip =>
        readyAppToHost(appResult, ip, key)
    )

}
