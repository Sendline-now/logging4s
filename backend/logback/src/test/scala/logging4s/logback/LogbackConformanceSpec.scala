package logging4s.logback

import java.io.ByteArrayOutputStream

import scala.util.Try

import org.slf4j.LoggerFactory
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.OutputStreamAppender
import ch.qos.logback.classic.{Level as LogbackLevel, Logger as LogbackLogger}

import logging4s.core.{Delay, Identity, Logging, LoggingContext}
import logging4s.core.conformance.{BackendConformance, CapturedRecord, JsonRecord}

import LogbackInstances.given

class LogbackConformanceSpec extends BackendConformance:

  LogbackWarmup.touch()

  override def backendName: String = "logback"

  override def atInfo(loggerName: String)(using Delay[Identity]): Logging[Identity] =
    LoggerFactory.getLogger(loggerName).asInstanceOf[LogbackLogger].setLevel(LogbackLevel.INFO)
    Logging.create[Identity](loggerName)

  override def capture(loggerName: String, context: LoggingContext)(run: Logging[Try] => Unit): CapturedRecord =
    LoggingLogbackJsonSpec.appenderLock.synchronized {
      val logbackLogger = LoggerFactory.getLogger(loggerName).asInstanceOf[LogbackLogger]
      logbackLogger.setLevel(LogbackLevel.TRACE)
      logbackLogger.setAdditive(false)

      val loggerContext = logbackLogger.getLoggerContext
      val out           = new ByteArrayOutputStream()

      val encoder = new Logging4sEncoder()
      encoder.setContext(loggerContext)
      encoder.start()

      val appender = new OutputStreamAppender[ILoggingEvent]()
      appender.setContext(loggerContext)
      appender.setEncoder(encoder)
      appender.setOutputStream(out)
      appender.start()

      logbackLogger.addAppender(appender)
      try run(Logging.createTry(loggerName, context).get)
      finally
        appender.stop()
        logbackLogger.detachAppender(appender)

      JsonRecord.parse(out.toString("UTF-8").linesIterator.next())
    }
