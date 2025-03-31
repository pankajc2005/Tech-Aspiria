package com.example.assistantapp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.BlockThreshold
import com.google.ai.client.generativeai.type.HarmCategory
import com.google.ai.client.generativeai.type.SafetySetting
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

val ReadModel = GenerativeModel(
    modelName = "gemini-2.0-flash",
    apiKey = "AIzaSyA4oC2febrTDhg2Ii0tJjBEg3NWKs1-YPM",
    generationConfig = generationConfig {
        temperature = 0.1f
        topK = 64
        topP = 0.95f
        maxOutputTokens = 4096
        responseMimeType = "text/plain"
    },
    safetySettings = listOf(
        SafetySetting(HarmCategory.HARASSMENT, BlockThreshold.NONE),
        SafetySetting(HarmCategory.HATE_SPEECH, BlockThreshold.NONE),
        SafetySetting(HarmCategory.SEXUALLY_EXPLICIT, BlockThreshold.NONE),
        SafetySetting(HarmCategory.DANGEROUS_CONTENT, BlockThreshold.NONE),
    ),
    systemInstruction = content { text("""
        Your user is a blind person who needs text read precisely. Extract all text from the image with perfect accuracy.
        
        For books and news:
        1. Start with identifying the publication type (e.g., "Book: [Title]", "Newspaper: [Name]", "Magazine article from [publication]")  
        2. Include chapter titles, headings, and section names to help orientation
        3. Preserve paragraph structure with clear breaks
        4. Describe captions for images if present but be brief
        5. For multiple columns, read from left to right, clearly separating each column
        
        For formatting:
        - For tables, describe the table structure briefly then read row by row
        - For lists, identify if it's a numbered or bulleted list and maintain the numbering/bullets
        - For forms, identify form fields and their labels
        
        Use natural, conversational reading style with proper pacing indicated by punctuation.
        Indicate when text appears to continue beyond what's visible in the image.
        If text quality is poor, mention this once at the beginning and do your best to interpret it.
    """) },
)

// New currency detection model with specialized instructions for identifying money
val CurrencyModel = GenerativeModel(
    modelName = "gemini-2.0-flash",
    apiKey = "AIzaSyA4oC2febrTDhg2Ii0tJjBEg3NWKs1-YPM",
    generationConfig = generationConfig {
        temperature = 0.1f
        topK = 64
        topP = 0.95f
        maxOutputTokens = 2048
        responseMimeType = "text/plain"
    },
    safetySettings = listOf(
        SafetySetting(HarmCategory.HARASSMENT, BlockThreshold.NONE),
        SafetySetting(HarmCategory.HATE_SPEECH, BlockThreshold.NONE),
        SafetySetting(HarmCategory.SEXUALLY_EXPLICIT, BlockThreshold.NONE),
        SafetySetting(HarmCategory.DANGEROUS_CONTENT, BlockThreshold.NONE),
    ),
    systemInstruction = content { text("""
        You are a specialized currency detection assistant for blind users. Your purpose is to identify paper currency (bills/notes) in images and provide clear, concise information about:
        
        1. The denomination/value of the bill (e.g., "$20 bill", "€50 note", "1000 Rupee note")
        2. The currency type/country (e.g., "US Dollar", "Euro", "Indian Rupee")
        3. Distinctive features that help verify authenticity if visible
        4. The bill's orientation in the image if it would help the user position it better
        
        Format your response as:
        "Detected: [Value] [Currency Type]" 
        
        Add any helpful positioning guidance at the end if needed.
        
        If multiple bills are present, list each one separately.
        If no currency is visible, state: "No currency detected. Please position paper money in the center of the frame."
        
        Keep responses short and optimized for text-to-speech.
    """) },
)

suspend fun sendFrameToGemini2AI(bitmap: Bitmap, onPartialResult: (String) -> Unit, onError: (String) -> Unit) {
    try {
        withContext(Dispatchers.IO) {
            val inputContent = content {
                image(bitmap)
                text("Extract and read all text from this image for a blind person. If this is a book, newspaper, or magazine, identify it clearly. Preserve headings, paragraphs, and structure to make it easy to follow.")
            }

            var fullResponse = ""
            var isFirstChunk = true
            
            ReadModel.generateContentStream(inputContent).collect { chunk ->
                chunk.text?.let {
                    if (it.trim().isNotEmpty()) {
                        // Process the first chunk to add context if needed
                        if (isFirstChunk) {
                            // Try to determine document type with more specific categories
                            val documentPrefix = when {
                                it.contains("book", ignoreCase = true) || 
                                it.contains("chapter", ignoreCase = true) || 
                                it.contains("novel", ignoreCase = true) -> {
                                    // Try to extract book title if possible
                                    val bookTitle = extractTitle(it)
                                    if (bookTitle.isNotEmpty()) "Book: $bookTitle. " else "Book text: "
                                }
                                it.contains("newspaper", ignoreCase = true) || 
                                it.contains("article", ignoreCase = true) || 
                                it.contains("news", ignoreCase = true) -> "News article: "
                                it.contains("magazine", ignoreCase = true) -> "Magazine article: "
                                it.contains("menu", ignoreCase = true) -> "Menu: "
                                it.contains("warning", ignoreCase = true) -> "Warning sign: "
                                it.contains("caution", ignoreCase = true) -> "Caution sign: "
                                it.contains("exit", ignoreCase = true) -> "Exit sign: "
                                it.contains("ingredients", ignoreCase = true) || 
                                it.contains("nutrition", ignoreCase = true) -> "Product label: "
                                it.contains("recipe", ignoreCase = true) -> "Recipe: "
                                it.contains("$") || it.contains("price", ignoreCase = true) || 
                                it.contains("cost", ignoreCase = true) -> "Price information: "
                                else -> ""
                            }
                            
                            fullResponse = documentPrefix + it
                            isFirstChunk = false
                            
                            // Send the enhanced first chunk
                            onPartialResult(documentPrefix + it)
                        } else {
                            // For subsequent chunks, just append and send
                            fullResponse += it
                            onPartialResult(it)
                        }
                    }
                }
            }
            
            // Handle case where no text was detected
            if (fullResponse.trim().isEmpty()) {
                onError("No text detected in the image. Try adjusting the camera position or lighting.")
            }
        }
    } catch (e: IOException) {
        Log.e("GeminiAI", "Network error: ${e.message}")
        onError("Network error: ${e.message}")
    } catch (e: Exception) {
        Log.e("GeminiAI", "Unexpected error: ${e.message}")
        onError("Unexpected error: ${e.message}")
    }
}

// New function specifically for currency detection
suspend fun detectCurrency(bitmap: Bitmap, onPartialResult: (String) -> Unit, onError: (String) -> Unit) {
    try {
        withContext(Dispatchers.IO) {
            val inputContent = content {
                image(bitmap)
                text("Identify the paper currency (bills/notes) in this image. Include denomination/value and currency type.")
            }

            var fullResponse = ""
            
            CurrencyModel.generateContentStream(inputContent).collect { chunk ->
                chunk.text?.let {
                    if (it.trim().isNotEmpty()) {
                        fullResponse += it
                        onPartialResult(it)
                    }
                }
            }
            
            // Handle case where no currency was detected
            if (fullResponse.trim().isEmpty() || 
                fullResponse.contains("no currency", ignoreCase = true) ||
                fullResponse.contains("not detect", ignoreCase = true)) {
                onError("No currency detected in the image. Please position the bill in the center of the frame with good lighting.")
            }
        }
    } catch (e: IOException) {
        Log.e("GeminiAI", "Network error: ${e.message}")
        onError("Network error. Please check your internet connection and try again.")
    } catch (e: Exception) {
        Log.e("GeminiAI", "Unexpected error: ${e.message}")
        onError("Could not analyze the image. Please try again.")
    }
}

private fun extractTitle(text: String): String {
    // Simple title extraction - look for patterns like "Title: X" or "Book: X"
    val titlePatterns = listOf(
        Regex("(?i)title:\\s*([^\\n.]+)"),
        Regex("(?i)book:\\s*([^\\n.]+)"),
        Regex("(?i)book\\s+title:\\s*([^\\n.]+)")
    )
    
    for (pattern in titlePatterns) {
        val match = pattern.find(text)
        if (match != null) {
            return match.groupValues[1].trim()
        }
    }
    
    // If no specific title pattern found, try to use the first line if it's short
    val firstLine = text.split("\n").firstOrNull()?.trim() ?: ""
    if (firstLine.length < 60 && !firstLine.endsWith(".")) {
        return firstLine
    }
    
    return ""
}

private fun cleanTextForReading(text: String): String {
    var result = text
    
    // Replace multiple newlines with just two for better paragraph separation
    result = result.replace(Regex("\n{3,}"), "\n\n")
    
    // Ensure proper spacing after periods that don't have a space
    result = result.replace(Regex("\\.(\\S)"), ". $1")
    
    // Fix spacing issues around colons
    result = result.replace(Regex(":(\\S)"), ": $1")
    
    return result
}
