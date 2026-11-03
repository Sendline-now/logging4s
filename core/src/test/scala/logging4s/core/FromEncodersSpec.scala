package logging4s.core

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers

import logging4s.core.config.{KeyNameStyle, LoggableEncodingConfig}

final case class Contact(firstName: String, lastName: String)

class OpaqueId(val raw: Int)

final case class Link(target: OpaqueId, hops: Int)

enum Target:
  case Remote(id: OpaqueId)
  case Local(path: String)

enum Delivery:
  case Courier(name: String)
  case Pickup

class FromEncodersSpec extends AnyWordSpec, Matchers:

  private given JsonEncoder[Contact] =
    c => JsonString(s"""{"firstName":"${c.firstName}","lastName":"${c.lastName}"}""")

  "Loggable.fromEncoders" must:
    "take JSON verbatim from the encoder, never applying keyNameStyle to it" in:
      given LoggableEncodingConfig = LoggableEncodingConfig(keyNameStyle = KeyNameStyle.SnakeCase)

      val loggable = Loggable.fromEncoders[Contact]

      loggable.key shouldEqual "contact"
      loggable.json(Contact("John", "Doe")) shouldEqual """{"firstName":"John","lastName":"Doe"}"""

    "derive plain structurally from config when no PlainEncoder is in scope" in:
      given LoggableEncodingConfig = LoggableEncodingConfig()

      Loggable.fromEncoders[Contact].plain(Contact("John", "Doe")) shouldEqual
        "first_name -> (John), last_name -> (Doe)"

    "use a user-provided PlainEncoder for plain when present" in:
      given PlainEncoder[Contact] = c => PlainString(s"${c.firstName} ${c.lastName}")

      Loggable.fromEncoders[Contact].plain(Contact("John", "Doe")) shouldEqual "John Doe"

    "fall back to the JSON string as plain for a non-ADT type without a PlainEncoder" in:
      given JsonEncoder[OpaqueId] = id => JsonString(id.raw.toString)

      val loggable = Loggable.fromEncoders[OpaqueId]

      loggable.key shouldEqual "opaqueId"
      loggable.json(new OpaqueId(7)) shouldEqual "7"
      loggable.plain(new OpaqueId(7)) shouldEqual "7"

    "derive plain structurally for a sum whose variants can all be derived" in:
      given JsonEncoder[Delivery] = d => JsonString.quoted(d.toString)

      val loggable = Loggable.fromEncoders[Delivery]

      loggable.plain(Delivery.Courier("Bob")) shouldEqual "name -> (Bob)"
      loggable.plain(Delivery.Pickup) shouldEqual "Pickup"

    "fall back to the JSON string as plain for a product with a field that has no Loggable" in:
      given JsonEncoder[Link] = l => JsonString(s"""{"target":${l.target.raw},"hops":${l.hops}}""")

      val loggable = Loggable.fromEncoders[Link]
      val link     = Link(new OpaqueId(7), 2)

      loggable.json(link) shouldEqual """{"target":7,"hops":2}"""
      loggable.plain(link) shouldEqual loggable.json(link).value

    "fall back to the JSON string as plain for a sum with a variant that cannot be derived" in:
      given JsonEncoder[Target] = t => JsonString.quoted(t.toString)

      val loggable = Loggable.fromEncoders[Target]

      loggable.plain(Target.Local("/tmp")) shouldEqual "\"Local(/tmp)\""
