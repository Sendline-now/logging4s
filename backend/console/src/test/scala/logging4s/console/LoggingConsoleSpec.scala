package logging4s.console

import java.io.{ByteArrayOutputStream, PrintStream}

import scala.util.Try

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers

import logging4s.core.config.LoggableEncodingConfig
import logging4s.core.{JsonString, Level, Logging, LoggableValue, PlainString, ValueKey}

import ConsoleInstances.given

class LoggingConsoleSpec extends AnyWordSpec, Matchers:

  private def capture(
      config: ConsoleConfig,
      encoding: LoggableEncodingConfig = LoggableEncodingConfig.Default,
      name: String = "ConsoleSpec",
  )(run: Logging[Try] => Unit): String =
    captureStreams(config, encoding, name)(run)(0)

  private def captureStreams(
      config: ConsoleConfig,
      encoding: LoggableEncodingConfig = LoggableEncodingConfig.Default,
      name: String,
  )(run: Logging[Try] => Unit): (String, String) =
    given ConsoleConfig          = config
    given LoggableEncodingConfig = encoding

    ConsoleCaptureLock.synchronized {
      val out              = new ByteArrayOutputStream()
      val err              = new ByteArrayOutputStream()
      val (stdout, stderr) = (System.out, System.err)
      System.setOut(new PrintStream(out, true, "UTF-8"))
      System.setErr(new PrintStream(err, true, "UTF-8"))
      try
        val logging = Logging.createTry(name).get
        run(logging)
      finally
        System.setOut(stdout)
        System.setErr(stderr)

      (out.toString("UTF-8"), err.toString("UTF-8"))
    }

  private val jsonAtInfo = ConsoleConfig(Threshold.At(Level.Info), Format.Json, ColorMode.Off, Stream.Stdout, -1)

  "LoggingConsole" must:
    "emit structured values as nested JSON with an envelope" in:
      val out = capture(jsonAtInfo) { logging =>
        logging.info("user created", LoggableValue(ValueKey("user"), PlainString("id=1"), JsonString("""{"id":1,"name":"John"}""")))
      }

      out should include(""""user":{"id":1,"name":"John"}""")
      out should include(""""level":"INFO"""")
      out should include(""""logger":"ConsoleSpec"""")
      out should include(""""message":"user created""")

    "not emit anything below the configured level" in:
      val out = capture(jsonAtInfo) { logging =>
        logging.debug("noisy", LoggableValue(ValueKey("k"), PlainString("v"), JsonString("1")))
      }

      out.trim shouldBe empty

    "render the plain format when configured" in:
      val out = capture(ConsoleConfig(Threshold.At(Level.Info), Format.Plain, ColorMode.Off, Stream.Stdout, -1)) { logging =>
        logging.info("hello", LoggableValue(ValueKey("k"), PlainString("v"), JsonString("\"v\"")))
      }

      out should include("INFO ConsoleSpec - hello")
      out should include("k -> (v)")

    "attach context values to a message logged without call-site values" in:
      val out = capture(jsonAtInfo) { logging =>
        logging
          .withContextValues(LoggableValue(ValueKey("session"), PlainString("abc"), JsonString("\"abc\"")))
          .info("no call-site values")
      }

      out should include(""""session":"abc"""")

    "omit the source field when the config disables it" in:
      val out = capture(jsonAtInfo, LoggableEncodingConfig(includeSourcePosition = false)) { logging =>
        logging.info("no source here")
      }

      out should not include "\"source\""
      out should include(""""message":"no source here"""")

    "include the source field by default" in:
      val out = capture(jsonAtInfo) { logging =>
        logging.info("with source")
      }

      out should include(""""source":"LoggingConsoleSpec.scala:""")

    "suppress a logger whose own mapped level is higher than the record" in:
      val config = jsonAtInfo.copy(levels = Map("io.netty" -> Threshold.At(Level.Error)))

      val quiet = capture(config, name = "io.netty.channel.Pipeline") { logging =>
        logging.info("noisy")
      }

      quiet.trim shouldBe empty

    "keep loggers that no mapping matches at the root level" in:
      val config = jsonAtInfo.copy(levels = Map("io.netty" -> Threshold.At(Level.Error)))

      val loud = capture(config, name = "com.acme.Service") { logging =>
        logging.info("kept")
      }

      loud should include("kept")

    "describe the cause in the message and write its stack trace as a field in JSON" in:
      val out = capture(jsonAtInfo, name = "ConsoleSpec-json-cause") { logging =>
        logging.error("payment failed", new RuntimeException("boom"))
      }

      out should include(""""message":"payment failed: class=java.lang.RuntimeException, message=boom"""")
      out should include(""""stack_trace":"java.lang.RuntimeException: boom""")

    "describe the cause on the plain line and print its stack trace below, cut to the configured length" in:
      val config = ConsoleConfig(Threshold.At(Level.Info), Format.Plain, ColorMode.Off, Stream.Stdout, 2)

      val out = capture(config, name = "ConsoleSpec-plain-cause") { logging =>
        logging.error("payment failed", new RuntimeException("boom"))
      }

      val lines = out.linesIterator.dropWhile(!_.contains("ConsoleSpec-plain-cause")).take(4).toList

      lines.head should include("ERROR ConsoleSpec-plain-cause - payment failed: class=java.lang.RuntimeException, message=boom")
      lines(1) shouldEqual "java.lang.RuntimeException: boom"
      lines(2) should startWith("\tat ")
      lines(3) should fullyMatch regex """\.\.\. \d+ more"""

    "write to stderr when configured, and not to stdout" in:
      val (out, err) = captureStreams(jsonAtInfo.copy(stream = Stream.Stderr), name = "ConsoleSpec-stderr") { logging =>
        logging.info("to stderr")
      }

      err should include(""""logger":"ConsoleSpec-stderr"""")
      out should not include "ConsoleSpec-stderr"

    "wrap each plain line in its level's ANSI color when color is on" in:
      val config = ConsoleConfig(Threshold.At(Level.Trace), Format.Plain, ColorMode.On, Stream.Stdout, -1)

      val out = capture(config, name = "ConsoleSpec-color") { logging =>
        logging.error("e")
        logging.warn("w")
        logging.info("i")
        logging.debug("d")
        logging.trace("t")
      }

      val lines = out.linesIterator.filter(_.contains("ConsoleSpec-color")).toList

      lines.map(_.take(5)) shouldEqual List("31", "33", "32", "36", "90").map(code => s"\u001b[${code}m")
      all(lines) should endWith("\u001b[0m")

    "leave a JSON line uncolored even when color is on" in:
      val out = capture(jsonAtInfo.copy(color = ColorMode.On), name = "ConsoleSpec-json-color") { logging =>
        logging.error("failed")
      }

      out should include(""""logger":"ConsoleSpec-json-color"""")
      out should not include "\u001b"

  "Console coloring" must:
    "follow the terminal in auto mode, and ignore it when color is on or off" in:
      Renderer.colored(ColorMode.Auto, interactive = false) shouldEqual false
      Renderer.colored(ColorMode.Auto, interactive = true) shouldEqual true
      Renderer.colored(ColorMode.On, interactive = false) shouldEqual true
      Renderer.colored(ColorMode.Off, interactive = true) shouldEqual false
