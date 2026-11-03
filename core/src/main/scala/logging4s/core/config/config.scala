package logging4s.core.config

import java.util.Locale
import java.util.regex.Pattern
import java.util.concurrent.ConcurrentHashMap

import logging4s.core.{LoggableValue, PlainString}

enum KeyNameStyle:
  case AsIs, SnakeCase, KebabCase, CamelCase, PascalCase

  def format(name: String): String =
    if this == AsIs then name
    else
      val cache  = KeyNameStyle.cacheFor(this)
      val cached = cache.get(name)

      if cached != null then cached
      else
        val formatted = reformat(name)
        if cache.size < KeyNameStyle.MaxCachedKeys then cache.putIfAbsent(name, formatted): Unit
        formatted

  private def reformat(name: String): String =
    this match
      case AsIs       => name
      case SnakeCase  => words(name).map(_.toLowerCase(Locale.ROOT)).mkString("_")
      case KebabCase  => words(name).map(_.toLowerCase(Locale.ROOT)).mkString("-")
      case PascalCase => words(name).map(capitalize).mkString
      case CamelCase  =>
        words(name) match
          case Nil          => ""
          case head :: tail => (head.toLowerCase(Locale.ROOT) +: tail.map(capitalize)).mkString

object KeyNameStyle:
  private val MaxCachedKeys = 1024

  private lazy val caches: Array[ConcurrentHashMap[String, String]] =
    Array.fill(values.length)(new ConcurrentHashMap[String, String])

  private def cacheFor(style: KeyNameStyle): ConcurrentHashMap[String, String] = caches(style.ordinal)

private val AcronymBoundary = Pattern.compile("([A-Z]+)([A-Z][a-z])")
private val CamelBoundary   = Pattern.compile("([a-z0-9])([A-Z])")
private val WordSeparator   = Pattern.compile("[_\\s-]+")

private[config] def words(name: String): List[String] =
  val withAcronymBoundary = AcronymBoundary.matcher(name).replaceAll("$1_$2")
  val withCamelBoundary   = CamelBoundary.matcher(withAcronymBoundary).replaceAll("$1_$2")
  WordSeparator
    .split(withCamelBoundary)
    .iterator
    .filter(_.nonEmpty)
    .toList

private def needsLogfmtQuoting(value: String): Boolean =
  value.isEmpty || value.exists(c => c.isWhitespace || c == '"' || c == '=' || c == '\\')

private[config] def logfmt(value: String): String =
  if !needsLogfmtQuoting(value)
  then value
  else
    val sb = new java.lang.StringBuilder(value.length + 8).append('"')
    value.foreach {
      case '"'  => sb.append("\\\""): Unit
      case '\\' => sb.append("\\\\"): Unit
      case '\n' => sb.append("\\n"): Unit
      case '\r' => sb.append("\\r"): Unit
      case '\t' => sb.append("\\t"): Unit
      case c    => sb.append(c): Unit
    }
    sb.append('"').toString

private[config] def capitalize(word: String): String =
  if word.isEmpty then word else s"${word.head.toUpper}${word.tail.toLowerCase(Locale.ROOT)}"

enum PlainTupleStyle:
  case AsScala, AsArray, Bare, Braces

  def render(elements: Seq[PlainString]): PlainString =
    this match
      case AsScala => PlainString(elements.mkString("(", ", ", ")"))
      case AsArray => PlainString(elements.mkString("[", ", ", "]"))
      case Bare    => PlainString(elements.mkString(", "))
      case Braces  => PlainString(elements.mkString("{", ", ", "}"))

enum PlainValuesStyle:
  case Arrow, ArrowBare, Logfmt, KeyValueComma, Colon, Bracketed, Parens, CurlyMap

  def render(values: Seq[LoggableValue]): String =
    appendValues(new java.lang.StringBuilder(32 * values.size), values).toString

  def renderFields(fields: Seq[(String, String)]): String =
    val sb    = open(new java.lang.StringBuilder(32 * fields.size))
    var first = true

    fields.foreach { (key, value) =>
      field(sb, key, value, first)
      first = false
    }

    close(sb).toString

  private[core] def appendValues(sb: java.lang.StringBuilder, values: Seq[LoggableValue]): java.lang.StringBuilder =
    var first = true
    open(sb): Unit

    values.foreach { value =>
      field(sb, value.key.value, value.plain.value, first)
      first = false
    }

    close(sb)

  private[core] def open(sb: java.lang.StringBuilder): java.lang.StringBuilder =
    if this == CurlyMap then sb.append('{') else sb

  private[core] def close(sb: java.lang.StringBuilder): java.lang.StringBuilder =
    if this == CurlyMap then sb.append('}') else sb

  private[core] def field(sb: java.lang.StringBuilder, key: String, value: String, first: Boolean): Unit =
    if !first then sb.append(separator): Unit

    this match
      case Arrow         => sb.append(key).append(" -> (").append(value).append(')'): Unit
      case ArrowBare     => sb.append(key).append(" -> ").append(value): Unit
      case Logfmt        => sb.append(key).append('=').append(logfmt(value)): Unit
      case KeyValueComma => sb.append(key).append('=').append(value): Unit
      case Colon         => sb.append(key).append(": ").append(value): Unit
      case Bracketed     => sb.append('[').append(key).append('=').append(value).append(']'): Unit
      case Parens        => sb.append(key).append('(').append(value).append(')'): Unit
      case CurlyMap      => sb.append(key).append('=').append(value): Unit

  private def separator: String =
    this match
      case Logfmt | Bracketed | Parens => " "
      case _                           => ", "

final case class LoggableEncodingConfig(
    jsonTupleAsArray: Boolean = true,
    keyNameStyle: KeyNameStyle = KeyNameStyle.SnakeCase,
    plainTupleStyle: PlainTupleStyle = PlainTupleStyle.AsScala,
    plainValuesStyle: PlainValuesStyle = PlainValuesStyle.Arrow,
    includeSourcePosition: Boolean = true,
    mapAsObject: Boolean = true,
    includeValuesInMessage: Boolean = true,
    omitAbsentValues: Boolean = false,
    sumTypeDiscriminator: Option[String] = None,
)

object LoggableEncodingConfig:
  val Default: LoggableEncodingConfig = LoggableEncodingConfig()

  given default: LoggableEncodingConfig = Default
