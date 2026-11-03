package logging4s.core

import java.nio.file.Paths
import java.net.{InetAddress, URI}
import java.util.{Currency, Date, Locale}
import java.time.{DayOfWeek, Instant, Month, MonthDay, Period, Year, YearMonth, ZoneId, ZoneOffset}

import scala.util.{Failure, Success, Try}
import scala.concurrent.duration.{Duration, FiniteDuration}

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers

class StandardInstancesSpec extends AnyWordSpec, Matchers:

  private def rendered[A](value: A)(using L: Loggable[A]): (String, String, String) =
    (L.key.value, L.plain(value).value, L.json(value).value)

  "Loggable for java.time types" must:
    "render a Year as a number" in:
      rendered(Year.of(2026)) shouldEqual ("year", "2026", "2026")

    "render the other calendar types as their ISO strings" in:
      rendered(YearMonth.of(2026, 11)) shouldEqual ("year_month", "2026-11", "\"2026-11\"")
      rendered(MonthDay.of(11, 3)) shouldEqual ("month_day", "--11-03", "\"--11-03\"")
      rendered(Period.of(1, 2, 3)) shouldEqual ("period", "P1Y2M3D", "\"P1Y2M3D\"")
      rendered(Month.NOVEMBER) shouldEqual ("month", "NOVEMBER", "\"NOVEMBER\"")
      rendered(DayOfWeek.TUESDAY) shouldEqual ("day_of_week", "TUESDAY", "\"TUESDAY\"")

    "render a zone and an offset by their ids under one key" in:
      rendered(ZoneId.of("Europe/Berlin")) shouldEqual ("zone", "Europe/Berlin", "\"Europe/Berlin\"")
      rendered(ZoneOffset.ofHours(2)) shouldEqual ("zone", "+02:00", "\"+02:00\"")

  "Loggable for java.net, java.nio and java.util types" must:
    "render a URI and a path as strings" in:
      rendered(URI.create("https://logging4s.org/docs")) shouldEqual ("uri", "https://logging4s.org/docs", "\"https://logging4s.org/docs\"")
      rendered(Paths.get("logs")) shouldEqual ("path", "logs", "\"logs\"")

    "render an address as its IP, without the host name" in:
      rendered(InetAddress.getByAddress("db", Array[Byte](10, 0, 0, 7))) shouldEqual ("ip", "10.0.0.7", "\"10.0.0.7\"")

    "render a locale as its language tag and a currency as its code" in:
      rendered(Locale.forLanguageTag("en-US")) shouldEqual ("locale", "en-US", "\"en-US\"")
      rendered(Currency.getInstance("EUR")) shouldEqual ("currency", "EUR", "\"EUR\"")

    "render a Date like the Instant it stands for" in:
      rendered(Date.from(Instant.parse("2026-11-03T10:15:30Z"))) shouldEqual
        ("time", "2026-11-03T10:15:30Z", "\"2026-11-03T10:15:30Z\"")

  "Loggable for Scala types" must:
    "render a finite Duration in milliseconds, like FiniteDuration" in:
      rendered[Duration](Duration(3, "seconds")) shouldEqual ("time_ms", "3000", "3000")

    "render an infinite Duration as JSON null, keeping the field numeric" in:
      rendered[Duration](Duration.Inf) shouldEqual ("time_ms", "Duration.Inf", "null")
      rendered[Duration](Duration.Undefined) shouldEqual ("time_ms", "Duration.Undefined", "null")

    "render a Try like an Either of a Throwable and the value" in:
      rendered[Try[Int]](Success(5)) shouldEqual ("error_int", "5", "5")
      rendered[Try[Int]](Failure(new IllegalStateException("boom"))) shouldEqual
        ("error_int", "class=java.lang.IllegalStateException, message=boom", """{"class":"java.lang.IllegalStateException","message":"boom"}""")

    "let these values be passed to a log call directly" in:
      Seq[LoggableValue](URI.create("a:b"), Year.of(2026), Try(1)).map(_.key.value) shouldEqual Seq("uri", "year", "error_int")

    "keep the subtype instance for a value whose supertype has one too" in:
      Seq[LoggableValue](FiniteDuration(3, "s"), ZoneOffset.UTC).map(_.json.value) shouldEqual Seq("3000", "\"Z\"")
