package logging4s.cats

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers

import cats.data.Kleisli
import cats.syntax.all.*
import cats.effect.{IO, IOLocal}
import cats.effect.unsafe.implicits.global

import logging4s.core.{Logging, LoggingContext}
import logging4s.core.syntax.all.*
import logging4s.core.testing.RecordingLogging

import CatsInstances.given

class IOLocalContextSpec extends AnyWordSpec, Matchers:
  import IOLocalContextSpec.*

  "IOLocalContext" must:
    "add the scoped context to the records made inside the scope only" in:
      val base = RecordingLogging[IO]()

      val program =
        for
          local <- IOLocalContext.make
          log    = Logging.contextual(base)(using local)
          _     <- local.scoped("r-1".asLogValue("request_id"))(log.info("inside"))
          _     <- log.info("outside")
        yield ()

      program.unsafeRunSync()

      base.recorded.map(_.keys) shouldEqual Vector(Seq("request_id"), Nil)

    "carry the context into a fiber started inside the scope" in:
      val base = RecordingLogging[IO]()

      val program =
        for
          local <- IOLocalContext.make
          log    = Logging.contextual(base)(using local)
          _     <- local.scoped("r-1".asLogValue("request_id"))(log.info("child").start.flatMap(_.join))
        yield ()

      program.unsafeRunSync()

      base.recorded.map(_.keys) shouldEqual Vector(Seq("request_id"))

    "restore the previous context when the scoped effect fails" in:
      val base = RecordingLogging[IO]()

      val program =
        for
          local <- IOLocalContext.make
          log    = Logging.contextual(base)(using local)
          _     <- local.scoped("r-1".asLogValue("request_id"))(IO.raiseError[Unit](new RuntimeException("boom"))).attempt
          _     <- log.info("after")
        yield ()

      program.unsafeRunSync()

      base.recorded.map(_.keys) shouldEqual Vector(Nil)

    "scope an effect of a tagless stack over IO, here a Kleisli" in:
      val base = RecordingLogging[App]()

      val program: App[Unit] =
        for
          local <- IOLocalContext.lifted[App]
          log    = Logging.contextual(base)(using local)
          inside = Kleisli.ask[IO, String].flatMap(user => log.info("inside", user.asLogValue("user")))
          _     <- local.scoped("r-1".asLogValue("request_id"))(inside)
          _     <- log.info("outside")
        yield ()

      program.run("u-1").unsafeRunSync()

      base.recorded.map(_.keys) shouldEqual Vector(Seq("request_id", "user"), Nil)

    "restore the previous context when a lifted scoped effect fails" in:
      val base = RecordingLogging[App]()

      val program: App[Unit] =
        for
          local <- IOLocalContext.lifted[App]
          log    = Logging.contextual(base)(using local)
          _     <- local.scoped("r-1".asLogValue("request_id"))(Kleisli.liftF[IO, String, Unit](IO.raiseError(new RuntimeException("boom")))).attempt
          _     <- log.info("after")
        yield ()

      program.run("u-1").unsafeRunSync()

      base.recorded.map(_.keys) shouldEqual Vector(Nil)

    "share one IOLocal between code in IO and code in a lifted effect" in:
      val base = RecordingLogging[App]()

      val program =
        for
          local <- IOLocal(LoggingContext.empty)
          log    = Logging.contextual(base)(using IOLocalContext.lift[App](local))
          _     <- IOLocalContext.from(local).scoped("r-1".asLogValue("request_id"))(log.info("inside").run("u-1"))
        yield ()

      program.unsafeRunSync()

      base.recorded.map(_.keys) shouldEqual Vector(Seq("request_id"))

object IOLocalContextSpec:
  type App[A] = Kleisli[IO, String, A]
