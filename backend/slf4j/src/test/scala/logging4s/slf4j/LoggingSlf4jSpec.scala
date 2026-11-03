package logging4s.slf4j

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers

import org.slf4j.LoggerFactory
import ch.qos.logback.classic.{Level as LogbackLevel, Logger as LogbackLogger}

import logging4s.core.{Delay, Identity, JsonString, Level, Logging, LoggableValue, PlainString, ValueKey}

import Slf4jInstances.given

class LoggingSlf4jSpec extends AnyWordSpec, Matchers:

  "Logging backed by bare slf4j" must:
    "right create for default Delay implementations" in:
      val resultTry =
        for
          logging <- Logging.createTry("LoggingSlf4jSpec")
          _       <- logging.info("Test log")
        yield ()

      assert(resultTry.isSuccess)

    "right log with structured values" in:
      val resultTry =
        for
          logging <- Logging.createTry("LoggingSlf4jSpec")
          _       <- logging.info("Test log with values", LoggableValue(ValueKey("user"), PlainString("John"), JsonString("\"John\"")))
        yield ()

      assert(resultTry.isSuccess)

    "right log duplicated keys without throwing" in:
      val resultTry =
        for
          logging <- Logging.createTry("LoggingSlf4jSpec")
          _       <- logging.error(
                       "Test log with error and duplicated keys",
                       new RuntimeException("boom"),
                       LoggableValue(ValueKey("k"), PlainString("1"), JsonString("\"1\"")),
                       LoggableValue(ValueKey("k"), PlainString("2"), JsonString("\"2\"")),
                     )
        yield ()

      assert(resultTry.isSuccess)

    "keep the message clean when the call site passes no values" in:
      val noValues = Seq.empty[LoggableValue]

      val resultTry =
        for
          logging <- Logging.createTry("LoggingSlf4jSpec")
          _       <- logging.info("nothing to add", noValues*)
        yield ()

      assert(resultTry.isSuccess)

    "build no effect for a record below the logger's level" in:
      var delays = 0

      given Delay[Identity] = new:
        override def delay[A](a: => A): Identity[A] =
          delays += 1
          a

        override val unit: Identity[Unit] = ()

      LoggerFactory.getLogger("LoggingSlf4jSpec-gate").asInstanceOf[LogbackLogger].setLevel(LogbackLevel.INFO)

      val logging = Logging.create[Identity]("LoggingSlf4jSpec-gate")
      delays = 0

      logging.debug("event", LoggableValue(ValueKey("n"), PlainString("1"), JsonString("1")))
      logging.emit(Level.Debug, "event", None, Nil)

      delays shouldEqual 0
