package com.neondrive.launcher.phone

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

data class PairedBtDevice(
    val address: String,
    val name: String,
    val connectedHeadset: Boolean,
    val connectedMedia: Boolean
) {
    val connected: Boolean get() = connectedHeadset || connectedMedia
}

/**
 * Список сопряжённых Bluetooth-устройств и их живой статус подключения — для
 * настроек «Bluetooth» (выбор устройства-телефона).
 *
 * Честная граница платформы: обычному приложению без системных прав Android не
 * даёт программно инициировать подключение уже сопряжённого классического
 * профиля (HFP/A2DP) — методы connect()/disconnect() публичных прокси
 * BluetoothHeadset/BluetoothA2dp скрыты и требуют BLUETOOTH_PRIVILEGED,
 * недоступного сторонним приложениям. Поэтому здесь только чтение статуса —
 * само подключение делает пользователь через системные настройки Bluetooth.
 */
object BluetoothDevicesRepository {

    fun hasPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.BLUETOOTH_CONNECT
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    suspend fun list(context: Context): List<PairedBtDevice> {
        if (!hasPermission(context)) return emptyList()
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter ?: return emptyList()
        if (!adapter.isEnabled) return emptyList()

        val bonded = runCatching { adapter.bondedDevices }.getOrNull().orEmpty()
        if (bonded.isEmpty()) return emptyList()

        val headsetConnected = connectedAddresses(context, adapter, BluetoothProfile.HEADSET)
        val a2dpConnected = connectedAddresses(context, adapter, BluetoothProfile.A2DP)

        return bonded.map { d ->
            PairedBtDevice(
                address = d.address,
                name = runCatching { d.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: d.address,
                connectedHeadset = d.address in headsetConnected,
                connectedMedia = d.address in a2dpConnected
            )
        }.sortedWith(compareByDescending<PairedBtDevice> { it.connected }.thenBy { it.name })
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectedAddresses(
        context: Context,
        adapter: BluetoothAdapter,
        profile: Int
    ): Set<String> = withTimeoutOrNull(1500) {
        runCatching {
            suspendCancellableCoroutine<Set<String>> { cont ->
                val listener = object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                        val addrs = runCatching {
                            proxy.connectedDevices.map { it.address }.toSet()
                        }.getOrDefault(emptySet())
                        runCatching { adapter.closeProfileProxy(profile, proxy) }
                        if (cont.isActive) cont.resume(addrs)
                    }

                    override fun onServiceDisconnected(p: Int) {
                        if (cont.isActive) cont.resume(emptySet())
                    }
                }
                val started = adapter.getProfileProxy(context.applicationContext, listener, profile)
                if (!started && cont.isActive) cont.resume(emptySet())
            }
        }.getOrDefault(emptySet())
    } ?: emptySet()
}
