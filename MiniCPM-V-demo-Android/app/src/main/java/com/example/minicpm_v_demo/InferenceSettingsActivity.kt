package com.example.minicpm_v_demo

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import kotlin.math.roundToInt

/**
 * One screen for every inference knob.
 *
 * The native layer reads these at two different moments, so the UI says as
 * much: context length / batch size / MoE slots are baked into the llama
 * context when the model loads (MainActivity reloads it on return), while the
 * sampling knobs and the per-turn token budget apply from the next turn.
 */
class InferenceSettingsActivity : AppCompatActivity() {

    private var settings = InferenceSettings()

    private lateinit var sliderCtx: Slider
    private lateinit var sliderMaxTokens: Slider
    private lateinit var sliderTemp: Slider
    private lateinit var sliderTopK: Slider
    private lateinit var sliderTopP: Slider
    private lateinit var sliderUbatch: Slider
    private lateinit var sliderSlice: Slider
    private lateinit var sliderMoe: Slider
    private lateinit var sliderGpu: Slider
    private lateinit var switchThinking: MaterialSwitch
    private lateinit var etSystemPrompt: EditText
    private lateinit var layoutMoe: View

    private lateinit var tvCtx: TextView
    private lateinit var tvMaxTokens: TextView
    private lateinit var tvTemp: TextView
    private lateinit var tvTopK: TextView
    private lateinit var tvTopP: TextView
    private lateinit var tvUbatch: TextView
    private lateinit var tvSlice: TextView
    private lateinit var tvMoe: TextView
    private lateinit var tvGpu: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_inference_settings)

        findViewById<Toolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        sliderCtx = findViewById(R.id.slider_ctx)
        sliderMaxTokens = findViewById(R.id.slider_max_tokens)
        sliderTemp = findViewById(R.id.slider_temp)
        sliderTopK = findViewById(R.id.slider_topk)
        sliderTopP = findViewById(R.id.slider_topp)
        sliderUbatch = findViewById(R.id.slider_ubatch)
        sliderSlice = findViewById(R.id.slider_slice)
        sliderMoe = findViewById(R.id.slider_moe)
        sliderGpu = findViewById(R.id.slider_gpu)
        switchThinking = findViewById(R.id.switch_thinking)
        etSystemPrompt = findViewById(R.id.et_system_prompt)
        layoutMoe = findViewById(R.id.layout_moe)

        tvCtx = findViewById(R.id.tv_ctx_value)
        tvMaxTokens = findViewById(R.id.tv_max_tokens_value)
        tvTemp = findViewById(R.id.tv_temp_value)
        tvTopK = findViewById(R.id.tv_topk_value)
        tvTopP = findViewById(R.id.tv_topp_value)
        tvUbatch = findViewById(R.id.tv_ubatch_value)
        tvSlice = findViewById(R.id.tv_slice_value)
        tvMoe = findViewById(R.id.tv_moe_value)
        tvGpu = findViewById(R.id.tv_gpu_value)

        settings = InferenceSettings.load(this)

        // The slot bank is only meaningful for les.moe GGUFs; hide the row for
        // everything else so it does not look like a broken knob.
        layoutMoe.visibility =
            if (LlamaEngine.getSelectedModel(this).isMoe) View.VISIBLE else View.GONE

        applyToUi()
        wireListeners()
    }

    private fun applyToUi() {
        sliderCtx.setSafe(settings.nCtx.toFloat())
        sliderMaxTokens.setSafe(settings.maxTokens.toFloat())
        sliderTemp.setSafe(settings.temperature)
        sliderTopK.setSafe(settings.topK.toFloat())
        sliderTopP.setSafe(settings.topP)
        sliderUbatch.setSafe(settings.nUbatch.toFloat())
        sliderSlice.setSafe(settings.imageMaxSlice.toFloat())
        sliderMoe.setSafe(settings.moeSlotBank.toFloat())
        sliderGpu.setSafe(settings.gpuLayers.toFloat())
        switchThinking.isChecked = settings.enableThinking
        etSystemPrompt.setText(settings.systemPrompt)
        refreshLabels()
    }

    private fun wireListeners() {
        // Explicit SAM constructor: an inferred lambda value would not be
        // converted to the Java listener interface here.
        val persist = Slider.OnChangeListener { _, _, fromUser ->
            if (fromUser) {
                readFromUi()
                InferenceSettings.save(this, settings)
                refreshLabels()
            }
        }
        sliderCtx.addOnChangeListener(persist)
        sliderMaxTokens.addOnChangeListener(persist)
        sliderTemp.addOnChangeListener(persist)
        sliderTopK.addOnChangeListener(persist)
        sliderTopP.addOnChangeListener(persist)
        sliderUbatch.addOnChangeListener(persist)
        sliderSlice.addOnChangeListener(persist)
        sliderMoe.addOnChangeListener(persist)
        sliderGpu.addOnChangeListener(persist)

        switchThinking.setOnCheckedChangeListener { _, checked ->
            settings = settings.copy(enableThinking = checked)
            // Keep the home screen's think button and the native flag in sync.
            LlamaEngine.setEnableThinkingPref(this, checked)
            InferenceSettings.save(this, settings)
        }

        findViewById<MaterialButton>(R.id.btn_recommend).setOnClickListener {
            val rec = DeviceProfile.recommend(this, settings)
            settings = rec.settings
            InferenceSettings.save(this, settings)
            applyToUi()
            Toast.makeText(this, getString(R.string.params_recommend_done, rec.summary), Toast.LENGTH_LONG).show()
        }

        findViewById<MaterialButton>(R.id.btn_reset).setOnClickListener {
            settings = InferenceSettings.reset(this)
            LlamaEngine.setEnableThinkingPref(this, settings.enableThinking)
            applyToUi()
            Toast.makeText(this, R.string.params_reset_done, Toast.LENGTH_SHORT).show()
        }
    }

    private fun readFromUi() {
        settings = settings.copy(
            nCtx = sliderCtx.value.roundToInt(),
            maxTokens = sliderMaxTokens.value.roundToInt(),
            // Snap to 0.05 / 0.01 so repeated open/save cycles stay stable.
            temperature = (sliderTemp.value * 20).roundToInt() / 20f,
            topK = sliderTopK.value.roundToInt(),
            topP = (sliderTopP.value * 100).roundToInt() / 100f,
            nUbatch = sliderUbatch.value.roundToInt(),
            imageMaxSlice = sliderSlice.value.roundToInt(),
            moeSlotBank = sliderMoe.value.roundToInt(),
            gpuLayers = sliderGpu.value.roundToInt(),
        )
    }

    private fun refreshLabels() {
        tvCtx.text = if (sliderCtx.value.roundToInt() == 0) getString(R.string.params_auto)
        else sliderCtx.value.roundToInt().toString()
        tvMaxTokens.text = if (sliderMaxTokens.value.roundToInt() == 0) getString(R.string.params_unlimited)
        else sliderMaxTokens.value.roundToInt().toString()
        tvTemp.text = String.format(java.util.Locale.US, "%.2f", sliderTemp.value)
        tvTopK.text = sliderTopK.value.roundToInt().toString()
        tvTopP.text = String.format(java.util.Locale.US, "%.2f", sliderTopP.value)
        tvUbatch.text = if (sliderUbatch.value.roundToInt() == 0) getString(R.string.params_auto)
        else sliderUbatch.value.roundToInt().toString()
        tvSlice.text = sliderSlice.value.roundToInt().toString()
        tvMoe.text = if (sliderMoe.value.roundToInt() == 0) getString(R.string.params_auto)
        else sliderMoe.value.roundToInt().toString()
        tvGpu.text = if (sliderGpu.value.roundToInt() == 0) getString(R.string.params_gpu_cpu)
        else sliderGpu.value.roundToInt().toString()
    }

    override fun onPause() {
        // Text input has no change listener worth persisting on every keystroke.
        settings = settings.copy(systemPrompt = etSystemPrompt.text?.toString()?.trim() ?: "")
        InferenceSettings.save(this, settings)
        super.onPause()
    }

    /**
     * Material Slider throws when the assigned value is not on a step boundary,
     * and the float-valued sliders cannot land on one exactly. Snap first.
     */
    private fun Slider.setSafe(target: Float) {
        val clamped = target.coerceIn(valueFrom, valueTo)
        value = if (stepSize > 0f) {
            val steps = ((clamped - valueFrom) / stepSize).roundToInt()
            (valueFrom + steps * stepSize).coerceIn(valueFrom, valueTo)
        } else {
            clamped
        }
    }
}
