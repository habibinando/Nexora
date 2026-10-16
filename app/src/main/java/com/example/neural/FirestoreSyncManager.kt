package com.example.neural

import android.content.Context
import android.util.Log
import com.example.R
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ServerTimestamp
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

@IgnoreExtraProperties
data class FirestoreDataset(
    @DocumentId val id: String = "",
    val name: String = "",
    val text: String = "",
    val charCount: Long = 0L,
    val description: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class FirestoreTrainingRun(
    @DocumentId val id: String = "",
    val datasetName: String = "",
    val hiddenSize: Long = 64L,
    val seqLen: Long = 32L,
    val learningRate: Double = 0.01,
    val targetEpochs: Long = 5L,
    val totalSteps: Long = 0L,
    val finalLoss: Double = 0.0,
    val initialLoss: Double = 0.0,
    val isNdkAccelerated: Boolean = false,
    val durationSeconds: Long = 0L,
    @ServerTimestamp val completedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class FirestoreCheckpoint(
    @DocumentId val id: String = "",
    val name: String = "",
    val parameterCount: Long = 0L,
    val hiddenSize: Long = 64L,
    val vocabSize: Long = 0L,
    val checksumSha256: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)

@IgnoreExtraProperties
data class FirestoreChatMessage(
    @DocumentId val id: String = "",
    val sender: String = "",
    val text: String = "",
    val tokensGenerated: Long = 0L,
    val latencyMs: Long = 0L,
    val tokensPerSec: Double = 0.0,
    val timestamp: Long = System.currentTimeMillis(),
    @ServerTimestamp val serverTimestamp: Timestamp? = null
)

class FirestoreSyncManager(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {
    companion object {
        private const val TAG = "FirestoreSyncManager"

        fun create(context: Context): FirestoreSyncManager {
            val databaseId = context.getString(R.string.firestore_database_id)
            val db = FirebaseFirestore.getInstance(databaseId)
            return FirestoreSyncManager(db)
        }
    }

    private fun requireUserId(): String {
        return auth.currentUser?.uid ?: error("Operasi cloud Firestore memerlukan login pengguna")
    }

    suspend fun saveUserProfile(displayName: String?, email: String?) {
        val uid = requireUserId()
        val userRef = db.collection("users").document(uid)
        val data = mapOf(
            "userId" to uid,
            "displayName" to (displayName ?: "Pengguna Nexora"),
            "email" to (email ?: ""),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        userRef.set(data).await()
    }

    // --- DATASETS ---
    fun observeDatasets(userId: String): Flow<List<FirestoreDataset>> {
        return db.collection("users").document(userId)
            .collection("datasets")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .snapshots()
            .map { it.toObjects(FirestoreDataset::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
    }

    suspend fun uploadDataset(name: String, text: String, description: String = ""): String {
        val uid = requireUserId()
        val colRef = db.collection("users").document(uid).collection("datasets")
        val docRef = colRef.document()
        val dataset = FirestoreDataset(
            id = docRef.id,
            name = name,
            text = text,
            charCount = text.length.toLong(),
            description = description
        )
        docRef.set(dataset).await()
        Log.i(TAG, "Dataset '${dataset.name}' uploaded to Firestore with ID: ${docRef.id}")
        return docRef.id
    }

    // --- TRAINING RUNS ---
    fun observeTrainingRuns(userId: String): Flow<List<FirestoreTrainingRun>> {
        return db.collection("users").document(userId)
            .collection("training_runs")
            .orderBy("completedAt", Query.Direction.DESCENDING)
            .snapshots()
            .map { it.toObjects(FirestoreTrainingRun::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
    }

    suspend fun recordTrainingRun(
        datasetName: String,
        hiddenSize: Int,
        seqLen: Int,
        learningRate: Float,
        targetEpochs: Int,
        totalSteps: Int,
        finalLoss: Float,
        initialLoss: Float,
        isNdkAccelerated: Boolean,
        durationSeconds: Long
    ): String {
        val uid = requireUserId()
        val colRef = db.collection("users").document(uid).collection("training_runs")
        val docRef = colRef.document()
        val run = FirestoreTrainingRun(
            id = docRef.id,
            datasetName = datasetName,
            hiddenSize = hiddenSize.toLong(),
            seqLen = seqLen.toLong(),
            learningRate = learningRate.toDouble(),
            targetEpochs = targetEpochs.toLong(),
            totalSteps = totalSteps.toLong(),
            finalLoss = finalLoss.toDouble(),
            initialLoss = initialLoss.toDouble(),
            isNdkAccelerated = isNdkAccelerated,
            durationSeconds = durationSeconds
        )
        docRef.set(run).await()
        Log.i(TAG, "Training run recorded to Firestore with ID: ${docRef.id}")
        return docRef.id
    }

    // --- CHECKPOINTS ---
    fun observeCheckpoints(userId: String): Flow<List<FirestoreCheckpoint>> {
        return db.collection("users").document(userId)
            .collection("checkpoints")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .snapshots()
            .map { it.toObjects(FirestoreCheckpoint::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
    }

    suspend fun syncCheckpointMetadata(checkpoint: ModelCheckpoint): String {
        val uid = requireUserId()
        val colRef = db.collection("users").document(uid).collection("checkpoints")
        val docId = checkpoint.filename.replace(".", "_")
        val docRef = colRef.document(docId)
        val cp = FirestoreCheckpoint(
            id = docId,
            name = checkpoint.filename,
            parameterCount = checkpoint.parametersCount,
            hiddenSize = checkpoint.hiddenSize.toLong(),
            vocabSize = checkpoint.vocabSize.toLong(),
            checksumSha256 = checkpoint.checksum
        )
        docRef.set(cp).await()
        return docRef.id
    }

    // --- CHAT MESSAGES ---
    fun observeChatMessages(userId: String): Flow<List<FirestoreChatMessage>> {
        return db.collection("users").document(userId)
            .collection("chat_messages")
            .orderBy("timestamp", Query.Direction.ASCENDING)
            .snapshots()
            .map { it.toObjects(FirestoreChatMessage::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
    }

    suspend fun syncChatMessage(msg: ChatMessage) {
        val uid = requireUserId()
        val colRef = db.collection("users").document(uid).collection("chat_messages")
        val docRef = colRef.document(msg.id)
        val chatDoc = FirestoreChatMessage(
            id = msg.id,
            sender = msg.sender.name,
            text = msg.text,
            tokensGenerated = msg.tokensGenerated.toLong(),
            latencyMs = msg.latencyMs,
            tokensPerSec = msg.tokensPerSec.toDouble(),
            timestamp = msg.timestamp
        )
        docRef.set(chatDoc).await()
    }
}
