package com.sersoluciones.flutter_pos_printer_platform.bluetooth

import android.bluetooth.*
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.util.Log
import com.sersoluciones.flutter_pos_printer_platform.bluetooth.SampleGattAttributes.Companion.CLIENT_CHARACTERISTIC_CONFIG
import com.sersoluciones.flutter_pos_printer_platform.bluetooth.SampleGattAttributes.Companion.HEART_RATE_MEASUREMENT
import io.flutter.plugin.common.MethodChannel
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val TAG = "BluetoothBleConnection"

/// Every BLE link starts here until a larger MTU is negotiated.
private const val DEFAULT_ATT_MTU = 23

/// ATT opcode + handle overhead on every write.
private const val ATT_HEADER_BYTES = 3

/// Largest MTU the spec allows; printers typically grant 185 or 247.
private const val PREFERRED_ATT_MTU = 517

/// Floor for the chunk size, so a bogus MTU can't produce zero-length writes.
private const val MIN_CHUNK_BYTES = 20

/// A chunk the stack never acknowledges. Generous: a busy printer can take a
/// moment, but the print thread must not park on a dead link forever.
private const val WRITE_TIMEOUT_MS = 5_000L

class BluetoothBleConnection(
    private val mContext: Context,
    handler: Handler,
    private var autoConnect: Boolean = false
) : IBluetoothConnection {

    @Volatile
    private var mHandler: Handler = handler
    private var bluetoothGatt: BluetoothGatt? = null
    private var mCharacteristic: BluetoothGattCharacteristic? = null
    private var mState: Int = BluetoothConstants.STATE_NONE

    /// Payload capacity of one ATT write, learned from [onMtuChanged].
    ///
    /// Starts at the spec default every BLE link begins on. We ask for more
    /// once services are discovered; if the printer refuses, 23 still works —
    /// it just means more chunks, which is the difference between a slow
    /// receipt and half a receipt.
    @Volatile
    private var negotiatedMtu: Int = DEFAULT_ATT_MTU

    /// Released by [ResponseBluetoothGattCallback.onCharacteristicWrite] so
    /// [write] can send the next chunk only once the stack has taken this one.
    @Volatile
    private var writeLatch: CountDownLatch? = null

    @Volatile
    private var lastWriteStatus: Int = BluetoothGatt.GATT_SUCCESS

    /// See [IBluetoothConnection.setHandler]. Volatile because the GATT
    /// callbacks arrive on a binder thread.
    override fun setHandler(handler: Handler) {
        mHandler = handler
    }

    /**
     * Return the current connection state.
     * Set the current state of the chat connection
     */
    @get:Synchronized
    @set:Synchronized
    override var state: Int
        get() = mState
        set(state) {
            // Log.d(TAG, "setState() " + mState + " -> " + state);

            if (state != BluetoothConstants.STATE_FAILED && state != BluetoothConstants.STATE_CONNECTED)
            // Give the new state to the Handler so the UI Activity can update
                mHandler.obtainMessage(BluetoothConstants.MESSAGE_STATE_CHANGE, state, -1).sendToTarget()
            if (state == BluetoothConstants.STATE_FAILED) mState = BluetoothConstants.STATE_NONE
            mState = state
        }


    /**
     * connect to bluetooth device
     */
    override fun connect(address: String, result: MethodChannel.Result) {
        if (!address.matches(Regex(BluetoothConstants.BLUETOOTH_REGEX))) return
        if (mState == BluetoothConstants.STATE_CONNECTED) return
        state = BluetoothConstants.STATE_CONNECTING

        BluetoothAdapter.getDefaultAdapter()?.let { adapter ->
            try {
                val device = adapter.getRemoteDevice(address)
                val bluetoothGattCallback = ResponseBluetoothGattCallback(result)

                // connect to the GATT server on the device
                bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    device.connectGatt(
                        mContext,
                        autoConnect,
                        bluetoothGattCallback,
                        BluetoothDevice.TRANSPORT_LE
                    )
                } else {
                    device.connectGatt(mContext, autoConnect, bluetoothGattCallback)
                }

                // Send the name of the connected device back to the UI Activity
                val msg = mHandler.obtainMessage(BluetoothConstants.MESSAGE_DEVICE_NAME)
                val bundle = Bundle()
                bundle.putString(BluetoothConstants.DEVICE_NAME, device.name)
                msg.data = bundle
                mHandler.sendMessage(msg)

            } catch (exception: IllegalArgumentException) {
                state = BluetoothConstants.STATE_FAILED
                Log.w(TAG, "Device not found with provided address.")
                // Give the new state to the Handler so the UI Activity can update
                mHandler.obtainMessage(BluetoothConstants.MESSAGE_STATE_CHANGE, state, -1, result).sendToTarget()
                state = BluetoothConstants.STATE_NONE
            }
            // connect to the GATT server on the device
        } ?: run {
            Log.w(TAG, "BluetoothAdapter not initialized")
            return
        }

    }

    /**
     * finish connection
     */
    override fun stop() {
        bluetoothGatt?.let { gatt ->
            gatt.disconnect()
            gatt.close()
            bluetoothGatt = null
            releasePendingWrite()
            negotiatedMtu = DEFAULT_ATT_MTU
            mCharacteristic = null
            state = BluetoothConstants.STATE_NONE
        }
    }

    /// Fail any chunk currently being waited on.
    ///
    /// Without this a print in flight when the link drops sits out the full
    /// [WRITE_TIMEOUT_MS] for the chunk it is on — and the MTU from the dead
    /// link would otherwise be reused to size chunks on the next one.
    private fun releasePendingWrite() {
        lastWriteStatus = BluetoothGatt.GATT_FAILURE
        writeLatch?.countDown()
    }

    /**
     * Write [out] to the printer in ATT-sized chunks, waiting for each one to
     * be acknowledged before sending the next.
     *
     * A single `writeCharacteristic` call carries at most ATT_MTU − 3 bytes.
     * This method used to hand it the WHOLE receipt and return: the first
     * ~182 bytes reached the printer, the remainder was discarded by the
     * stack without an error, and the caller reported a successful print. On
     * an 80mm ticket that is the restaurant name, address and phone — and
     * nothing else, not even the cut, because the cut command sits at the end
     * of the buffer that never went out.
     *
     * Blocks until the whole payload is acknowledged or a chunk fails. Callers
     * must not run it on the main thread.
     */
    override fun write(out: ByteArray?): Boolean {
        val payload = out ?: return false
        if (payload.isEmpty()) return true

        val characteristic = mCharacteristic ?: run {
            Log.w(TAG, "write: no writable characteristic — not connected?")
            return false
        }
        val gatt = bluetoothGatt ?: run {
            Log.w(TAG, "write: not connected to a BLE device")
            return false
        }

        // Prefer write-WITHOUT-response: receipt printers are sinks, they have
        // nothing to say back, and acked writes roughly halve throughput. On
        // Android the completion callback still fires for no-response writes —
        // it is the stack's "buffer free again" signal — so it paces us either
        // way. Fall back to an acked write if the characteristic demands one.
        val writeType = if (characteristic.supportsWriteWithoutResponse()) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }

        val chunkSize = (negotiatedMtu - ATT_HEADER_BYTES).coerceAtLeast(MIN_CHUNK_BYTES)
        var offset = 0
        var chunkIndex = 0

        while (offset < payload.size) {
            val end = minOf(offset + chunkSize, payload.size)
            val chunk = payload.copyOfRange(offset, end)

            writeLatch = CountDownLatch(1)
            lastWriteStatus = BluetoothGatt.GATT_SUCCESS

            characteristic.writeType = writeType
            characteristic.value = chunk

            val queued = gatt.writeCharacteristic(characteristic)
            if (!queued) {
                Log.e(TAG, "write: stack refused chunk $chunkIndex " +
                    "(${chunk.size}B at offset $offset of ${payload.size}B)")
                writeLatch = null
                return false
            }

            // A chunk that is never acknowledged is worse than one that fails:
            // without the timeout the print thread would park forever holding
            // the printer.
            val acked = try {
                writeLatch?.await(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS) ?: false
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                Log.w(TAG, "write: interrupted waiting for chunk $chunkIndex")
                writeLatch = null
                return false
            }
            writeLatch = null

            if (!acked) {
                Log.e(TAG, "write: chunk $chunkIndex timed out after ${WRITE_TIMEOUT_MS}ms " +
                    "(${offset + chunk.size}/${payload.size} bytes sent)")
                return false
            }
            if (lastWriteStatus != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "write: chunk $chunkIndex failed, status $lastWriteStatus " +
                    "(${offset + chunk.size}/${payload.size} bytes sent)")
                return false
            }

            offset = end
            chunkIndex++
        }

        Log.d(TAG, "write: sent ${payload.size}B in $chunkIndex chunk(s) of up to ${chunkSize}B")
        // Share the sent message back to the UI Activity
        mHandler.obtainMessage(BluetoothConstants.MESSAGE_WRITE, -1, -1, payload)
            .sendToTarget()
        return true
    }

    private fun BluetoothGattCharacteristic.supportsWriteWithoutResponse(): Boolean =
        properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0

    /***
     *
     */
    private inner class ResponseBluetoothGattCallback(private val result: MethodChannel.Result) : BluetoothGattCallback() {
        private var mmChannelResult: MethodChannel.Result? = null

        init {
            mmChannelResult = result
        }

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, " ---------- onConnectionStateChange: newState $newState status $status")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    // successfully connected to the GATT Server
                    Log.d(TAG, "onConnectionStateChange: STATE_CONNECTED")
                    state = BluetoothConstants.STATE_CONNECTED

                    if (mmChannelResult != null) {
                        // Give the new state to the Handler so the UI Activity can update
                        mHandler.obtainMessage(BluetoothConstants.MESSAGE_STATE_CHANGE, state, -1, result).sendToTarget()
                        mmChannelResult = null
                    } else {
                        mHandler.obtainMessage(BluetoothConstants.MESSAGE_STATE_CHANGE, state, -1).sendToTarget()
                    }
                    // Attempts to discover services after successful connection.
                    bluetoothGatt?.discoverServices()

                }
                BluetoothProfile.STATE_CONNECTING -> {
                    Log.d(TAG, "onConnectionStateChange: STATE_CONNECTING")
                    // connecting from the GATT Server
                    state = BluetoothConstants.STATE_CONNECTING
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "onConnectionStateChange: STATE_DISCONNECTED")
                    // Unblock a print that was mid-receipt when the link died.
                    releasePendingWrite()
                    negotiatedMtu = DEFAULT_ATT_MTU
                    if (mmChannelResult != null) {
                        // disconnected from the GATT Server
                        state = BluetoothConstants.STATE_FAILED
                        // Give the new state to the Handler so the UI Activity can update
                        mHandler.obtainMessage(BluetoothConstants.MESSAGE_STATE_CHANGE, state, -1, result).sendToTarget()
                        mmChannelResult = null
                    }

                    state = BluetoothConstants.STATE_NONE
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {

                displayGattServices(getSupportedGattServices())
                // Ask for the biggest ATT payload the printer will grant. A
                // receipt is 700–1200 bytes; at the default MTU that is ~60
                // round trips, and every one of them is a chance to stall.
                // Best-effort — [onMtuChanged] records whatever we actually get.
                if (gatt?.requestMtu(PREFERRED_ATT_MTU) != true) {
                    Log.w(TAG, "requestMtu was refused; staying at $negotiatedMtu")
                }
            } else {
                Log.w(TAG, "onServicesDiscovered received: $status")
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            super.onMtuChanged(gatt, mtu, status)
            if (status == BluetoothGatt.GATT_SUCCESS && mtu > 0) {
                negotiatedMtu = mtu
                Log.d(TAG, "ATT MTU negotiated: $mtu (${mtu - ATT_HEADER_BYTES}B per write)")
            } else {
                Log.w(TAG, "MTU negotiation failed (status $status); staying at $negotiatedMtu")
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                broadcastUpdate(characteristic)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            broadcastUpdate(characteristic)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            when (status) {
                BluetoothGatt.GATT_SUCCESS -> {
                    // Nothing to log per chunk — a receipt is dozens of them.
                }
                BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH -> {
                    Log.e(TAG, "Write exceeded connection ATT MTU ($negotiatedMtu)!")
                }
                BluetoothGatt.GATT_WRITE_NOT_PERMITTED -> {
                    Log.e(TAG, "Write not permitted for ${characteristic.uuid}!")
                }
                else -> {
                    Log.e(TAG, "Chunk write failed for ${characteristic.uuid}, error: $status")
                }
            }
            // Record BEFORE releasing, so the waiting thread can't read a
            // stale status through the gap.
            lastWriteStatus = status
            writeLatch?.countDown()
        }
    }


    /***
     * Demonstrates how to iterate through the supported GATT
     * Services/Characteristics.
     * In this sample, we populate the data structure that is bound to the
     * ExpandableListView on the UI.
     */
    private fun displayGattServices(gattServices: List<BluetoothGattService>?) {
        if (gattServices == null) return
        var uuid: String?

        // Loops through available GATT Services.
        gattServices.forEach { gattService ->
            uuid = gattService.uuid.toString()

            // Loops through available Characteristics.
            gattService.characteristics.forEach { gattCharacteristic ->
                uuid = gattCharacteristic.uuid.toString()
//                Log.d(
//                    TAG,
//                    " ------- gattCharacteristics -> name: ${
//                        SampleGattAttributes.lookup(
//                            uuid!!,
//                            "Servicio desconocido"
//                        )!!
//                    } uuid: $uuid"
//                )

                setCharacteristicNotification(gattCharacteristic)

            }
        }

    }

    fun getSupportedGattServices(): List<BluetoothGattService>? {
        return bluetoothGatt?.services
    }


    // Read from the InputStream
    private var buffer = ArrayList<Byte>()

    private fun broadcastUpdate(characteristic: BluetoothGattCharacteristic?) {

        if (characteristic != null) {
            when (characteristic.uuid) {
                UUID_HEART_RATE_MEASUREMENT -> {

                }
                else -> {
                    // For all other profiles, writes the data formatted in HEX.
                    val data: ByteArray? = characteristic.value
                    if (data?.isNotEmpty() == true) {

                        // 30 33 20 30 30 20 30 30 20 30 30 20 30 30 20 30 30 20 44 38 20 38 32 20 0D 0A
                        // 48 51 32 48 48 32 48 48 32 48 48 32 48 48 32 48 48 32 68 56 32 56 50 32 13 10
                        for (byte in data) {

                            buffer.add(byte)
//                            buffer += byte
                            if (byte.toInt() == 13) {
                                sendMsg()
                                break
                            }
                        }

                    }
                }
            }
        }
    }

    private fun sendMsg() {
        val hexString: String = buffer.joinToString(separator = " ") {
            String.format("%02X", it)
        }
//        Log.d(TAG, "sendMsg data $hexString value size ${hexString.length}")
        // Send the obtained bytes to the UI Activity
        mHandler.obtainMessage(
            BluetoothConstants.MESSAGE_READ,
            buffer.size,
            -1,
            buffer.toByteArray()
        ).sendToTarget()

        buffer = arrayListOf()

    }

    @Suppress("unused")
    fun readCharacteristic(characteristic: BluetoothGattCharacteristic) {
        bluetoothGatt?.readCharacteristic(characteristic) ?: run {
            Log.w(TAG, "BluetoothGatt not initialized")
            return
        }
    }

    private fun setCharacteristicNotification(
        characteristic: BluetoothGattCharacteristic
    ) {
        bluetoothGatt?.let { gatt ->

//            if (UUID_MEASUREMENT == characteristic.uuid) {
            gatt.setCharacteristicNotification(characteristic, true)

            val descriptor: BluetoothGattDescriptor =
                characteristic.getDescriptor(UUID.fromString(CLIENT_CHARACTERISTIC_CONFIG))
                    ?: return
            mCharacteristic = characteristic
//            Log.w(TAG, " *************** BluetoothGatt descriptor ${characteristic.uuid}")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(descriptor)
//            }
        } ?: run {
            Log.w(TAG, "BluetoothGatt not initialized")
        }
    }

    @Suppress("unused")
    fun disableNotifications(characteristic: BluetoothGattCharacteristic) {
        bluetoothGatt?.let { gatt ->
            characteristic.getDescriptor(UUID.fromString(CLIENT_CHARACTERISTIC_CONFIG))
                ?.let { cccDescriptor ->
                    if (bluetoothGatt?.setCharacteristicNotification(
                            characteristic,
                            false
                        ) == false
                    ) {
                        Log.e(
                            "ConnectionManager",
                            "setCharacteristicNotification failed for ${characteristic.uuid}"
                        )
                        return
                    }

                    cccDescriptor.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(cccDescriptor)
                } ?: Log.e(
                "ConnectionManager",
                "${characteristic.uuid} doesn't contain the CCC descriptor!"
            )
        }
    }


    companion object {

        val UUID_HEART_RATE_MEASUREMENT: UUID =
            UUID.fromString(HEART_RATE_MEASUREMENT)

    }
}