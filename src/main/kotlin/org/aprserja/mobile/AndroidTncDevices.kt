// SPDX-License-Identifier: AGPL-3.0-only
package org.aprserja.mobile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import org.aprsfoundation.core.BleKissProfile
import java.util.concurrent.ConcurrentHashMap
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

internal interface TncIo {
    fun read(buffer: ByteArray): Int
    fun write(frame: ByteArray)
}

@SuppressLint("MissingPermission") // Main requests BLUETOOTH_CONNECT before listing Bluetooth devices.
internal class AndroidTncDevices(private val context: Context) {
    private val usb = context.getSystemService(UsbManager::class.java)
    private val bluetooth get() = context.getSystemService(BluetoothManager::class.java)?.adapter
    private fun serialDrivers() = usb?.let { UsbSerialProber.getDefaultProber().findAllDrivers(it) }.orEmpty()
    private fun usbId(device: UsbDevice, port: Int) = "${device.deviceName}#$port"

    suspend fun list(kind: String): String {
        val devices = JSONArray()
        fun add(id: String, name: String) {
            devices.put(JSONObject().put("id", id).put("name", name))
        }
        when (kind) {
            "usb" -> serialDrivers().forEach { driver ->
                val device = driver.device
                driver.ports.indices.forEach { port ->
                    add(usbId(device, port), "${device.productName ?: "USB serial"} · ${device.deviceName} · Port ${port + 1}")
                }
            }
            "ble" -> scanBle().forEach {
                add(it.address, (it.name ?: "Bluetooth LE TNC") + " · " + it.address)
            }
            "bluetooth" -> {
                val adapter = checkNotNull(bluetooth) { "Bluetooth is unavailable" }
                check(adapter.isEnabled) { "Turn on Bluetooth in Android Settings" }
                adapter.bondedDevices.sortedBy { it.name ?: it.address }.forEach {
                    add(it.address, "${it.name ?: "Bluetooth device"} · ${it.address}")
                }
            }
        }
        return devices.toString()
    }

    private suspend fun scanBle(): List<BluetoothDevice> {
        val scanner = checkNotNull(bluetooth?.bluetoothLeScanner) { "Turn on Bluetooth in Android Settings" }
        val devices = ConcurrentHashMap<String, BluetoothDevice>()
        val result = CompletableDeferred<Int>()
        val callback = object : ScanCallback() {
            override fun onScanResult(type: Int, result: ScanResult) {
                if (result.isConnectable) devices[result.device.address] = result.device
            }
            override fun onScanFailed(errorCode: Int) {
                result.complete(errorCode)
            }
        }
        val filters = BleKissProfile.entries.map {
            ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(it.service)).build()
        }
        scanner.startScan(filters, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
        try {
            val error = withTimeoutOrNull(5_000) { result.await() }
            check(error == null) { "Bluetooth LE scan failed ($error)" }
        } finally {
            scanner.stopScan(callback)
        }
        return devices.values.sortedWith(compareBy({ it.name.orEmpty() }, { it.address }))
    }

    suspend fun prepare(kind: String, device: String, host: String, port: Int, baud: Int): (TncLink) -> TncIo =
        when (kind) {
            "usb" -> prepareUsb(device, baud)
            "ble" -> {
                val adapter = checkNotNull(bluetooth) { "Bluetooth LE is unavailable" }
                check(adapter.isEnabled) { "Turn on Bluetooth in Android Settings" }
                val selected = adapter.getRemoteDevice(device)
                val open: (TncLink) -> TncIo = { link ->
                    val ble = BleTnc()
                    link.own(ble)
                    ble.open(context, selected)
                    ble
                }
                open
            }
            "bluetooth" -> {
                val adapter = checkNotNull(bluetooth) { "Bluetooth is unavailable" }
                check(adapter.isEnabled) { "Turn on Bluetooth in Android Settings" }
                val paired = adapter.bondedDevices.firstOrNull { it.address == device }
                    ?: error("Select a paired Bluetooth Classic TNC")
                val open: (TncLink) -> TncIo = { link ->
                    val socket = paired.createRfcommSocketToServiceRecord(SPP)
                    link.own(socket)
                    socket.connect()
                    stream(socket.inputStream, socket.outputStream)
                }
                open
            }
            "tcp" -> {
                require(host.isNotBlank() && port in 1..65535) { "Enter a valid TNC host and TCP port" }
                val open: (TncLink) -> TncIo = { link ->
                    val socket = Socket()
                    link.own(socket)
                    socket.connect(InetSocketAddress(host, port), 5_000)
                    stream(socket.getInputStream(), socket.getOutputStream())
                }
                open
            }
            else -> error("Select a TNC connection type")
        }

    private suspend fun prepareUsb(id: String, baud: Int): (TncLink) -> TncIo {
        require(baud > 0) { "Select a USB serial baud rate" }
        val manager = checkNotNull(usb) { "USB host is unavailable" }
        val driver = serialDrivers().firstOrNull { it.device.deviceName == id.substringBeforeLast('#') }
        val port = driver?.ports?.getOrNull(id.substringAfterLast('#').toIntOrNull() ?: -1)
            ?: error("Select a connected USB serial port")
        val device = port.driver.device
        if (!manager.hasPermission(device)) requestUsbPermission(manager, device)
        return { link ->
            val connection = checkNotNull(manager.openDevice(device)) { "Could not open USB serial device" }
            try {
                port.open(connection)
                link.own(Closeable { port.close() })
                port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            } catch (error: Exception) {
                connection.close()
                throw error
            }
            object : TncIo {
                override fun read(buffer: ByteArray): Int = port.read(buffer, 250)
                override fun write(frame: ByteArray) = port.write(frame, 2_000)
            }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun requestUsbPermission(manager: UsbManager, device: UsbDevice) {
        val action = "${context.packageName}.USB_TNC.${UUID.randomUUID()}"
        val result = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == action) {
                    result.complete(manager.hasPermission(device))
                }
            }
        }
        val filter = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(receiver, filter)
        try {
            val reply = PendingIntent.getBroadcast(context, 0, Intent(action).setPackage(context.packageName),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
            try {
                manager.requestPermission(device, reply)
                check(withTimeoutOrNull(60_000) { result.await() } == true) { "USB serial permission was not granted" }
            } finally {
                reply.cancel()
            }
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    private fun stream(input: InputStream, output: OutputStream) = object : TncIo {
        override fun read(buffer: ByteArray): Int = input.read(buffer)
        override fun write(frame: ByteArray) {
            output.write(frame)
            output.flush()
        }
    }

    private companion object {
        val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
    }
}
