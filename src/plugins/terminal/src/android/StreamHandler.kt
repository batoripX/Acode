package com.foxdebug.acode.rk.exec.terminal

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream

object StreamHandler {

    fun interface OutputListener {
        fun onLine(line: String)
    }

    /**
     * Streams output from an InputStream to a listener
     */
    fun streamOutput(inputStream: InputStream, listener: OutputListener) {
        try {
            BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                var line: BufferedReader.() -> String? = reader::readLine
                // Alternatively, reader.forEachLine { listener.onLine(it) }
                while (true) {
                    val lineStr = reader.readLine() ?: break
                    listener.onLine(lineStr)
                }
            }
        } catch (_: IOException) {
            // Silently ignore stream closures or thread interruptions
        }
    }

    /**
     * Writes input to an OutputStream
     */
    @Throws(IOException::class)
    fun writeToStream(outputStream: OutputStream, input: String) {
        outputStream.write("$input\n".toByteArray(Charsets.UTF_8))
        outputStream.flush()
    }
}
