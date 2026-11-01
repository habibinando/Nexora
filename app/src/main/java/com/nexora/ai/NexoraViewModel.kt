package com.nexora.ai

import android.app.Application
import androidx.compose.runtime.mutableStateListOf
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
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal data class NexoraUiState(
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
    val visionSupported: Boolean = false,
    val visionImageName: String? = null,
    val visionImageBytes: ByteArray? = null,
    val visionResult: String = ""
)

internal class NexoraViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(NexoraUiState())
    val uiState: StateFlow<NexoraUiState> = _uiState.asStateFlow()
    val models = mutableStateListOf<LocalModel>()
    val messages = mutableStateListOf<ChatMessage>()

    private val conversationFile = File(application.filesDir, "conversation.json")
    private val persistenceMutex = Mutex()

    @Volatile
    private var conversationModelId: String? = null

    init {
        restoreConversationFromDisk()
    }

    fun updateState(reducer: NexoraUiState.() -> NexoraUiState) {
        _uiState.update(reducer)
    }

    fun hasConversationForModel(modelId: String): Boolean =
        conversationModelId == modelId

    fun appendMessage(modelId: String?, mode: ChatMode, message: ChatMessage) {
        messages.add(message)
        persistConversation(modelId, mode)
    }

    fun clearConversation(modelId: String?, mode: ChatMode) {
        messages.clear()
        conversationModelId = modelId
        persistConversation(modelId, mode)
    }

    fun persistConversation(modelId: String?, mode: ChatMode) {
        conversationModelId = modelId
        val snapshot = messages.toList()
        viewModelScope.launch(Dispatchers.IO) {
            persistenceMutex.withLock {
                runCatching {
                    writeConversationAtomically(modelId, mode, snapshot)
                }.onFailure { failure ->
                    updateState {
                        copy(errorText = "Gagal menyimpan riwayat lokal: ${failure.message ?: failure.javaClass.simpleName}")
                    }
                }
            }
        }
    }

    private fun restoreConversationFromDisk() {
        if (!conversationFile.isFile) return
        runCatching {
            val root = JSONObject(conversationFile.readText(Charsets.UTF_8))
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
            messages.addAll(restored)
            _uiState.update { it.copy(chatMode = savedMode) }
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

        val parent = conversationFile.parentFile ?: error("Direktori riwayat lokal tidak tersedia.")
        check(parent.exists() || parent.mkdirs()) { "Direktori riwayat lokal tidak dapat dibuat." }
        val temporary = File(parent, "${conversationFile.name}.partial")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(root.toString().toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(),
                    conversationFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), conversationFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    override fun onCleared() {
        super.onCleared()
        Thread({
            runCatching { NativeLlama.cancelGeneration() }
            runCatching { NativeLlama.nextToken() }
            runCatching { NativeLlama.unload() }
            runCatching { NativeLlama.shutdown() }
        }, "NexoraNativeCleanup").apply {
            isDaemon = true
            start()
        }
    }
}
