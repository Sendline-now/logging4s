package logging4s.core

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers

import logging4s.core.config.LoggableEncodingConfig

class SumDiscriminatorSpec extends AnyWordSpec, Matchers:
  import SumDiscriminatorSpec.*

  private val tagged = LoggableEncodingConfig(sumTypeDiscriminator = Some("type"))

  "A derived sum type" must:
    "render the variant alone by default" in:
      Loggable.derived[Payment].json(Payment.Card("1234")) shouldEqual """{"last4":"1234"}"""

    "lead with the variant name when a discriminator is configured" in:
      given LoggableEncodingConfig = tagged
      val loggable                 = Loggable.derived[Payment]

      loggable.json(Payment.Card("1234")) shouldEqual """{"type":"Card","last4":"1234"}"""
      loggable.json(Payment.Cash(10)) shouldEqual """{"type":"Cash","amount":10}"""

    "render a singleton case as an object with only the tag, so the field keeps one JSON type" in:
      given LoggableEncodingConfig = tagged

      Loggable.derived[Payment].json(Payment.Pending) shouldEqual """{"type":"Pending"}"""
      Loggable.derived[Shape].json(Dot) shouldEqual """{"type":"Dot"}"""

    "leave a singleton case as its name without a discriminator" in:
      Loggable.derived[Payment].json(Payment.Pending) shouldEqual "\"Pending\""

    "leave a variant with its own non-object rendering untouched" in:
      given LoggableEncodingConfig = tagged

      Loggable.derived[Status].json(Status.Code(7)) shouldEqual "7"

    "keep the plain rendering unchanged" in:
      given LoggableEncodingConfig = tagged

      Loggable.derived[Payment].plain(Payment.Card("1234")) shouldEqual "last4 -> (1234)"

    "suffix the discriminator when the variant has a field of the same name" in:
      given LoggableEncodingConfig = tagged

      Loggable.derived[Event].json(Event.Typed("click", 1)) shouldEqual """{"type_2":"Typed","type":"click","id":1}"""

    "tag a nested hierarchy once, with the leaf's name" in:
      given LoggableEncodingConfig = tagged
      val loggable                 = Loggable.derived[Shape]

      loggable.json(Circle(2)) shouldEqual """{"type":"Circle","radius":2}"""
      loggable.json(Square(3)) shouldEqual """{"type":"Square","side":3}"""

object SumDiscriminatorSpec:
  enum Payment:
    case Card(last4: String)
    case Cash(amount: Int)
    case Pending

  enum Event:
    case Typed(`type`: String, id: Int)

  sealed trait Shape
  sealed trait Round                   extends Shape
  final case class Circle(radius: Int) extends Round
  final case class Square(side: Int)   extends Shape
  case object Dot                      extends Round

  enum Status:
    case Code(value: Int)
    case Unknown

  object Status:
    given Loggable[Code] = Loggable.make[Code](code => JsonString(code.value.toString), code => PlainString(code.value.toString))
