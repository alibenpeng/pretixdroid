package eu.pretix.pretixdroid

import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Binder
import android.os.IBinder
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID

/**
 * Service for managing connection and data communication with a GATT server hosted on a
 * given Bluetooth LE device.
 */
class BluetoothLeService : Service() {

    private var mBluetoothManager: BluetoothManager? = null
    private var mBluetoothAdapter: BluetoothAdapter? = null
    private var mBluetoothDeviceAddress: String? = null
    private var mBluetoothGatt: BluetoothGatt? = null
    private var mConnectionState = STATE_DISCONNECTED

    // Implements callback methods for GATT events that the app cares about.  For example,
    // connection change and services discovered.
    private val mGattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val intentAction: String
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                intentAction = ACTION_GATT_CONNECTED
                mConnectionState = STATE_CONNECTED
                broadcastUpdate(intentAction)
                Log.i(TAG, "Connected to GATT server.")
                // Attempts to discover services after successful connection.
                Log.i(TAG, "Attempting to start service discovery:" + mBluetoothGatt!!.discoverServices())

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                intentAction = ACTION_GATT_DISCONNECTED
                mConnectionState = STATE_DISCONNECTED
                Log.i(TAG, "Disconnected from GATT server.")
                broadcastUpdate(intentAction)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                initCharacteristics()
                broadcastUpdate(ACTION_GATT_SERVICES_DISCOVERED)
            } else {
                Log.w(TAG, "onServicesDiscovered received: $status")
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt,
                                          characteristic: BluetoothGattCharacteristic,
                                          status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "onCharacteristicWrite ${characteristic.uuid}")
                if (characteristic.uuid == UUID_UART_TX_Char) {
                    if (uart_tx_chunk_iterator == null) return

                    if (uart_tx_chunk_iterator!!.hasNext())
                        writeNextChunk(characteristic, uart_tx_chunk_iterator!!.next())
                    else
                        broadcastUpdate(ACTION_DATA_SENT)
                }
            }
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt,
                                          characteristic: BluetoothGattCharacteristic,
                                          status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "onCharacteristicRead: ${characteristic.uuid}")
            }
            // we need to re-enable the notification for the RX Characteristic!
            if (uart_rx_characteristic != null)
                setCharacteristicNotification(uart_rx_characteristic!!, true)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt,
                                             characteristic: BluetoothGattCharacteristic) {
            Log.i(TAG, "onCharacteristicChanged")
            broadcastUpdate(ACTION_DATA_AVAILABLE, characteristic)
        }
    }

    private val mBinder = LocalBinder()

    /**
     * Retrieves a list of supported GATT services on the connected device. This should be
     * invoked only after `BluetoothGatt#discoverServices()` completes successfully.
     *
     * @return A `List` of supported services.
     */
    val supportedGattServices: List<BluetoothGattService>?
        get() = if (mBluetoothGatt == null) null else mBluetoothGatt!!.services

    /**
     * Retrieves the UART TX Characteristic
     */
    val uartTxCharacteristic: BluetoothGattCharacteristic?
        get() = if (mBluetoothGatt == null) null else uart_tx_characteristic

    /**
     * Broadcast update without data
     * Used for all cases except `onCharacteristicChanged`
     */
    private fun broadcastUpdate(action: String) {
        val intent = Intent(action)
        sendBroadcast(intent)
    }

    private fun broadcastUpdate(action: String,
                                characteristic: BluetoothGattCharacteristic) {
        val intent = Intent(action)

        Log.w(TAG, "broadcastUpdate()")

        val data = characteristic.value

        Log.v(TAG, "data.length: " + data!!.size)

        if (data != null && data.size > 0) {
            val stringBuilder = StringBuilder(data.size)
            for (byteChar in data) {
                stringBuilder.append(String.format("%02X ", byteChar))

                Log.v(TAG, String.format("%02X ", byteChar))
            }
            intent.putExtra(EXTRA_DATA, String(data))
        }

        sendBroadcast(intent)
    }

     inner class LocalBinder : Binder() {
         internal val service: BluetoothLeService
            get() = this@BluetoothLeService
    }

    override fun onBind(intent: Intent): IBinder? {
        return mBinder
    }

    override fun onUnbind(intent: Intent): Boolean {
        // After using a given device, you should make sure that BluetoothGatt.close() is called
        // such that resources are cleaned up properly.  In this particular example, close() is
        // invoked when the UI is disconnected from the Service.
        close()
        return super.onUnbind(intent)
    }

    /**
     * Initializes a reference to the local Bluetooth adapter.
     *
     * @return Return true if the initialization is successful.
     */
    fun initialize(): Boolean {
        // For API level 18 and above, get a reference to BluetoothAdapter through
        // BluetoothManager.
        if (mBluetoothManager == null) {
            mBluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            if (mBluetoothManager == null) {
                Log.e(TAG, "Unable to initialize BluetoothManager.")
                return false
            }
        }

        mBluetoothAdapter = mBluetoothManager!!.adapter
        if (mBluetoothAdapter == null) {
            Log.e(TAG, "Unable to obtain a BluetoothAdapter.")
            return false
        }

        return true
    }

    /**
     * Connects to the GATT server hosted on the Bluetooth LE device.
     *
     * @param address The device address of the destination device.
     *
     * @return Return true if the connection is initiated successfully. The connection result
     * is reported asynchronously through the
     * `BluetoothGattCallback#onConnectionStateChange(
     * android.bluetooth.BluetoothGatt, int, int)`
     * callback.
     */
    fun connect(address: String?): Boolean {
        val config = AppConfig(this)

        if (mBluetoothAdapter == null) {
            Log.e(TAG, "BluetoothAdapter not initialized.")
            return false
        }

        if (address == null) {
            Log.e(TAG, "Unspecified address.")
            return false
        }

        // Previously connected device.  Try to reconnect.
        if (mBluetoothDeviceAddress != null && address == mBluetoothDeviceAddress
                && mBluetoothGatt != null) {
            Log.d(TAG, "Trying to use an existing mBluetoothGatt for connection.")
            if (mBluetoothGatt!!.connect()) {
                config.blePrinterAddress = address
                mConnectionState = STATE_CONNECTING
                return true
            } else {
                return false
            }
        }

        val device = mBluetoothAdapter!!.getRemoteDevice(address)
        if (device == null) {
            Log.w(TAG, "Device not found.  Unable to connect.")
            return false
        }
        // We want to directly connect to the device, so we are setting the autoConnect
        // parameter to false.
        mBluetoothGatt = device.connectGatt(this, false, mGattCallback)
        Log.d(TAG, "Trying to create a new connection.")
        mBluetoothDeviceAddress = address
        config.blePrinterAddress = address
        mConnectionState = STATE_CONNECTING
        return true
    }

    /**
     * Disconnects an existing connection or cancel a pending connection. The disconnection result
     * is reported asynchronously through the
     * `BluetoothGattCallback#onConnectionStateChange(
     * android.bluetooth.BluetoothGatt, int, int)`
     * callback.
     */
    fun disconnect() {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized")
            return
        }
        mBluetoothGatt!!.disconnect()
    }

    /**
     * After using a given BLE device, the app must call this method to ensure resources are
     * released properly.
     */
    fun close() {
        if (mBluetoothGatt == null) {
            return
        }
        mBluetoothGatt!!.close()
        mBluetoothGatt = null
    }

    /**
     * Request a read on a given `BluetoothGattCharacteristic`. The read result is reported
     * asynchronously through the `BluetoothGattCallback#onCharacteristicRead(
     * android.bluetooth.BluetoothGatt, android.bluetooth.BluetoothGattCharacteristic, int)`
     * callback.
     *
     * @param characteristic The characteristic to read from.
     */
    fun readCharacteristic(characteristic: BluetoothGattCharacteristic) {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized")
            return
        }
        mBluetoothGatt!!.readCharacteristic(characteristic)
    }

    fun writeCharacteristic(characteristic: BluetoothGattCharacteristic) {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized")
            return
        }
        Log.w(TAG, "writeCharacteristic: ${characteristic.uuid}")
        mBluetoothGatt!!.writeCharacteristic(characteristic)
    }

    /**
     * Enables or disables notification on a given characteristic.
     *
     * @param characteristic Characteristic to act on.
     * @param enabled If true, enable notification. False otherwise.
     */
    fun setCharacteristicNotification(characteristic: BluetoothGattCharacteristic,
                                      enabled: Boolean) {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized")
            return
        }
        val characteristicNotification = mBluetoothGatt!!.setCharacteristicNotification(characteristic, enabled)

        // Enable notifications for RX Characteristic via its CCCD
        if (UUID_UART_RX_Char == characteristic.uuid) {
            Log.i(TAG, "Enabling notifications for characteristic UUID ${characteristic.uuid.toString()}")
            val descriptor = characteristic.getDescriptor(UUID_CCCD)

            if (descriptor == null) {
                Log.w(TAG, "descriptor is null!")
            } else {
                Log.i(TAG, "notifications enabled: ${characteristicNotification}, descriptor uuid: ${descriptor.uuid.toString()}")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                mBluetoothGatt!!.writeDescriptor(descriptor)
            }
        }
    }

    fun writeNextChunk(characteristic: BluetoothGattCharacteristic, chunk: String?) {
        Log.i(TAG, "write to BLE: " + chunk)

        // Sadly, the printer does not support Unicode, so...
        val printDataEnc = chunk?.toByteArray(Charsets.ISO_8859_1)
        printDataEnc?.forEach {
//            Log.d("CHARACTER ENCODING", String.format("Print data: %02x\n", it))
        }

        characteristic.value = printDataEnc
        writeCharacteristic(characteristic)

    }

    fun writeUartData(characteristic: BluetoothGattCharacteristic, data: String){

        val full_string: String = data + "\r\n"

        // split string into chunks of 20 characters
        uart_tx_chunks = full_string.chunked(20) as MutableList<String>
        uart_tx_chunk_iterator = uart_tx_chunks?.iterator()

        writeNextChunk(characteristic, uart_tx_chunk_iterator?.next())
    }

    fun initCharacteristics() {
        if (supportedGattServices == null) return

        for (gattService in supportedGattServices!!.iterator()) {
            val gattCharacteristics = gattService.characteristics
            for (characteristic in gattCharacteristics) {

                Log.i(TAG, "initCharacteristics for UUID ${characteristic.uuid}")

                when (characteristic.uuid) {
                    UUID_UART_Baudrate_Char -> uart_baudrate_characteristic = characteristic
                    UUID_UART_HWFC_Char -> uart_hwfc_characteristic = characteristic
                    UUID_UART_Name_Char -> uart_name_characteristic = characteristic
                    UUID_UART_TX_Char -> uart_tx_characteristic = characteristic
                    UUID_UART_RX_Char -> uart_rx_characteristic = characteristic
                }

                //if (characteristic.uuid != UUID_UART_RX_Char) readCharacteristic(characteristic)
            }
        }

        readCharacteristic(uart_baudrate_characteristic!!)
    }

    companion object {

        private val TAG = BluetoothLeService::class.java.simpleName

        private val STATE_DISCONNECTED = 0
        private val STATE_CONNECTING = 1
        private val STATE_CONNECTED = 2

        val ACTION_GATT_CONNECTED = "android-er.ACTION_GATT_CONNECTED"
        val ACTION_GATT_DISCONNECTED = "android-er.ACTION_GATT_DISCONNECTED"
        val ACTION_GATT_SERVICES_DISCOVERED = "android-er.ACTION_GATT_SERVICES_DISCOVERED"
        val ACTION_DATA_AVAILABLE = "android-er.ACTION_DATA_AVAILABLE"
        val ACTION_DATA_SENT = "android-er.ACTION_DATA_SENT"
        val EXTRA_DATA = "android-er.EXTRA_DATA"

        private var uart_tx_chunks: MutableList<String>? = null
        private var uart_tx_chunk_iterator: Iterator<String>? = null

        private var uart_baudrate_characteristic: BluetoothGattCharacteristic? = null
        private var uart_name_characteristic: BluetoothGattCharacteristic? = null
        private var uart_hwfc_characteristic: BluetoothGattCharacteristic? = null
        private var uart_tx_characteristic: BluetoothGattCharacteristic? = null
        private var uart_rx_characteristic: BluetoothGattCharacteristic? = null

        val String_Silpion_UART = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
        val ParcelUuid_Silpion_UART = ParcelUuid.fromString(String_Silpion_UART)

        // UART TX Characteristic: Android --> nRF (write, write_wo_resp)
        val String_UART_TX_Char = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"
        val UUID_UART_TX_Char = UUID.fromString(String_UART_TX_Char)

        // UART RX Characteristic: nRF --> Android (notify)
        val String_UART_RX_Char = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"
        val UUID_UART_RX_Char = UUID.fromString(String_UART_RX_Char)

        // UART Baudrate Characteristic: read, write
        val String_UART_Baudrate_Char = "6E400004-B5A3-F393-E0A9-E50E24DCCA9E"
        val UUID_UART_Baudrate_Char = UUID.fromString(String_UART_Baudrate_Char)

        // UART Hardware Flow Control Characteristic: read, write
        val String_UART_HWFC_Char = "6E400005-B5A3-F393-E0A9-E50E24DCCA9E"
        val UUID_UART_HWFC_Char = UUID.fromString(String_UART_HWFC_Char)

        // UART Name Characteristic: read, write
        val String_UART_Name_Char = "6E400006-B5A3-F393-E0A9-E50E24DCCA9E"
        val UUID_UART_Name_Char = UUID.fromString(String_UART_Name_Char)

        // This needs to be based on the generic Attribute UUID, not our Service UUID!
        val String_CCCD = "00002902-0000-1000-8000-00805f9b34fb"
        val UUID_CCCD = UUID.fromString(String_CCCD)
    }
}
