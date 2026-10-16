package com.example.neural

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R

class NeuralTrainingForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "nexora_neural_training_channel"
        const val NOTIFICATION_ID = 2001

        const val ACTION_START = "com.example.neural.ACTION_START_TRAINING_SERVICE"
        const val ACTION_UPDATE = "com.example.neural.ACTION_UPDATE_TRAINING_SERVICE"
        const val ACTION_STOP = "com.example.neural.ACTION_STOP_TRAINING_SERVICE"

        const val EXTRA_EPOCH = "extra_epoch"
        const val EXTRA_TOTAL_EPOCHS = "extra_total_epochs"
        const val EXTRA_STEP = "extra_step"
        const val EXTRA_TOTAL_STEPS = "extra_total_steps"
        const val EXTRA_LOSS = "extra_loss"

        fun startService(context: Context, epoch: Int, totalEpochs: Int, step: Int, totalSteps: Int, loss: Float) {
            val intent = Intent(context, NeuralTrainingForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_EPOCH, epoch)
                putExtra(EXTRA_TOTAL_EPOCHS, totalEpochs)
                putExtra(EXTRA_STEP, step)
                putExtra(EXTRA_TOTAL_STEPS, totalSteps)
                putExtra(EXTRA_LOSS, loss)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun updateProgress(context: Context, epoch: Int, totalEpochs: Int, step: Int, totalSteps: Int, loss: Float) {
            val intent = Intent(context, NeuralTrainingForegroundService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_EPOCH, epoch)
                putExtra(EXTRA_TOTAL_EPOCHS, totalEpochs)
                putExtra(EXTRA_STEP, step)
                putExtra(EXTRA_TOTAL_STEPS, totalSteps)
                putExtra(EXTRA_LOSS, loss)
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                // Ignore if service not running
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, NeuralTrainingForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                // Ignore if service not running
            }
        }
    }

    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val epoch = intent.getIntExtra(EXTRA_EPOCH, 1)
                val totalEpochs = intent.getIntExtra(EXTRA_TOTAL_EPOCHS, 1)
                val step = intent.getIntExtra(EXTRA_STEP, 0)
                val totalSteps = intent.getIntExtra(EXTRA_TOTAL_STEPS, 1)
                val loss = intent.getFloatExtra(EXTRA_LOSS, 0f)

                val notification = buildNotification(epoch, totalEpochs, step, totalSteps, loss)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            }
            ACTION_UPDATE -> {
                val epoch = intent.getIntExtra(EXTRA_EPOCH, 1)
                val totalEpochs = intent.getIntExtra(EXTRA_TOTAL_EPOCHS, 1)
                val step = intent.getIntExtra(EXTRA_STEP, 0)
                val totalSteps = intent.getIntExtra(EXTRA_TOTAL_STEPS, 1)
                val loss = intent.getFloatExtra(EXTRA_LOSS, 0f)

                val notification = buildNotification(epoch, totalEpochs, step, totalSteps, loss)
                notificationManager.notify(NOTIFICATION_ID, notification)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Nexora Pelatihan Saraf On-Device",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifikasi status proses training neural network on-device di background"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(
        epoch: Int,
        totalEpochs: Int,
        step: Int,
        totalSteps: Int,
        loss: Float
    ): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val lossText = if (loss > 0f) " • Loss: %.4f".format(loss) else ""
        val contentText = "Epoch $epoch/$totalEpochs • Step $step/$totalSteps$lossText"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Nexora • Pelatihan On-Device Aktif")
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(totalSteps.coerceAtLeast(1), step.coerceIn(0, totalSteps), false)
            .build()
    }
}
