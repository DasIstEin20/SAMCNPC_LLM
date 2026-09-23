package io.samcnpc.llm.expression

import io.samcnpc.llm.provider.LlmJson
import java.math.BigDecimal

internal sealed interface SamValue {
    data class Call(val name: String, val arguments: List<Argument>) : SamValue
    data class Argument(val name: String?, val value: SamValue)
    data class Text(val value: String) : SamValue
    data class Number(val text: String) : SamValue {
        val decimal: BigDecimal get() = BigDecimal(text)
        val integer: Boolean get() = '.' !in text
    }
    data class Flag(val value: Boolean) : SamValue
    data object Null : SamValue
    data class Sequence(val values: List<SamValue>) : SamValue
    data class Coordinates(val values: List<Number>) : SamValue
}

/** A data grammar only. No name resolution, reflection, world access or executable expressions. */
internal object SamExpressionSyntax {
    const val MAX_BYTES = 16384
    const val MAX_TOKENS = 8192
    const val MAX_NODES = 4096
    const val MAX_DEPTH = 16
    const val MAX_LIST = 128
    const val MAX_ARGUMENTS = 64
    const val MAX_STRING = 1024

    fun parse(source: String): SamValue.Call {
        require(source.length <= MAX_BYTES && LlmJson.utf8(source).size <= MAX_BYTES) { "EXPRESSION_SIZE" }
        return Cursor(Lexer(source).tokens()).parse()
    }

    private enum class Kind { ID, STRING, NUMBER, LEFT, RIGHT, OPEN, CLOSE, COMMA, EQUAL, END }
    private data class Token(val kind: Kind, val text: String = "")

    private class Lexer(private val source: String) {
        private var at = 0
        fun tokens(): List<Token> {
            val output = ArrayList<Token>()
            while (at < source.length) {
                val c = source[at]
                if (c in " \t\r\n") { at++; continue }
                require(output.size < MAX_TOKENS) { "EXPRESSION_TOKENS" }
                val token = when (c) {
                    '(' -> punctuation(Kind.LEFT)
                    ')' -> punctuation(Kind.RIGHT)
                    '[' -> punctuation(Kind.OPEN)
                    ']' -> punctuation(Kind.CLOSE)
                    ',' -> punctuation(Kind.COMMA)
                    '=' -> punctuation(Kind.EQUAL)
                    '\'', '"' -> string(c)
                    '-', in '0'..'9' -> number()
                    in 'a'..'z', in 'A'..'Z', '_' -> identifier()
                    else -> throw IllegalArgumentException("EXPRESSION_CHARACTER")
                }
                output.add(token)
            }
            output.add(Token(Kind.END))
            return output
        }
        private fun punctuation(kind: Kind): Token { at++; return Token(kind) }
        private fun identifier(): Token {
            val start = at++
            while (at < source.length && (source[at] in 'a'..'z' || source[at] in 'A'..'Z' ||
                        source[at] in '0'..'9' || source[at] == '_')) at++
            require(at - start <= 64) { "EXPRESSION_IDENTIFIER" }
            return Token(Kind.ID, source.substring(start, at))
        }
        private fun number(): Token {
            val start = at
            if (source[at] == '-') at++
            require(at < source.length && source[at] in '0'..'9') { "EXPRESSION_NUMBER" }
            if (source[at] == '0') at++ else while (at < source.length && source[at] in '0'..'9') {
                at++; require(at - start <= 32) { "EXPRESSION_NUMBER" }
            }
            if (at < source.length && source[at] == '.') {
                at++
                val fraction = at
                while (at < source.length && source[at] in '0'..'9') {
                    at++; require(at - start <= 32) { "EXPRESSION_NUMBER" }
                }
                require(at > fraction) { "EXPRESSION_NUMBER" }
            }
            require(at - start <= 32) { "EXPRESSION_NUMBER" }
            val text = source.substring(start, at)
            require(BigDecimal(text).abs() <= BigDecimal(30000000)) { "EXPRESSION_NUMBER_RANGE" }
            return Token(Kind.NUMBER, text)
        }
        private fun string(quote: Char): Token {
            at++
            val value = StringBuilder()
            while (at < source.length) {
                val c = source[at++]
                if (c == quote) {
                    LlmJson.utf8(value.toString())
                    return Token(Kind.STRING, value.toString())
                }
                require(c.code >= 32) { "EXPRESSION_STRING_CONTROL" }
                if (c != '\\') value.append(c) else {
                    require(at < source.length) { "EXPRESSION_ESCAPE" }
                    when (val escape = source[at++]) {
                        '\\', '/', '\'', '"' -> value.append(escape)
                        'b' -> value.append('\b')
                        'f' -> value.append('\u000c')
                        'n' -> value.append('\n')
                        'r' -> value.append('\r')
                        't' -> value.append('\t')
                        'u' -> {
                            require(at + 4 <= source.length) { "EXPRESSION_ESCAPE" }
                            val hex = source.substring(at, at + 4)
                            require(hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) { "EXPRESSION_ESCAPE" }
                            value.append(hex.toInt(16).toChar()); at += 4
                        }
                        else -> throw IllegalArgumentException("EXPRESSION_ESCAPE")
                    }
                }
                require(value.length <= MAX_STRING) { "EXPRESSION_STRING_SIZE" }
            }
            throw IllegalArgumentException("EXPRESSION_UNCLOSED_STRING")
        }
    }

    private class Cursor(private val tokens: List<Token>) {
        private var at = 0
        private var nodes = 0
        private fun peek() = tokens[at]
        private fun take(kind: Kind): Token {
            val token = peek()
            require(token.kind == kind) { "EXPRESSION_EXPECTED_" + kind.name }
            at++
            return token
        }
        private fun accept(kind: Kind): Boolean {
            if (peek().kind != kind) return false
            at++; return true
        }
        fun parse(): SamValue.Call {
            val result = value(0)
            require(result is SamValue.Call) { "EXPRESSION_TOP_LEVEL" }
            take(Kind.END)
            return result
        }
        private fun value(depth: Int): SamValue {
            require(depth <= MAX_DEPTH && ++nodes <= MAX_NODES) { "EXPRESSION_COMPLEXITY" }
            return when (peek().kind) {
                Kind.STRING -> SamValue.Text(take(Kind.STRING).text)
                Kind.NUMBER -> SamValue.Number(take(Kind.NUMBER).text)
                Kind.ID -> when (val name = take(Kind.ID).text) {
                    "True" -> SamValue.Flag(true)
                    "False" -> SamValue.Flag(false)
                    "None" -> SamValue.Null
                    else -> call(name, depth)
                }
                Kind.OPEN -> {
                    take(Kind.OPEN)
                    val values = mutableListOf<SamValue>()
                    if (!accept(Kind.CLOSE)) do {
                        require(values.size < MAX_LIST) { "EXPRESSION_LIST_SIZE" }
                        values.add(value(depth + 1))
                        if (accept(Kind.CLOSE)) break
                        take(Kind.COMMA)
                    } while (!accept(Kind.CLOSE))
                    SamValue.Sequence(values.toList())
                }
                Kind.LEFT -> {
                    take(Kind.LEFT)
                    val values = (0..2).map { index ->
                        if (index > 0) take(Kind.COMMA)
                        val coordinate = value(depth + 1)
                        require(coordinate is SamValue.Number) { "EXPRESSION_COORDINATE" }
                        coordinate
                    }
                    accept(Kind.COMMA); take(Kind.RIGHT)
                    SamValue.Coordinates(values)
                }
                else -> throw IllegalArgumentException("EXPRESSION_VALUE")
            }
        }
        private fun call(name: String, depth: Int): SamValue.Call {
            take(Kind.LEFT)
            val arguments = mutableListOf<SamValue.Argument>()
            val names = mutableSetOf<String>()
            var keywords = false
            if (!accept(Kind.RIGHT)) do {
                require(arguments.size < MAX_ARGUMENTS) { "EXPRESSION_ARGUMENTS" }
                val key = if (peek().kind == Kind.ID && tokens[at + 1].kind == Kind.EQUAL) {
                    val nameToken = take(Kind.ID).text
                    take(Kind.EQUAL)
                    require(names.add(nameToken)) { "EXPRESSION_DUPLICATE_ARGUMENT" }
                    keywords = true
                    nameToken
                } else {
                    require(!keywords) { "EXPRESSION_POSITIONAL_AFTER_KEYWORD" }
                    null
                }
                arguments.add(SamValue.Argument(key, value(depth + 1)))
                if (accept(Kind.RIGHT)) break
                take(Kind.COMMA)
            } while (!accept(Kind.RIGHT))
            return SamValue.Call(name, arguments.toList())
        }
    }
}
