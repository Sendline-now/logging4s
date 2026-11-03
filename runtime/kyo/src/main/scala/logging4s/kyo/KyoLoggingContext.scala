package logging4s.kyo

import scala.annotation.targetName

import kyo.{<, Frame, Local}

import logging4s.core.{LocalContext, LoggableValue, LoggingContext}

object KyoLoggingContext:

  private val local: Local[LoggingContext] = Local.init(LoggingContext.empty)

  def get(using Frame): LoggingContext < Any = local.get

  def use[A, S](f: LoggingContext => A < S)(using Frame): A < S = local.use(f)

  def scoped[A, S](context: LoggingContext)(v: A < S)(using Frame): A < S = local.update(_ + context)(v)

  @targetName("scopedValues")
  def scoped[A, S](values: LoggableValue*)(v: A < S)(using Frame): A < S = scoped(LoggingContext(values))(v)

trait LocalContextInstance:

  given KioLocalContext: LocalContext[KIO] = new:
    override def use[A](f: LoggingContext => KIO[A]): KIO[A]            = KyoLoggingContext.use(f)
    override def scoped[A](context: LoggingContext)(fa: KIO[A]): KIO[A] = KyoLoggingContext.scoped(context)(fa)

  given KyoLocalContext: [S] => LocalContext[[A] =>> A < S] = new:
    override def use[A](f: LoggingContext => A < S): A < S            = KyoLoggingContext.use(f)
    override def scoped[A](context: LoggingContext)(fa: A < S): A < S = KyoLoggingContext.scoped(context)(fa)

object LocalContextInstance extends LocalContextInstance
