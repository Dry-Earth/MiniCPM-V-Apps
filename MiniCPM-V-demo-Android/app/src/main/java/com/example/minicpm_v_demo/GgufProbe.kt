package com.example.minicpm_v_demo

import android.util.Log
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream

/**
 * Minimal GGUF metadata reader.
 *
 * Only a handful of keys matter to this app, all of them near the front of the
 * file, and the app has no business pulling in a full GGUF parser for Android
 * just to answer "which MiniCPM-V is this". So this walks the key/value block
 * with a byte cap and stops as soon as it has what it needs.
 *
 * Layout (all little endian):
 *   magic "GGUF" | version u32 | tensor_count u64 | kv_count u64
 *   then kv_count x (key: u64 len + bytes | type u32 | value)
 *
 * Arrays are skipped element by element because their element type may be a
 * string, which is itself length-prefixed.
 */
object GgufProbe {

    private const val TAG = "GgufProbe"
    private const val MAX_SCAN_BYTES = 256L * 1024 * 1024

    // GGUF metadata value types.
    private const val T_UINT8 = 0
    private const val T_INT8 = 1
    private const val T_UINT16 = 2
    private const val T_INT16 = 3
    private const val T_UINT32 = 4
    private const val T_INT32 = 5
    private const val T_FLOAT32 = 6
    private const val T_BOOL = 7
    private const val T_STRING = 8
    private const val T_ARRAY = 9
    private const val T_UINT64 = 10
    private const val T_INT64 = 11
    private const val T_FLOAT64 = 12

    data class Info(
        val generalName: String? = null,
        val architecture: String? = null,
        /** `clip.projector_type` from an mmproj file. */
        val projectorType: String? = null,
        /** Model declares the inlined routed-expert layout (`les.moe.layout`). */
        val hasLesMoe: Boolean = false,
    ) {
        /**
         * MiniCPM-V family code understood by llama_jni.cpp:
         * 5 = V4.0, 46 = V4.6/4.7-A3B, 47 = V4.7-1B, 471 = OCR 1.0, 0 = unknown.
         *
         * The projector type is authoritative when present (it is what mtmd
         * dispatches on); the name is only a fallback for text-only files.
         */
        val familyVersion: Int
            get() {
                projectorType?.let { p ->
                    when (p) {
                        "minicpmv4_6" -> return 46
                        "minicpmv4_7" -> return 47
                        "minicpm_ocr_1_0" -> return 471
                    }
                }
                val n = generalName?.lowercase() ?: return 0
                return when {
                    "ocr" in n -> 471
                    "4.7-1b" in n || "4_7-1b" in n -> 47
                    "4.7" in n || "4_7" in n || "4.6" in n || "4_6" in n -> 46
                    "minicpm-v-4" in n || "minicpm_v_4" in n -> 5
                    else -> 0
                }
            }
    }

    fun read(file: File): Info = try {
        FileInputStream(file).use { fis ->
            val input = DataInputStream(BufferedInputStream(fis, 1 shl 20))
            readFrom(input)
        }
    } catch (e: Exception) {
        Log.w(TAG, "cannot probe ${file.name}: ${e.message}")
        Info()
    }

    private fun readFrom(input: DataInputStream): Info {
        val magic = ByteArray(4)
        input.readFully(magic)
        if (String(magic, Charsets.US_ASCII) != "GGUF") {
            Log.w(TAG, "not a GGUF file")
            return Info()
        }
        input.readInt()                  // version
        input.readLong()                 // tensor_count
        val kvCount = input.readLong()

        var name: String? = null
        var arch: String? = null
        var projector: String? = null
        var lesMoe = false
        var scanned = 24L

        var i = 0L
        while (i < kvCount) {
            val key = readString(input)
            scanned += 8 + key.length
            if (scanned > MAX_SCAN_BYTES) break

            val wanted = key == "general.name" ||
                key == "general.architecture" ||
                key == "clip.projector_type" ||
                key == "les.moe.layout"

            if (wanted) {
                when (val t = input.readInt()) {
                    T_STRING -> {
                        val v = readString(input)
                        scanned += v.length
                        when (key) {
                            "general.name" -> name = v
                            "general.architecture" -> arch = v
                            "clip.projector_type" -> projector = v
                        }
                    }
                    else -> {
                        // les.moe.layout may be a struct/array in some builds;
                        // its presence alone is the signal we care about.
                        if (key == "les.moe.layout") lesMoe = true
                        skipValue(input, t, 0)
                    }
                }
                if (key == "les.moe.layout") lesMoe = true
            } else {
                val t = input.readInt()
                skipValue(input, t, 0)
            }

            if (name != null && arch != null && (projector != null || key.startsWith("clip."))) {
                // Past the interesting prefix; the rest is tokenizer/vocab noise.
                break
            }
            i++
        }

        return Info(
            generalName = name,
            architecture = arch,
            projectorType = projector,
            hasLesMoe = lesMoe,
        )
    }

    private fun readString(input: DataInputStream): String {
        val len = input.readLong()
        if (len < 0 || len > 1L shl 24) throw IllegalStateException("bad string length $len")
        val bytes = ByteArray(len.toInt())
        input.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun skipValue(input: DataInputStream, type: Int, depth: Int) {
        if (depth > 4) throw IllegalStateException("value nesting too deep")
        when (type) {
            T_UINT8, T_INT8, T_BOOL -> input.skipBytes(1)
            T_UINT16, T_INT16 -> input.skipBytes(2)
            T_UINT32, T_INT32, T_FLOAT32 -> input.skipBytes(4)
            T_UINT64, T_INT64, T_FLOAT64 -> input.skipBytes(8)
            T_STRING -> {
                val len = input.readLong()
                if (len < 0) throw IllegalStateException("bad string length $len")
                var remaining = len
                while (remaining > 0) {
                    val step = input.skip(remaining)
                    if (step <= 0) {
                        // skip() may return 0 at EOF; read one byte to force progress.
                        if (input.read() < 0) break
                        remaining -= 1
                    } else {
                        remaining -= step
                    }
                }
            }
            T_ARRAY -> {
                val elemType = input.readInt()
                val count = input.readLong()
                if (count < 0) throw IllegalStateException("bad array length $count")
                // Fixed-width elements can be skipped in one go.
                val fixed = when (elemType) {
                    T_UINT8, T_INT8, T_BOOL -> 1
                    T_UINT16, T_INT16 -> 2
                    T_UINT32, T_INT32, T_FLOAT32 -> 4
                    T_UINT64, T_INT64, T_FLOAT64 -> 8
                    else -> 0
                }
                if (fixed > 0) {
                    var remaining = count * fixed
                    while (remaining > 0) {
                        val step = input.skip(remaining)
                        if (step <= 0) {
                            if (input.read() < 0) break
                            remaining -= 1
                        } else {
                            remaining -= step
                        }
                    }
                } else {
                    var k = 0L
                    while (k < count) {
                        skipValue(input, elemType, depth + 1)
                        k++
                    }
                }
            }
            else -> throw IllegalStateException("unknown GGUF value type $type")
        }
    }
}
