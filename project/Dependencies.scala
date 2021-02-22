import sbt._

object Dependencies {
  val scalaV = "2.13"

  val akkaV = "2.6.13"
  val akkaHttpV = "10.2.4"
  val googleV = "1.23.0"
  val automationGoogleV = "1.30.5"
  val scalaLoggingV = "3.9.2"
  val scalaTestV = "3.2.6"
  val slickV = "3.3.3"
  val http4sVersion = "0.21.20"
  val guavaV = "30.1-jre"
  val monocleV = "2.1.0"
  val opencensusV = "0.28.3"

  private val workbenchLibsHash = "244a616"
  val serviceTestV = s"0.18-$workbenchLibsHash"
  val workbenchModelV = s"0.14-$workbenchLibsHash"
  val workbenchGoogleV = s"0.21-$workbenchLibsHash"
  val workbenchGoogle2V = s"0.19-$workbenchLibsHash"
  val workbenchOpenTelemetryV = s"0.1-$workbenchLibsHash"
  val workbenchErrorReportingV = s"0.1-$workbenchLibsHash"

  val helmScalaSdkV = "0.0.1-RC5"

  val excludeAkkaHttp = ExclusionRule(organization = "com.typesafe.akka", name = s"akka-http_${scalaV}")
  val excludeAkkaStream = ExclusionRule(organization = "com.typesafe.akka", name = s"akka-stream_${scalaV}")
  val excludeAkkaHttpSprayJson = ExclusionRule(organization = "com.typesafe.akka", name = s"akka-http-spray-json_${scalaV}")
  val excludeGuavaJDK5 = ExclusionRule(organization = "com.google.guava", name = "guava-jdk5")
  val excludeGuava = ExclusionRule(organization = "com.google.guava", name = "guava")
  val excludeWorkbenchModel = ExclusionRule(organization = "org.broadinstitute.dsde.workbench", name = s"workbench-model_${scalaV}")
  val excludeWorkbenchMetrics = ExclusionRule(organization = "org.broadinstitute.dsde.workbench", name = s"workbench-metrics_${scalaV}")
  val excludeIoGrpc = ExclusionRule(organization = "io.grpc", name = "grpc-core")
  val excludeFindbugsJsr = ExclusionRule(organization = "com.google.code.findbugs", name = "jsr305")
  val excludeGson = ExclusionRule(organization = "com.google.code.gson", name = "gson")
  val excludeGoogleApiClient = ExclusionRule(organization = "com.google.api-client", name = "google-api-client")
  val excludeGoogleErrorReporting = ExclusionRule(organization = "com.google.cloud", name = "google-cloud-errorreporting")
  val excludeGoogleApiClientJackson2 = ExclusionRule(organization = "com.google.http-client", name = "google-http-client-jackson2")
  val excludeGoogleHttpClient = ExclusionRule(organization = "com.google.http-client", name = "google-http-client")
  val excludeJacksonCore = ExclusionRule(organization = "com.fasterxml.jackson.core", name = "jackson-core")
  val excludeJacksonAnnotation = ExclusionRule(organization = "com.fasterxml.jackson.core", name = "jackson-annotations")
  val excludeSlf4j = ExclusionRule(organization = "org.slf4j", name = "slf4j-api")
  val excludeLogbackCore = ExclusionRule(organization = "ch.qos.logback", name = "logback-core")
  val excludeLogbackClassic = ExclusionRule(organization = "ch.qos.logback", name = "logback-classic")
  val excludeTypesafeConfig = ExclusionRule(organization = "com.typesafe", name = "config")
  val excludeTypesafeSslConfig = ExclusionRule(organization = "com.typesafe", name = "ssl-config-core")
  val excludeGoogleError = ExclusionRule(organization = "com.google.errorprone", name = "error_prone_annotations")
  val excludeHttpComponent = ExclusionRule(organization = "org.apache.httpcomponents", name = "httpclient")
  val excludeReactiveStream = ExclusionRule(organization = "org.reactivestreams", name = "reactive-streams")
  val excludeFirestore = ExclusionRule(organization = "com.google.cloud", name = s"google-cloud-firestore")
  val excludeBouncyCastle = ExclusionRule(organization = "org.bouncycastle", name = s"bcprov-jdk15on")
  val excludeBouncyCastleExt = ExclusionRule(organization = "org.bouncycastle", name = s"bcprov-ext-jdk15on")
  val excludeSundrCodegen = ExclusionRule(organization = "io.sundr", name = s"sundr-codegen")

  val logbackClassic: ModuleID =  "ch.qos.logback"              % "logback-classic" % "1.2.3"
  val scalaLogging: ModuleID =    "com.typesafe.scala-logging"  %% "scala-logging"  % scalaLoggingV
  val swaggerUi: ModuleID =       "org.webjars"                 % "swagger-ui"      % "3.44.0"
  val ficus: ModuleID =           "com.iheart"                  %% "ficus"          % "1.5.0"
  val enumeratum: ModuleID =      "com.beachape"                %% "enumeratum"     % "1.6.1"

  val akkaSlf4j: ModuleID =         "com.typesafe.akka" %% "akka-slf4j"           % akkaV
  val akkaHttp: ModuleID =          "com.typesafe.akka" %% "akka-http"            % akkaHttpV
  val akkaHttpSprayJson: ModuleID = "com.typesafe.akka" %% "akka-http-spray-json" % akkaHttpV
  val akkaStream: ModuleID = "com.typesafe.akka" %% "akka-stream" % akkaV
  val akkaTestKit: ModuleID =       "com.typesafe.akka" %% "akka-testkit"         % akkaV     % "test"
  val akkaHttpTestKit: ModuleID =   "com.typesafe.akka" %% "akka-http-testkit"    % akkaHttpV % "test"

  val googleDataproc: ModuleID =            "com.google.apis" % "google-api-services-dataproc"    % s"v1-rev91-$googleV" excludeAll (excludeGuavaJDK5, excludeJacksonCore, excludeFindbugsJsr, excludeHttpComponent, excludeFirestore, excludeGoogleApiClient)
  val googleRpc: ModuleID =                 "io.grpc"         % "grpc-core"                       % "1.36.0" excludeAll (excludeGuava, excludeGson, excludeFindbugsJsr)
  val googleGaxGrpc: ModuleID = "com.google.api" % "gax-grpc" % "1.57.0"  excludeAll (excludeGuava, excludeFindbugsJsr, excludeGoogleApiClient, excludeGoogleApiClientJackson2, excludeGoogleHttpClient, excludeHttpComponent)
  val googleErrorReporting: ModuleID = "com.google.cloud" % "google-cloud-errorreporting" % "0.119.2-beta"

  val scalaTest: ModuleID = "org.scalatest"                 %% "scalatest"     % scalaTestV  % Test
  val scalaTestScalaCheck = "org.scalatestplus" %% "scalacheck-1-15" % s"${scalaTestV}.0" % Test // https://github.com/scalatest/scalatestplus-scalacheck
  val scalaTestMockito = "org.scalatestplus" %% "mockito-3-4" % s"${scalaTestV}.0" % Test // https://github.com/scalatest/scalatestplus-selenium
  val scalaTestSelenium =  "org.scalatestplus" %% "selenium-3-141" % s"3.2.6.0" % Test // https://github.com/scalatest/scalatestplus-selenium

  // Exclude workbench-libs transitive dependencies so we can control the library versions individually.
  // workbench-google pulls in workbench-{util, model, metrics} and workbcan ench-metrics pulls in workbench-util.
  val workbenchModel: ModuleID =        "org.broadinstitute.dsde.workbench" %% "workbench-model"    % workbenchModelV excludeAll (excludeGoogleError, excludeGuava)
  val workbenchGoogle: ModuleID =       "org.broadinstitute.dsde.workbench" %% "workbench-google"   % workbenchGoogleV excludeAll (excludeIoGrpc, excludeFindbugsJsr, excludeGoogleApiClient, excludeGoogleError, excludeHttpComponent, excludeGuava)
  val workbenchGoogle2: ModuleID =      "org.broadinstitute.dsde.workbench" %% "workbench-google2"  % workbenchGoogle2V excludeAll (
    excludeWorkbenchModel,
    excludeWorkbenchMetrics,
    excludeIoGrpc,
    excludeFindbugsJsr,
    excludeGoogleError,
    excludeHttpComponent,
    excludeFirestore,
    excludeBouncyCastle,
    excludeBouncyCastleExt,
    excludeSundrCodegen,
    excludeGuava)
  val workbenchGoogleTest: ModuleID =   "org.broadinstitute.dsde.workbench" %% "workbench-google"   % workbenchGoogleV  % "test" classifier "tests" excludeAll (excludeWorkbenchModel, excludeGuava)
  val workbenchGoogle2Test: ModuleID =  "org.broadinstitute.dsde.workbench" %% "workbench-google2"  % workbenchGoogle2V % "test" classifier "tests" excludeAll (excludeGuava) //for generators
  val workbenchOpenTelemetry: ModuleID =     "org.broadinstitute.dsde.workbench" %% "workbench-opentelemetry" % workbenchOpenTelemetryV excludeAll (excludeGuava)
  val workbenchOpenTelemetryTest: ModuleID = "org.broadinstitute.dsde.workbench" %% "workbench-opentelemetry" % workbenchOpenTelemetryV % Test classifier "tests" excludeAll (excludeGuava)
  val workbenchErrorReporting: ModuleID =      "org.broadinstitute.dsde.workbench" %% "workbench-error-reporting"  % workbenchErrorReportingV excludeAll(excludeGoogleErrorReporting)
  val workbenchErrorReportingTest: ModuleID =      "org.broadinstitute.dsde.workbench" %% "workbench-error-reporting"  % workbenchErrorReportingV % Test classifier "tests" excludeAll(excludeGoogleErrorReporting)

  val helmScalaSdk: ModuleID = "org.broadinstitute.dsp" %% "helm-scala-sdk" % helmScalaSdkV
  val helmScalaSdkTest: ModuleID = "org.broadinstitute.dsp" %% "helm-scala-sdk" % helmScalaSdkV % Test classifier "tests"

  val slick: ModuleID =           "com.typesafe.slick"  %% "slick"                % slickV excludeAll (excludeTypesafeConfig, excludeReactiveStream)
  val hikariCP: ModuleID =        "com.typesafe.slick"  %% "slick-hikaricp"       % slickV excludeAll (excludeSlf4j)
  val mysql: ModuleID =           "mysql"               % "mysql-connector-java"  % "8.0.22"
  val liquibase: ModuleID =       "org.liquibase"       % "liquibase-core"        % "4.3.1"
  val sealerate: ModuleID =       "ca.mrvisser"         %% "sealerate"            % "0.0.6"
  val googleCloudNio: ModuleID =  "com.google.cloud"    % "google-cloud-nio"      % "0.122.10" % Test // brought in for FakeStorageInterpreter

  val http4sCirce =       "org.http4s"        %% "http4s-circe"         % http4sVersion
  val circeYaml =         "io.circe"          %% "circe-yaml"           % "0.13.1"
  val http4sBlazeClient = "org.http4s"        %% "http4s-blaze-client"  % http4sVersion
  val http4sDsl =         "org.http4s"        %% "http4s-dsl"           % http4sVersion
  val guava: ModuleID =   "com.google.guava"  % "guava"                 % guavaV

  val coreDependencies = List(
    scalaTest,
    slick,
    guava,
    workbenchModel,
    workbenchGoogle2,
    workbenchGoogle2Test,
    workbenchOpenTelemetry,
    workbenchOpenTelemetryTest,
    helmScalaSdk,
    helmScalaSdkTest,
    "net.logstash.logback" % "logstash-logback-encoder" % "6.6", // for structured logging in logback
    "com.github.julien-truffaut" %%  "monocle-core"  % monocleV,
    "com.github.julien-truffaut" %%  "monocle-macro" % monocleV,
    // using provided because `http` depends on `core`, and `http`'s `opencensus-exporter-trace-stackdriver`
    // brings in an older version of `pureconfig`
    "com.github.pureconfig" %% "pureconfig" % "0.14.1" % Provided,
    sealerate,
    enumeratum,
    http4sCirce,
    circeYaml,
    http4sBlazeClient,
    http4sDsl,
    scalaTestScalaCheck
  )

  val httpDependencies = Seq(
    logbackClassic,
    scalaLogging,
    swaggerUi,
    ficus,
    enumeratum,
    akkaSlf4j,
    akkaHttp,
    akkaHttpSprayJson,
    akkaTestKit,
    akkaHttpTestKit,
    akkaStream,
    "de.heikoseeberger" %% "akka-http-circe" % "1.35.3" excludeAll(excludeAkkaHttp, excludeAkkaStream),
    googleDataproc,
    googleRpc,
    googleErrorReporting, // forcing an older versin of google-cloud-errorreporting because latest version brings in higher version of gax-grpc, which isn't compatible with other google dependencies

    hikariCP,
    workbenchGoogle,
    workbenchGoogleTest,
    workbenchErrorReporting,
    workbenchErrorReportingTest,
    "com.rms.miu" %% "slick-cats" % "0.10.4",
    googleCloudNio,
    mysql,
    liquibase,
    "com.github.sebruck" %% "opencensus-scala-akka-http" % "0.7.2",

    // Dependent on the trace exporters you want to use add one or more of the following
    "io.opencensus" % "opencensus-exporter-trace-stackdriver" % opencensusV,
    "org.http4s" %% "http4s-blaze-server" % http4sVersion % Test,
    scalaTestSelenium,
    scalaTestMockito
  )

  val workbenchServiceTest: ModuleID = "org.broadinstitute.dsde.workbench" %% "workbench-service-test" % serviceTestV % "test" classifier "tests" excludeAll (excludeGuava)

  val automationDependencies = List(
    "com.fasterxml.jackson.module" %% "jackson-module-scala"   % "2.12.2" % "test",
    "ch.qos.logback" % "logback-classic" % "1.2.3" % "test",

    "com.typesafe.akka" %% "akka-http-core" % akkaHttpV,
    "com.typesafe.akka" %% "akka-stream-testkit" % akkaV % "test",
    "com.typesafe.akka" %% "akka-http" % akkaHttpV,
    "com.typesafe.akka" %% "akka-testkit" % akkaV % "test",
    "com.typesafe.akka" %% "akka-slf4j" % akkaV,
    "com.typesafe.scala-logging" %% "scala-logging" % scalaLoggingV,
    googleRpc,
    workbenchGoogle,
    workbenchGoogle2,
    workbenchServiceTest,
    googleCloudNio,
    akkaHttpSprayJson,
    scalaTest,
    scalaTestSelenium,
    scalaTestMockito
  )
}
