package logging4s.core.testing

import java.util.concurrent.atomic.AtomicReference

import logging4s.core.config.LoggableEncodingConfig
import logging4s.core.{Delay, Level, LogRecord, Logging, LoggableValue, LoggingContext, Position}

final case class RecordedEvent(
    level: Level,
    message: String,
    cause: Option[Throwable],
    values: Seq[LoggableValue],
    context: Seq[LoggableValue],
    fields: Seq[LoggableValue],
    position: Position,
):
  def keys: Seq[String] = fields.map(_.key.value)

  def jsonOf(key: String): Option[String] = field(key).map(_.json.value)

  def plainOf(key: String): Option[String] = field(key).map(_.plain.value)

  def valueOf(key: String): Option[String] = jsonOf(key)

  private def field(key: String): Option[LoggableValue] = fields.find(_.key.value == key)

final class RecordingLogging[F[*]: Delay] private (
    context: Seq[LoggableValue],
    threshold: Level,
    state: AtomicReference[Vector[RecordedEvent]],
)(using LoggableEncodingConfig)
    extends Logging[F]:

  override def withContext(moreContext: LoggingContext): Logging[F] =
    new RecordingLogging(LogRecord.context(context, moreContext).values, threshold, state)

  override def enabled(level: Level): Boolean = level.enabledAt(threshold)

  override def unit: F[Unit] = Delay[F].unit

  override def emit(level: Level, message: String, cause: Option[Throwable], values: Seq[LoggableValue])(using Position): F[Unit] =
    emitIn(LoggingContext.empty, level, message, cause, values)

  override def emitWith(context: LoggingContext, level: Level, message: String, cause: Option[Throwable], values: Seq[LoggableValue])(using
      Position
  ): F[Unit] =
    emitIn(context, level, message, cause, values)

  private def emitIn(ambient: LoggingContext, level: Level, message: String, cause: Option[Throwable], values: Seq[LoggableValue])(using
      position: Position
  ): F[Unit] =
    if !enabled(level) then unit
    else
      Delay[F].delay {
        val merged = if ambient.values.isEmpty then context else LogRecord.context(context, ambient).values
        val record = LogRecord.prepare(merged, values, position, LogRecord.ReservedKeys)
        val event  = RecordedEvent(level, message, cause, LoggableValue.normalizeKeys(values), merged, record.values, position)
        state.updateAndGet(_ :+ event): Unit
      }

  def recorded: Vector[RecordedEvent] = state.get

  def clear(): Unit = state.set(Vector.empty)

object RecordingLogging:

  def apply[F[*]: Delay](threshold: Level = Level.Trace)(using LoggableEncodingConfig): RecordingLogging[F] =
    new RecordingLogging(Seq.empty, threshold, new AtomicReference(Vector.empty))
