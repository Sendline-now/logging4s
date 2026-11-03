package logging4s.core

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers

import logging4s.core.syntax.all.*

final case class RenderCalls(var json: Int, var plain: Int)

object Tracked:
  val calls: RenderCalls = RenderCalls(0, 0)

  final case class Value(raw: String)

  given Loggable[Value] = Loggable.make[Value]("tracked")(
    v =>
      calls.json += 1
      JsonString.quoted(v.raw)
    ,
    v =>
      calls.plain += 1
      PlainString(v.raw),
  )

class LazyLoggableValueSpec extends AnyWordSpec, Matchers:

  import Tracked.given

  private def freshValue: LoggableValue =
    Tracked.calls.json = 0
    Tracked.calls.plain = 0
    Tracked.Value("x").asLogValue

  "A LoggableValue built from a Loggable" must:
    "not render anything until it is read" in:
      val value = freshValue

      Tracked.calls.json shouldEqual 0
      Tracked.calls.plain shouldEqual 0
      value.key shouldEqual ValueKey("tracked")

    "render json only once no matter how often it is read" in:
      val value = freshValue

      value.json shouldEqual "\"x\""
      value.json shouldEqual "\"x\""
      value.json shouldEqual "\"x\""

      Tracked.calls.json shouldEqual 1

    "render plain only once no matter how often it is read" in:
      val value = freshValue

      value.plain shouldEqual "x"
      value.plain shouldEqual "x"

      Tracked.calls.plain shouldEqual 1

    "not render json when only plain is read" in:
      val value = freshValue

      value.plain shouldEqual "x"

      Tracked.calls.json shouldEqual 0

    "survive key rewriting without rendering" in:
      val value      = freshValue
      val normalized = LoggableValue.normalizeKeys(Seq(value))(using
        logging4s.core.config.LoggableEncodingConfig(keyNameStyle = logging4s.core.config.KeyNameStyle.PascalCase)
      )

      normalized.head.key shouldEqual ValueKey("Tracked")
      Tracked.calls.json shouldEqual 0
      Tracked.calls.plain shouldEqual 0

    "survive deduplication without rendering" in:
      Tracked.calls.json = 0
      Tracked.calls.plain = 0

      val values = Seq(Tracked.Value("a").asLogValue, Tracked.Value("b").asLogValue)
      val result = LoggableValue.deduplicateKeys(values)

      result.map(_.key) shouldEqual Seq(ValueKey("tracked"), ValueKey("tracked_2"))
      Tracked.calls.json shouldEqual 0
      Tracked.calls.plain shouldEqual 0

    "render a failing Loggable as a placeholder naming the error, in both forms, and count the value present" in:
      val error = new IllegalStateException("bad \"codec\"")

      val broken: Loggable[Int] = new:
        override val key: ValueKey              = ValueKey("broken")
        override def json(a: Int): JsonString   = throw error
        override def plain(a: Int): PlainString = throw error
        override def isAbsent(a: Int): Boolean  = throw error

      val value = LoggableValue.deferred(ValueKey("broken"), 1, broken)

      value.plain shouldEqual """[failed to render: java.lang.IllegalStateException: bad "codec"]"""
      value.json shouldEqual """"[failed to render: java.lang.IllegalStateException: bad \"codec\"]""""
      value.absent shouldEqual false

  "A hand-built LoggableValue" must:
    "keep working with pre-rendered strings" in:
      val value = LoggableValue(ValueKey("k"), PlainString("p"), JsonString("1"))

      value.key shouldEqual ValueKey("k")
      value.plain shouldEqual "p"
      value.json shouldEqual "1"
