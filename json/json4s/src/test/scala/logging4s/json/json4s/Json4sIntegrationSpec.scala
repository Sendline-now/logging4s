package logging4s.json.json4s

import java.time.Instant

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers

import org.json4s.{DefaultFormats, Formats}

import logging4s.core.{Loggable, PlainEncoder, PlainString}

import Json4sInstances.given

class Json4sIntegrationSpec extends AnyWordSpec, Matchers:

  final case class User(name: String, age: Int)

  given PlainEncoder[User] = user => PlainString(s"name=${user.name}, age=${user.age}")
  given Formats            = DefaultFormats

  "Json4s integration" must:
    "use given Formats for implementation JsonEncoder" in:
      val user     = User("John", 18)
      val expected = """{"name":"John","age":18}"""

      Loggable.fromEncoders[User]("user").json(user) shouldEqual expected

    "escape a string's quotes and line break, keeping the value on one line" in:
      val json = Loggable.fromEncoders[User]("user").json(User("Jo \"Q\"\nDoe", 18)).value

      json should include("""Jo \"Q\"\nDoe""")
      json should not include "\n"

    "fail at runtime rather than at compile time for a type it cannot reflect into, since it accepts any type" in:
      an[RuntimeException] should be thrownBy Loggable.fromEncoders[Instant]("at").json(Instant.EPOCH)
