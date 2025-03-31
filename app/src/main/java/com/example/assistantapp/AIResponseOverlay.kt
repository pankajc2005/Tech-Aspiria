package com.example.assistantapp

import android.speech.tts.TextToSpeech
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay


@Composable
fun AIResponseOverlay(
    currentMode: String,
    navigationResponse: String,
    chatResponse: String,
    readingModeResult: String,
    tts: TextToSpeech?,
    lastSpokenIndex: Int,
    response: String = ""  // Add default empty string
) {
    val context = LocalContext.current
    var isConnected = remember { mutableStateOf(isInternetAvailable(context)) }
    var currentIndex by remember { mutableStateOf(lastSpokenIndex) } // Track the current sentence index
    val sentences = if (response.isNotEmpty()) response.split(".") else emptyList() // Split the response into sentences or use empty list
    var lastSpokenText by remember { mutableStateOf("") } // Track the last spoken text
    
    // For reading mode enhancement
    val readingContent = remember { mutableStateOf(readingModeResult) }
    val readingParagraphs = remember(readingModeResult) { 
        if (readingModeResult.isNotEmpty()) {
            readingModeResult.split("\n\n").filter { it.trim().isNotEmpty() }
        } else emptyList()
    }
    var currentParagraphIndex by remember { mutableStateOf(0) }
    var isBookOrNewspaper by remember(readingModeResult) {
        mutableStateOf(
            readingModeResult.startsWith("Book:", ignoreCase = true) ||
            readingModeResult.startsWith("News", ignoreCase = true) ||
            readingModeResult.startsWith("Magazine", ignoreCase = true) ||
            readingModeResult.startsWith("Newspaper", ignoreCase = true)
        )
    }

    // Continuously check internet connectivity
    LaunchedEffect(Unit) {
        while (true) {
            isConnected.value = isInternetAvailable(context)
            delay(5000) // Check every 5 seconds
        }
    }

    // Skip to the next sentence every 4 seconds (reduced from 8)
    LaunchedEffect(Unit) {
        while (true) {
            delay(3000) // Wait for 3 seconds instead of 4
            if (isConnected.value && sentences.isNotEmpty() && sentences.size > 1) {
                // Prioritize sentences with instructions
                val instructionSentenceIndex = sentences.indexOfFirst { sentence ->
                    val text = sentence.trim().lowercase()
                    text.contains("turn") || text.contains("stop") || 
                    text.contains("proceed") || text.contains("go") || 
                    text.contains("careful") || text.contains("caution") ||
                    text.contains("watch") || text.contains("step")
                }
                
                // If we found an instruction, prioritize it
                if (instructionSentenceIndex >= 0) {
                    currentIndex = instructionSentenceIndex
                } else {
                    // Otherwise, just go to the next sentence
                    currentIndex = (currentIndex + 1) % sentences.size
                }
                
                val newText = sentences[currentIndex].trim()
                if (newText.isNotEmpty() && newText != lastSpokenText && newText.length > 3) {
                    tts?.speak(newText, TextToSpeech.QUEUE_FLUSH, null, null)
                    lastSpokenText = newText
                }
            }
        }
    }

    LaunchedEffect(response) {
        if (sentences.isNotEmpty()) {
            val newText = sentences[currentIndex].trim()
            if (newText.isNotEmpty() && newText != lastSpokenText && newText.length > 3) {
                tts?.speak(newText, TextToSpeech.QUEUE_FLUSH, null, null)
                lastSpokenText = newText
            }
        }
    }

    LaunchedEffect(currentMode, navigationResponse, chatResponse, readingModeResult) {
        when (currentMode) {
            "navigation" -> {
                val newText = navigationResponse.substring(lastSpokenIndex)
                if (newText.trim().isNotEmpty()) {
                    // Check if text contains instructions to prioritize
                    if (newText.contains("turn") || newText.contains("stop") || 
                        newText.contains("proceed") || newText.contains("careful") ||
                        newText.contains("watch out") || newText.contains("caution")) {
                        
                        tts?.speak(newText, TextToSpeech.QUEUE_FLUSH, null, null)
                    } else {
                        tts?.speak(newText, TextToSpeech.QUEUE_FLUSH, null, null)
                    }
                }
            }
            "assistant" -> {
                // Don't automatically speak in assistant mode
            }
            "reading" -> {
                // Update reading content when new text is received
                if (readingModeResult.isNotEmpty()) {
                    readingContent.value = readingModeResult
                    currentParagraphIndex = 0
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Bottom,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (!isConnected.value) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .background(Color(0x88000000))
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "You are not connected to the internet",
                    color = Color.Red,
                    fontSize = 20.sp,
                    modifier = Modifier.padding(8.dp)
                )
                tts?.speak("You are not connected to the internet", TextToSpeech.QUEUE_FLUSH, null, null)
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (currentMode == "reading" && isBookOrNewspaper) 300.dp else 200.dp)
                    .background(Color(0x88000000))
                    .padding(16.dp),
                contentAlignment = Alignment.TopStart
            ) {
                val scrollState = rememberScrollState()
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                ) {
                    when (currentMode) {
                        "reading" -> {
                            // Enhanced reading mode display with better formatting
                            if (isBookOrNewspaper && readingParagraphs.isNotEmpty()) {
                                // Extract title from reading content if possible
                                val title = if (readingContent.value.startsWith("Book:") || 
                                               readingContent.value.startsWith("News article:") ||
                                               readingContent.value.startsWith("Magazine article:") ||
                                               readingContent.value.startsWith("Newspaper:")) {
                                    val endOfTitle = readingContent.value.indexOf("\n")
                                    if (endOfTitle > 0) {
                                        readingContent.value.substring(0, endOfTitle).trim()
                                    } else {
                                        readingContent.value.substringBefore(".").trim()
                                    }
                                } else ""
                                
                                // Display title
                                if (title.isNotEmpty()) {
                                    Text(
                                        text = title,
                                        color = Color.White,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier
                                            .background(Color(0xAA000000))
                                            .padding(8.dp)
                                            .fillMaxWidth()
                                    )
                                    
                                    Spacer(modifier = Modifier.height(8.dp))
                                }
                                
                                // Display paragraphs with better formatting
                                readingParagraphs.forEachIndexed { index, paragraph ->
                                    if (index != 0 || title.isEmpty() || !paragraph.startsWith(title)) {
                                        Text(
                                            text = paragraph,
                                            color = Color.White,
                                            fontSize = 16.sp,
                                            fontStyle = if (paragraph.startsWith("Chapter") || paragraph.all { it.isUpperCase() }) 
                                                       FontStyle.Italic else FontStyle.Normal,
                                            fontWeight = if (paragraph.startsWith("Chapter") || paragraph.all { it.isUpperCase() })
                                                       FontWeight.Bold else FontWeight.Normal,
                                            modifier = Modifier
                                                .background(
                                                    if (index == currentParagraphIndex) 
                                                        Color(0xAA004080) else Color(0xAA000000)
                                                )
                                                .padding(8.dp)
                                                .fillMaxWidth()
                                        )
                                        
                                        Spacer(modifier = Modifier.height(4.dp))
                                    }
                                }
                            } else {
                                // Fall back to original display for non-book content
                                Text(
                                    text = readingModeResult,
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    fontStyle = FontStyle.Normal,
                                    modifier = Modifier
                                        .background(Color(0xAA000000))
                                        .padding(8.dp)
                                        .fillMaxWidth()
                                )
                            }
                        }
                        "assistant" -> {
                            Text(
                                text = "Chat: $chatResponse",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontStyle = FontStyle.Italic,
                                modifier = Modifier
                                    .background(Color(0xAA000000))
                                    .padding(8.dp)
                            )
                        }
                        "navigation" -> {
                            Text(
                                text = navigationResponse,
                                color = Color.White,
                                fontSize = 16.sp,
                                fontStyle = FontStyle.Italic,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}