package com.shilapi.xcertplay

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * Describes Android's own Bluetooth as LoopLink sees it. Many head units run calls and music on a
 * separate Bluetooth chip; a phone paired there never shows up here. Counts only, no names or addresses.
 */
internal object BluetoothDiagnostics {
    private const val TAG = "LoopLinkBluetooth"

    @SuppressLint("MissingPermission")
    fun describe(context: Context): String {
        val pm = context.packageManager
        val feature = pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)
        val permission = Build.VERSION.SDK_INT < 31 ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        val settingsPage = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).resolveActivity(pm) != null
        val globalOn = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BLUETOOTH_ON, -1) }.getOrDefault(-1)
        val adapter = runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter }.getOrNull()
        val base = "Bluetooth: feature=$feature settingsPage=$settingsPage globalOn=$globalOn connectPermission=$permission"
        if (adapter == null) return "$base adapter=missing"
        val state = when (runCatching { adapter.state }.getOrDefault(-1)) {
            BluetoothAdapter.STATE_ON -> "on"
            BluetoothAdapter.STATE_OFF -> "off"
            BluetoothAdapter.STATE_TURNING_ON -> "turning-on"
            BluetoothAdapter.STATE_TURNING_OFF -> "turning-off"
            else -> "unknown"
        }
        if (!permission) return "$base adapter=$state bonded=unreadable(no permission)"
        val bonded = runCatching { adapter.bondedDevices.orEmpty() }.getOrElse {
            return "$base adapter=$state bonded=unreadable(${it.javaClass.simpleName})"
        }
        val iPhones = bonded.filter { it.name?.contains("iPhone", ignoreCase = true) == true }
        val phones = bonded.count { runCatching { it.bluetoothClass?.majorDeviceClass }.getOrNull() == android.bluetooth.BluetoothClass.Device.Major.PHONE }
        val connected = bonded.count(::isConnected)
        fun profile(id: Int) = when (runCatching { adapter.getProfileConnectionState(id) }.getOrDefault(-1)) {
            BluetoothProfile.STATE_CONNECTED -> "connected"
            BluetoothProfile.STATE_CONNECTING -> "connecting"
            BluetoothProfile.STATE_DISCONNECTED -> "disconnected"
            else -> "unknown"
        }
        val saved = DiPlayPreferences.phoneAddress(context)
        val savedState = when {
            saved == null -> "none"
            bonded.any { it.address.equals(saved, ignoreCase = true) } -> "bonded"
            else -> "not-bonded"
        }
        return "$base adapter=$state bonded=${bonded.size} phones=$phones iPhoneNamed=${iPhones.size} " +
            "iPhoneConnected=${iPhones.count(::isConnected)} anyConnected=$connected " +
            "headset=${profile(BluetoothProfile.HEADSET)} a2dp=${profile(BluetoothProfile.A2DP)} savedPhone=$savedState"
    }

    fun log(context: Context, reason: String) {
        Log.i(TAG, "$reason · ${describe(context)}")
    }

    private fun isConnected(device: BluetoothDevice): Boolean = runCatching {
        BluetoothDevice::class.java.getMethod("isConnected").invoke(device) as Boolean
    }.getOrDefault(false)
}
