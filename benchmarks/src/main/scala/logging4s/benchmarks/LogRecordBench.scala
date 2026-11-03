package logging4s.benchmarks

import java.util.concurrent.TimeUnit

import scala.collection.immutable.ArraySeq

import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

import logging4s.core.syntax.all.*
import logging4s.core.{LogRecord, LoggableValue, Position}

object LogRecordBench:
  val position: Position = Position("LogRecordBench.scala", 42)

  val one: Seq[LoggableValue] = ArraySeq("u-1".asLogValue("user_id"))

  val few: Seq[LoggableValue] =
    ArraySeq("u-1".asLogValue("user_id"), "o-7".asLogValue("order_id"), 3.asLogValue("attempt"))

  val context: Seq[LoggableValue] = ArraySeq("r-9".asLogValue("request_id"), "eu".asLogValue("region"))

@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
class LogRecordBench:

  import LogRecordBench.*

  @Benchmark
  def prepareOne(bh: Blackhole): Unit =
    bh.consume(LogRecord.prepare(Nil, one, position, LogRecord.ReservedKeys))

  @Benchmark
  def prepareFew(bh: Blackhole): Unit =
    bh.consume(LogRecord.prepare(Nil, few, position, LogRecord.ReservedKeys))

  @Benchmark
  def prepareWithContext(bh: Blackhole): Unit =
    bh.consume(LogRecord.prepare(context, few, position, LogRecord.ReservedKeys))
