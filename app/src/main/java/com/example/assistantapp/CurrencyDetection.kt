package com.example.assistantapp

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.speech.tts.TextToSpeech
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.util.Log
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput

@Composable
fun CurrencyDetectionScreen(navController: NavHostController = rememberNavController()) {
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    var capturedImage by remember { mutableStateOf<Bitmap?>(null) }
    var currencyResult by remember { mutableStateOf("") }
    var isProcessing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    val tts = remember { mutableStateOf<TextToSpeech?>(null) }
    
    // Track detected currency notes
    var detectedNotes by remember { mutableStateOf<List<Int>>(emptyList()) }
    var totalAmount by remember { mutableStateOf(0) }
    var lastDetectionTimestamp = remember { mutableStateOf(0L) }
    
    val currencyDetector = remember { 
        try {
            CurrencyDetector(context)
        } catch (e: Exception) {
            Log.e("CurrencyDetection", "Error creating CurrencyDetector", e)
            errorMessage = "Error initializing currency detection: ${e.message}"
            null
        }
    }
    
    // Create imageCapture reference at this level so it can be accessed by the capture button
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }
    
    // Create a variable to store the callback function for processing captured images
    val processCapturedImage: (Bitmap) -> Unit = remember {
        { bitmap: Bitmap ->
            if (currencyDetector == null) {
                errorMessage = "Currency detection is not initialized properly"
            } else {
                capturedImage = bitmap
                coroutineScope.launch {
                    // Don't clear previous results when doing auto-detection
                    // Only clear error messages
                    errorMessage = ""
                    isProcessing = true
                    
                    // Don't announce processing for automatic detection to avoid too much speech
                    // Only speak when manual detection is triggered via button
                    
                    // Process image with CurrencyDetector
                    currencyDetector.let { detector ->
                        detector.detectCurrency(
                            bitmap,
                            { result ->
                                currencyResult = result
                                isProcessing = false
                                
                                // Extract all detected denominations
                                val notesRegex = "(\\d+)\\s+Rupee".toRegex()
                                val matchResults = notesRegex.findAll(result)
                                
                                // Parse the detected denominations
                                val detectedDenominations = matchResults.mapNotNull { 
                                    it.groupValues[1].toIntOrNull() 
                                }.filter { it > 0 }.toList()
                                
                                // Get total value if mentioned in the result
                                val totalRegex = "Total value: (\\d+) Rupees".toRegex()
                                val totalMatch = totalRegex.find(result)
                                val resultTotal = totalMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                                
                                if (detectedDenominations.isNotEmpty()) {
                                    // Check if these are new notes or just detecting the same notes again
                                    val currentTime = System.currentTimeMillis()
                                    val timeSinceLastDetection = currentTime - lastDetectionTimestamp.value
                                    
                                    // Consider it a new detection if:
                                    // 1. More than 3 seconds have passed since last detection
                                    // 2. OR the detected notes are different from what we already have
                                    val hasNewNotes = detectedDenominations.any { denomination ->
                                        !detectedNotes.contains(denomination)
                                    }
                                    
                                    val isNewDetection = timeSinceLastDetection > 3000 || hasNewNotes
                                    
                                    if (isNewDetection) {
                                        // Track the new notes
                                        val newNotes = mutableListOf<Int>()
                                        
                                        for (denomination in detectedDenominations) {
                                            if (!detectedNotes.contains(denomination) || timeSinceLastDetection > 3000) {
                                                newNotes.add(denomination)
                                                detectedNotes = detectedNotes + denomination
                                            }
                                        }
                                        
                                        // Update total amount
                                        totalAmount = if (resultTotal > 0) {
                                            // Use the total from result if available
                                            resultTotal
                                        } else {
                                            // Otherwise calculate from detected notes
                                            detectedNotes.sum()
                                        }
                                        
                                        // Update the last detection timestamp
                                        lastDetectionTimestamp.value = currentTime
                                        
                                        // Only speak if we found new notes
                                        if (newNotes.isNotEmpty()) {
                                            // Prepare message about the new notes
                                            val newNotesMessage = if (newNotes.size == 1) {
                                                "Detected ${newNotes[0]} Rupee note"
                                            } else {
                                                "Detected notes: ${newNotes.joinToString(", ")} Rupee"
                                            }
                                            
                                            // Speak the current notes and total
                                            val message = "$newNotesMessage. " +
                                                    "You now have ${detectedNotes.size} notes worth a total of $totalAmount Rupees."
                                            
                                            tts.value?.speak(message, TextToSpeech.QUEUE_FLUSH, null, null)
                                        }
                                    }
                                } else if (!result.contains("No currency detected", ignoreCase = true)) {
                                    // Only speak result if it's not "no currency detected"
                                    tts.value?.speak(result, TextToSpeech.QUEUE_FLUSH, null, null)
                                }
                            },
                            { error ->
                                errorMessage = error
                                isProcessing = false
                                // Don't speak errors during continuous detection to avoid too much talking
                            }
                        )
                    }
                }
            }
        }
    }
    
    // Function to capture a photo and analyze it
    val captureAndAnalyze: () -> Unit = {
        // Reset state for a new capture
        currencyResult = ""
        errorMessage = ""
        isProcessing = true
        
        // Trigger image capture for currency detection
        tts.value?.speak(
            "Manually capturing image for currency detection. Please hold the camera steady.",
            TextToSpeech.QUEUE_FLUSH,
            null,
            null
        )
        
        // Take a new picture for analysis
        try {
            val outputOptions = ImageCapture.OutputFileOptions.Builder(createTempFile(context)).build()
            imageCapture.takePicture(
                outputOptions,
                cameraExecutor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        try {
                            val savedUri = outputFileResults.savedUri ?: return
                            val bitmap = BitmapFactory.decodeFile(savedUri.path)
                            if (bitmap != null) {
                                lastDetectionTimestamp.value = 0 // Reset timestamp to force this to be treated as a new detection
                                processCapturedImage(bitmap)
                            } else {
                                errorMessage = "Failed to process image"
                                isProcessing = false
                                tts.value?.speak(
                                    "Failed to process image. Please try again with better lighting.",
                                    TextToSpeech.QUEUE_FLUSH,
                                    null,
                                    null
                                )
                            }
                        } catch (e: Exception) {
                            Log.e("CurrencyDetectionCamera", "Error processing saved image: ${e.message}")
                            errorMessage = "Error processing image: ${e.message}"
                            isProcessing = false
                            tts.value?.speak(
                                "Error processing the image. Please try again with better lighting and ensure the currency note is clearly visible.",
                                TextToSpeech.QUEUE_FLUSH,
                                null,
                                null
                            )
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Log.e("CurrencyDetectionCamera", "Error taking picture: ${exception.message}")
                        errorMessage = "Error capturing image: ${exception.message}"
                        isProcessing = false
                        tts.value?.speak(
                            "Error capturing image. Please make sure the camera is not obstructed and try again.",
                            TextToSpeech.QUEUE_FLUSH,
                            null,
                            null
                        )
                    }
                }
            )
        } catch (e: Exception) {
            Log.e("CurrencyDetectionCamera", "Error taking picture: ${e.message}")
            errorMessage = "Error capturing image: ${e.message}"
            isProcessing = false
            tts.value?.speak(
                "Error capturing image. Please make sure the app has camera permissions and try again.",
                TextToSpeech.QUEUE_FLUSH,
                null,
                null
            )
        }
    }
    
    // Function to report total currency amount
    val reportTotal = {
        if (detectedNotes.isEmpty()) {
            tts.value?.speak(
                "No currency notes have been detected yet.",
                TextToSpeech.QUEUE_FLUSH,
                null,
                null
            )
        } else {
            val notesByDenomination = detectedNotes.groupBy { it }
                .map { "${it.value.size} × ${it.key}" }
                .joinToString(", ")
            
            tts.value?.speak(
                "You have $notesByDenomination. Total amount is $totalAmount Rupees.",
                TextToSpeech.QUEUE_FLUSH,
                null,
                null
            )
        }
    }
    
    // Function to reset tracked currency
    val resetTracking = {
        detectedNotes = emptyList()
        totalAmount = 0
        tts.value?.speak(
            "Currency tracking reset. Ready to detect new notes.",
            TextToSpeech.QUEUE_FLUSH,
            null,
            null
        )
    }
    
    // Check for permissions
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    
    // Initialize TTS
    LaunchedEffect(context) {
        tts.value = TextToSpeech(context) { status ->
            if (status != TextToSpeech.ERROR) {
                tts.value?.language = Locale.US
                tts.value?.setSpeechRate(0.9f)
                tts.value?.setPitch(1.0f)
                
                // No welcome message
            }
        }
    }
    
    // Clean up resources
    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
            tts.value?.stop()
            tts.value?.shutdown()
            currencyDetector?.close()
        }
    }
    
    // Handle back button press
    BackHandler {
        tts.value?.stop()
        navController.popBackStack()
    }
    
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Black
    ) {
        if (hasPermission) {
            Box(
                modifier = Modifier.fillMaxSize()
            ) {
                // Show camera preview
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onDoubleTap = { offset ->
                                    // Report total on double tap
                                    reportTotal()
                                },
                                onLongPress = { offset ->
                                    // Reset on long press
                                    resetTracking()
                                }
                            )
                        }
                ) {
                    CurrencyDetectionCamera(
                        onImageCaptured = processCapturedImage,
                        cameraExecutor = cameraExecutor,
                        imageCapture = imageCapture
                    )
                }
                
                // Currency tracking display
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.MonetizationOn,
                        contentDescription = "Currency Detection Active",
                        modifier = Modifier.size(64.dp),
                        tint = Color(0xFFFF9800) // Orange color
                    )
                    
                    Text(
                        text = if (isProcessing) "Analyzing" else "Ready",
                        color = Color.White,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    
                    // Display count of notes and total amount
                    if (detectedNotes.isNotEmpty()) {
                        // Group notes by denomination for better display
                        val notesByDenomination = detectedNotes.groupBy { it }
                            .map { "${it.value.size} × ${it.key}" }
                            .joinToString(", ")
                            
                        Text(
                            text = notesByDenomination,
                            color = Color.White,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        
                        Text(
                            text = "₹$totalAmount total",
                            color = Color(0xFF4CAF50), // Green color for total amount
                            fontSize = 22.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
                
                // Help text for gestures
                Text(
                    text = "Double tap: Hear total | Long press: Reset",
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 16.dp, end = 16.dp)
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(8.dp)
                )
                
                // Back button
                IconButton(
                    onClick = {
                        tts.value?.stop()
                        navController.popBackStack()
                    },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
                
                // Button to capture a photo and analyze it
                Button(
                    onClick = captureAndAnalyze,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 32.dp)
                        .size(width = 200.dp, height = 60.dp) // Make button larger
                ) {
                    Text("Detect Currency", fontSize = 18.sp)
                }
                
                // Additional button for total reporting
                Button(
                    onClick = {
                        reportTotal()
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(bottom = 32.dp, end = 16.dp)
                ) {
                    Text("Report Total", fontSize = 16.sp)
                }
                
                // Button to reset tracking
                Button(
                    onClick = {
                        resetTracking()
                    },
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(bottom = 32.dp, start = 16.dp)
                ) {
                    Text("Reset", fontSize = 16.sp)
                }
                
                // Result display area
                if (currencyResult.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 80.dp, start = 16.dp, end = 16.dp)
                            .background(Color.Black.copy(alpha = 0.7f))
                            .padding(16.dp)
                    ) {
                        Text(
                            text = currencyResult,
                            color = Color.White,
                            fontSize = 20.sp
                        )
                    }
                }

                // Error message display
                if (errorMessage.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 80.dp, start = 16.dp, end = 16.dp)
                            .background(Color.Red.copy(alpha = 0.7f))
                            .padding(16.dp)
                    ) {
                        Text(
                            text = errorMessage,
                            color = Color.White,
                            fontSize = 16.sp
                        )
                    }
                }
            }
        } else {
            // No camera permission UI
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Camera permission is required for currency detection",
                    color = Color.White,
                    modifier = Modifier.padding(16.dp)
                )
                
                Button(
                    onClick = {
                        // Request permission logic would go here
                    },
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text("Grant Permission")
                }
            }
        }
    }
}

@Composable
fun CurrencyDetectionCamera(
    onImageCaptured: (Bitmap) -> Unit,
    cameraExecutor: ExecutorService,
    imageCapture: ImageCapture
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    var lastAnalysisTimestamp = remember { 0L }
    val analysisCooldown = 100000L // 2 seconds between automatic analyses for faster response

    val preview = Preview.Builder().build()
    val previewView = remember { PreviewView(context) }
    
    // Function to capture a photo automatically for analysis
    fun takeAutoAnalysisPicture() {
        try {
            lastAnalysisTimestamp = System.currentTimeMillis()
            val outputOptions = ImageCapture.OutputFileOptions.Builder(createTempFile(context)).build()
            imageCapture.takePicture(
                outputOptions,
                cameraExecutor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        try {
                            val savedUri = outputFileResults.savedUri ?: return
                            val bitmap = BitmapFactory.decodeFile(savedUri.path)
                            if (bitmap != null) {
                                onImageCaptured(bitmap)
                            }
                        } catch (e: Exception) {
                            Log.e("CurrencyDetectionCamera", "Error processing auto analysis image: ${e.message}")
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Log.e("CurrencyDetectionCamera", "Error taking auto analysis picture: ${exception.message}")
                    }
                }
            )
        } catch (e: Exception) {
            Log.e("CurrencyDetectionCamera", "Error taking auto analysis picture: ${e.message}")
        }
    }

    LaunchedEffect(Unit) {
        try {
            val cameraProvider = ProcessCameraProvider.getInstance(context).get()
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageCapture
            )
            preview.setSurfaceProvider(previewView.surfaceProvider)
            
            // Set up automatic analysis timer
            coroutineScope.launch {
                while (true) {
                    kotlinx.coroutines.delay(analysisCooldown)
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastAnalysisTimestamp >= analysisCooldown) {
                        takeAutoAnalysisPicture()
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e("CurrencyDetectionCamera", "Error setting up camera: ${e.message}")
        }
    }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

private fun createTempFile(context: android.content.Context): File {
    val tempDir = context.cacheDir
    return File.createTempFile("currency_detection_", ".jpg", tempDir).apply {
        deleteOnExit()
    }
} 