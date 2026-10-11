package com.example.network

import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONObject
import java.io.StringReader

/** Bound parser depth and reject ambiguous syntax before org.json's coercing API. */
internal object StrictJson {
    private const val MAX_DEPTH = 32

    fun objectFrom(text: String): JSONObject {
        JsonReader(StringReader(text)).use { reader ->
            reader.isLenient = false
            require(reader.peek() == JsonToken.BEGIN_OBJECT) { "Expected object" }
            consume(reader, 0)
            require(reader.peek() == JsonToken.END_DOCUMENT) { "Trailing JSON" }
        }
        return JSONObject(text)
    }

    private fun consume(reader: JsonReader, depth: Int) {
        when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                require(depth < MAX_DEPTH) { "JSON nesting limit" }
                reader.beginObject()
                val names = hashSetOf<String>()
                while (reader.hasNext()) {
                    require(names.add(reader.nextName())) { "Duplicate JSON field" }
                    consume(reader, depth + 1)
                }
                reader.endObject()
            }
            JsonToken.BEGIN_ARRAY -> {
                require(depth < MAX_DEPTH) { "JSON nesting limit" }
                reader.beginArray()
                while (reader.hasNext()) consume(reader, depth + 1)
                reader.endArray()
            }
            JsonToken.NUMBER -> require(reader.nextString().toDouble().isFinite()) { "Non-finite JSON number" }
            JsonToken.STRING -> reader.nextString()
            JsonToken.BOOLEAN -> reader.nextBoolean()
            JsonToken.NULL -> reader.nextNull()
            else -> throw IllegalArgumentException("Invalid JSON value")
        }
    }
}

internal fun JSONObject.stringField(name: String): String = get(name).let {
    require(it is String) { "Invalid string field" }; it
}
internal fun JSONObject.longField(name: String): Long = get(name).let {
    require(it is Int || it is Long) { "Invalid integer field" }; (it as Number).toLong()
}
internal fun JSONObject.intField(name: String): Int = longField(name).let {
    require(it in Int.MIN_VALUE..Int.MAX_VALUE) { "Integer out of range" }; it.toInt()
}
internal fun JSONObject.booleanField(name: String): Boolean = get(name).let {
    require(it is Boolean) { "Invalid boolean field" }; it
}
internal fun JSONObject.optionalString(name: String, default: String? = null): String? =
    if (has(name)) stringField(name) else default
internal fun JSONObject.optionalLong(name: String, default: Long): Long =
    if (has(name)) longField(name) else default
internal fun JSONObject.optionalInt(name: String, default: Int): Int =
    if (has(name)) intField(name) else default
internal fun JSONObject.optionalBoolean(name: String, default: Boolean): Boolean =
    if (has(name)) booleanField(name) else default
