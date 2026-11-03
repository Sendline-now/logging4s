package logging4s.log4j2

import org.apache.logging.log4j.message.StringMapMessage

import logging4s.core.JsonString

final class LoggableMapMessage(
    entries: Seq[(String, String)],
    plainMessage: String,
) extends StringMapMessage(entries.size):

  entries.foreach((key, value) => put(key, value))

  override def getFormattedMessage: String = plainMessage

  override def formatTo(buffer: java.lang.StringBuilder): Unit = buffer.append(plainMessage): Unit

  override def getFormattedMessage(formats: Array[String]): String =
    if formats != null && formats.exists(_.equalsIgnoreCase("JSON"))
    then json
    else super.getFormattedMessage(formats)

  private def json: String =
    var size = 2
    entries.foreach((key, value) => size += key.length + value.length + 4)

    val sb = new java.lang.StringBuilder(size).append('{')

    entries.foreach { (key, value) =>
      if sb.length > 1 then sb.append(','): Unit
      sb.append(JsonString.quoted(key).value).append(':').append(value): Unit
    }

    sb.append('}').toString
