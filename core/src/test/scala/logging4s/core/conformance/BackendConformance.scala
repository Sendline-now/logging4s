package logging4s.core.conformance

import scala.util.Try

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers

import logging4s.core.syntax.all.*
import logging4s.core.{Delay, Identity, Level, Loggable, LoggableValue, Logging, LoggingContext, ValueKey}

final case class CapturedRecord(message: String, fields: Seq[(String, String)]):
  def keys: Seq[String]                  = fields.map(_._1)
  def apply(key: String): Option[String] = fields.collectFirst { case (k, v) if k == key => v }
  def count(key: String): Int            = keys.count(_ == key)

trait BackendConformance extends AnyWordSpec, Matchers:

  def backendName: String

  def capture(loggerName: String, context: LoggingContext = LoggingContext.empty)(run: Logging[Try] => Unit): CapturedRecord

  def atInfo(loggerName: String)(using Delay[Identity]): Logging[Identity]

  s"$backendName, as a logging4s backend," must:

    "attach a call-site value as its own field" in:
      val record = capture(s"$backendName-conformance-value")(_.info("event", 7.asLogValue("count")))

      record("count") shouldEqual Some("7")

    "attach context values to every record" in:
      val record = capture(s"$backendName-conformance-context") { logging =>
        logging.withContext(LoggingContext("svc".asLogValue("service"))).info("event")
      }

      record("service") shouldEqual Some("\"svc\"")

    "let a call-site value override a context value with the same key" in:
      val record = capture(s"$backendName-conformance-override") { logging =>
        logging.withContext(LoggingContext(1.asLogValue("id"))).info("event", 2.asLogValue("id"))
      }

      record("id") shouldEqual Some("2")
      record.count("id") shouldEqual 1

    "let a later context replace an earlier one under the same key" in:
      val record = capture(s"$backendName-conformance-context-chain") { logging =>
        logging
          .withContext(LoggingContext(1.asLogValue("id")))
          .withContext(LoggingContext(2.asLogValue("id")))
          .info("event")
      }

      record("id") shouldEqual Some("2")
      record.count("id") shouldEqual 1

    "treat context keys that normalize to the same name as one key" in:
      val record = capture(s"$backendName-conformance-normalized") { logging =>
        logging
          .withContext(LoggingContext(1.asLogValue("userId")))
          .withContext(LoggingContext(2.asLogValue("user_id")))
          .info("event")
      }

      record.count("user_id") shouldEqual 1
      record("user_id") shouldEqual Some("2")

    "treat keys of the context given at creation that normalize to the same name as one key, like withContext does" in:
      val context = LoggingContext(1.asLogValue("userId"), 2.asLogValue("user_id"))
      val record  = capture(s"$backendName-conformance-created-context", context)(_.info("event"))

      record.count("user_id") shouldEqual 1
      record("user_id") shouldEqual Some("2")

    "place an ambient context between the logger's own context and the call site" in:
      val own     = LoggingContext(1.asLogValue("x"), 1.asLogValue("y"), 1.asLogValue("user_id"))
      val ambient = LoggingContext(2.asLogValue("y"), 2.asLogValue("z"), 2.asLogValue("userId"))

      val record = capture(s"$backendName-conformance-ambient", own) { logging =>
        logging.emitWith(ambient, Level.Info, "event", None, Seq(3.asLogValue("z")))
      }

      Seq("x", "y", "z", "user_id").map(record.apply) shouldEqual Seq(Some("1"), Some("2"), Some("3"), Some("2"))
      Seq("x", "y", "z", "user_id").map(record.count) shouldEqual Seq(1, 1, 1, 1)

    "emit the call site as a source field, and never in the message text" in:
      val record = capture(s"$backendName-conformance-source")(_.info("event"))

      record("source") should not be empty
      record.message should not include "source"

    "never emit the same key twice, whatever the call site passes" in:
      val record = capture(s"$backendName-conformance-duplicates") { logging =>
        logging.info("event", 1.asLogValue("id"), 2.asLogValue("id"), 3.asLogValue("id_2"))
      }

      record.keys.distinct.size shouldEqual record.keys.size

    "keep a user value that collides with an envelope name out of the envelope" in:
      val record = capture(s"$backendName-conformance-reserved")(_.info("event", "forged".asLogValue("level")))

      record.count("level") should be <= 1

    "keep the static message text intact" in:
      val record = capture(s"$backendName-conformance-message")(_.info("user created", 7.asLogValue("id")))

      record.message should startWith("user created")

    "describe the cause in the message text the same way as every other backend" in:
      val record = capture(s"$backendName-conformance-cause") { logging =>
        logging.error("payment failed", new RuntimeException("boom"), 1.asLogValue("n"))
      }

      record.message shouldEqual "payment failed: class=java.lang.RuntimeException, message=boom, n -> (1)"

    "keep the record and the effect when a value fails to render, showing the failure in its place" in:
      val broken = Loggable.make[Int]("broken")(_ => throw new IllegalStateException("bad codec"), _ => throw new IllegalStateException("bad codec"))
      var result = Try(())

      val record = capture(s"$backendName-conformance-broken") { logging =>
        result = logging.info("event", LoggableValue.deferred(ValueKey("broken"), 1, broken), 2.asLogValue("n"))
      }

      result.isSuccess shouldEqual true
      record("broken") shouldEqual Some("\"[failed to render: java.lang.IllegalStateException: bad codec]\"")
      record("n") shouldEqual Some("2")
      record.message shouldEqual "event: broken -> ([failed to render: java.lang.IllegalStateException: bad codec]), n -> (2)"

    "build no effect for a record below the logger's level" in:
      var delays = 0

      given Delay[Identity] = new:
        override def delay[A](a: => A): Identity[A] =
          delays += 1
          a

        override val unit: Identity[Unit] = ()

      val logging = atInfo(s"$backendName-conformance-gate")
      delays = 0

      logging.debug("event", 1.asLogValue("n"))
      logging.emit(Level.Debug, "event", None, Seq(1.asLogValue("n")))
      logging.withContext(LoggingContext(1.asLogValue("n"))).trace("event")

      delays shouldEqual 0
