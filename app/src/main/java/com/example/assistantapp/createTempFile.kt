package com.example.assistantapp

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * Creates a temporary file for image storage with proper error handling.
 * 
 * @param context The context used to get the cache directory
 * @return A temporary file or null if creation fails
 */
fun createTempFile(context: Any): File {
    try {
        // Use app's cache directory instead of toString() on context
        val tempDir = when (context) {
            is Context -> context.cacheDir
            else -> {
                // Fallback to creating a directory from context's string representation
                // This is not ideal but keeps compatibility with existing code
                File(context.toString()).also { 
                    if (!it.exists()) {
                        it.mkdirs()
                    }
                }
            }
        }
        
        if (!tempDir.exists() && !tempDir.mkdirs()) {
            Log.e("TempFile", "Failed to create directory: ${tempDir.absolutePath}")
        }
        
        return File.createTempFile("reading_", ".jpg", tempDir)
    } catch (e: IOException) {
        Log.e("TempFile", "Failed to create temp file: ${e.message}")
        // Create file in default temp directory as fallback
        return File.createTempFile("reading_", ".jpg")
    } catch (e: Exception) {
        Log.e("TempFile", "Unexpected error creating temp file: ${e.message}")
        // Create file in default temp directory as fallback
        return File.createTempFile("reading_", ".jpg")
    }
} 