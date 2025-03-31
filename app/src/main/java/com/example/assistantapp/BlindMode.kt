package com.example.assistantapp

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController

// Import the flashlight control functions
import com.example.assistantapp.turnFlashlightOn
import com.example.assistantapp.turnFlashlightOff

@Composable
fun BlindModeScreen(navController: NavHostController = rememberNavController()) {
    var capturedImage by remember { mutableStateOf<Bitmap?>(null) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val context = LocalContext.current
    LocalLifecycleOwner.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var currentMode by remember { mutableStateOf("navigation") }
    var overlayText by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()
    var isAssistantMode by remember { mutableStateOf(false) }
    var sessionStarted by remember { mutableStateOf(true) } // Start session immediately
    var analysisResult by remember { mutableStateOf("") }
    val tts = remember { mutableStateOf<TextToSpeech?>(null) }
    var lastSpokenIndex by remember { mutableStateOf(0) }
    var lastProcessedTimestamp by remember { mutableStateOf(0L) }
    val frameInterval = 5000 // Process a frame every 5 seconds instead of 12
    var navigationPaused by remember { mutableStateOf(false) }
    var isMicActive by remember { mutableStateOf(false) }
    var chatResponse by remember { mutableStateOf("") }
    var isReadingMode by remember { mutableStateOf(false) }
    var readingModeResult by remember { mutableStateOf("") }
    var isFlashlightOn by remember { mutableStateOf(false) } // Track flashlight state
    // Add state to track consecutive dark frames
    var darkFrameCount by remember { mutableStateOf(0) }
    var brightFrameCount by remember { mutableStateOf(0) }
    // Threshold for darkness detection with hysteresis
    val darknessBrightnessThreshold = 60 // Increased threshold to detect darkness more readily (higher value = more sensitive)
    val lightnessBrightnessThreshold = 80 // Higher threshold for brightness (to avoid flickering)
    val consecutiveDarkFramesThreshold = 2 // Require fewer dark frames for faster activation
    // Add timestamp to prevent rapid toggling
    var lastFlashlightToggleTime by remember { mutableStateOf(0L) }
    val minToggleIntervalMs = 5000 // Reduced to 5 seconds for faster response
    // Increased flashlight on duration to 10 minutes
    val minFlashlightOnDurationMs = 600000 // 10 minutes in milliseconds

    // Use mutableStateOf for speechRecognizer so it can be updated
    var speechRecognizer by remember { mutableStateOf(SpeechRecognizer.createSpeechRecognizer(context)) }
    val speechIntent = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500)
        }
    }

    // New function to process voice commands in a uniform way (moved up before first use)
    fun processVoiceCommand(spokenText: String) {
        // Log the recognized text (can be removed in production)
        println("Voice recognized: $spokenText")
        
        // Check for "Hey Siri" wake word - broader variants for better recognition
        if (spokenText.contains("hey siri") || 
            spokenText.contains("hey series") ||
            spokenText.contains("hey ciri") || 
            spokenText.contains("hi siri") ||
            spokenText.contains("a siri") ||
            spokenText.contains("hey serie") ||
            spokenText.contains("hey zero") ||
            spokenText.contains("hey silly")) {
            
            // Activate voice assistant mode
            if (!isAssistantMode) {
                isAssistantMode = true
                navigationPaused = true
                tts.value?.speak("I'm listening. How can I help you?", 
                    TextToSpeech.QUEUE_FLUSH, null, null)
            }
            return
        }
        
        // Check for reading mode with more variants and simpler phrases
        if (spokenText.contains("read") || 
            spokenText.contains("reading") || 
            spokenText.contains("read mode") || 
            spokenText.contains("reader") ||
            spokenText.contains("text mode")) {
            
            // Only switch to reading mode if not already in it
            if (!isReadingMode) {
                isReadingMode = true
                currentMode = "reading"
                navigationPaused = true
                tts.value?.speak("Reading mode activated. Point camera at text.", 
                    TextToSpeech.QUEUE_FLUSH, null, null)
            }
            return
        }
        
        // Check for navigation commands
        if (spokenText.contains("navigate") || 
            spokenText.contains("navigation") || 
            spokenText.contains("guide") ||
            spokenText.contains("walking mode")) {
            
            isReadingMode = false
            isAssistantMode = false
            navigationPaused = false
            currentMode = "navigation"
            tts.value?.speak("Navigation mode activated. I'll guide you.", 
                TextToSpeech.QUEUE_FLUSH, null, null)
            return
        }
        
        // Handle flashlight commands
        if (spokenText.contains("light on") || 
            spokenText.contains("flashlight on") || 
            spokenText.contains("torch on") ||
            spokenText.contains("turn on light")) {
            
            coroutineScope.launch {
                turnFlashlightOn()
                isFlashlightOn = true
                lastFlashlightToggleTime = System.currentTimeMillis() // Update toggle time for manual activation
                tts.value?.speak("Flashlight on for 10 minutes", 
                    TextToSpeech.QUEUE_FLUSH, null, null)
            }
            return
        }
        
        if (spokenText.contains("light off") || 
            spokenText.contains("flashlight off") || 
            spokenText.contains("torch off") ||
            spokenText.contains("turn off light")) {
            
            // Only allow turning off if 10 minutes have passed
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastFlashlightToggleTime >= minFlashlightOnDurationMs) {
                coroutineScope.launch {
                    turnFlashlightOff()
                    isFlashlightOn = false
                    lastFlashlightToggleTime = currentTime // Update toggle time for manual deactivation
                    tts.value?.speak("Flashlight off", 
                        TextToSpeech.QUEUE_FLUSH, null, null)
                }
            } else {
                // Inform the user that the flashlight needs to stay on for the full duration
                val remainingMinutes = ((minFlashlightOnDurationMs - (currentTime - lastFlashlightToggleTime)) / 60000).toInt()
                tts.value?.speak("Flashlight must stay on for another $remainingMinutes minutes for safety", 
                    TextToSpeech.QUEUE_FLUSH, null, null)
            }
            return
        }
        
        // If in assistant mode and not a special command, treat as a query
        if (isAssistantMode && spokenText.length > 3) {
            coroutineScope.launch {
                chatResponse = sendMessageToGeminiAI(spokenText, analysisResult)
                tts.value?.speak(chatResponse, TextToSpeech.QUEUE_FLUSH, null, null)
            }
        }
    }

    LaunchedEffect(context) {
        tts.value = TextToSpeech(context) { status ->
            if (status != TextToSpeech.ERROR) {
                tts.value?.language = Locale.US
                tts.value?.setSpeechRate(1.1f) // Slightly faster but still natural
                tts.value?.setPitch(1.0f) // More natural pitch
                
                // Try to set a more natural-sounding voice
                val voices = tts.value?.voices
                voices?.find { it.name.contains("female", ignoreCase = true) && 
                               it.name.contains("en-us", ignoreCase = true) }?.let {
                    tts.value?.setVoice(it)
                }
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            tts.value?.stop()
            tts.value?.shutdown()
            speechRecognizer.destroy()
        }
    }

    LaunchedEffect(Unit) {
        // Use direct suspension in LaunchedEffect which is a coroutine scope
        kotlinx.coroutines.delay(2000) // Short delay to ensure TTS initialization
        isMicActive = true
        speechRecognizer.startListening(speechIntent)
        
        // Welcome message for blind users when app starts
        tts.value?.speak(
            "Welcome to Blind Navigator. I'm ready to help you. Voice commands are now active.", 
            TextToSpeech.QUEUE_FLUSH, null, null
        )
    }

    // Handle voice commands continuously when microphone is active
    LaunchedEffect(isMicActive) {
        if (isMicActive) {
            while (true) {
                // Use suspension function directly in LaunchedEffect coroutine
                kotlinx.coroutines.delay(500) // Shorter delay between checks
                
                // Make sure speech recognition is running
                try {
                    speechRecognizer.startListening(speechIntent)
                } catch (e: Exception) {
                    // If there's an error, recreate the speech recognizer
                    speechRecognizer.destroy()
                    val newRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
                    newRecognizer.setRecognitionListener(object : RecognitionListener {
                        override fun onResults(results: Bundle?) {
                            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            if (!matches.isNullOrEmpty()) {
                                processVoiceCommand(matches[0].lowercase())
                            }
                        }
                        
                        override fun onPartialResults(partialResults: Bundle?) {
                            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            if (!matches.isNullOrEmpty()) {
                                processVoiceCommand(matches[0].lowercase())
                            }
                        }
                        
                        // Other required methods...
                        override fun onReadyForSpeech(params: Bundle?) {}
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {
                            // We need to use Handler for threading context issues
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                try {
                                    speechRecognizer.startListening(speechIntent)
                                } catch (e: Exception) {
                                    // Handle exception
                                }
                            }, 100)
                        }
                        override fun onError(error: Int) {
                            // We need to use Handler for threading context issues
                            val delayMs = when (error) {
                                SpeechRecognizer.ERROR_NO_MATCH -> 100L
                                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 200L
                                else -> 500L
                            }
                            
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                try {
                                    speechRecognizer.startListening(speechIntent)
                                } catch (e: Exception) {
                                    // Handle exception
                                }
                            }, delayMs)
                        }
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                    // Now we can safely reassign
                    speechRecognizer = newRecognizer
                    speechRecognizer.startListening(speechIntent)
                }
            }
        }
    }

    if (hasPermission) {
        if (sessionStarted) {
            if (isReadingMode) {
                // Improve reading mode with clearer instructions
                tts.value?.speak("Point your camera at the text you want to read.", TextToSpeech.QUEUE_ADD, null, null)
                
                InlineReadingCamera(
                    onImageCaptured = { bitmap: Bitmap ->
                        capturedImage = bitmap
                        coroutineScope.launch {
                            readingModeResult = ""
                            sendFrameToGemini2AI(bitmap, { partialResult ->
                                readingModeResult += partialResult
                                tts.value?.speak(partialResult, TextToSpeech.QUEUE_ADD, null, null)
                            }, { error ->
                                // Handle error
                            })
                        }
                    },

                    cameraExecutor = cameraExecutor
                )
            } else if (!navigationPaused) {
                CameraPreviewWithAnalysis(
                    onImageCaptured = { imageProxy ->
                        val currentTimestamp = System.currentTimeMillis()
                        if (currentTimestamp - lastProcessedTimestamp >= frameInterval) {
                            coroutineScope.launch {
                                val bitmap = imageProxy.toBitmap()
                                if (bitmap != null) {
                                    // Check image brightness
                                    val brightness = calculateAverageBrightness(bitmap)
                                    val currentTime = System.currentTimeMillis()
                                    
                                    // Log brightness for debugging
                                    println("Current brightness: $brightness, dark threshold: $darknessBrightnessThreshold")
                                    
                                    // Handle darkness detection with improved logic
                                    if (brightness < darknessBrightnessThreshold) {
                                        // Increment dark frame counter, reset bright counter
                                        darkFrameCount++
                                        println("Dark frame detected: $darkFrameCount/$consecutiveDarkFramesThreshold")
                                        
                                        // Only turn on flashlight if:
                                        // 1. It's not already on
                                        // 2. We've seen enough consecutive dark frames
                                        // 3. Enough time has passed since last toggle
                                        if (!isFlashlightOn && 
                                            darkFrameCount >= consecutiveDarkFramesThreshold && 
                                            currentTime - lastFlashlightToggleTime > minToggleIntervalMs) {
                                            
                                            // Environment is consistently dark, turn on flashlight
                                            println("Activating flashlight due to darkness")
                                            turnFlashlightOn()
                                            isFlashlightOn = true
                                            lastFlashlightToggleTime = currentTime
                                            tts.value?.speak("It's dark, turning on flashlight for 10 minutes", 
                                                TextToSpeech.QUEUE_FLUSH, null, null)
                                        }
                                    } else {
                                        // Not dark enough, reset dark counter
                                        if (darkFrameCount > 0) {
                                            println("Resetting dark frame count from $darkFrameCount to 0")
                                            darkFrameCount = 0
                                        }
                                        
                                        // IMPORTANT: Never turn off the flashlight automatically
                                        // We leave it to the user to do so after the 10-minute period
                                    }
                                    
                                    // Clear previous analysis result after 30 seconds to avoid accumulating too much text
                                    if (currentTimestamp - lastProcessedTimestamp > 30000) {
                                        analysisResult = ""
                                        lastSpokenIndex = 0
                                    }
                                    
                                    sendFrameToGeminiAI(bitmap, { partialResult ->
                                        // Only add non-empty results
                                        if (partialResult.trim().isNotEmpty()) {
                                            // For instructions, speak immediately and prioritize
                                            if (partialResult.contains("turn") || 
                                                partialResult.contains("stop") || 
                                                partialResult.contains("proceed") || 
                                                partialResult.contains("careful") ||
                                                partialResult.contains("watch out") ||
                                                partialResult.contains("caution")) {
                                                
                                                tts.value?.speak(partialResult, TextToSpeech.QUEUE_FLUSH, null, null)
                                            }
                                            
                                            // Turn on flashlight if AI mentions dark conditions or poor visibility
                                            val lowerCaseText = partialResult.lowercase()
                                            if (!isFlashlightOn && (
                                                lowerCaseText.contains("dark") || 
                                                lowerCaseText.contains("dim") || 
                                                lowerCaseText.contains("poor visibility") || 
                                                lowerCaseText.contains("poor light") ||
                                                lowerCaseText.contains("difficult to see") ||
                                                lowerCaseText.contains("low light"))) {
                                                
                                                // Use the manual torch control via the suspending function
                                                coroutineScope.launch {
                                                    turnFlashlightOn()
                                                    isFlashlightOn = true
                                                    lastFlashlightToggleTime = System.currentTimeMillis()
                                                    tts.value?.speak("Turning on flashlight for 10 minutes", 
                                                        TextToSpeech.QUEUE_ADD, null, null)
                                                }
                                            }
                                            
                                            analysisResult += " $partialResult"
                                            val newText = analysisResult.substring(lastSpokenIndex)
                                            // Only speak if there's new content
                                            if (newText.trim().isNotEmpty()) {
                                                tts.value?.speak(newText, TextToSpeech.QUEUE_FLUSH, null, null)
                                                lastSpokenIndex = analysisResult.length
                                            }
                                        }
                                    }, { error ->
                                        // Handle error here
                                    })
                                    lastProcessedTimestamp = currentTimestamp
                                }
                                imageProxy.close()
                            }
                        } else {
                            imageProxy.close()
                        }
                    },
                    onFlashlightStateChanged = { isOn ->
                        if (isOn != isFlashlightOn) {
                            isFlashlightOn = isOn
                            if (isOn) {
                                // More descriptive feedback for blind users
                                lastFlashlightToggleTime = System.currentTimeMillis()
                                tts.value?.speak("Low light detected. Flashlight activated for 10 minutes.", 
                                    TextToSpeech.QUEUE_ADD, null, null)
                            }
                            // Remove the automatic flashlight turn-off notification
                        }
                    }
                )
            }
        }
    } else {
        ActivityCompat.requestPermissions(
            (context as Activity),
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
            1
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        if (!isReadingMode) {
                            navigationPaused = !navigationPaused
                            isAssistantMode = navigationPaused
                            if (navigationPaused) {
                                tts.value?.stop()
                                currentMode = "assistant"
                                overlayText = ""
                                tts.value?.speak("Assistant mode activated.", TextToSpeech.QUEUE_FLUSH, null, null)
                            } else {
                                tts.value?.stop()
                                currentMode = "navigation"
                                overlayText = ""
                                chatResponse = ""
                                tts.value?.speak("Assistant mode deactivated.", TextToSpeech.QUEUE_FLUSH, null, null)
                            }
                        }
                    },
                    onLongPress = {
                        if (!isAssistantMode) {
                            isReadingMode = !isReadingMode
                            if (isReadingMode) {
                                tts.value?.stop()
                                currentMode = "reading"
                                overlayText = ""
                                navigationPaused = true
                                tts.value?.speak("Entering reading mode", TextToSpeech.QUEUE_FLUSH, null, null)
                            } else {
                                tts.value?.stop()
                                currentMode = "navigation"
                                overlayText = ""
                                readingModeResult = ""
                                navigationPaused = false
                                tts.value?.speak("Exiting reading mode", TextToSpeech.QUEUE_FLUSH, null, null)
                            }
                        } else {
                            // Exit assistant mode and enter navigation mode
                            tts.value?.stop()
                            isAssistantMode = false
                            navigationPaused = false
                            isReadingMode = false
                            currentMode = "navigation"
                            overlayText = ""
                            chatResponse = ""
                            tts.value?.speak("Exiting assistant mode, entering navigation mode", TextToSpeech.QUEUE_FLUSH, null, null)
                        }
                    }
                )
            }
    ) {
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            if (sessionStarted) {
                AIResponseOverlay(
                    currentMode = currentMode,
                    navigationResponse = analysisResult,
                    response = analysisResult,
                    chatResponse = chatResponse,
                    readingModeResult = readingModeResult,
                    tts = tts.value,
                    lastSpokenIndex = lastSpokenIndex
                )
            }
            Icon(
                imageVector = Icons.Filled.Book,
                contentDescription = "Book Icon",
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .size(64.dp),
                tint = if (isReadingMode) Color.Green else Color(0xFFB0B1B1)
            )
            Icon(
                imageVector = Icons.Filled.Mic,
                contentDescription = "Mic Icon",
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(64.dp),
                tint = if (isMicActive) Color.Green else Color(0xFFB0B1B1)
            )
        }
    }

    // Add a dedicated function to ensure the flashlight stays on
    DisposableEffect(Unit) {
        // Check every 10 seconds if the flashlight should be on
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                if (isFlashlightOn) {
                    // Make sure the flashlight stays on
                    coroutineScope.launch {
                        turnFlashlightOn()
                    }
                }
                handler.postDelayed(this, 10000) // Check every 10 seconds
            }
        }
        handler.postDelayed(runnable, 10000)
        
        onDispose {
            handler.removeCallbacks(runnable)
        }
    }
}

@Composable
fun InlineReadingCamera(
    onImageCaptured: (Bitmap) -> Unit,
    cameraExecutor: ExecutorService
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val preview = Preview.Builder().build()
    val previewView = remember { PreviewView(context) }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }

    LaunchedEffect(Unit) {
        val cameraProvider = ProcessCameraProvider.getInstance(context).get()
        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            imageCapture
        )
        preview.setSurfaceProvider(previewView.surfaceProvider)

        // Capture image once when reading mode is activated
        val outputOptions = ImageCapture.OutputFileOptions.Builder(createTempFile(context.toString())).build()
        imageCapture.takePicture(
            outputOptions,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val savedUri = outputFileResults.savedUri ?: return
                    val bitmap = BitmapFactory.decodeFile(savedUri.path)
                    onImageCaptured(bitmap)
                }

                override fun onError(exception: ImageCaptureException) {
                    // Handle error
                }
            }
        )
    }

    AndroidView({ previewView }, modifier = Modifier.fillMaxSize())
}

// Modify the brightness calculation function to be more robust
private fun calculateAverageBrightness(bitmap: Bitmap): Int {
    val width = bitmap.width
    val height = bitmap.height
    
    // Focus even more on center area which is most important for navigation
    val centerStartX = width / 3
    val centerEndX = width * 2 / 3
    val centerStartY = height / 3
    val centerEndY = height * 2 / 3
    
    var totalPixels = 0
    var totalBrightness = 0L
    
    // Sample pixels instead of checking every pixel for performance
    val sampleStep = 8 // Increased sampling density for better accuracy
    
    try {
        for (x in centerStartX until centerEndX step sampleStep) {
            for (y in centerStartY until centerEndY step sampleStep) {
                val pixel = bitmap.getPixel(x, y)
                
                // Calculate brightness using standard luminance formula
                val r = (pixel shr 16) and 0xff
                val g = (pixel shr 8) and 0xff
                val b = pixel and 0xff
                
                // Calculate perceived brightness using standard coefficients
                val brightness = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
                
                totalBrightness += brightness
                totalPixels++
            }
        }
    } catch (e: Exception) {
        // Handle any potential exceptions (like array index issues)
        return 50 // Return a middle brightness value if calculation fails
    }
    
    // Apply a small amount of smoothing by capping extreme values
    val avgBrightness = if (totalPixels > 0) (totalBrightness / totalPixels).toInt() else 50
    return avgBrightness.coerceIn(10, 245) // Prevent extreme outliers
}

