package logging4s.core

import scala.collection.immutable.Queue

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers

import logging4s.core.syntax.all.*
import logging4s.core.testing.{RecordedEvent, RecordingLogging}

class ImplicitConversionSpec extends AnyWordSpec, Matchers:

  private def recorded(f: Logging[Identity] ?=> Unit): RecordedEvent =
    val log = RecordingLogging[Identity]()
    f(using log)
    log.recorded.head

  private def rendered(event: RecordedEvent): Seq[(String, String)] =
    event.values.map(value => value.key.value -> value.json.value)

  "A value passed straight to a log call" must:
    "convert every standard collection, not only the ones with a single candidate instance" in:
      val event = recorded(Logging[Identity].info("x", List(1), Vector(2), Seq(3), Set(4), Array(5), Queue(6)))

      rendered(event) shouldEqual Seq("ints" -> "[1]", "ints" -> "[2]", "ints" -> "[3]", "ints" -> "[4]", "ints" -> "[5]", "ints" -> "[6]")

    "convert a map and an option" in:
      val event = recorded(Logging[Identity].info("x", Map("a" -> 1), Option(2)))

      rendered(event) shouldEqual Seq("string_ints" -> """{"a":1}""", "int" -> "2")

  "Some and None" must:
    "render the same way through the conversion, asLogValue and the interpolator" in:
      val some = Some(1)
      val none = None

      val direct       = recorded(Logging[Identity].info("x", some, none))
      val syntax       = recorded(Logging[Identity].info("x", some.asLogValue("some"), none.asLogValue("none")))
      val interpolated = recorded(info"x $some $none")

      rendered(direct).map(_._2) shouldEqual Seq("1", "null")
      rendered(syntax) shouldEqual Seq("some" -> "1", "none" -> "null")
      rendered(interpolated) shouldEqual Seq("some" -> "1", "none" -> "null")

    "never resolve to the collection instance" in:
      Loggable[Some[Int]].key shouldEqual ValueKey("int")
      Loggable[Some[Int]].json(Some(1)) shouldEqual "1"
      Loggable[None.type].json(None) shouldEqual "null"

    "be absent, like an empty Option" in:
      Loggable[None.type].isAbsent(None) shouldBe true
      Loggable[Some[Option[Int]]].isAbsent(Some(None)) shouldBe true
      Loggable[Some[Int]].isAbsent(Some(1)) shouldBe false
