package logging4s.core

object StructuredJson:

  def line(stringFields: Iterable[(String, String)], values: Iterable[LoggableValue]): String =
    val builder = new Builder
    builder.fields(stringFields)
    builder.values(values)
    builder.result
  end line

  def capacity(text: String, values: Iterable[LoggableValue]): Int =
    var size = 192 + text.length
    values.foreach(value => size += value.key.value.length + value.json.value.length + 4)
    size

  final class Builder(capacity: Int):
    private val sb     = new java.lang.StringBuilder(capacity).append('{')
    private var first  = true
    private var closed = false

    def this() = this(256)

    private def separate(): Unit =
      if !first then sb.append(','): Unit
      first = false

    def field(key: String, value: String): Unit =
      separate()
      JsonString.appendQuoted(JsonString.appendQuoted(sb, key).append(':'), value): Unit

    def fields(entries: Iterable[(String, String)]): Unit =
      entries.foreach((key, value) => field(key, value))

    def value(value: LoggableValue): Unit =
      separate()
      JsonString.appendQuoted(sb, value.key.value).append(':').append(value.json.value): Unit

    def values(values: Iterable[LoggableValue]): Unit =
      values.foreach(value)

    def result: String =
      close()
      sb.toString

    def resultWithNewline: String =
      close()
      val length = sb.length
      val text   = sb.append('\n').toString
      sb.setLength(length)
      text

    private def close(): Unit =
      if !closed
      then
        sb.append('}'): Unit
        closed = true
