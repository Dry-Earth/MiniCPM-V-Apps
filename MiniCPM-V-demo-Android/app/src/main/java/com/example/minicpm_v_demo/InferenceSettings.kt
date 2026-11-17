package com.example.minicpm_v_demo

import android.content.Context

/**
 * Every runtime knob the native layer exposes, in one place.
 *
 * Values are read twice: once at model load ([LlamaEngine.loadModel] reads
 * n_ctx / temp / top_k / top_p / n_ubatch / moe_slot_bank) and once per turn
 * (the token budget). So a change that only affects sampling can be applied
 * without a reload, while context / batch / slot-bank changes need one.
 *
 * Defaults mirror the previous hardcoded behaviour so an untouched install
 * behaves exactly like the upstream demo.
 */
data class InferenceSettings(
    /** 0 = let the native layer pick (4096, or the 4.6-family 8192). */
    val nCtx: Int = 0,
    /** Per-turn token budget. 0 = unlimited (bounded by the context). */
    val maxTokens: Int = DEFAULT_MAX_TOKENS,
    val temperature: Float = DEFAULT_TEMPERATURE,
    val topK: Int = DEFAULT_TOP_K,
    val topP: Float = DEFAULT_TOP_P,
    /** 0 = native default (2048). */
    val nUbatch: Int = 0,
    val imageMaxSlice: Int = MAX_IMAGE_SLICE,
    /** Resident routed-expert slots for `les.moe` GGUFs. 0 = model default. */
    val moeSlotBank: Int = 0,
    val enableThinking: Boolean = false,
    /** Empty = no system prompt is injected (upstream behaviour). */
    val systemPrompt: String = "",
) {
    /** Effective budget handed to the native prefill; 0 means "fill the context". */
    fun effectiveMaxTokens(): Int =
        if (maxTokens <= 0) UNLIMITED_TOKENS else maxTokens

    companion object {
        const val DEFAULT_MAX_TOKENS = 1024
        const val DEFAULT_TEMPERATURE = 0.7f
        const val DEFAULT_TOP_K = 0
        const val DEFAULT_TOP_P = 1.0f

        // Slider bounds. Kept here so the UI and the sanitiser agree.
        const val MIN_N_CTX = 0
        const val MAX_N_CTX = 32768
        const val N_CTX_STEP = 1024
        const val MIN_MAX_TOKENS = 256
        const val MAX_MAX_TOKENS = 16384
        const val MAX_TOKENS_STEP = 256
        const val MAX_UBATCH = 2048
        const val UBATCH_STEP = 64
        const val MIN_IMAGE_SLICE = 1
        const val MAX_IMAGE_SLICE = 9
        const val MAX_MOE_SLOT_BANK = 256
        const val MOE_SLOT_BANK_STEP = 8

        // Anything this large is "no practical cap"; the context window and the
        // stop tokens still end the turn.
        const val UNLIMITED_TOKENS = 100_000

        private const val PREFS = "inference_settings"
        private const val KEY_N_CTX = "n_ctx"
        private const val KEY_MAX_TOKENS = "max_tokens"
        private const val KEY_TEMP = "temperature"
        private const val KEY_TOP_K = "top_k"
        private const val KEY_TOP_P = "top_p"
        private const val KEY_UBATCH = "n_ubatch"
        private const val KEY_IMAGE_SLICE = "image_max_slice"
        private const val KEY_MOE_SLOT_BANK = "moe_slot_bank"
        private const val KEY_THINKING = "enable_thinking"
        private const val KEY_SYSTEM_PROMPT = "system_prompt"

        fun load(context: Context): InferenceSettings {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val d = InferenceSettings()
            return InferenceSettings(
                nCtx = p.getInt(KEY_N_CTX, d.nCtx).coerceIn(MIN_N_CTX, MAX_N_CTX),
                maxTokens = p.getInt(KEY_MAX_TOKENS, d.maxTokens)
                    .coerceIn(0, MAX_MAX_TOKENS),
                temperature = p.getFloat(KEY_TEMP, d.temperature).coerceIn(0f, 2f),
                topK = p.getInt(KEY_TOP_K, d.topK).coerceIn(0, 100),
                topP = p.getFloat(KEY_TOP_P, d.topP).coerceIn(0f, 1f),
                nUbatch = p.getInt(KEY_UBATCH, d.nUbatch).coerceIn(0, MAX_UBATCH),
                imageMaxSlice = p.getInt(KEY_IMAGE_SLICE, d.imageMaxSlice)
                    .coerceIn(MIN_IMAGE_SLICE, MAX_IMAGE_SLICE),
                moeSlotBank = p.getInt(KEY_MOE_SLOT_BANK, d.moeSlotBank)
                    .coerceIn(0, MAX_MOE_SLOT_BANK),
                enableThinking = p.getBoolean(KEY_THINKING, d.enableThinking),
                systemPrompt = p.getString(KEY_SYSTEM_PROMPT, d.systemPrompt) ?: "",
            )
        }

        fun save(context: Context, settings: InferenceSettings) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_N_CTX, settings.nCtx)
                .putInt(KEY_MAX_TOKENS, settings.maxTokens)
                .putFloat(KEY_TEMP, settings.temperature)
                .putInt(KEY_TOP_K, settings.topK)
                .putFloat(KEY_TOP_P, settings.topP)
                .putInt(KEY_UBATCH, settings.nUbatch)
                .putInt(KEY_IMAGE_SLICE, settings.imageMaxSlice)
                .putInt(KEY_MOE_SLOT_BANK, settings.moeSlotBank)
                .putBoolean(KEY_THINKING, settings.enableThinking)
                .putString(KEY_SYSTEM_PROMPT, settings.systemPrompt)
                .apply()
        }

        fun reset(context: Context): InferenceSettings {
            val fresh = InferenceSettings()
            save(context, fresh)
            return fresh
        }
    }
}
