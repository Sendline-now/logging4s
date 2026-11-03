package logging4s.core

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers

import logging4s.core.syntax.all.*
import logging4s.core.testing.RecordingLogging

class RecordingLoggingSpec extends AnyWordSpec, Matchers:

  "RecordingLogging" must:
    "let a call-site value override a context value, as every backend does" in:
      val log = RecordingLogging[Identity]()

      log.withContext(LoggingContext("ctx".asLogValue("user"))).info("x", "call".asLogValue("user"))

      val event = log.recorded.head
      event.keys shouldEqual Seq("user")
      event.valueOf("user") shouldEqual Some("\"call\"")

    "read a field as JSON or as plain text, valueOf being the JSON form" in:
      val log = RecordingLogging[Identity]()

      log.info("x", "svc".asLogValue("service"), 3.asLogValue("n"))

      val event = log.recorded.head
      event.jsonOf("service") shouldEqual Some("\"svc\"")
      event.plainOf("service") shouldEqual Some("svc")
      event.valueOf("service") shouldEqual event.jsonOf("service")
      event.plainOf("n") shouldEqual Some("3")
      event.plainOf("missing") shouldEqual None

    "normalize keys the way the backends do" in:
      val log                 = RecordingLogging[Identity]()
      given Logging[Identity] = log
      val userId              = 7

      info"loaded $userId"

      log.recorded.head.keys shouldEqual Seq("user_id")

    "suffix a value named after an envelope field" in:
      val log = RecordingLogging[Identity]()

      log.info("x", "warn".asLogValue("level"))

      log.recorded.head.keys shouldEqual Seq("level_2")

    "keep the call site out of the fields and in the position" in:
      val log = RecordingLogging[Identity]()

      log.info("x")

      log.recorded.head.keys should not contain "source"
      log.recorded.head.position.file shouldEqual "RecordingLoggingSpec.scala"

    "keep the call-site values and the context apart from the merged fields" in:
      val log = RecordingLogging[Identity]()

      log.withContext(LoggingContext("svc".asLogValue("service"))).info("x", 1.asLogValue("n"))

      val event = log.recorded.head
      event.values.map(_.key.value) shouldEqual Seq("n")
      event.context.map(_.key.value) shouldEqual Seq("service")
      event.keys shouldEqual Seq("service", "n")

    "build no effect for a record below its threshold" in:
      var delays = 0

      given Delay[Identity] = new:
        override def delay[A](a: => A): Identity[A] =
          delays += 1
          a

        override val unit: Identity[Unit] = ()

      val log = RecordingLogging[Identity](Level.Info)

      log.emit(Level.Debug, "x", None, Nil)

      delays shouldEqual 0
      log.recorded shouldBe empty
