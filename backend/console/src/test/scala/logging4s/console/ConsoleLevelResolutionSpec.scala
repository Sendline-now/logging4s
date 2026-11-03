package logging4s.console

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers

import com.typesafe.config.{ConfigFactory, ConfigResolveOptions}

import logging4s.core.Level

import scala.jdk.CollectionConverters.given

class ConsoleLevelResolutionSpec extends AnyWordSpec, Matchers:

  private def config(levels: (String, Threshold)*): ConsoleConfig =
    ConsoleConfig(Threshold.At(Level.Info), Format.Json, ColorMode.Off, Stream.Stdout, -1, levels.toMap)

  "ConsoleConfig.thresholdFor" must:
    "fall back to the root level when there are no mappings" in:
      config().thresholdFor("com.acme.Service") shouldEqual Threshold.At(Level.Info)

    "fall back to the root level when nothing matches" in:
      config("io.netty" -> Threshold.At(Level.Error)).thresholdFor("com.acme.Service") shouldEqual Threshold.At(Level.Info)

    "use an exact match" in:
      config("com.acme.Service" -> Threshold.At(Level.Debug)).thresholdFor("com.acme.Service") shouldEqual Threshold.At(Level.Debug)

    "apply a prefix to child loggers" in:
      config("io.netty" -> Threshold.At(Level.Error)).thresholdFor("io.netty.channel.Pipeline") shouldEqual Threshold.At(Level.Error)

    "not treat a partial segment as a prefix" in:
      config("io.netty" -> Threshold.At(Level.Error)).thresholdFor("io.nettyfoo.Bar") shouldEqual Threshold.At(Level.Info)

    "prefer the most specific prefix" in:
      val cfg = config("io" -> Threshold.At(Level.Error), "io.netty" -> Threshold.At(Level.Warn), "io.netty.channel" -> Threshold.At(Level.Debug))

      cfg.thresholdFor("io.netty.channel.Pipeline") shouldEqual Threshold.At(Level.Debug)
      cfg.thresholdFor("io.netty.buffer.Buf") shouldEqual Threshold.At(Level.Warn)
      cfg.thresholdFor("io.grpc.Server") shouldEqual Threshold.At(Level.Error)

  "ConsoleConfig loading" must:
    "read quoted and nested mapping keys the same way" in:
      val raw = ConfigFactory.parseString(
        """logging4s.console {
          |  level = "info"
          |  format = "json"
          |  color = "off"
          |  stream = "stdout"
          |  max-stack-trace-lines = -1
          |  levels {
          |    "io.netty" = "warn"
          |    com.acme.Service = "debug"
          |  }
          |}""".stripMargin
      )

      val loaded = ConsoleConfig.load(raw)

      loaded.level shouldEqual Threshold.At(Level.Info)
      loaded.levels shouldEqual Map("io.netty" -> Threshold.At(Level.Warn), "com.acme.Service" -> Threshold.At(Level.Debug))

    "default to no mappings when the section is absent" in:
      val raw = ConfigFactory.parseString(
        """logging4s.console {
          |  level = "warn"
          |  format = "plain"
          |  color = "off"
          |  stream = "stderr"
          |  max-stack-trace-lines = 5
          |}""".stripMargin
      )

      ConsoleConfig.load(raw).levels shouldBe empty

    "read off as a threshold that lets nothing through" in:
      val raw = ConfigFactory.parseString(
        """logging4s.console {
          |  level = "info"
          |  format = "json"
          |  color = "off"
          |  stream = "stdout"
          |  max-stack-trace-lines = -1
          |  levels {
          |    "io.netty" = "off"
          |  }
          |}""".stripMargin
      )

      val loaded = ConsoleConfig.load(raw)

      loaded.thresholdFor("io.netty.channel.Pipeline") shouldEqual Threshold.Off
      Level.values.foreach(level => loaded.thresholdFor("io.netty").allows(level) shouldEqual false)
      loaded.thresholdFor("com.acme.Service").allows(Level.Info) shouldEqual true

    "turn the whole console off when the root level is off" in:
      val raw = ConfigFactory.parseString(
        """logging4s.console {
          |  level = "off"
          |  format = "json"
          |  color = "off"
          |  stream = "stdout"
          |  max-stack-trace-lines = -1
          |}""".stripMargin
      )

      val loaded = ConsoleConfig.load(raw)

      Level.values.foreach(level => loaded.thresholdFor("anything").allows(level) shouldEqual false)

    "reject an invalid value with an error that names the key" in:
      def loadWith(key: String): ConsoleConfig =
        ConsoleConfig.load(ConfigFactory.parseString(s"""logging4s.console.$key = "bogus"""").withFallback(ConfigFactory.defaultReference()))

      Seq("level", "format", "color", "stream").foreach { key =>
        val error = intercept[IllegalArgumentException](loadWith(key))
        error.getMessage shouldEqual s"Invalid logging4s.console.$key: 'bogus'"
      }

    "keep the reference defaults when no LOGGING4S_CONSOLE_* variable is set" in:
      val reference = ConfigFactory.parseResources("reference.conf").resolve(ConfigResolveOptions.noSystem())

      ConsoleConfig.load(reference) shouldEqual ConsoleConfig(Threshold.At(Level.Info), Format.Json, ColorMode.Auto, Stream.Stdout, -1)

    "let LOGGING4S_CONSOLE_* variables override the reference defaults" in:
      val variables = ConfigFactory.parseMap(
        Map(
          "LOGGING4S_CONSOLE_LEVEL"                 -> "debug",
          "LOGGING4S_CONSOLE_FORMAT"                -> "plain",
          "LOGGING4S_CONSOLE_COLOR"                 -> "on",
          "LOGGING4S_CONSOLE_STREAM"                -> "stderr",
          "LOGGING4S_CONSOLE_MAX_STACK_TRACE_LINES" -> "7",
        ).asJava
      )

      val loaded = ConsoleConfig.load(variables.withFallback(ConfigFactory.parseResources("reference.conf")).resolve(ConfigResolveOptions.noSystem()))

      loaded shouldEqual ConsoleConfig(Threshold.At(Level.Debug), Format.Plain, ColorMode.On, Stream.Stderr, 7)
