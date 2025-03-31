package com.example.assistantapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.ArrayList
import java.util.PriorityQueue
import java.util.Comparator
import android.util.Log
import android.graphics.Matrix

class CurrencyDetector(private val context: Context) {
    private var interpreter: Interpreter? = null
    private var labels: List<String> = emptyList()
    private val modelFile = "currency_model.tflite"
    private val labelsFile = "currency_labels.txt"
    // Default values, will be updated when model is loaded
    private var modelInputWidth = 360
    private var modelInputHeight = 360
    private val numChannels = 3 // RGB
    private val numThreads = 4
    
    init {
        try {
            Log.d("CurrencyDetector", "Initializing currency detector")
            loadLabels()
            Log.d("CurrencyDetector", "Labels loaded successfully: ${labels.joinToString()}")
            loadModel()
            updateModelInputDimensions()
            Log.d("CurrencyDetector", "Model loaded successfully with input dimensions: ${modelInputWidth}x${modelInputHeight}")
        } catch (e: Exception) {
            Log.e("CurrencyDetector", "Error initializing CurrencyDetector", e)
        }
    }
    
    private fun updateModelInputDimensions() {
        interpreter?.let { interp ->
            val inputTensor = interp.getInputTensor(0)
            val shape = inputTensor.shape()
            if (shape.size >= 4) {
                // TFLite models typically have shape [1, height, width, channels]
                modelInputHeight = shape[1]
                modelInputWidth = shape[2]
                Log.d("CurrencyDetector", "Updated model dimensions from tensor: ${modelInputWidth}x${modelInputHeight}")
            } else {
                Log.w("CurrencyDetector", "Unexpected input tensor shape: ${shape.contentToString()}")
            }
        }
    }
    
    fun detectCurrency(
        bitmap: Bitmap,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            if (interpreter == null) {
                Log.d("CurrencyDetector", "Interpreter is null, attempting to reload model")
                loadModel()
                updateModelInputDimensions()
                
                if (interpreter == null) {
                    onError("TensorFlow Lite interpreter is not initialized")
                    return
                }
            }
            
            // Resize bitmap to match model input dimensions
            val resizedBitmap = resizeBitmap(bitmap, modelInputWidth, modelInputHeight)
            Log.d("CurrencyDetector", "Image resized to ${resizedBitmap.width}x${resizedBitmap.height}")
            
            // Convert bitmap to ByteBuffer
            val byteBuffer = convertBitmapToByteBuffer(resizedBitmap)
            
            // Run inference
            val outputBuffer = Array(1) { FloatArray(labels.size) }
            interpreter?.run(byteBuffer, outputBuffer)
            
            // Get result
            val result = getDetectionResult(outputBuffer[0])
            Log.d("CurrencyDetector", "Detection result: $result")
            onSuccess(result)
        } catch (e: Exception) {
            Log.e("CurrencyDetector", "Error detecting currency", e)
            onError("Error detecting currency: ${e.message}")
        }
    }
    
    private fun resizeBitmap(bitmap: Bitmap, width: Int, height: Int): Bitmap {
        Log.d("CurrencyDetector", "Processing bitmap from ${bitmap.width}x${bitmap.height} to ${width}x${height}")
        
        // First, let's apply some basic image enhancement
        val enhancedBitmap = enhanceBitmap(bitmap)
        
        // Then resize the enhanced bitmap
        return Bitmap.createScaledBitmap(enhancedBitmap, width, height, true)
    }
    
    private fun enhanceBitmap(bitmap: Bitmap): Bitmap {
        try {
            // Create a mutable copy of the bitmap for processing
            val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            
            // Simple contrast enhancement
            val pixels = IntArray(mutableBitmap.width * mutableBitmap.height)
            mutableBitmap.getPixels(pixels, 0, mutableBitmap.width, 0, 0, mutableBitmap.width, mutableBitmap.height)
            
            // Increase these values for currency detection which benefits from higher contrast
            val contrast = 1.5f // Higher contrast to make text/patterns more visible
            val brightness = 15f // Slightly more brightness to help with details
            
            for (i in pixels.indices) {
                val r = (pixels[i] shr 16 and 0xFF)
                val g = (pixels[i] shr 8 and 0xFF)
                val b = (pixels[i] and 0xFF)
                
                // Apply contrast and brightness adjustments
                val newR = Math.min(255, Math.max(0, ((r - 128) * contrast + 128 + brightness).toInt()))
                val newG = Math.min(255, Math.max(0, ((g - 128) * contrast + 128 + brightness).toInt()))
                val newB = Math.min(255, Math.max(0, ((b - 128) * contrast + 128 + brightness).toInt()))
                
                pixels[i] = (pixels[i] and 0xFF000000.toInt()) or (newR shl 16) or (newG shl 8) or newB
            }
            
            mutableBitmap.setPixels(pixels, 0, mutableBitmap.width, 0, 0, mutableBitmap.width, mutableBitmap.height)
            Log.d("CurrencyDetector", "Applied enhanced contrast and brightness for currency detection")
            
            return mutableBitmap
        } catch (e: Exception) {
            Log.e("CurrencyDetector", "Error enhancing bitmap", e)
            return bitmap // Return original if enhancement fails
        }
    }
    
    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        // Calculate buffer size based on model input dimensions
        val inputSize = modelInputWidth * modelInputHeight * numChannels * 4 // 4 bytes per float
        Log.d("CurrencyDetector", "Creating buffer of size: $inputSize bytes")
        
        val byteBuffer = ByteBuffer.allocateDirect(inputSize).apply {
            order(ByteOrder.nativeOrder())
        }
        
        if (bitmap.width != modelInputWidth || bitmap.height != modelInputHeight) {
            Log.w("CurrencyDetector", "Bitmap dimensions (${bitmap.width}x${bitmap.height}) don't match model input (${modelInputWidth}x${modelInputHeight})")
        }
        
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        
        // Use MobileNet V2 standard normalization (subtract mean, divide by std)
        // Mean and std values are standard for ImageNet trained models
        val meanRGB = 127.5f
        val stdRGB = 127.5f
        
        var position = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (position < pixels.size) {
                    val pixelValue = pixels[position++]
                    
                    // Extract RGB values (0-255)
                    val r = (pixelValue shr 16 and 0xFF)
                    val g = (pixelValue shr 8 and 0xFF)
                    val b = (pixelValue and 0xFF)
                    
                    // Normalize using standard MobileNet approach: (pixel - mean) / std
                    // This results in a range of [-1, 1]
                    val normalizedR = (r - meanRGB) / stdRGB
                    val normalizedG = (g - meanRGB) / stdRGB
                    val normalizedB = (b - meanRGB) / stdRGB
                    
                    // Add to buffer in RGB order (most models expect this)
                    byteBuffer.putFloat(normalizedR)
                    byteBuffer.putFloat(normalizedG)
                    byteBuffer.putFloat(normalizedB)
                }
            }
        }
        
        Log.d("CurrencyDetector", "ByteBuffer position after filling: ${byteBuffer.position()}")
        byteBuffer.rewind()
        return byteBuffer
    }
    
    private fun getDetectionResult(output: FloatArray): String {
        // Log all confidence scores for debugging
        for (i in output.indices) {
            if (i < labels.size) {
                Log.d("CurrencyDetector", "Confidence for ${labels[i]}: ${output[i]}")
            }
        }
        
        // Create a list of all predictions with their confidences
        val allPredictions = mutableListOf<Pair<Int, Float>>() // index, confidence
        
        for (i in output.indices) {
            if (i < labels.size && !labels[i].equals("none", ignoreCase = true)) {
                if (output[i] > 0.2) { // Consider all predictions with confidence > 20%
                    allPredictions.add(Pair(i, output[i]))
                }
            }
        }
        
        // Sort by confidence (highest first)
        allPredictions.sortByDescending { it.second }
        
        // If there are no valid predictions above threshold
        if (allPredictions.isEmpty()) {
            return "No currency detected. Please aim the camera at currency notes with good lighting."
        }
        
        // If there's only one prediction
        if (allPredictions.size == 1) {
            val index = allPredictions[0].first
            val confidence = allPredictions[0].second
            val confidencePercent = (confidence * 100).toInt()
            
            return if (confidence > 0.4) {
                "Detected ${labels[index]} Rupee note with ${confidencePercent}% confidence"
            } else {
                "Possibly ${labels[index]} Rupee note (${confidencePercent}% confidence). Try again with better lighting."
            }
        }
        
        // If there are multiple predictions (possible multiple notes)
        val detectedNotes = allPredictions.filter { it.second > 0.4 }
            .map { labels[it.first] }
        
        val possibleNotes = allPredictions.filter { it.second <= 0.4 && it.second > 0.2 }
            .map { labels[it.first] }
        
        val result = StringBuilder()
        
        if (detectedNotes.isNotEmpty()) {
            result.append("Detected notes: ${detectedNotes.joinToString(", ")} Rupee")
            
            // Calculate total value
            val totalValue = detectedNotes.sumOf { it.toIntOrNull() ?: 0 }
            if (totalValue > 0) {
                result.append(". Total value: $totalValue Rupees")
            }
        }
        
        if (possibleNotes.isNotEmpty()) {
            if (result.isNotEmpty()) result.append(". ")
            result.append("Possibly also: ${possibleNotes.joinToString(", ")} Rupee")
        }
        
        return result.toString()
    }
    
    @Throws(IOException::class)
    private fun loadModelFile(): MappedByteBuffer {
        try {
            context.assets.openFd(modelFile).use { fileDescriptor ->
                FileInputStream(fileDescriptor.fileDescriptor).use { inputStream ->
                    val fileChannel = inputStream.channel
                    val startOffset = fileDescriptor.startOffset
                    val declaredLength = fileDescriptor.declaredLength
                    return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
                }
            }
        } catch (e: IOException) {
            Log.e("CurrencyDetector", "Error loading model file: $modelFile", e)
            throw e
        }
    }
    
    private fun loadModel() {
        try {
            val options = Interpreter.Options().apply {
                setNumThreads(numThreads)
            }
            
            val modelBuffer = loadModelFile()
            interpreter = Interpreter(modelBuffer, options)
            Log.d("CurrencyDetector", "Model loaded successfully")
        } catch (e: Exception) {
            Log.e("CurrencyDetector", "Error loading TensorFlow Lite model", e)
            interpreter = null
        }
    }
    
    private fun loadLabels() {
        try {
            context.assets.open(labelsFile).bufferedReader().use { reader ->
                labels = reader.readLines()
            }
            Log.d("CurrencyDetector", "Labels loaded: ${labels.size}")
        } catch (e: IOException) {
            Log.e("CurrencyDetector", "Error loading label file: $labelsFile", e)
            labels = emptyList()
        }
    }
    
    fun close() {
        interpreter?.close()
        interpreter = null
    }

    data class Recognition(
        val id: String,
        val title: String,
        val confidence: Float,
        val location: RectF?
    )
} 