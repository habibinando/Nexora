package com.nexora.ai

import android.app.Application
import android.util.AtomicFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicLong

internal data class NexoraUiState(
    val models: List<LocalModel> = emptyList(),
    val messages: List<ChatMessage> = emptyList(),
    val activeModelId: String? = null,
    val page: AppPage = AppPage.CHAT,
    val chatMode: ChatMode = ChatMode.CHAT,
    val engineReady: Boolean = false,
    val isBusy: Boolean = false,
    val canStopGeneration: Boolean = false,
    val busyLabel: String = "",
    val busyProgress: Float = -1f,
    val progressDetail: String = "",
    val benchmarkResult: String = "",
    val maxOutputTokens: Int = 4096,
    val generationPreview: String = "",
    val statusText: String = "Menyiapkan runtime lokal…",
    val systemInfo: String = "Backend belum diinisialisasi.",
    val hardwareInfo: String = "Memeriksa perangkat…",
    val errorText: String? = null,
    val networkToolsEnabled: Boolean = false,
    val visionSupported: Boolean = false,
    val visionImageName: String? = null,
    val visionImageBytes: ByteArray? = null,
    val visionResult: String = ""
)

internal class NexoraViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(NexoraUiState())
    val uiState: StateFlow<NexoraUiState> = _uiState.asStateFlow()
    val models: List<LocalModel> get() = _uiState.value.models
    val messages: List<ChatMessage> get() = _uiState.value.messages

    private val conversationFile = AtomicFile(File(application.filesDir, "conversation.json"))
    private val persistenceMutex = Mutex()
    private val persistenceRevision = AtomicLong(0L)
    private var modelIndexLoaded = false

    @Volatile
    private var conversationModelId: String? = null

    init {
        restoreConversationFromDisk()
    }

    fun updateState(reducer: NexoraUiState.() -> NexoraUiState) {
        _uiState.update(reducer)
    }

    fun hasConversationForModel(modelId: String): Boolean = conversationModelId == modelId
    fun hasLoadedModelIndex(): Boolean = modelIndexLoaded
    fun replaceModels(models: List<LocalModel>) {
        modelIndexLoaded = true
        updateState { copy(models = models.toList()) }
    }
    fun addModel(model: LocalModel) = updateState { copy(models = models + model) }
    fun updateModel(model: LocalModel) = updateState {
        copy(models = models.map { current -> if (current.id == model.id) model else current })
    }
    fun removeModel(modelId: String) = updateState { copy(models = models.filterNot { it.id == modelId }) }
    fun appendMessage(modelId: String?, mode: ChatMode, message: ChatMessage) {
        updateState { copy(messages = messages + message) }
        persistConversation(modelId, mode)
    }
    fun clearConversation(modelId: String?, mode: ChatMode) {
        conversationModelId = modelId
        updateState { copy(messages = emptyList(), generationPreview = "") }
        persistConversation(modelId, mode)
    }
    fun persistConversation(modelId: String?, mode: ChatMode) {
        conversationModelId = modelId
        val revision = persistenceRevision.incrementAndGet()
        val snapshot = uiState.value.messages.toList()
        viewModelScope.launch(Dispatchers.IO) {
            persistenceMutex.withLock {
                if (revision != persistenceRevision.get()) return@withLock
                runCatching { writeConversationAtomically(modelId, mode, snapshot) }
                    .onFailure { failure ->
                        updateState {
                            copy(errorText = "Gagal menyimpan riwayat lokal: ${failure.message ?: failure.javaClass.simpleName}")
                        }
                    }
            }
        }
    }

    private fun restoreConversationFromDisk() {
        val input = runCatching { conversationFile.openRead() }.getOrNull() ?: return
        runCatching {
            val root = input.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
            conversationModelId = if (root.isNull("modelId")) null else root.optString("modelId")
            val savedMode = ChatMode.entries.firstOrNull { it.name == root.optString("chatMode") }
                ?: ChatMode.CHAT
            val rows = root.optJSONArray("messages") ?: JSONArray()
            val restored = ArrayList<ChatMessage>(rows.length())
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val role = row.optString("role")
                if (role == "user" || role == "assistant") {
                    restored += ChatMessage(role, row.optString("content"))
                }
            }
            val interrupted = restored.lastOrNull()?.role == "user"
            if (interrupted) {
                restored += ChatMessage("assistant", getApplication<Application>().getString(R.string.generation_interrupted))
            }
            _uiState.update { it.copy(messages = restored, chatMode = savedMode) }
            if (interrupted) persistConversation(conversationModelId, savedMode)
        }.onFailure { failure ->
            _uiState.update {
                it.copy(errorText = "Riwayat lokal tidak dapat dibaca: ${failure.message ?: failure.javaClass.simpleName}")
            }
        }
    }

    private fun writeConversationAtomically(
        modelId: String?,
        mode: ChatMode,
        snapshot: List<ChatMessage>
    ) {
        val root = JSONObject()
            .put("schemaVersion", 1)
            .put("modelId", modelId ?: JSONObject.NULL)
            .put("chatMode", mode.name)
        val rows = JSONArray()
        snapshot.forEach { message ->
            rows.put(JSONObject().put("role", message.role).put("content", message.content))
        }
        root.put("messages", rows)

        val stream = conversationFile.startWrite()
        try {
            stream.write(root.toString().toByteArray(Charsets.UTF_8))
            stream.flush()
            stream.fd.sync()
            conversationFile.finishWrite(stream)
        } catch (error: Throwable) {
            conversationFile.failWrite(stream)
            throw error
        }
    }

    override fun onCleared() {
        val snapshot = uiState.value.messages.toList()
        val modelId = conversationModelId
        val mode = uiState.value.chatMode
        Thread({
            runCatching {
                kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                    persistenceMutex.withLock { writeConversationAtomically(modelId, mode, snapshot) }
                }
            }
            runCatching { NativeLlama.cancelGeneration() }
            runCatching { NativeLlama.nextToken() }
            if (runCatching { NativeLlama.isModelLoaded() }.getOrDefault(false)) runCatching { NativeLlama.unload() }
            runCatching { NativeLlama.shutdown() }
        }, "NexoraNativeCleanup").apply {
            isDaemon = true
            start()
        }
        super.onCleared()
    }
}
