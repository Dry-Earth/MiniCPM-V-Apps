package com.example.minicpm_v_demo

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Registry for models the user side-loaded from device storage instead of
 * downloading them from HuggingFace / ModelScope.
 *
 * Layout on disk is the same one the downloader uses, so everything above
 * this file ([LlamaEngine.modelPath], the delete flow, the loader) works
 * unchanged:
 *
 *     files/models/<custom-id>/<gguf>
 *     files/models/<custom-id>/<mmproj>   (optional)
 *
 * The registry itself is a small JSON array in SharedPreferences. Entries whose
 * GGUF has been deleted out-of-band are dropped on read, so the model list can
 * never point at a missing file.
 */
object CustomModelStore {

    private const val TAG = "CustomModelStore"
    private const val PREFS = "custom_model_registry"
    private const val KEY_ENTRIES = "entries"
    private const val ID_PREFIX = "custom-"

    /** Built-in models first, imported ones appended. */
    fun all(context: Context): List<ModelInfo> =
        ModelInfo.AVAILABLE_MODELS + load(context)

    fun load(context: Context): List<ModelInfo> {
        val raw = prefs(context).getString(KEY_ENTRIES, null) ?: return emptyList()
        val root = File(LlamaEngine.modelDir(context))
        val out = ArrayList<ModelInfo>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.getString("id")
                val ggufName = obj.getString("gguf")
                val mmprojName = obj.optString("mmproj", "").takeIf { it.isNotBlank() }
                val gguf = File(File(root, id), ggufName)
                if (!gguf.isFile) {
                    Log.w(TAG, "dropping $id: ${gguf.absolutePath} is gone")
                    continue
                }
                val mmprojMissing = mmprojName != null && !File(File(root, id), mmprojName).isFile
                out.add(
                    ModelInfo(
                        id = id,
                        displayName = obj.optString("name", ggufName),
                        descriptionResName = "",
                        ggufFileName = ggufName,
                        mmprojFileName = if (mmprojMissing) null else mmprojName,
                        isCustom = true,
                        isMoe = obj.optBoolean("moe", false),
                        customSizeBytes = obj.optLong("gguf_bytes", gguf.length()) +
                            (if (mmprojMissing) 0L else obj.optLong("mmproj_bytes", 0L)),
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "registry unreadable, ignoring", e)
        }
        return out
    }

    fun find(context: Context, id: String): ModelInfo? =
        load(context).find { it.id == id }

    /**
     * Copies [ggufUri] (and the optional [mmprojUri]) into a fresh directory and
     * registers the pair. Blocking: call from Dispatchers.IO.
     *
     * [onProgress] receives human-readable status lines for the UI.
     */
    fun import(
        context: Context,
        ggufUri: Uri,
        mmprojUri: Uri?,
        displayName: String?,
        onProgress: (String) -> Unit,
        reservedIds: Collection<String> = emptyList(),
    ): ModelInfo {
        val resolver = context.contentResolver
        val ggufName = sanitize(displayNameOf(context, ggufUri) ?: "model.gguf")
        val mmprojName = mmprojUri?.let { sanitize(displayNameOf(context, it) ?: "mmproj.gguf") }

        val id = uniqueId(context, ggufName, reservedIds)
        val dir = File(LlamaEngine.modelDir(context), id)
        if (!dir.exists() && !dir.mkdirs()) {
            throw IllegalStateException("cannot create ${dir.absolutePath}")
        }

        try {
            onProgress(ggufName)
            copyTo(resolver.openInputStream(ggufUri), File(dir, ggufName))
            if (mmprojUri != null && mmprojName != null) {
                onProgress(mmprojName)
                copyTo(resolver.openInputStream(mmprojUri), File(dir, mmprojName))
            }
        } catch (e: Exception) {
            // Never leave a half-copied directory behind: it would show up as a
            // broken model on the next launch.
            dir.deleteRecursively()
            throw e
        }

        val ggufFile = File(dir, ggufName)
        val mmprojFile = mmprojName?.let { File(dir, it) }?.takeIf { it.isFile }
        // Imported `les.moe` GGUFs need moe_slot_bank at load time, exactly like
        // the built-in 35B-A3B entry; everything else must get 0 or llama.cpp
        // rejects the load. Sniff the metadata once, at import time.
        val isMoe = declaresMoeLayout(ggufFile)

        val entry = JSONObject()
            .put("id", id)
            .put("name", displayName?.trim()?.takeIf { it.isNotEmpty() } ?: ggufName)
            .put("gguf", ggufName)
            .put("mmproj", mmprojFile?.name ?: "")
            .put("gguf_bytes", ggufFile.length())
            .put("mmproj_bytes", mmprojFile?.length() ?: 0L)
            .put("moe", isMoe)
        appendEntry(context, entry)

        return ModelInfo(
            id = id,
            displayName = entry.getString("name"),
            descriptionResName = "",
            ggufFileName = ggufName,
            mmprojFileName = mmprojFile?.name,
            isCustom = true,
            isMoe = isMoe,
            customSizeBytes = ggufFile.length() + (mmprojFile?.length() ?: 0L),
        )
    }

    /** Deletes the files and the registry entry. */
    fun remove(context: Context, id: String) {
        val root = File(LlamaEngine.modelDir(context))
        File(root, id).deleteRecursively()
        val remaining = JSONArray()
        loadEntriesRaw(context).forEach { obj ->
            if (obj.optString("id") != id) remaining.put(obj)
        }
        prefs(context).edit().putString(KEY_ENTRIES, remaining.toString()).apply()
    }

    private fun appendEntry(context: Context, entry: JSONObject) {
        val arr = JSONArray()
        loadEntriesRaw(context).forEach { arr.put(it) }
        arr.put(entry)
        prefs(context).edit().putString(KEY_ENTRIES, arr.toString()).apply()
    }

    private fun loadEntriesRaw(context: Context): List<JSONObject> {
        val raw = prefs(context).getString(KEY_ENTRIES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull {
                runCatching { arr.getJSONObject(it) }.getOrNull()
            }
        } catch (e: Exception) {
            Log.e(TAG, "registry unreadable, ignoring", e)
            emptyList()
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun copyTo(input: java.io.InputStream?, dest: File) {
        if (input == null) throw IllegalStateException("cannot open ${dest.name}")
        input.use { src ->
            dest.outputStream().use { dst -> src.copyTo(dst, DEFAULT_BUFFER_SIZE * 8) }
        }
    }

    private fun displayNameOf(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

    private fun sanitize(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        return base.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "model.gguf" }
    }

    /**
     * True when the GGUF declares the `les.moe.layout` metadata key.
     *
     * GGUF keeps its key/value block at the start of the file, so scanning the
     * first 32 MiB is enough even for a 16 GB expert file. A false negative
     * only costs the user a failed first load; a false positive would make
     * llama.cpp refuse a dense model, so the needle is the full key name.
     */
    private fun declaresMoeLayout(file: File): Boolean {
        val needle = "les.moe.layout".toByteArray(Charsets.US_ASCII)
        return try {
            file.inputStream().use { input ->
                val head = ByteArray(32 * 1024 * 1024)
                var filled = 0
                while (filled < head.size) {
                    val n = input.read(head, filled, head.size - filled)
                    if (n <= 0) break
                    filled += n
                }
                indexOf(head, filled, needle) >= 0
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not sniff ${file.name} for les.moe.layout", e)
            false
        }
    }

    private fun indexOf(haystack: ByteArray, length: Int, needle: ByteArray): Int {
        if (needle.isEmpty() || length < needle.size) return -1
        outer@ for (i in 0..(length - needle.size)) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun uniqueId(context: Context, ggufName: String, reservedIds: Collection<String>): String {
        val stem = ggufName.substringBeforeLast('.').take(24)
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        var candidate = ID_PREFIX + stem
        var n = 2
        val taken = (load(context).map { it.id } + reservedIds).toSet()
        while (candidate in taken || File(LlamaEngine.modelDir(context), candidate).exists()) {
            candidate = "$ID_PREFIX$stem-$n"
            n++
        }
        return candidate
    }
}
