package logging4s.core

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers

import logging4s.core.syntax.all.*

class DirectLevelGateSpec extends AnyWordSpec, Matchers:

  import DirectLevelGateSpec.*

  "The per-level methods" must:
    "not reach emit when their level is disabled" in:
      val log   = CountingLogging(Level.Error)
      val boom  = new RuntimeException("boom")
      val value = 1.asLogValue("n")

      log.warn("x")
      log.warn("x", boom)
      log.warn("x", value)
      log.warn("x", boom, value)
      log.info("x")
      log.info("x", boom)
      log.info("x", value)
      log.info("x", boom, value)
      log.debug("x")
      log.debug("x", boom)
      log.debug("x", value)
      log.debug("x", boom, value)
      log.trace("x")
      log.trace("x", boom)
      log.trace("x", value)
      log.trace("x", boom, value)
      log.log(Level.Warn, "x")
      log.log(Level.Warn, "x", boom)
      log.log(Level.Warn, "x", value)
      log.log(Level.Warn, "x", boom, value)

      log.emitted shouldEqual 0

      log.error("x")
      log.error("x", boom)
      log.error("x", value)
      log.error("x", boom, value)
      log.log(Level.Error, "x", boom, value)

      log.emitted shouldEqual 5

    "not evaluate the values, the cause or the position of a suppressed call" in:
      val log       = CountingLogging(Level.Info)
      var evaluated = 0

      def value(): LoggableValue =
        evaluated += 1
        1.asLogValue("n")

      def boom(): Throwable =
        evaluated += 1
        new RuntimeException("boom")

      def position(): Position =
        evaluated += 1
        Position("Spec.scala", 1)

      log.debug("x", value())
      log.debug("x", boom())
      log.debug("x", boom(), value(), value())
      log.log(Level.Trace, "x", boom(), value())
      log.debug("x")(using position())

      evaluated shouldEqual 0

    "evaluate each value and the cause exactly once when the level is enabled" in:
      val log       = CountingLogging(Level.Info)
      var evaluated = 0

      def value(): LoggableValue =
        evaluated += 1
        1.asLogValue("n")

      def boom(): Throwable =
        evaluated += 1
        new RuntimeException("boom")

      log.info("x", boom(), value(), value())

      evaluated shouldEqual 3
      log.emitted shouldEqual 1

    "evaluate a dynamic level once" in:
      val log       = CountingLogging(Level.Info)
      var evaluated = 0

      def level(): Level =
        evaluated += 1
        Level.Info

      log.log(level(), "x")

      evaluated shouldEqual 1
      log.emitted shouldEqual 1

object DirectLevelGateSpec:

  final class CountingLogging(threshold: Level) extends Logging[Identity]:
    var emitted: Int = 0

    override def withContext(context: LoggingContext): Logging[Identity] = this
    override def enabled(level: Level): Boolean                          = level.enabledAt(threshold)
    override def unit: Identity[Unit]                                    = ()

    override def emit(level: Level, message: String, cause: Option[Throwable], values: Seq[LoggableValue])(using Position): Identity[Unit] =
      emitted += 1
