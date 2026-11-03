package logging4s.cats

import cats.syntax.all.*
import cats.effect.{IO, IOLocal, LiftIO, MonadCancelThrow}

import logging4s.core.{LocalContext, LoggingContext}

object IOLocalContext:

  def make: IO[LocalContext[IO]] =
    IOLocal(LoggingContext.empty).map(from)

  def from(local: IOLocal[LoggingContext]): LocalContext[IO] =
    lift[IO](local)

  def lifted[F[*]: {LiftIO, MonadCancelThrow}]: F[LocalContext[F]] =
    LiftIO[F].liftIO(IOLocal(LoggingContext.empty)).map(lift[F])

  def lift[F[*]: {LiftIO, MonadCancelThrow}](local: IOLocal[LoggingContext]): LocalContext[F] =
    new:
      override def use[A](f: LoggingContext => F[A]): F[A] = LiftIO[F].liftIO(local.get).flatMap(f)

      override def scoped[A](context: LoggingContext)(fa: F[A]): F[A] =
        val enter = LiftIO[F].liftIO(local.modify(current => (current + context, current)))
        MonadCancelThrow[F].bracket(enter)(_ => fa)(previous => LiftIO[F].liftIO(local.set(previous)))
