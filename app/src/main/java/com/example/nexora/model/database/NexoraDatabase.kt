package com.example.nexora.model.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [
        MemoryEntity::class,
        StrategyEntity::class,
        ExperimentEntity::class,
        ToolHistoryEntity::class,
        TrainingDataEntity::class,
        AdapterModuleEntity::class,
        ModelVersionEntity::class,
        ChatMessageEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class NexoraDatabase : RoomDatabase() {
    abstract fun nexoraDao(): NexoraDao

    companion object {
        @Volatile
        private var INSTANCE: NexoraDatabase? = null

        fun getDatabase(context: Context, scope: CoroutineScope = CoroutineScope(Dispatchers.IO)): NexoraDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    NexoraDatabase::class.java,
                    "nexora_memory.db"
                )
                .addCallback(DatabaseCallback(scope))
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }

        private class DatabaseCallback(
            private val scope: CoroutineScope
        ) : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                INSTANCE?.let { database ->
                    scope.launch(Dispatchers.IO) {
                        populateInitialData(database.nexoraDao())
                    }
                }
            }

            suspend fun populateInitialData(dao: NexoraDao) {
                // Initial Default Model: Nexora Core Quantized
                dao.insertModelVersion(
                    ModelVersionEntity(
                        versionId = "NEXORA-CORE-Q4",
                        modelName = "Nexora Core Quantized (4-Bit INT4)",
                        sizeMb = 480,
                        quantization = "Q4_K_M",
                        contextLength = 4096,
                        requiredRamMb = 650,
                        isCurrentActive = true,
                        installedAt = System.currentTimeMillis()
                    )
                )

                // Initial Bootstrap Memories
                dao.insertMemory(
                    MemoryEntity(
                        type = "LONG_TERM_KNOWLEDGE",
                        title = "Nexora Core Directive",
                        content = "Operate local-first, prioritize privacy and offline reasoning. Whenever a task or hypothesis fails, classify the root cause, remember the flaw, and synthesize an improved strategy.",
                        tags = "core,principles,offline",
                        confidence = 1.0f
                    )
                )
                dao.insertMemory(
                    MemoryEntity(
                        type = "LESSON_LEARNED",
                        title = "Tool Argument Validation Heuristic",
                        content = "Always sanitize numeric strings and json keys before tool dispatch to prevent silent type errors.",
                        tags = "tools,debugging,rules",
                        confidence = 0.95f
                    )
                )

                // Initial Seed Strategy
                dao.insertStrategy(
                    StrategyEntity(
                        strategyId = "STRAT-INIT-001",
                        goal = "Verify on-device sandbox environment and available tool sensors",
                        parentStrategies = "",
                        stepsJson = "[\"Inspect local device memory specs\",\"Test calculator execution\",\"Confirm persistent memory write\"]",
                        requiredTools = "AndroidUtilityTool,CalculatorTool,DatabaseTool",
                        expectedResult = "All sandbox diagnostics return healthy status with 0 errors.",
                        riskOfFailure = "LOW",
                        reasoningSummary = "Baseline verification ensures self-healing loops have operational foundations.",
                        isSuccessful = true,
                        successCount = 1
                    )
                )

                // Initial Chat Greeting
                dao.insertChatMessage(
                    ChatMessageEntity(
                        sender = "NEXORA",
                        text = "Nexora local core initialized. Operating offline with persistent memory, tool registry, and experiment engine ready. How can I assist or experiment today?"
                    )
                )
            }
        }
    }
}
