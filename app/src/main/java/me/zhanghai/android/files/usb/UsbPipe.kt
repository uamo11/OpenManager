/*
 * Copyright (c) 2026 OpenManager
 * All Rights Reserved.
 */

package me.zhanghai.android.files.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import java.io.Closeable
import java.io.IOException

/** A bulk in/out pair: what ADB and fastboot both run on. */
interface UsbPipe : Closeable {
    @Throws(IOException::class)
    fun write(bytes: ByteArray)

    /** Reads into [buffer] (one USB transfer); returns how many bytes came. */
    @Throws(IOException::class)
    fun read(buffer: ByteArray, timeoutMs: Int = 10_000): Int
}

/** What the attached phone is talking: ADB (system running) or fastboot (bootloader). */
enum class UsbToolMode { ADB, FASTBOOT }

class UsbBulkPipe(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val input: UsbEndpoint,
    private val output: UsbEndpoint
) : UsbPipe {

    override fun write(bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val n = connection.bulkTransfer(output, bytes, offset, minOf(16 * 1024, bytes.size - offset), 10_000)
            if (n < 0) throw IOException("USB write failed")
            offset += n
        }
        // A transfer that is a whole number of packets needs an empty one to end it.
        if (bytes.isNotEmpty() && bytes.size % output.maxPacketSize == 0) {
            connection.bulkTransfer(output, ByteArray(0), 0, 1_000)
        }
    }

    override fun read(buffer: ByteArray, timeoutMs: Int): Int {
        val n = connection.bulkTransfer(input, buffer, buffer.size, timeoutMs)
        if (n < 0) throw IOException("USB read failed or timed out")
        return n
    }

    override fun close() {
        connection.releaseInterface(usbInterface)
        connection.close()
    }

    companion object {
        /** The ADB or fastboot interface of a device: vendor class 0xFF, subclass 0x42. */
        fun findInterface(device: UsbDevice): Pair<UsbInterface, UsbToolMode>? {
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                if (intf.interfaceClass != 0xFF || intf.interfaceSubclass != 0x42) continue
                when (intf.interfaceProtocol) {
                    0x01 -> return intf to UsbToolMode.ADB
                    0x03 -> return intf to UsbToolMode.FASTBOOT
                }
            }
            return null
        }

        fun open(connection: UsbDeviceConnection, intf: UsbInterface): UsbBulkPipe {
            var input: UsbEndpoint? = null
            var output: UsbEndpoint? = null
            for (e in 0 until intf.endpointCount) {
                val endpoint = intf.getEndpoint(e)
                if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (endpoint.direction == UsbConstants.USB_DIR_IN) input = endpoint else output = endpoint
            }
            if (input == null || output == null || !connection.claimInterface(intf, true)) {
                connection.close()
                throw IOException("No bulk endpoints on the USB interface")
            }
            return UsbBulkPipe(connection, intf, input, output)
        }
    }
}
