package com.example.minicpm_v_demo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ModelManagerActivity : AppCompatActivity() {

    private lateinit var tvModelStatus: TextView
    private lateinit var btnDownload: MaterialButton
    private lateinit var btnLoadModel: MaterialButton
    private lateinit var btnDeleteModel: MaterialButton
    private lateinit var progressDownload: LinearProgressIndicator
    private lateinit var recyclerModels: RecyclerView
    private lateinit var tvLanguageValue: TextView
    private lateinit var btnImportModel: MaterialButton

    /** GGUF picked in the first step of the import flow, waiting for mmproj. */
    private var pendingGgufUri: android.net.Uri? = null

    private lateinit var engine: LlamaEngine
    private lateinit var modelAdapter: ModelAdapter

    // Android 13+: POST_NOTIFICATIONS is a runtime permission. We need it
    // for the foreground download service's progress notification (without
    // a notification the OS will outright kill the foreground service).
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // We start the download regardless of grant: the service still
            // posts a notification, the user just won't see it. Foreground
            // service itself is allowed without the permission.
            if (!granted) {
                Toast.makeText(
                    this,
                    getString(R.string.toast_notification_denied),
                    Toast.LENGTH_LONG
                ).show()
            }
            startDownloadService()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_model_manager)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        tvModelStatus = findViewById(R.id.tv_model_status)
        btnDownload = findViewById(R.id.btn_download)
        btnLoadModel = findViewById(R.id.btn_load_model)
        btnDeleteModel = findViewById(R.id.btn_delete_model)
        progressDownload = findViewById(R.id.progress_download)
        recyclerModels = findViewById(R.id.recycler_models)
        tvLanguageValue = findViewById(R.id.tv_language_value)
        btnImportModel = findViewById(R.id.btn_import_model)

        engine = LlamaEngine.getInstance(applicationContext)

        setupModelList()
        updateLoadButtonState()
        observeEngineState()
        observeDownloadStatus()
        updateLanguageDisplay()

        btnDownload.setOnClickListener { onDownloadClicked() }
        btnLoadModel.setOnClickListener { loadSelectedModel() }
        btnDeleteModel.setOnClickListener { confirmDeleteModel() }
        findViewById<View>(R.id.btn_language).setOnClickListener { showLanguagePicker() }
        btnImportModel.setOnClickListener { pickGguf.launch(arrayOf("*/*")) }
    }

    private fun setupModelList() {
        val selectedModel = LlamaEngine.getSelectedModel(this)
        modelAdapter = ModelAdapter(
            models = CustomModelStore.all(this),
            selectedModelId = selectedModel.id,
            onModelSelected = { model ->
                val previousModelId = LlamaEngine.getSelectedModel(this).id
                LlamaEngine.setSelectedModel(this, model.id)
                updateLoadButtonState()

                if (previousModelId != model.id) {
                    val wasLoaded = engine.state.value is LlamaState.ModelReady
                    if (wasLoaded) {
                        reloadSelectedModel()
                    } else {
                        tvModelStatus.text = getString(R.string.switched_to_load, model.displayName)
                    }
                }
            }
        )

        recyclerModels.layoutManager = LinearLayoutManager(this)
        recyclerModels.adapter = modelAdapter
    }

    private fun reloadSelectedModel() {
        val model = LlamaEngine.getSelectedModel(this)
        val modelPath = LlamaEngine.modelPath(applicationContext)

        if (!File(modelPath).exists()) {
            tvModelStatus.text = getString(R.string.switched_to_download, model.displayName)
            lifecycleScope.launch(Dispatchers.IO) {
                try { engine.unloadModel() } catch (_: Exception) {}
                withContext(Dispatchers.Main) { updateLoadButtonState() }
            }
            return
        }

        btnLoadModel.isEnabled = false
        btnDownload.isEnabled = false
        tvModelStatus.text = getString(R.string.switching_to, model.displayName)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                engine.unloadModel()
                val settings = InferenceSettings.load(applicationContext)
                val mmprojPath = LlamaEngine.mmprojPath(applicationContext)
                val mmprojArg = mmprojPath?.let { if (File(it).exists()) it else null }
                engine.loadModel(
                    pathToModel = modelPath,
                    pathToMmproj = mmprojArg,
                    imageMaxSliceNums = settings.imageMaxSlice,
                    nCtx = settings.nCtx,
                    temperature = settings.temperature,
                    topK = settings.topK,
                    topP = settings.topP,
                    nUbatch = settings.nUbatch,
                    moeSlotBank = settings.effectiveMoeSlotBank(model.isMoe),
                )
                if (settings.systemPrompt.isNotBlank()) {
                    engine.setSystemPrompt(settings.systemPrompt)
                }
                LlamaEngine.markModelSwitched(applicationContext)
                withContext(Dispatchers.Main) {
                    updateLoadButtonState()
                    Toast.makeText(this@ModelManagerActivity, getString(R.string.toast_load_success, model.displayName), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error reloading model", e)
                engine.resetToInitialized()
                withContext(Dispatchers.Main) {
                    tvModelStatus.text = getString(R.string.toast_load_failed, e.message)
                    updateLoadButtonState()
                }
            }
        }
    }

    private fun observeEngineState() {
        lifecycleScope.launch {
            engine.state.collect { state ->
                when (state) {
                    is LlamaState.Uninitialized -> {
                        tvModelStatus.text = getString(R.string.status_uninitialized)
                    }
                    is LlamaState.Initializing -> {
                        tvModelStatus.text = getString(R.string.status_initializing)
                    }
                    is LlamaState.Initialized -> {
                        tvModelStatus.text = getString(R.string.status_initialized)
                        updateLoadButtonState()
                    }
                    is LlamaState.LoadingModel -> {
                        tvModelStatus.text = getString(R.string.status_loading)
                        btnLoadModel.isEnabled = false
                        btnDownload.isEnabled = false
                    }
                    is LlamaState.ModelReady -> {
                        tvModelStatus.text = getString(R.string.status_ready)
                        btnLoadModel.isEnabled = true
                        btnDownload.isEnabled = true
                        updateLoadButtonState()
                    }
                    is LlamaState.ProcessingSystemPrompt,
                    is LlamaState.ProcessingUserPrompt -> {
                        tvModelStatus.text = getString(R.string.status_generating)
                    }
                    is LlamaState.PrefillingImage -> {
                        tvModelStatus.text = getString(R.string.status_prefilling_image)
                    }
                    is LlamaState.Generating -> {
                        tvModelStatus.text = getString(R.string.status_generating)
                    }
                    is LlamaState.UnloadingModel -> {
                        tvModelStatus.text = getString(R.string.status_unloading)
                    }
                    is LlamaState.Error -> {
                        tvModelStatus.text = getString(R.string.status_error, state.exception.message)
                        btnLoadModel.isEnabled = true
                        btnDownload.isEnabled = true
                    }
                }
            }
        }
    }

    private fun updateLoadButtonState() {
        val exists = LlamaEngine.modelsExist(this)
        val isReady = engine.state.value is LlamaState.ModelReady
        btnLoadModel.isEnabled = exists
        btnDeleteModel.visibility = if (exists) View.VISIBLE else View.GONE
        btnLoadModel.text = when {
            isReady -> getString(R.string.reload_model)
            exists -> getString(R.string.load_model)
            else -> getString(R.string.no_model_file)
        }
    }

    private fun onDownloadClicked() {
        if (LlamaEngine.getSelectedModel(this).isCustom) {
            Toast.makeText(this, R.string.toast_custom_no_download, Toast.LENGTH_LONG).show()
            return
        }
        if (ModelDownloadController.isRunning) {
            Toast.makeText(this, R.string.toast_downloading, Toast.LENGTH_SHORT).show()
            return
        }
        if (LlamaEngine.modelsExist(this)) {
            Toast.makeText(this, R.string.toast_already_downloaded, Toast.LENGTH_SHORT).show()
            return
        }

        // Android 13+ needs runtime POST_NOTIFICATIONS so the foreground
        // service notification is actually visible. Lower OS versions get
        // the permission for free at install time and just fall through.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }

        startDownloadService()
    }

    private fun startDownloadService() {
        // Drop any prior terminal status so the UI re-enters Running cleanly.
        ModelDownloadController.acknowledge()

        btnDownload.isEnabled = false
        btnLoadModel.isEnabled = false
        progressDownload.visibility = View.VISIBLE
        tvModelStatus.text = getString(R.string.status_downloading)

        ModelDownloadService.start(applicationContext)
    }

    /**
     * Mirrors the foreground service's [ModelDownloadController] state into
     * the UI. We use repeatOnLifecycle(STARTED) so we don't burn cycles
     * collecting while the Activity is in the background, but we still
     * pick up any progress that arrived during that window when we resume.
     */
    private fun observeDownloadStatus() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ModelDownloadController.status.collect { status ->
                    when (status) {
                        is ModelDownloadController.Status.Idle -> {
                            progressDownload.visibility = View.GONE
                            btnDownload.isEnabled = true
                            updateLoadButtonState()
                        }
                        is ModelDownloadController.Status.Running -> {
                            progressDownload.visibility = View.VISIBLE
                            btnDownload.isEnabled = false
                            btnLoadModel.isEnabled = false
                            tvModelStatus.text = status.message
                        }
                        is ModelDownloadController.Status.Completed -> {
                            progressDownload.visibility = View.GONE
                            tvModelStatus.text = getString(R.string.download_complete_status)
                            Toast.makeText(this@ModelManagerActivity, R.string.download_complete_toast, Toast.LENGTH_SHORT).show()
                            btnDownload.isEnabled = true
                            updateLoadButtonState()
                            ModelDownloadController.acknowledge()
                        }
                        is ModelDownloadController.Status.Cancelled -> {
                            progressDownload.visibility = View.GONE
                            tvModelStatus.text = getString(R.string.download_cancelled)
                            btnDownload.isEnabled = true
                            updateLoadButtonState()
                            ModelDownloadController.acknowledge()
                        }
                        is ModelDownloadController.Status.Failed -> {
                            Log.w(TAG, "Download failed: ${status.message}")
                            progressDownload.visibility = View.GONE
                            tvModelStatus.text = getString(R.string.download_failed, status.message)
                            Toast.makeText(
                                this@ModelManagerActivity,
                                getString(R.string.download_failed, status.message),
                                Toast.LENGTH_LONG
                            ).show()
                            btnDownload.isEnabled = true
                            updateLoadButtonState()
                            ModelDownloadController.acknowledge()
                        }
                    }
                }
            }
        }
    }

    private fun loadSelectedModel() {
        val model = LlamaEngine.getSelectedModel(applicationContext)
        // TTS models are loaded on-demand by TtsActivity, not via LlamaEngine.
        if (model.isTts || model.isOcr) {
            LlamaEngine.markModelSwitched(applicationContext)
            finish() // return to parent; MainActivity will redirect
            return
        }

        val currentState = engine.state.value
        if (currentState is LlamaState.LoadingModel) {
            Toast.makeText(this, R.string.toast_already_loading, Toast.LENGTH_SHORT).show()
            return
        }

        val modelPath = LlamaEngine.modelPath(applicationContext)
        val mmprojPath = LlamaEngine.mmprojPath(applicationContext)

        if (!File(modelPath).exists()) {
            Toast.makeText(this, R.string.toast_model_not_found, Toast.LENGTH_LONG).show()
            return
        }

        val isReload = currentState is LlamaState.ModelReady

        btnLoadModel.isEnabled = false
        btnDownload.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (isReload) {
                    Log.i(TAG, "Unloading model for reload...")
                    engine.unloadModel()
                }

                val mmprojArg = mmprojPath?.let { path ->
                    if (File(path).exists()) path else null
                }
                val settings = InferenceSettings.load(applicationContext)
                engine.loadModel(
                    pathToModel = modelPath,
                    pathToMmproj = mmprojArg,
                    imageMaxSliceNums = settings.imageMaxSlice,
                    nCtx = settings.nCtx,
                    temperature = settings.temperature,
                    topK = settings.topK,
                    topP = settings.topP,
                    nUbatch = settings.nUbatch,
                    moeSlotBank = settings.effectiveMoeSlotBank(model.isMoe),
                )
                // Empty by default (no system prompt), matching upstream; a
                // non-empty one must be injected before the first user turn.
                if (settings.systemPrompt.isNotBlank()) {
                    engine.setSystemPrompt(settings.systemPrompt)
                }

                withContext(Dispatchers.Main) {
                    btnLoadModel.isEnabled = true
                    btnDownload.isEnabled = true
                    updateLoadButtonState()
                    Toast.makeText(this@ModelManagerActivity, R.string.toast_model_loaded, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading model", e)
                engine.resetToInitialized()
                withContext(Dispatchers.Main) {
                    tvModelStatus.text = getString(R.string.toast_load_failed, e.message)
                    btnLoadModel.isEnabled = true
                    btnDownload.isEnabled = true
                    updateLoadButtonState()
                }
            }
        }
    }

    private fun confirmDeleteModel() {
        val model = LlamaEngine.getSelectedModel(this)
        val fileList = buildString {
            append("• ${model.ggufFileName}")
            model.mmprojFileName?.let { append("\n• $it") }
            model.layoutFileName?.let { append("\n• $it") }
            model.acousticFileName?.let { append("\n• $it") }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_dialog_title)
            .setMessage(getString(R.string.delete_dialog_message, model.displayName, fileList))
            .setPositiveButton(R.string.delete) { _, _ -> deleteModelFiles() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun deleteModelFiles() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val modelPath = LlamaEngine.modelPath(applicationContext)
                val mmprojPath = LlamaEngine.mmprojPath(applicationContext)

                var deleted = false
                File(modelPath).let { if (it.exists()) { it.delete(); deleted = true } }
                mmprojPath?.let { File(it) }?.let { if (it.exists()) { it.delete(); deleted = true } }
                val layoutPath = LlamaEngine.layoutPath(applicationContext)
                layoutPath?.let { File(it) }?.let { if (it.exists()) { it.delete(); deleted = true } }
                // Also delete the acoustic GGUF for TTS models
                val acousticPath = LlamaEngine.acousticPath(applicationContext)
                acousticPath?.let { File(it) }?.let { if (it.exists()) { it.delete(); deleted = true } }
                // Imported models also have a registry entry to drop, otherwise
                // the list keeps a row that points at deleted files.
                if (LlamaEngine.getSelectedModel(applicationContext).isCustom) {
                    CustomModelStore.remove(applicationContext, LlamaEngine.getSelectedModel(applicationContext).id)
                }

                withContext(Dispatchers.Main) {
                    updateLoadButtonState()
                    if (deleted) {
                        tvModelStatus.text = getString(R.string.model_files_deleted)
                        Toast.makeText(this@ModelManagerActivity, R.string.model_files_deleted, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@ModelManagerActivity, R.string.toast_no_files_to_delete, Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting model", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ModelManagerActivity, getString(R.string.toast_delete_failed, e.message), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updateLanguageDisplay() {
        tvLanguageValue.text = LocaleManager.currentLanguage(this).displayName
    }

    private fun showLanguagePicker() {
        val current = LocaleManager.currentLanguage(this)
        val options = LocaleManager.AppLanguage.entries.toTypedArray()
        val items = options.map {
            if (it == current) "${it.displayName}  \u2713" else it.displayName
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(R.string.language_picker_title)
            .setItems(items) { _, which ->
                val picked = options[which]
                if (picked != current) {
                    LocaleManager.setLanguageAndRestart(this, picked)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------
    // Local model import
    //
    // Two-step SAF flow: the GGUF first, then an optional mmproj. SAF cannot
    // hand out a directory, and a GGUF can be many GB, so the file is streamed
    // straight into files/models/<id>/ instead of being buffered.
    // ------------------------------------------------------------------

    private val pickGguf = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val name = displayNameOf(uri)
        if (name == null || !name.lowercase().endsWith(".gguf")) {
            Toast.makeText(this, R.string.import_need_gguf, Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        pendingGgufUri = uri
        AlertDialog.Builder(this)
            .setMessage(R.string.import_pick_mmproj)
            .setPositiveButton(R.string.import_local_model) { _, _ ->
                pickMmproj.launch(arrayOf("*/*"))
            }
            .setNeutralButton(R.string.import_skip) { _, _ -> startImport(null) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private val pickMmproj = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            startImport(null)
        } else {
            startImport(uri)
        }
    }

    private fun displayNameOf(uri: android.net.Uri): String? =
        contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null, null, null
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

    private fun startImport(mmprojUri: android.net.Uri?) {
        val gguf = pendingGgufUri ?: return
        pendingGgufUri = null

        btnImportModel.isEnabled = false
        tvModelStatus.text = getString(R.string.import_in_progress, displayNameOf(gguf) ?: "")

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val model = CustomModelStore.import(
                    context = applicationContext,
                    ggufUri = gguf,
                    mmprojUri = mmprojUri,
                    displayName = null,
                    onProgress = { name ->
                        runOnUiThread {
                            tvModelStatus.text = getString(R.string.import_in_progress, name)
                        }
                    },
                )
                LlamaEngine.setSelectedModel(applicationContext, model.id)
                withContext(Dispatchers.Main) {
                    btnImportModel.isEnabled = true
                    setupModelList()
                    updateLoadButtonState()
                    Toast.makeText(
                        this@ModelManagerActivity,
                        getString(R.string.import_done, model.displayName),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "import failed", e)
                withContext(Dispatchers.Main) {
                    btnImportModel.isEnabled = true
                    tvModelStatus.text = getString(R.string.import_failed, e.message)
                    Toast.makeText(
                        this@ModelManagerActivity,
                        getString(R.string.import_failed, e.message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    companion object {
        private val TAG = ModelManagerActivity::class.java.simpleName
    }
}
