package com.samsung.navicam

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

// A Lambda Type Declaration
// Input parameter = Double luma
// Return Type = void
typealias LumaListener = (luma: Double) -> Unit

// Passing A Lambda function instead of variable in parameter of class
class LuminosityAnalyzer(private val listener: LumaListener) : ImageAnalysis.Analyzer{
    private fun ByteBuffer.toByteArray(): ByteArray {
        rewind()    // Rewind the buffer to zero
        val data = ByteArray(remaining())
        get(data)   // Copy the buffer into a byte array
        return data // Return the byte array
    }

    override fun analyze(image: ImageProxy) {
        val buffer = image.planes[0].buffer
        val data = buffer.toByteArray()
        val pixels = data.map { it.toInt() and 0xFF }
        // Calculate Luma
        val luma = pixels.average()
        // Calling my Lambda function to set Luma (Initialize Luma)
        listener(luma)
        image.close()
    }
}