package com.example.eodigaji.navi

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.eodigaji.MainActivity
import com.example.eodigaji.R

class BlindDriveMonitorService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val blindName = intent?.getStringExtra(EXTRA_BLIND_NAME).orEmpty().ifBlank { "비밀 장소" }
        ensureChannel()
        val notification = buildNotification(blindName)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIF_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start foreground location service: ${e.message}")
            stopSelf()
        }
        return START_STICKY
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "비밀 드라이브 도착 감지",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "내비게이션 안내 중 목적지 도착 여부를 백그라운드에서 확인합니다."
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(blindName: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_brand_mark)
            .setContentTitle("‘$blindName’로 안내 중")
            .setContentText("목적지에 도착하면 정체를 열어 드릴게요.")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    companion object {
        private const val TAG = "BlindDriveMonitorSvc"
        private const val CHANNEL_ID = "blind_drive_monitor_channel"
        private const val NOTIF_ID = 1002
        private const val EXTRA_BLIND_NAME = "extra_blind_name"
        private const val ACTION_STOP = "com.example.eodigaji.action.STOP_MONITOR"

        fun start(context: Context, blindName: String) {
            val hasFine = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            val hasCoarse = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasFine && !hasCoarse) {
                Log.d(TAG, "Skipping BlindDriveMonitorService start: missing location permission")
                return
            }

            val intent = Intent(context, BlindDriveMonitorService::class.java).apply {
                putExtra(EXTRA_BLIND_NAME, blindName)
            }
            runCatching {
                ContextCompat.startForegroundService(context, intent)
            }.onFailure { e ->
                Log.w(TAG, "startForegroundService failed: ${e.message}")
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, BlindDriveMonitorService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching {
                context.stopService(intent)
            }.onFailure { e ->
                Log.w(TAG, "stopService failed: ${e.message}")
            }
        }
    }
}
