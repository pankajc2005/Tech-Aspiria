package com.example.assistantapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LifecycleOwner
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest

// Create a shared flow to control flashlight from outside this component
val manualFlashlightControl = MutableSharedFlow<Boolean>()
var isManualControl = false // Track if flashlight is under manual control

@Composable
fun CameraPreviewWithAnalysis(
    onImageCaptured: (ImageProxy) -> Unit,
    onFlashlightStateChanged: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val preview = Preview.Builder().build()
    val previewView = PreviewView(context)
    
    // States to manage flashlight
    val camera = remember { mutableStateOf<Camera?>(null) }
    val isFlashlightOn = remember { mutableStateOf(false) }
    val lastBrightnessCheckTime = remember { mutableStateOf(0L) }
    val brightnessCheckInterval = 2000L // Check brightness every 2 seconds
    val darkFramesCount = remember { mutableStateOf(0) } // Count consecutive dark frames for more accuracy
    var lastFlashlightOnTime = remember { mutableStateOf(0L) } // Track when flashlight was last turned on
    val minFlashlightOnDuration = 600000L // 10 minutes in milliseconds
    
    // Function to control the flashlight directly
    fun controlFlashlight(turnOn: Boolean) {
        // If we're turning off the flashlight and it's not from manual control,
        // check if the minimum duration has passed
        if (!turnOn && isFlashlightOn.value && !isManualControl) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastFlashlightOnTime.value < minFlashlightOnDuration) {
                // Don't turn off if minimum duration hasn't passed
                Log.d("CameraFlash", "Prevented automatic flashlight turn off - minimum time not reached")
                return
            }
        }
        
        if (turnOn != isFlashlightOn.value) {
            camera.value?.cameraControl?.enableTorch(turnOn)
            isFlashlightOn.value = turnOn
            onFlashlightStateChanged(turnOn)
            Log.d("CameraFlash", if (turnOn) "Flashlight turned ON" else "Flashlight turned OFF")
            
            // If turning on, record the time for minimum duration enforcement
            if (turnOn) {
                lastFlashlightOnTime.value = System.currentTimeMillis()
            }
        }
        
        // Reset manual control flag
        isManualControl = false
    }
    
    // Listen for manual flashlight control requests
    LaunchedEffect(Unit) {
        manualFlashlightControl.collectLatest { turnOn ->
            controlFlashlight(turnOn)
        }
    }
    
    val imageAnalysis = ImageAnalysis.Builder()
        .setTargetResolution(Size(1280, 720))
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .build()
        .also {
            it.setAnalyzer(Executors.newSingleThreadExecutor(), ImageAnalysis.Analyzer { imageProxy ->
                val currentTime = System.currentTimeMillis()
                
                // Check image brightness periodically
                if (currentTime - lastBrightnessCheckTime.value >= brightnessCheckInterval) {
                    lastBrightnessCheckTime.value = currentTime
                    
                    // Analyze image brightness
                    val brightness = calculateBrightness(imageProxy)
                    Log.d("CameraFlash", "Current image brightness: $brightness")
                    
                    // Use consecutive dark frames for more reliable detection
                    if (brightness < 40) {  // Threshold for dark environment
                        darkFramesCount.value++
                        // Turn on flashlight after 2 consecutive dark frames
                        if (darkFramesCount.value >= 2 && !isFlashlightOn.value) {
                            controlFlashlight(true)
                        }
                    } else if (brightness > 60) {  // Threshold to turn off flashlight
                        darkFramesCount.value = 0
                        // IMPORTANT: Remove the automatic flashlight turn-off functionality
                        // We never turn off the flashlight automatically
                        
                        // REMOVED THIS CODE:
                        // if (isFlashlightOn.value) {
                        //     controlFlashlight(false)
                        // }
                    }
                }
                
                // Pass image for further processing
                onImageCaptured(imageProxy)
            })
        }

    LaunchedEffect(cameraProviderFuture) {
        val cameraProvider = cameraProviderFuture.get()
        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        preview.setSurfaceProvider(previewView.surfaceProvider)

        try {
            cameraProvider.unbindAll()
            // Bind camera use cases and store camera reference
            camera.value = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageAnalysis
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = Modifier.fillMaxSize()
    )
}

// Public function to manually control flashlight from outside this file
suspend fun turnFlashlightOn() {
    isManualControl = true
    manualFlashlightControl.emit(true)
}

suspend fun turnFlashlightOff() {
    isManualControl = true
    manualFlashlightControl.emit(false)
}

// Calculate brightness of an image with improved analysis
private fun calculateBrightness(imageProxy: ImageProxy): Float {
    val bitmap = imageProxy.toBitmapForBrightness()
    if (bitmap == null) {
        Log.e("CameraFlash", "Failed to convert image to bitmap for brightness calculation")
        return 100f  // Default to bright if we can't analyze
    }
    
    var r = 0
    var g = 0
    var b = 0
    val height = bitmap.height
    val width = bitmap.width
    var n = 0
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    
    // Analyze central portion of image more heavily (where most important content is)
    val centerRegionStart = width * height / 3
    val centerRegionEnd = 2 * width * height / 3
    
    for (i in 0 until pixels.size step 10) {  // Sample every 10th pixel for efficiency
        val color = pixels[i]
        // Give 2x weight to central pixels
        val weight = if (i > centerRegionStart && i < centerRegionEnd) 2 else 1
        r += Color.red(color) * weight
        g += Color.green(color) * weight
        b += Color.blue(color) * weight
        n += weight
    }
    
    // Calculate brightness using perceived brightness formula (human eye sensitivity)
    val brightness = (0.299f * r/n + 0.587f * g/n + 0.114f * b/n) / 255f * 100
    
    // Clean up the temporary bitmap
    bitmap.recycle()
    
    return brightness
}

// Extension function to convert ImageProxy to Bitmap for brightness calculation
fun ImageProxy.toBitmapForBrightness(): Bitmap? {
    val buffer = this.planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}

fun isInternetAvailable(context: Context): Boolean {
    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val network = connectivityManager.activeNetwork ?: return false
        val activeNetwork = connectivityManager.getNetworkCapabilities(network) ?: return false
        return when {
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> true
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> true
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> true
            else -> false
        }
    } else {
        @Suppress("DEPRECATION")
        val networkInfo = connectivityManager.activeNetworkInfo ?: return false
        return networkInfo.isConnected
    }
}
