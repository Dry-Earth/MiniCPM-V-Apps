package com.example.minicpm_v_demo

import android.app.ActivityManager
import android.content.Context

/**
 * Device-aware defaults.
 *
 * This is not a general SoC database: the only thing that changes the numbers
 * is how much RAM the phone has, because that is what actually decides whether
 * a 4K or 8K context and a 9-slice image budget fit. The recommended values are
 * deliberately conservative — the user can raise everything afterwards in the
 * same screen.
 */
object DeviceProfile {

    data class Recommendation(
        val settings: InferenceSettings,
        val ramGb: Int,
        val summary: String,
    )

    fun totalRamGb(context: Context): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return (info.totalMem / (1024L * 1024L * 1024L)).toInt()
    }

    fun recommend(context: Context, current: InferenceSettings): Recommendation {
        val ram = totalRamGb(context)
        val base = when {
            // Big phones: enough headroom for the 4.6/4.7 8K path and a 9-slice
            // image budget, which is what the vision tower wants.
            ram >= 12 -> InferenceSettings(
                nCtx = 4096,
                maxTokens = 2048,
                nUbatch = 0,
                imageMaxSlice = 9,
                moeSlotBank = 256,
                gpuLayers = MAX_GPU,
            )
            ram >= 8 -> InferenceSettings(
                nCtx = 4096,
                maxTokens = 1536,
                nUbatch = 1024,
                imageMaxSlice = 6,
                moeSlotBank = 128,
                gpuLayers = MAX_GPU,
            )
            // 6 GB class: keep the context and the image budget small, because
            // a single 9-slice turn is ~447 tokens plus the KV growth.
            else -> InferenceSettings(
                nCtx = 2048,
                maxTokens = 1024,
                nUbatch = 512,
                imageMaxSlice = 4,
                moeSlotBank = 64,
                gpuLayers = 0,
            )
        }
        // Keep the user's prompt and thinking preference; those are not device
        // dependent.
        val merged = base.copy(
            systemPrompt = current.systemPrompt,
            enableThinking = current.enableThinking,
            temperature = current.temperature,
            topK = current.topK,
            topP = current.topP,
        )
        val summary = "RAM ${ram}GB · ctx ${merged.nCtx} · out ${merged.maxTokens} · " +
            "slice ${merged.imageMaxSlice} · slots ${merged.moeSlotBank} · GPU ${merged.gpuLayers}"
        return Recommendation(merged, ram, summary)
    }

    /**
     * 99 = "let llama.cpp put every layer it can on the GPU". llama.cpp clamps
     * this to n_layer+1 internally, so there is no need to guess the model.
     */
    const val MAX_GPU = 99
}
