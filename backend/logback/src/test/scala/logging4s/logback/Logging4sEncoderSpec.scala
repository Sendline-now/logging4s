package logging4s.logback

import java.time.Instant
import java.io.ByteArrayOutputStream
import java.time.temporal.ChronoUnit

import scala.util.Try

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers

import org.slf4j.{LoggerFactory, MDC}
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.OutputStreamAppender
import net.logstash.logback.marker.{LogstashMarker, Markers}
import ch.qos.logback.classic.{Level, Logger as LogbackLogger}
import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}

import logging4s.core.conformance.JsonRecord
import logging4s.core.{JsonString, Logging, LoggableValue, PlainString, ValueKey}

import LogbackInstances.given

class Logging4sEncoderSpec extends AnyWordSpec, Matchers:

  LogbackWarmup.touch()

  private val mapper = new ObjectMapper()

  private def capture(loggerName: String)(run: Logging[Try] => Unit): JsonNode =
    mapper.readTree(captureLine(loggerName)(run))

  private def captureLine(loggerName: String)(run: Logging[Try] => Unit): String =
    captureDirect(loggerName)(_ => run(Logging.createTry(loggerName).get))

  private def captureDirect(loggerName: String)(run: LogbackLogger => Unit): String =
    LoggingLogbackJsonSpec.appenderLock.synchronized {
      val logbackLogger = LoggerFactory.getLogger(loggerName).asInstanceOf[LogbackLogger]
      logbackLogger.setLevel(Level.TRACE)

      val context = logbackLogger.getLoggerContext
      val out     = new ByteArrayOutputStream()

      val encoder = new Logging4sEncoder()
      encoder.setContext(context)
      encoder.start()

      val appender = new OutputStreamAppender[ILoggingEvent]()
      appender.setContext(context)
      appender.setEncoder(encoder)
      appender.setOutputStream(out)
      appender.start()

      logbackLogger.addAppender(appender)
      try run(logbackLogger)
      finally
        appender.stop()
        logbackLogger.detachAppender(appender)

      out.toString("UTF-8")
    }

  private def withMdc[A](entries: (String, String)*)(run: => A): A =
    entries.foreach((key, value) => MDC.put(key, value))
    try run
    finally entries.foreach((key, _) => MDC.remove(key))

  "Logging4sEncoder" must:
    "let a logging4s value win over an MDC entry with the same key, so the line carries that key once" in:
      val traceId = LoggableValue(ValueKey("trace_id"), PlainString("from-value"), JsonString("\"from-value\""))

      val record = JsonRecord.parse(captureLine("Logging4sEncoderSpec-mdc-own") { logging =>
        withMdc("trace_id" -> "from-mdc")(logging.info("handled", traceId))
      })

      record.count("trace_id") shouldEqual 1
      record("trace_id") shouldEqual Some("\"from-value\"")

    "suffix an MDC entry named after an envelope field instead of emitting that field twice" in:
      val record = JsonRecord.parse(captureLine("Logging4sEncoderSpec-mdc-envelope") { logging =>
        withMdc("level" -> "custom", "message" -> "shadow")(logging.info("handled"))
      })

      record.count("level") shouldEqual 1
      record.count("message") shouldEqual 1
      record("level_2") shouldEqual Some("\"custom\"")
      record("message_2") shouldEqual Some("\"shadow\"")
      record.message shouldEqual "handled"

    "skip a suffix that a logging4s value already uses" in:
      val levelTwo = LoggableValue(ValueKey("level_2"), PlainString("mine"), JsonString("\"mine\""))

      val record = JsonRecord.parse(captureLine("Logging4sEncoderSpec-mdc-suffix") { logging =>
        withMdc("level" -> "custom")(logging.info("handled", levelTwo))
      })

      record.count("level_2") shouldEqual 1
      record("level_2") shouldEqual Some("\"mine\"")
      record("level_3") shouldEqual Some("\"custom\"")

    "render structured values as real nested JSON, read from the event markers" in:
      val user = LoggableValue(ValueKey("user"), PlainString("id=1, name=John"), JsonString("""{"id":1,"name":"John"}"""))

      val json = capture("Logging4sEncoderSpec-object") { logging =>
        logging.info("User created", user)
      }

      json.get("user").isObject shouldEqual true
      json.get("user").get("id").asInt() shouldEqual 1
      json.get("user").get("name").asText() shouldEqual "John"

    "write the standard envelope fields" in:
      val json = capture("Logging4sEncoderSpec-envelope") { logging =>
        logging.info("hello", LoggableValue(ValueKey("k"), PlainString("v"), JsonString("\"v\"")))
      }

      json.get("level").asText() shouldEqual "INFO"
      json.get("logger").asText() shouldEqual "Logging4sEncoderSpec-envelope"
      json.get("message").asText() should include("hello")
      json.get("@timestamp").isTextual shouldEqual true
      json.get("k").asText() shouldEqual "v"

    "attach several values as distinct fields" in:
      val json = capture("Logging4sEncoderSpec-many") { logging =>
        logging.info(
          "multi",
          LoggableValue(ValueKey("a"), PlainString("1"), JsonString("1")),
          LoggableValue(ValueKey("b"), PlainString("2"), JsonString("2")),
        )
      }

      json.get("a").asInt() shouldEqual 1
      json.get("b").asInt() shouldEqual 2

    "keep structured data that plain SLF4J code attached through the fluent API" in:
      val json = mapper.readTree(captureDirect("Logging4sEncoderSpec-fluent") { logger =>
        logger.atInfo().addKeyValue("request_id", "abc").log("event")
      })

      json.get("request_id").asText() shouldEqual "abc"
      json.get("message").asText() shouldEqual "event"

    "write a key-value pair and an MDC entry that carry no value as JSON null, keeping the line" in:
      val json = mapper.readTree(captureDirect("Logging4sEncoderSpec-null-ambient") { logger =>
        withMdc("tenant" -> null)(logger.atInfo().addKeyValue("request_id", null: Object).log("event"))
      })

      json.get("tenant").isNull shouldEqual true
      json.get("request_id").isNull shouldEqual true
      json.get("message").asText() shouldEqual "event"

    "write an empty message when the event carries none" in:
      val json = mapper.readTree(captureDirect("Logging4sEncoderSpec-no-message")(_.info(null: String)))

      json.get("message").asText() shouldEqual ""

    "write the cause's stack trace under stack_trace" in:
      val json = capture("Logging4sEncoderSpec-cause") { logging =>
        logging.error("failed", new IllegalStateException("kaboom"))
      }

      json.get("stack_trace").asText() should startWith("java.lang.IllegalStateException: kaboom")
      json.get("stack_trace").asText() should include("at logging4s.logback.Logging4sEncoderSpec")

    "leave stack_trace out when there is no cause" in:
      capture("Logging4sEncoderSpec-no-cause")(_.info("handled")).has("stack_trace") shouldEqual false

    "name the thread that logged the event" in:
      capture("Logging4sEncoderSpec-thread")(_.info("handled")).get("thread").asText() shouldEqual Thread.currentThread.getName

    "write @timestamp as an ISO-8601 instant in UTC, at the time of the call" in:
      val before    = Instant.now.truncatedTo(ChronoUnit.MILLIS)
      val timestamp = capture("Logging4sEncoderSpec-timestamp")(_.info("handled")).get("@timestamp").asText()
      val after     = Instant.now

      timestamp should endWith("Z")
      Instant.parse(timestamp) should (be >= before and be <= after)

    "find logging4s values referenced from another marker and ignore that marker's own fields" in:
      val user   = LoggableValue(ValueKey("user"), PlainString("bob"), JsonString("\"bob\""))
      val marker = Markers.append("foreign", "x").and[LogstashMarker](LoggableValuesMarker(Seq(user)))

      val json = mapper.readTree(captureDirect("Logging4sEncoderSpec-marker-tree")(_.info(marker, "event")))

      json.get("user").asText() shouldEqual "bob"
      json.has("foreign") shouldEqual false
