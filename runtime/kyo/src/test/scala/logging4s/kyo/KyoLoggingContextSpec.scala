package logging4s.kyo

import scala.concurrent.duration.DurationInt

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers

import kyo.{<, Abort, AllowUnsafe, Async, Duration, KyoApp, Result, Sync}

import logging4s.core.{LocalContext, Logging}
import logging4s.core.syntax.all.*
import logging4s.core.testing.RecordingLogging

import KyoInstances.given

class KyoLoggingContextSpec extends AnyWordSpec, Matchers:

  private given AllowUnsafe = AllowUnsafe.embrace.danger

  private def run[A](io: KIO[A]): A = Sync.Unsafe.evalOrThrow(io)

  "KyoLoggingContext" must:
    "add the scoped context to the records made inside the scope only" in:
      val base = RecordingLogging[KIO]()
      val log  = Logging.contextual(base)

      val program =
        for
          _ <- KyoLoggingContext.scoped("r-1".asLogValue("request_id"))(log.info("inside"))
          _ <- log.info("outside")
        yield ()

      run(program)

      base.recorded.map(_.keys) shouldEqual Vector(Seq("request_id"), Nil)

    "nest scopes, the inner one adding to the outer" in:
      val base = RecordingLogging[KIO]()
      val log  = Logging.contextual(base)

      run(KyoLoggingContext.scoped("r-1".asLogValue("request_id"))(KyoLoggingContext.scoped("u-1".asLogValue("user"))(log.info("x"))))

      base.recorded.map(_.keys) shouldEqual Vector(Seq("request_id", "user"))

    "let an inner scope override a key of the outer one and give it back on exit" in:
      val base = RecordingLogging[KIO]()
      val log  = Logging.contextual(base)

      val inner = KyoLoggingContext.scoped("r-2".asLogValue("request_id"))(log.info("inner"))

      run(KyoLoggingContext.scoped("r-1".asLogValue("request_id"))(log.info("outer").andThen(inner).andThen(log.info("outer again"))))

      base.recorded.map(_.plainOf("request_id")) shouldEqual Vector(Some("r-1"), Some("r-2"), Some("r-1"))

    "carry the context into the fibers of a parallel foreach" in:
      val base = RecordingLogging[KIO]()
      val log  = Logging.contextual(base)

      val program = KyoLoggingContext.scoped("r-1".asLogValue("request_id")) {
        Async.foreachDiscard(Seq("a", "b", "c"))(item => log.info("item", item.asLogValue("item")))
      }

      KyoApp.Unsafe.runAndBlock(Duration.fromScala(5.seconds))(program).getOrThrow

      base.recorded.map(_.keys) shouldEqual Vector.fill(3)(Seq("request_id", "item"))

    "expose the current context" in:
      run(KyoLoggingContext.scoped("r-1".asLogValue("request_id"))(KyoLoggingContext.get)).values.map(_.key.value) shouldEqual Seq("request_id")

    "give any A < S a LocalContext, so a logger mapped into it can be made contextual" in:
      type App[A] = A < (Sync & Abort[String])

      val base = RecordingLogging[KIO]()
      val log  = Logging.contextual(base.mapK[App]([A] => (fa: KIO[A]) => fa))

      run(Abort.run[String](LocalContext[App].scoped("r-1".asLogValue("request_id"))(log.info("x")))) shouldEqual Result.succeed(())

      base.recorded.map(_.keys) shouldEqual Vector(Seq("request_id"))

    "keep the dedicated KIO instance ahead of the generic one" in:
      LocalContext[KIO] should be theSameInstanceAs KyoInstances.KioLocalContext
