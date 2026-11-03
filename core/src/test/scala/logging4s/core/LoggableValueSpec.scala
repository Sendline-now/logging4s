package logging4s.core

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers

class LoggableValueSpec extends AnyWordSpec, Matchers:

  import LoggableValueSpec.*

  "LoggableValue.deduplicateKeys" must:
    "keep unique keys untouched and in order" in:
      val values = Seq(
        LoggableValue(ValueKey("a"), PlainString("1"), JsonString("1")),
        LoggableValue(ValueKey("b"), PlainString("2"), JsonString("2")),
        LoggableValue(ValueKey("c"), PlainString("3"), JsonString("3")),
      )

      LoggableValue.deduplicateKeys(values) shouldEqual values

    "keep the first occurrence of a duplicated key unsuffixed and suffix the rest from _2" in:
      val values = Seq(
        LoggableValue(ValueKey("k"), PlainString("1"), JsonString("1")),
        LoggableValue(ValueKey("k"), PlainString("2"), JsonString("2")),
        LoggableValue(ValueKey("k"), PlainString("3"), JsonString("3")),
      )

      val result = LoggableValue.deduplicateKeys(values)

      result.map(_.key) shouldEqual Seq("k", "k_2", "k_3")
      result.map(_.plain) shouldEqual Seq("1", "2", "3")

    "preserve original order when duplicates are interleaved with other keys" in:
      val values = Seq(
        LoggableValue(ValueKey("k"), PlainString("1"), JsonString("1")),
        LoggableValue(ValueKey("other"), PlainString("x"), JsonString("x")),
        LoggableValue(ValueKey("k"), PlainString("2"), JsonString("2")),
      )

      val result = LoggableValue.deduplicateKeys(values)

      result.map(_.key) shouldEqual Seq("k", "other", "k_2")

    "pick a suffix that is not already taken by another value" in:
      val values = Seq(
        LoggableValue(ValueKey("x"), PlainString("a"), JsonString("1")),
        LoggableValue(ValueKey("x"), PlainString("b"), JsonString("2")),
        LoggableValue(ValueKey("x_2"), PlainString("c"), JsonString("3")),
      )

      val keys = LoggableValue.deduplicateKeys(values).map(_.key)

      keys.distinct.size shouldEqual keys.size
      keys should contain(ValueKey("x_2"))

    "find a duplicate in a long list as well, where the keys are hashed rather than scanned" in:
      val values = numbered(1 to 10, "a") ++ numbered(3 to 3, "b")

      LoggableValue.deduplicateKeys(values).map(_.key) shouldEqual (1 to 10).map(i => s"k$i") :+ "k3_2"

    "keep a long list of unique keys untouched" in:
      val values = numbered(1 to 12, "a")

      LoggableValue.deduplicateKeys(values) shouldBe theSameInstanceAs(values)

  "LoggableValue.mergeByKey" must:
    "let later values replace earlier ones with the same key, short or long, keeping the survivors first" in:
      Seq(1 to 3, 1 to 12).foreach { keys =>
        val merged = LoggableValue.mergeByKey(numbered(0 to keys.last, "old"), numbered(keys, "new"))

        merged.map(value => (value.key, value.plain)) shouldEqual ("k0", "old") +: keys.map(i => (s"k$i", "new"))
      }

    "append the later values when none of them overrides an earlier one" in:
      Seq(1 to 3, 1 to 12).foreach { keys =>
        val earlier = numbered(Range(100, 102), "old")
        val later   = numbered(keys, "new")

        LoggableValue.mergeByKey(earlier, later) shouldEqual earlier ++ later
      }

object LoggableValueSpec:

  def numbered(keys: Range, label: String): Seq[LoggableValue] =
    keys.map(i => LoggableValue(ValueKey(s"k$i"), PlainString(label), JsonString.quoted(label)))
