package com.tsproxy.android.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.tsproxy.android.R
import com.tsproxy.android.TsProxyApp
import com.tsproxy.android.ui.MainActivity
import tsproxy.Tsproxy
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class TsProxyService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 1. 系统杀掉重启 intent 为 null，从 prefs 恢复
        val action = intent?.action
        if (action == null) {
            val prefs = Prefs(this)
            if (prefs.hasConfig()) {
                startProxy(prefs.socks, prefs.hostname, prefs.dir)
            } else {
                stopSelf()
            }
            return START_REDELIVER_INTENT
        }

        when (action) {
            ACTION_START -> {
                val socks = intent.getStringExtra(EXTRA_SOCKS) ?: Prefs(this).socks ?: "127.0.0.1:1080"
                val hostname = intent.getStringExtra(EXTRA_HOSTNAME) ?: Prefs(this).hostname ?: "ts-socks5"
                var tsnetDir = intent.getStringExtra(EXTRA_TSNET_DIR) ?: Prefs(this).dir ?: ""
                if (tsnetDir.isEmpty()) tsnetDir = "${filesDir.absolutePath}/tsnet"
                // 落盘，下次被杀能恢复
                Prefs(this).save(socks, hostname, tsnetDir)
                startProxy(socks, hostname, tsnetDir)
            }
            ACTION_STOP -> stopProxy()
        }
        // 2. REDELIVER 会把原始 Intent（含参数）重发，比 STICKY 稳得多
        return START_REDELIVER_INTENT
    }

    private fun acquireLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (wakeLock == null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ts-proxy::keepalive").apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld != true) wakeLock?.acquire() // 不带超时，stop 时手动放

            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (wifiLock == null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ts-proxy::wifilock").apply {
                    setReferenceCounted(false)
                }
            }
            if (wifiLock?.isHeld != true) wifiLock?.acquire()
            TsProxyApp.appendLog("locks acquired")
        } catch (e: Exception) {
            TsProxyApp.appendLog("lock failed: ${e.message}")
        }
    }

    private fun releaseLocks() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            wifiLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {}
        wakeLock = null
        wifiLock = null
    }

    private fun startProxy(socks: String, hostname: String, tsnetDir: String) {
        if (!running.compareAndSet(false, true)) {
            TsProxyApp.appendLog("startProxy: already running, ignore")
            return
        }
        val notification = buildNotification("Starting ts-socks5...")
        // 3. Android 10+/14 前台类型必须带上，否则后台存活时间很短
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        acquireLocks()
        TsProxyApp.appendLog("startProxy: socks=$socks host=$hostname dir=$tsnetDir")

        executor.execute {
            try {
                val result = Tsproxy.start(socks, hostname, tsnetDir)
                val status = when {
                    result.startsWith("ERROR:") -> "Failed: ${result.removePrefix("ERROR: ").take(80)}"
                    else -> "Running on $socks"
                }
                TsProxyApp.appendLog("Tsproxy.start returned: $status")
                updateNotification(status)
                // Tsproxy.start 如果返回了说明退出了，标记不在运行，方便下次拉起
                if (result.startsWith("ERROR:")) running.set(false)
            } catch (e: Exception) {
                running.set(false)
                TsProxyApp.appendLog("startProxy EXCEPTION: ${Log.getStackTraceString(e)}")
                updateNotification("Crash: ${e.message?.take(80) ?: "unknown"}")
            }
        }
    }

    private fun stopProxy() {
        executor.execute {
            try { Tsproxy.stop() } catch (e: Exception) {
                Log.e(TAG, "Stop failed", e)
            } finally {
                running.set(false)
                releaseLocks()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, TsProxyApp.CHANNEL_ID)
            .setContentTitle("ts-socks5")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification(text))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update notification", e)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 4. 划掉任务栏也要带上参数重启
        val prefs = Prefs(this)
        val restartIntent = Intent(applicationContext, TsProxyService::class.java).apply {
            action = ACTION_START
            putExtra(EXTRA_SOCKS, prefs.socks)
            putExtra(EXTRA_HOSTNAME, prefs.hostname)
            putExtra(EXTRA_TSNET_DIR, prefs.dir)
        }
        val pendingIntent = PendingIntent.getService(
            applicationContext, 1, restartIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        alarmManager.set(
            android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
            android.os.SystemClock.elapsedRealtime() + 1000,
            pendingIntent
        )
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        releaseLocks()
        super.onDestroy()
    }

    // 简单 prefs 封装，放同文件底下就行
    private class Prefs(ctx: Context) {
        private val sp = ctx.getSharedPreferences("ts-proxy", Context.MODE_PRIVATE)
        val socks: String? get() = sp.getString("socks", null)
        val hostname: String? get() = sp.getString("hostname", null)
        val dir: String? get() = sp.getString("dir", null)
        fun hasConfig() = socks != null
        fun save(s: String, h: String, d: String) {
            sp.edit().putString("socks", s).putString("hostname", h).putString("dir", d).apply()
        }
    }

    companion object {
        private const val TAG = "TsProxyService"
        const val ACTION_START = "com.tsproxy.android.START"
        const val ACTION_STOP = "com.tsproxy.android.STOP"
        const val EXTRA_SOCKS = "socks"
        const val EXTRA_HOSTNAME = "hostname"
        const val EXTRA_TSNET_DIR = "tsnet_dir"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context, socks: String, hostname: String, tsnetDir: String) {
            val intent = Intent(context, TsProxyService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SOCKS, socks)
                putExtra(EXTRA_HOSTNAME, hostname)
                putExtra(EXTRA_TSNET_DIR, tsnetDir)
            }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, TsProxyService::class.java).apply {
                action = ACTION_STOP
            }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }
    }
}
