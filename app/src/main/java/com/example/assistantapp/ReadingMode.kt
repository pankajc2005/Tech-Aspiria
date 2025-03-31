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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.filled.Book
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
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

@Composable
fun ReadingModeScreen(navController: NavHostController = rememberNavController()) {
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    var capturedImage by remember { mutableStateOf<Bitmap?>(null) }
    var readingModeResult by remember { mutableStateOf("") }
    var isReading by remember { mutableStateOf(false) }
    var isPaused by remember { mutableStateOf(false) }
    val tts = remember { mutableStateOf<TextToSpeech?>(null) }
    
    // Check for permissions
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    
    // Initialize TTS with better parameters for reading
    LaunchedEffect(context) {
        tts.value = TextToSpeech(context) { status ->
            if (status != TextToSpeech.ERROR) {
                tts.value?.language = Locale.US
                // Slightly slower speech rate for better comprehension
                tts.value?.setSpeechRate(0.9f)
                tts.value?.setPitch(1.0f)
                
                // More informative welcome message
                tts.value?.speak(
                    "Reading mode activated. Point your camera at any text, book, newspaper or document. " +
                    "Tap once to pause reading, double tap to resume. Swipe left to restart.",
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    null
                )
            }
        }
    }
    
    // Clean up resources
    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
            tts.value?.stop()
            tts.value?.shutdown()
        }
    }
    
    // Handle back button press
    BackHandler {
        tts.value?.stop()
        navController.popBackStack()
    }
    
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        // Pause/resume reading on tap
                        if (isReading && !isPaused) {
                            isPaused = true
                            tts.value?.stop()
                            tts.value?.speak(
                                "Reading paused. Tap again to resume.",
                                TextToSpeech.QUEUE_FLUSH,
                                null, 
                                null
                            )
                        } else if (isReading && isPaused) {
                            isPaused = false
                            tts.value?.speak(
                                "Resuming reading. $readingModeResult",
                                TextToSpeech.QUEUE_FLUSH,
                                null,
                                null
                            )
                        }
                    },
                    onDoubleTap = {
                        // Re-read the entire text
                        if (readingModeResult.isNotEmpty()) {
                            tts.value?.speak(
                                "Reading from the beginning: $readingModeResult",
                                TextToSpeech.QUEUE_FLUSH,
                                null,
                                null
                            )
                        }
                    }
                )
            },
        color = Color.Black
    ) {
        if (hasPermission) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Show camera preview
                Column(modifier = Modifier.fillMaxSize()) {
                    TextReadingCamera(
                        onImageCaptured = { bitmap: Bitmap ->
                            capturedImage = bitmap
                            coroutineScope.launch {
                                readingModeResult = ""
                                isReading = true
                                isPaused = false
                                
                                // More detailed processing message
                                tts.value?.speak(
                                    "Processing text. This may take a moment for longer documents.",
                                    TextToSpeech.QUEUE_FLUSH,
                                    null,
                                    null
                                )
                                
                                // Process image with Gemini AI
                                sendFrameToGemini2AI(bitmap, { partialResult ->
                                    readingModeResult += partialResult
                                    if (!isPaused) {
                                        tts.value?.speak(partialResult, TextToSpeech.QUEUE_ADD, null, null)
                                    }
                                }, { error ->
                                    // More helpful error message
                                    isReading = false
                                    tts.value?.speak(
                                        "I couldn't read the text properly. Please try again with better lighting or hold the camera closer to the document.",
                                        TextToSpeech.QUEUE_FLUSH,
                                        null,
                                        null
                                    )
                                })
                            }
                        },
                        cameraExecutor = cameraExecutor
                    )
                }
                
                // Reading indicator icon with additional label
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Book,
                        contentDescription = "Reading Mode Active",
                        modifier = Modifier.size(64.dp),
                        tint = Color.Green
                    )
                    
                    Text(
                        text = if (isPaused) "Paused" else if (isReading) "Reading" else "Ready",
                        color = Color.White,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                
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
                
                // Show the AI response overlay
                AIResponseOverlay(
                    currentMode = "reading",
                    navigationResponse = "",
                    response = "",
                    chatResponse = "",
                    readingModeResult = readingModeResult,
                    tts = tts.value,
                    lastSpokenIndex = 0
                )
            }
        } else {
            // No camera permission UI
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Camera permission is required for reading mode",
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
fun TextReadingCamera(
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

            // Capture image once when reading mode is activated
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
                                    onImageCaptured(bitmap)
                                }
                            } catch (e: Exception) {
                                Log.e("TextReadingCamera", "Error processing saved image: ${e.message}")
                            }
                        }

                        override fun onError(exception: ImageCaptureException) {
                            Log.e("TextReadingCamera", "Error taking picture: ${exception.message}")
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e("TextReadingCamera", "Error setting up image capture: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e("TextReadingCamera", "Error setting up camera: ${e.message}")
        }
    }

    AndroidView({ previewView }, modifier = Modifier.fillMaxSize())
} 