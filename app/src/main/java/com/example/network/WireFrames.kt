package com.example.network

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Limits bytes while reading, before allocating an unbounded JSON line. */
object WireFrames {
    const val MAX_BYTES = 1024 * 1024

    fun read(input: InputStream): String {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val next = input.read()
            if (next == -1) throw java.io.EOFException("Peer closed the connection")
            if (next == 10) break
            if (bytes.size() >= MAX_BYTES) throw SecurityException("Frame too large")
            bytes.write(next)
        }
        return Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
    }

    fun write(output: OutputStream, frame: String) {
        val bytes = frame.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "Frame too large" }
        synchronized(output) {
            output.write(bytes)
            output.write(10)
            output.flush()
        }
    }
}
