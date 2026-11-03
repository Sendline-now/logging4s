package logging4s.logback

import java.io.ByteArrayOutputStream

import scala.util.Try

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers

import org.slf4j.LoggerFactory
import ch.qos.logback.core.encoder.Encoder
import ch.qos.logback.core.read.ListAppender
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.OutputStreamAppender
import net.logstash.logback.encoder.LogstashEncoder
import ch.qos.logback.classic.{Level, Logger as LogbackLogger}
import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}

import logging4s.core.syntax.all.*
import logging4s.core.{JsonString, Logging, LoggableValue, PlainString, ValueKey}

import scala.jdk.CollectionConverters.given

import LogbackInstances.given

class LoggableValuesMarkerSpec extends AnyWordSpec, Matchers:
  import LoggableValuesMarkerSpec.*

  LogbackWarmup.touch()

  private val mapper = new ObjectMapper()

  private def attach(logger: LogbackLogger, encoder: Encoder[ILoggingEvent]): (OutputStreamAppender[ILoggingEvent], ByteArrayOutputStream) =
    val out      = new ByteArrayOutputStream()
    val appender = new OutputStreamAppender[ILoggingEvent]()

    encoder.setContext(logger.getLoggerContext)
    encoder.start()
    appender.setContext(logger.getLoggerContext)
    appender.setEncoder(encoder)
    appender.setOutputStream(out)
    appender.start()
    logger.addAppender(appender)

    (appender, out)

  private def captureBoth(loggerName: String)(run: Logging[Try] => Unit): (JsonNode, JsonNode) =
    LoggingLogbackJsonSpec.appenderLock.synchronized {
      val logbackLogger = LoggerFactory.getLogger(loggerName).asInstanceOf[LogbackLogger]
      logbackLogger.setLevel(Level.TRACE)
      logbackLogger.setAdditive(false)

      val (logstash, logstashOut) = attach(logbackLogger, new LogstashEncoder())
      val (own, ownOut)           = attach(logbackLogger, new Logging4sEncoder())

      try run(Logging.createTry(loggerName).get)
      finally
        List(logstash, own).foreach { appender =>
          appender.stop()
          logbackLogger.detachAppender(appender): Unit
        }

      (mapper.readTree(logstashOut.toString("UTF-8")), mapper.readTree(ownOut.toString("UTF-8")))
    }

  private def captureEvent(loggerName: String)(run: Logging[Try] => Unit): ILoggingEvent =
    LoggingLogbackJsonSpec.appenderLock.synchronized {
      val logbackLogger = LoggerFactory.getLogger(loggerName).asInstanceOf[LogbackLogger]
      val appender      = new ListAppender[ILoggingEvent]()

      logbackLogger.setLevel(Level.TRACE)
      appender.setContext(logbackLogger.getLoggerContext)
      appender.start()
      logbackLogger.addAppender(appender)

      try run(Logging.createTry(loggerName).get)
      finally logbackLogger.detachAppender(appender): Unit

      appender.list.asScala.last
    }

  private def fields(json: JsonNode, envelope: Set[String]): Map[String, JsonNode] =
    json.fieldNames.asScala.filterNot(envelope).map(name => name -> json.get(name)).toMap

  "The values marker" must:
    "give LogstashEncoder and Logging4sEncoder the same fields for one event" in:
      val user = LoggableValue(ValueKey("user"), PlainString("id=1, name=John"), JsonString("""{"id":1,"name":"John"}"""))

      val (logstash, own) = captureBoth("LoggableValuesMarkerSpec-parity") { logging =>
        logging
          .withContextValues("abc".asLogValue("session"))
          .error(
            "parity",
            new IllegalStateException("boom"),
            user,
            List(1, 2).asLogValue("ids"),
            "line1\n\"q\" C:\\x".asLogValue("text"),
            Nickname(1, None).asLogValue,
          ): Unit
      }

      fields(own, OwnEnvelope).keySet shouldEqual Set("session", "user", "ids", "text", "nickname", "source")
      fields(logstash, LogstashEnvelope) shouldEqual fields(own, OwnEnvelope)
      logstash.get("message") shouldEqual own.get("message")
      logstash.get("stack_trace").asText() should include("boom")
      own.get("stack_trace").asText() should include("boom")

    "be the only marker of a record, with no references to walk" in:
      val event = captureEvent("LoggableValuesMarkerSpec-single") { logging =>
        logging.info("one marker", 1.asLogValue("a"), "x".asLogValue("b")): Unit
      }

      val markers = event.getMarkerList.asScala.toList

      markers.map(_.getClass) shouldEqual List(classOf[LoggableValuesMarker])
      markers.head.hasReferences shouldEqual false

    "render as key=json pairs for pattern layouts" in:
      val values = Seq(
        LoggableValue(ValueKey("a"), PlainString("1"), JsonString("1")),
        LoggableValue(ValueKey("user"), PlainString("John"), JsonString("\"John\"")),
      )

      LoggableValuesMarker(values).toString shouldEqual "a=1, user=\"John\""

object LoggableValuesMarkerSpec:
  private val OwnEnvelope      = Set("@timestamp", "level", "logger", "thread", "message", "stack_trace")
  private val LogstashEnvelope = Set("@timestamp", "@version", "message", "logger_name", "thread_name", "level", "level_value", "stack_trace")
