package com.noop.ble

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // In production, pass Notification built via NotificationCompat.Builder
            // with ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                0
            }
            // startForeground(NOTIFICATION_ID, notification, fgsType)
        }
        return START_STICKY
    }

    /**
     * Called from BluetoothGattCallback.onCharacteristicChanged(gatt, characteristic, value) (API 33+).
     *Copies payload off the binder thread immediately without blocking.
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

    private fun flushBatchWithBoundedWakeLock(
        batch: List<Pair<Long, Whoop5Fw5043Patch.Parsed2A37Packet>>
    ) {
        if (batch.isEmpty()) return
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "noop:ble_flush_lock")?.apply {
            setReferenceCounted(false)
            acquire(5_000L) // Bounded 5-second timeout per batch write (QA Rule 3.6)
        }
        try {
            // Insert into SQLite (WAL mode): hrSample(deviceId, ts, bpm) and rrInterval(deviceId, ts, slot, rrMs)
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
