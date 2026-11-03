package logging4s.logback

import java.time.Instant
import java.util.Collections
import java.nio.charset.StandardCharsets

import scala.collection.mutable

import org.slf4j.Marker
import ch.qos.logback.core.encoder.EncoderBase
import ch.qos.logback.classic.spi.{ILoggingEvent, ThrowableProxyUtil}

import logging4s.core.{LoggableValue, StructuredJson, ValueKey}

class Logging4sEncoder extends EncoderBase[ILoggingEvent]:

  private var fields               = Logging4sEncoder.FieldNames()
  private var includeThread        = true
  private var includeMdc           = true
  private var includeKeyValuePairs = true

  def setTimestampField(name: String): Unit  = fields = fields.copy(timestamp = name)
  def setLevelField(name: String): Unit      = fields = fields.copy(level = name)
  def setLoggerField(name: String): Unit     = fields = fields.copy(logger = name)
  def setThreadField(name: String): Unit     = fields = fields.copy(thread = name)
  def setMessageField(name: String): Unit    = fields = fields.copy(message = name)
  def setStackTraceField(name: String): Unit = fields = fields.copy(stackTrace = name)

  def setIncludeThread(include: Boolean): Unit        = includeThread = include
  def setIncludeMdc(include: Boolean): Unit           = includeMdc = include
  def setIncludeKeyValuePairs(include: Boolean): Unit = includeKeyValuePairs = include

  override def headerBytes(): Array[Byte] = Array.emptyByteArray
  override def footerBytes(): Array[Byte] = Array.emptyByteArray

  override def encode(event: ILoggingEvent): Array[Byte] =
    val names     = fields
    val values    = LoggableValue.deduplicateKeys(loggableValues(event.getMarkerList), names.keys)
    val message   = Option(event.getFormattedMessage).getOrElse("")
    val builder   = new StructuredJson.Builder(StructuredJson.capacity(message, values))
    val mdc       = if includeMdc then event.getMDCPropertyMap else Collections.emptyMap[String, String]
    val keyValues = if includeKeyValuePairs then event.getKeyValuePairs else null
    val throwable = event.getThrowableProxy

    builder.field(names.timestamp, Instant.ofEpochMilli(event.getTimeStamp).toString)
    builder.field(names.level, event.getLevel.toString)
    builder.field(names.logger, event.getLoggerName)
    if includeThread then builder.field(names.thread, event.getThreadName)
    builder.field(names.message, message)

    if !mdc.isEmpty || (keyValues != null && !keyValues.isEmpty) then
      val own   = values.iterator.map(_.key.value).toSet
      val taken = mutable.HashSet.from(names.all) ++= own

      def ambient(key: String, value: String): Unit =
        if !own.contains(key) then
          var name  = key
          var index = 2

          while !taken.add(name) do
            name = s"${key}_$index"
            index += 1

          builder.field(name, value)

      mdc.forEach((key, value) => ambient(key, value))
      if keyValues != null then keyValues.forEach(pair => ambient(pair.key, if pair.value == null then null else pair.value.toString))

    if throwable != null then builder.field(names.stackTrace, ThrowableProxyUtil.asString(throwable))

    builder.values(values)

    builder.resultWithNewline.getBytes(StandardCharsets.UTF_8)

  private def loggableValues(markers: java.util.List[Marker]): Seq[LoggableValue] =
    if markers == null || markers.isEmpty
    then Seq.empty
    else
      val collected = mutable.ArrayBuffer.empty[LoggableValue]
      markers.forEach(marker => collect(marker, collected))
      collected.toSeq

  private def collect(marker: Marker, into: mutable.ArrayBuffer[LoggableValue]): Unit =
    marker match
      case values: LoggableValuesMarker => into ++= values.values: Unit
      case _ if marker.hasReferences    => marker.iterator.forEachRemaining(ref => collect(ref, into))
      case _                            => ()

object Logging4sEncoder:

  private final case class FieldNames(
      timestamp: String = "@timestamp",
      level: String = "level",
      logger: String = "logger",
      thread: String = "thread",
      message: String = "message",
      stackTrace: String = "stack_trace",
  ):
    lazy val all: Set[String]    = Set(timestamp, level, logger, thread, message, stackTrace)
    lazy val keys: Set[ValueKey] = all.map(ValueKey(_))
