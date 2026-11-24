// SPDX-License-Identifier: AGPL-3.0-only
package org.aprserja.mobile

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic as Characteristic
import android.bluetooth.BluetoothGattDescriptor as Descriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import org.aprsfoundation.core.BleKissProfile
import java.io.Closeable
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@SuppressLint("MissingPermission")
internal class BleTnc : BluetoothGattCallback(), TncIo, Closeable {
    private val ready = CompletableFuture<Unit>()
    private val chunks = ArrayBlockingQueue<ByteArray>(256)
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var closed = false
    @Volatile private var failure: IOException? = null
    @Volatile private var written: CompletableFuture<Unit>? = null
    private lateinit var writer: Characteristic
    private lateinit var reader: Characteristic
    private lateinit var configuration: Descriptor

    fun open(context: Context, device: BluetoothDevice) {
        synchronized(this) {
            check(!closed) { "TNC disconnected" }
            gatt = device.connectGatt(context, false, this, BluetoothDevice.TRANSPORT_LE)
        }
        ready.get(12, TimeUnit.SECONDS)
    }

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, state: Int) {
        if (closed) return
        when {
            status != BluetoothGatt.GATT_SUCCESS || state == BluetoothProfile.STATE_DISCONNECTED ->
                fail("Bluetooth LE disconnected (status $status)")
            state == BluetoothProfile.STATE_CONNECTED && !gatt.discoverServices() ->
                fail("Could not discover Bluetooth LE services")
        }
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        if (closed) return
        if (status != BluetoothGatt.GATT_SUCCESS) {
            fail("Could not discover Bluetooth LE services")
            return
        }
        val pair = BleKissProfile.entries.firstNotNullOfOrNull { profile ->
            val service = gatt.getService(UUID.fromString(profile.service)) ?: return@firstNotNullOfOrNull null
            val tx = service.getCharacteristic(UUID.fromString(profile.write)) ?: return@firstNotNullOfOrNull null
            val rx = service.getCharacteristic(UUID.fromString(profile.notify)) ?: return@firstNotNullOfOrNull null
            when {
                canWrite(tx) && canNotify(rx) -> tx to rx
                canWrite(rx) && canNotify(tx) -> rx to tx
                else -> null
            }
        }
        if (pair == null) {
            fail("Device does not expose a supported KISS BLE service")
            return
        }
        writer = pair.first
        reader = pair.second
        val descriptor = reader.getDescriptor(CCCD)
        if (descriptor == null || !gatt.setCharacteristicNotification(reader, true)) {
            fail("Could not enable TNC notifications")
            return
        }
        configuration = descriptor
        val value = if (reader.properties and Characteristic.PROPERTY_NOTIFY != 0)
            Descriptor.ENABLE_NOTIFICATION_VALUE else Descriptor.ENABLE_INDICATION_VALUE
        if (!writeDescriptor(gatt, descriptor, value)) fail("Could not subscribe to TNC notifications")
    }

    override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: Descriptor, status: Int) {
        if (closed || !::configuration.isInitialized || descriptor != configuration) return
        if (status == BluetoothGatt.GATT_SUCCESS) ready.complete(Unit) else fail("TNC notification subscription failed")
    }

    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: Characteristic, value: ByteArray) {
        if (!closed && ::reader.isInitialized && characteristic == reader && !chunks.offer(value.copyOf()))
            fail("TNC notification buffer overrun")
    }

    @Deprecated("Android 12 and earlier callback")
    @Suppress("DEPRECATION")
    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: Characteristic) {
        onCharacteristicChanged(gatt, characteristic, characteristic.value ?: return)
    }

    override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: Characteristic, status: Int) {
        if (closed || !::writer.isInitialized || characteristic != writer) return
        if (status == BluetoothGatt.GATT_SUCCESS) written?.complete(Unit) else fail("KISS BLE write failed")
    }

    override fun read(buffer: ByteArray): Int {
        failure?.let { throw it }
        if (closed) return -1
        val bytes = chunks.poll(250, TimeUnit.MILLISECONDS) ?: return 0
        bytes.copyInto(buffer)
        return bytes.size
    }

    override fun write(frame: ByteArray) {
        val active = gatt ?: throw IOException("Bluetooth LE disconnected")
        val type = if (writer.properties and Characteristic.PROPERTY_WRITE != 0)
            Characteristic.WRITE_TYPE_DEFAULT else Characteristic.WRITE_TYPE_NO_RESPONSE
        // The default ATT MTU is 23 bytes, leaving 20 bytes for each value.
        for (offset in frame.indices step 20) {
            failure?.let { throw it }
            val result = CompletableFuture<Unit>()
            written = result
            try {
                val chunk = frame.copyOfRange(offset, minOf(offset + 20, frame.size))
                check(writeCharacteristic(active, chunk, type)) { "Could not write KISS BLE data" }
                result.get(4, TimeUnit.SECONDS)
            } catch (error: Exception) {
                fail("KISS BLE write failed")
                throw IOException("KISS BLE write failed", error)
            } finally {
                written = null
            }
        }
    }

    private fun fail(message: String) {
        val error = IOException(message)
        failure = error
        ready.completeExceptionally(error)
        written?.completeExceptionally(error)
    }

    override fun close() {
        val active = synchronized(this) {
            closed = true
            gatt.also { gatt = null }
        } ?: return
        fail("Bluetooth LE disconnected")
        try {
            if (::reader.isInitialized) active.setCharacteristicNotification(reader, false)
            active.disconnect()
        } finally {
            active.close()
        }
    }

    @Suppress("DEPRECATION")
    private fun writeDescriptor(gatt: BluetoothGatt, descriptor: Descriptor, value: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= 33) gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        else {
            descriptor.value = value
            gatt.writeDescriptor(descriptor)
        }

    @Suppress("DEPRECATION")
    private fun writeCharacteristic(gatt: BluetoothGatt, bytes: ByteArray, type: Int): Boolean =
        if (Build.VERSION.SDK_INT >= 33) gatt.writeCharacteristic(writer, bytes, type) == BluetoothStatusCodes.SUCCESS
        else {
            writer.writeType = type
            writer.value = bytes
            gatt.writeCharacteristic(writer)
        }

    private companion object {
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        fun canWrite(c: Characteristic) =
            c.properties and (Characteristic.PROPERTY_WRITE or Characteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
        fun canNotify(c: Characteristic) =
            c.properties and (Characteristic.PROPERTY_NOTIFY or Characteristic.PROPERTY_INDICATE) != 0
    }
}
