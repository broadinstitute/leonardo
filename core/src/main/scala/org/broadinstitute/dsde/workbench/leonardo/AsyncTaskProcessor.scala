package org.broadinstitute.dsde.workbench.leonardo

import java.time.Instant
import cats.effect.{Concurrent, Timer}
import cats.syntax.all._
import fs2.Stream
import fs2.concurrent.InspectableQueue
import io.chrisdavenport.log4cats.StructuredLogger
import org.broadinstitute.dsde.workbench.leonardo.AsyncTaskProcessor._
import org.broadinstitute.dsde.workbench.model.TraceId
import org.broadinstitute.dsde.workbench.openTelemetry.OpenTelemetryMetrics

import scala.concurrent.duration._

final class AsyncTaskProcessor[F[_]: Timer](config: AsyncTaskProcessor.Config, asyncTasks: InspectableQueue[F, Task[F]])(
  implicit logger: StructuredLogger[F],
  F: Concurrent[F],
  metrics: OpenTelemetryMetrics[F]
) {
  def process: Stream[F, Unit] = {
    val asyncTaskStream = asyncTasks
      .dequeueChunk(config.maxConcurrentTasks)
      .parEvalMapUnordered(config.maxConcurrentTasks)(task => handler(task))

    Stream(asyncTaskStream, recordCurrentNumOfTasks).covary[F].parJoin(2)
  }

  private def handler(task: Task[F]): F[Unit] =
    for {
      now <- nowInstant[F]
      latency = (now.toEpochMilli - task.enqueuedTime.toEpochMilli).millis
      _ <- recordLatency(latency)
      _ <- logger.info(Map("traceId" -> task.traceId.asString))(
        s"Executing task with latency of ${latency.toSeconds} seconds"
      )
      _ <- task.op.handleErrorWith {
        case err =>
          task.errorHandler.traverse(cb => cb(err)) >> logger.error(err)(
            s"${task.traceId.asString} | Error when executing async task"
          )
      }
    } yield ()

  private def recordCurrentNumOfTasks: Stream[F, Unit] = {
    val record = for {
      size <- asyncTasks.size
      _ <- Stream.eval(metrics.gauge("asyncTasksCount", size))
    } yield ()

    (record ++ Stream.sleep_(30 seconds)).repeat
  }

  // record the latency between message being enqueued and task gets executed
  private def recordLatency(latency: FiniteDuration): F[Unit] =
    metrics.recordDuration("asyncTaskLatency",
                           latency,
                           List(
                             10 seconds,
                             1 minutes,
                             2 minutes,
                             4 minutes,
                             8 minutes,
                             16 minutes,
                             32 minutes
                           ))
}

object AsyncTaskProcessor {
  def apply[F[_]: Timer](
    config: Config,
    asyncTasks: InspectableQueue[F, Task[F]]
  )(implicit logger: StructuredLogger[F], F: Concurrent[F], metrics: OpenTelemetryMetrics[F]): AsyncTaskProcessor[F] =
    new AsyncTaskProcessor(config, asyncTasks)

  final case class Task[F[_]](traceId: TraceId,
                              op: F[Unit],
                              errorHandler: Option[Throwable => F[Unit]] = None,
                              enqueuedTime: Instant)
  final case class Config(queueBound: Int, maxConcurrentTasks: Int)
}
