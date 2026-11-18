package com.noop.ble

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.noop.NoopApplication
import com.noop.R
import com.noop.data.HrRow
import com.noop.data.RrRow
import com.noop.data.StreamBatch
import com.noop.protocol.RrSourceChannel
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/**
 * Android 14/15/16 Compliant Foreground BLE Live-Stream Banker Service (QA Section 3).
 *
 * - Uses FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE (never dataSync, which has a 6h timeout on Android 15).
 * - Verifies BLUETOOTH_CONNECT runtime permission before calling startForeground().
 * - Runs GATT 0x2A37 parsing off the binder thread via a single-writer Channel -> SQLite batch queue
 *   inside a SupervisorJob + CoroutineExceptionHandler scope so bad packets never crash the process.
 * - Uses a bounded 5,000ms partial WakeLock per SQLite batch flush rather than holding a lock all night.
 * - Persists BackfillCircuitState per strap MAC/deviceId in SharedPreferences.
 */
class LiveStreamBankerService : Service() {

    private val exceptionHandler = CoroutineExceptionHandler { _, _ -> }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
    private val packetQueue = Channel<Pair<Long, ByteArray>>(capacity = 256)

    override fun onCreate() {
        super.onCreate()
        startSingleWriterConsumer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!hasBluetoothConnectPermission()) {
            stopSelf()
            return START_NOT_STICKY
        }
        val channelId = "noop_live_banker"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            val ch = NotificationChannel(
                channelId,
                "WHOOP Live Stream Banker",
                NotificationManager.IMPORTANCE_LOW,
            )
            nm.createNotificationChannel(ch)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("WHOOP 5.0 Live Stream Active")
            .setContentText("Banking live heart rate and R-R intervals to local SQLite")
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(4201, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(4201, notification)
        }
        return START_STICKY
    }

    /**
     * Called from BluetoothGattCallback.onCharacteristicChanged(gatt, characteristic, value) (API 33+).
     * Copies payload off the binder thread immediately without blocking.
     */
    fun onRaw2A37Notification(nowEpochSec: Long, value: ByteArray) {
        packetQueue.trySend(nowEpochSec to value.copyOf())
    }

    private fun startSingleWriterConsumer() {
        serviceScope.launch {
            val batch = ArrayList<Pair<Long, Whoop5Fw5043Patch.Parsed2A37Packet>>(16)
            for ((tsSec, rawBytes) in packetQueue) {
                val parsed = Whoop5Fw5043Patch.parseStandardHeartRate2A37(rawBytes) ?: continue
                batch.add(tsSec to parsed)
                if (batch.size >= 10 || packetQueue.isEmpty) {
                    flushBatchWithBoundedWakeLock(batch)
                    batch.clear()
                }
            }
        }
    }

    private suspend fun flushBatchWithBoundedWakeLock(
        batch: List<Pair<Long, Whoop5Fw5043Patch.Parsed2A37Packet>>
    ) {
        if (batch.isEmpty()) return
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "noop:ble_flush_lock")?.apply {
            setReferenceCounted(false)
            acquire(5_000L) // Bounded 5-second timeout per batch write (QA Rule 3.6)
        }
        try {
            val noopApp = application as? NoopApplication ?: return
            val repo = noopApp.repository
            val devId = noopApp.activeDeviceId.ifBlank { WhoopBleClient.DEFAULT_DEVICE_ID }
            val hrRows = ArrayList<HrRow>(batch.size)
            val rrRows = ArrayList<RrRow>(batch.size * 2)
            for ((tsSec, pkt) in batch) {
                if (pkt.hrBpm in 30..220) {
                    hrRows.add(HrRow(ts = tsSec, bpm = pkt.hrBpm))
                }
                for (rrMs in pkt.rrIntervalsMs) {
                    if (rrMs in 250..3000) {
                        rrRows.add(RrRow(ts = tsSec, rrMs = rrMs, srcChannel = RrSourceChannel.WHOOP5_STANDARD))
                    }
                }
            }
            if (hrRows.isNotEmpty() || rrRows.isNotEmpty()) {
                repo.insert(StreamBatch(hr = hrRows, rr = rrRows), devId)
            }
        } finally {
            if (wakeLock?.isHeld == true) {
                wakeLock.release()
            }
        }
    }

    fun loadPersistedBreakerState(deviceId: String): Whoop5Fw5043Patch.BackfillCircuitState {
        val prefs = getSharedPreferences("noop_fw5043_breaker", Context.MODE_PRIVATE)
        return Whoop5Fw5043Patch.BackfillCircuitState.decode(prefs.getString(deviceId, null))
    }

    fun savePersistedBreakerState(deviceId: String, state: Whoop5Fw5043Patch.BackfillCircuitState) {
        val prefs = getSharedPreferences("noop_fw5043_breaker", Context.MODE_PRIVATE)
        prefs.edit().putString(deviceId, state.encode()).apply()
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    override fun onDestroy() {
        packetQueue.close()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
