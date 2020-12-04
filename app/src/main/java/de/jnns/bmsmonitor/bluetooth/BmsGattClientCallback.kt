package de.jnns.bmsmonitor.bluetooth

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.util.Log
import de.jnns.bmsmonitor.bms.BmsCellInfoResponse
import de.jnns.bmsmonitor.bms.BmsGeneralInfoResponse
import java.util.*

@ExperimentalUnsignedTypes
class BmsGattClientCallback(
    val onGeneralInfoCallback: (cellInfo: BmsGeneralInfoResponse) -> Unit,
    val onCellInfoCallback: (cellInfo: BmsCellInfoResponse) -> Unit,
    val onConnectionSucceeded: () -> Unit,
    val onConnectionFailed: () -> Unit
) :
    BluetoothGattCallback() {
    lateinit var readCharacteristic: BluetoothGattCharacteristic
    lateinit var writeCharacteristic: BluetoothGattCharacteristic

    var isConnected = false

    private val uartUuid = UUID.fromString("0000ff00-0000-1000-8000-00805f9b34fb")
    private val rxUuid = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb")
    private val txUuid = UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb")

    private val bufferSize: Int = 80
    private var uartBuffer = ByteArray(bufferSize)
    private var uartBufferPos: Int = 0

    private var isInFrame = false

    override fun onConnectionStateChange(
        gatt: BluetoothGatt,
        status: Int,
        newState: Int
    ) {
        super.onConnectionStateChange(gatt, status, newState)

        if (status != BluetoothGatt.GATT_SUCCESS) {
            Log.i("GATT", "Connection failed")
            onConnectionFailed()
            isConnected = false
            return
        }

        if (newState == BluetoothProfile.STATE_CONNECTED) {
            Log.i("GATT", "Connected")
            gatt.discoverServices()
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            Log.i("GATT", "Disconnected")
            isConnected = false
        }
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        super.onServicesDiscovered(gatt, status)
        Log.i("GATT", "onServicesDiscovered status: $status")

        if (status != BluetoothGatt.GATT_SUCCESS) {
            return
        }

        val uartService = gatt.getService(uartUuid)

        readCharacteristic = uartService.getCharacteristic(rxUuid)
        writeCharacteristic = uartService.getCharacteristic(txUuid)

        gatt.setCharacteristicNotification(writeCharacteristic, true)
        gatt.setCharacteristicNotification(readCharacteristic, true)

        onConnectionSucceeded()
        isConnected = true
    }

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ) {
        super.onCharacteristicChanged(gatt, characteristic)

        // Log.i("GATT", "BLE Data (" + characteristic.value.size + "): " + characteristic.value.toHexString())

        for (byte: Byte in characteristic.value) {
            uartBuffer[uartBufferPos] = byte

            if (isInFrame) {
                if (byte.toUByte() == 0x77.toUByte()) {
                    isInFrame = false
                    onFrameComplete(uartBufferPos)
                    uartBufferPos = 0
                } else {
                    uartBufferPos++
                }
            } else if (byte.toUByte() == 0xDD.toUByte()) {
                isInFrame = true
                uartBufferPos++
            }
        }
    }

    private fun onFrameComplete(size: Int) {
        val frameBytes = uartBuffer.slice(IntRange(0, size)).toByteArray()

        // Log.i("GATT", "FrameData (" + frameBytes.size + "): " + frameBytes.toHexString())

        if (frameBytes[1] == 0x3.toByte()) {
            val generalInfo = BmsGeneralInfoResponse(frameBytes)
            onGeneralInfoCallback(generalInfo)
        } else if (frameBytes[1] == 0x4.toByte()) {
            val cellInfo = BmsCellInfoResponse(frameBytes)
            onCellInfoCallback(cellInfo)
        }
    }

    private fun ByteArray.toHexString() = joinToString("") { "%02x".format(it) }
}