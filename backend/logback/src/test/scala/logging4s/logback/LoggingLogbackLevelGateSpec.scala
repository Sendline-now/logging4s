package logging4s.logback

import java.io.ByteArrayOutputStream

import scala.util.Try

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers

import org.slf4j.LoggerFactory
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.OutputStreamAppender
import net.logstash.logback.encoder.LogstashEncoder
import ch.qos.logback.classic.{Level as LogbackLevel, Logger as LogbackLogger}

import logging4s.core.{JsonString, Loggable, Logging, PlainString}
import logging4s.core.syntax.all.*

import LogbackInstances.given

object Rendered:
  var json: Int  = 0
  var plain: Int = 0

  def reset(): Unit =
    json = 0
    plain = 0

  final case class Payload(raw: String)

  given Loggable[Payload] = Loggable.make[Payload]("payload")(
    p =>
      json += 1
      JsonString.quoted(p.raw)
    ,
    p =>
      plain += 1
      PlainString(p.raw),
  )

class LoggingLogbackLevelGateSpec extends AnyWordSpec, Matchers:

  LogbackWarmup.touch()

  import Rendered.given

  private def loggerAt(name: String, level: LogbackLevel): Logging[Try] =
    LoggerFactory.getLogger(name).asInstanceOf[LogbackLogger].setLevel(level)
    Logging.createTry(name).get

  "The log interpolator over a real logback logger" must:
    "render nothing when the level is disabled" in:
      given Logging[Try] = loggerAt("LevelGateSpec-disabled", LogbackLevel.INFO)

      Rendered.reset()
      val payload = Rendered.Payload("x")

      assert(debug"handling $payload".isSuccess)

      Rendered.json shouldEqual 0
      Rendered.plain shouldEqual 0

    "render the plain text once and leave JSON to an encoder that writes it" in:
      given Logging[Try] = loggerAt("LevelGateSpec-enabled", LogbackLevel.DEBUG)

      Rendered.reset()
      val payload = Rendered.Payload("x")

      assert(debug"handling $payload".isSuccess)

      Rendered.json shouldEqual 0
      Rendered.plain shouldEqual 1

    "render JSON once however many encoders write the event" in:
      val name          = "LevelGateSpec-encoders"
      val logbackLogger = LoggerFactory.getLogger(name).asInstanceOf[LogbackLogger]

      val appenders = List(new LogstashEncoder(), new Logging4sEncoder()).map { encoder =>
        val appender = new OutputStreamAppender[ILoggingEvent]()
        encoder.setContext(logbackLogger.getLoggerContext)
        encoder.start()
        appender.setContext(logbackLogger.getLoggerContext)
        appender.setEncoder(encoder)
        appender.setOutputStream(new ByteArrayOutputStream())
        appender.start()
        appender
      }

      given Logging[Try] = loggerAt(name, LogbackLevel.DEBUG)

      LoggingLogbackJsonSpec.appenderLock.synchronized {
        appenders.foreach(logbackLogger.addAppender)
        Rendered.reset()
        val payload = Rendered.Payload("x")

        try assert(debug"handling $payload".isSuccess)
        finally appenders.foreach(appender => logbackLogger.detachAppender(appender): Unit)
      }

      Rendered.json shouldEqual 1
      Rendered.plain shouldEqual 1
