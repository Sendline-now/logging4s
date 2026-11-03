package logging4s.logback

import scala.util.Try

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers

import org.slf4j.LoggerFactory
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import ch.qos.logback.classic.Logger as LogbackLogger

import logging4s.core.{LoggableValue, Logging}
import logging4s.core.syntax.all.*

import LogbackInstances.given

import scala.jdk.CollectionConverters.given

class LoggingLogbackSpec extends AnyWordSpec, Matchers:

  LogbackWarmup.touch()

  private def loggerNamesUnder(name: String)(create: => Try[Logging[Try]]): List[String] =
    val logbackLogger = LoggerFactory.getLogger(name).asInstanceOf[LogbackLogger]
    val appender      = new ListAppender[ILoggingEvent]()

    appender.setContext(logbackLogger.getLoggerContext)
    appender.start()
    logbackLogger.addAppender(appender)

    try create.flatMap(_.info("hello")).isSuccess shouldEqual true
    finally logbackLogger.detachAppender(appender)

    appender.list.asScala.map(_.getLoggerName).toList

  "Logging backed by logback" must:
    "right create for default Delay implementations" in:
      val resultTry =
        for
          logging <- Logging.createTry("LoggingLogbackSpec")
          _       <- logging.info("Test log")
        yield ()

      assert(resultTry.isSuccess)

    "not fail when a call site passes no values" in:
      val noValues = Seq.empty[LoggableValue]

      val resultTry =
        for
          logging <- Logging.createTry("LoggingLogbackSpec-no-values")
          _       <- logging.info("Test log", noValues*)
        yield ()

      assert(resultTry.isSuccess)

    "not fail for an interpolated message without interpolated values" in:
      given Logging[Try] = Logging.createTry("LoggingLogbackSpec-interpolated").get

      assert(info"application started".isSuccess)

    "name the logger after the type, without a trailing dollar for objects" in:
      loggerNamesUnder("logging4s.logback.NamedProbe")(Logging.createTry[NamedProbe.type]) shouldEqual List("logging4s.logback.NamedProbe")

    "name a nested type's logger as getLogger(classOf[...]) does, which logback files under the outer type" in:
      val nestedObject = loggerNamesUnder("logging4s.logback.NamedProbe")(Logging.createTry[NamedProbe.Inner.type])
      val nestedClass  = loggerNamesUnder("logging4s.logback.NamedProbe")(Logging.createTry[NamedProbe.Nested])

      nestedObject shouldEqual List("logging4s.logback.NamedProbe$Inner")
      nestedClass shouldEqual List(LoggerFactory.getLogger(classOf[NamedProbe.Nested]).getName)

object NamedProbe:
  object Inner

  final class Nested
