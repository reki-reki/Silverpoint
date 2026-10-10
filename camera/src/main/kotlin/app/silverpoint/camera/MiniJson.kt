package app.silverpoint.camera

/**
 * Tiny JSON encoder and plain-text renderer for the probe report.
 *
 * Reports are trees of Map<String, Any?>, List and scalars. Keeping this in pure Kotlin means it can
 * be unit tested on the JVM (org.json is only a stub there).
 */
object MiniJson {
    fun encode(value: Any?, pretty: Boolean = true): String {
        val sb = StringBuilder()
        write(sb, value, pretty, 0)
        return sb.toString()
    }

    private fun write(sb: StringBuilder, value: Any?, pretty: Boolean, depth: Int) {
        when (val v = normalize(value)) {
            null -> sb.append("null")
            is Boolean -> sb.append(v)
            is Int, is Long, is Short, is Byte -> sb.append(v.toString())
            is Float -> sb.append(number(v.toDouble()))
            is Double -> sb.append(number(v))
            is Number -> sb.append(number(v.toDouble()))
            is String -> quote(sb, v)
            is Map<*, *> -> writeMap(sb, v, pretty, depth)
            is List<*> -> writeList(sb, v, pretty, depth)
            else -> quote(sb, v.toString())
        }
    }

    private fun writeMap(sb: StringBuilder, m: Map<*, *>, pretty: Boolean, depth: Int) {
        if (m.isEmpty()) {
            sb.append("{}")
            return
        }
        sb.append('{')
        var first = true
        for ((k, v) in m) {
            if (!first) sb.append(',')
            first = false
            newline(sb, pretty, depth + 1)
            quote(sb, k.toString())
            sb.append(if (pretty) ": " else ":")
            write(sb, v, pretty, depth + 1)
        }
        newline(sb, pretty, depth)
        sb.append('}')
    }

    private fun writeList(sb: StringBuilder, l: List<*>, pretty: Boolean, depth: Int) {
        if (l.isEmpty()) {
            sb.append("[]")
            return
        }
        // Lists of scalars stay on one line to keep reports short.
        val scalars = l.all { isScalar(normalize(it)) }
        sb.append('[')
        l.forEachIndexed { i, item ->
            if (i > 0) sb.append(if (pretty && scalars) ", " else ",")
            if (!scalars) newline(sb, pretty, depth + 1)
            write(sb, item, pretty, depth + 1)
        }
        if (!scalars) newline(sb, pretty, depth)
        sb.append(']')
    }

    private fun newline(sb: StringBuilder, pretty: Boolean, depth: Int) {
        if (!pretty) return
        sb.append('\n')
        repeat(depth) { sb.append("  ") }
    }

    private fun number(d: Double): String = when {
        d.isNaN() || d.isInfinite() -> "null"
        d == Math.rint(d) && Math.abs(d) < 1e15 -> d.toLong().toString()
        else -> d.toString()
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    /** Turns primitive arrays and Arrays into Lists so callers can pass raw Camera2 values. */
    internal fun normalize(value: Any?): Any? = when (value) {
        is IntArray -> value.toList()
        is LongArray -> value.toList()
        is FloatArray -> value.toList()
        is DoubleArray -> value.toList()
        is ShortArray -> value.toList()
        is ByteArray -> value.toList()
        is BooleanArray -> value.toList()
        is Array<*> -> value.toList()
        is Set<*> -> value.toList()
        else -> value
    }

    internal fun isScalar(v: Any?): Boolean = v == null || v is Boolean || v is Number || v is String
}

/** Renders the same report tree as indented plain text, easy to read and paste into an issue. */
object ReportText {
    fun render(report: Map<String, Any?>): String {
        val sb = StringBuilder()
        renderMap(sb, report, 0)
        return sb.toString()
    }

    private fun renderMap(sb: StringBuilder, m: Map<*, *>, depth: Int) {
        val pad = "  ".repeat(depth)
        for ((k, raw) in m) {
            val v = MiniJson.normalize(raw)
            when {
                v is Map<*, *> && v.isEmpty() -> sb.append(pad).append(k).append(": {}\n")
                v is Map<*, *> -> {
                    sb.append(pad).append(k).append(":\n")
                    renderMap(sb, v, depth + 1)
                }
                v is List<*> && v.all { MiniJson.isScalar(MiniJson.normalize(it)) } ->
                    sb.append(pad).append(k).append(": ").append(scalarList(v)).append('\n')
                v is List<*> -> {
                    sb.append(pad).append(k).append(":\n")
                    v.forEachIndexed { i, item ->
                        val n = MiniJson.normalize(item)
                        if (n is Map<*, *>) {
                            sb.append(pad).append("  [").append(i).append("]\n")
                            renderMap(sb, n, depth + 2)
                        } else {
                            sb.append(pad).append("  - ").append(n).append('\n')
                        }
                    }
                }
                else -> sb.append(pad).append(k).append(": ").append(v ?: "null").append('\n')
            }
        }
    }

    private fun scalarList(l: List<*>): String =
        if (l.isEmpty()) "[]" else l.joinToString(", ", "[", "]") { (MiniJson.normalize(it) ?: "null").toString() }
}
