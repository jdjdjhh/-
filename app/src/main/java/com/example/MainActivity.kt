package com.example

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.system.measureTimeMillis

data class ConsoleLine(val text: String, val isHighlight: Boolean = false, val isDim: Boolean = false)

class MainActivity : ComponentActivity() {

    private var displayLines by mutableStateOf<List<ConsoleLine>>(emptyList())
    private var currentFileName by mutableStateOf<String?>(null)

    private val getContent = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { handleFileUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        displayLines = listOf(
            ConsoleLine("No file loaded.", isDim = true),
            ConsoleLine("Click the folder icon in the top right to open a .pil file.", isDim = true)
        )
        
        intent?.data?.let { uri ->
            handleFileUri(uri)
        }

        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = ThemeBackground,
                    contentWindowInsets = WindowInsets.systemBars,
                    bottomBar = { BottomNavBar() },
                    floatingActionButton = {
                        FloatingActionButton(
                            onClick = { /* Action */ },
                            containerColor = ThemeFabBackground,
                            contentColor = ThemeFabIcon,
                            shape = RoundedCornerShape(16.dp),
                            elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 8.dp)
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = "Add")
                        }
                    }
                ) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        CustomHeader(onOpenFile = { getContent.launch(arrayOf("*/*")) })
                        
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(16.dp)
                        ) {
                            val modeText = if (currentFileName?.lowercase()?.contains("python") == true || currentFileName?.lowercase()?.contains("c++") == true) {
                                "(Code Mode)"
                            } else "(Text Mode)"
                            
                            FileBadge(fileName = currentFileName ?: "No File", mode = modeText)
                            
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            ConsoleBox(lines = displayLines)
                        }
                    }
                }
            }
        }
    }

    private fun handleFileUri(uri: Uri) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val fileName = getFileName(uri) ?: "Unknown"
                withContext(Dispatchers.Main) { 
                    currentFileName = fileName 
                    displayLines = listOf(ConsoleLine("interpreting script...", isDim = true))
                }
                
                val content = readTextFromUri(uri)
                val processed = mutableListOf<ConsoleLine>()
                
                val time = measureTimeMillis {
                    val resultText = processPilContent(fileName, content)
                    if (resultText.startsWith("Syntax Error:")) {
                        processed.add(ConsoleLine(resultText, isHighlight = false))
                    } else {
                        val lines = resultText.lines()
                        for (l in lines) {
                            processed.add(ConsoleLine(l, isHighlight = true))
                        }
                    }
                }
                
                // Add final summary
                processed.add(0, ConsoleLine("interpreting script...", isDim = true))
                processed.add(ConsoleLine("", isDim = true)) // spacer
                processed.add(ConsoleLine("execution finished (${time}ms)", isDim = true))
                
                withContext(Dispatchers.Main) {
                    displayLines = processed
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    displayLines = listOf(ConsoleLine("Error reading file: ${e.message}", isDim = false))
                }
            }
        }
    }

    private fun readTextFromUri(uri: Uri): String {
        val inputStream = contentResolver.openInputStream(uri) ?: return ""
        val reader = BufferedReader(InputStreamReader(inputStream))
        val stringBuilder = java.lang.StringBuilder()
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            stringBuilder.append(line).append('\n')
        }
        inputStream.close()
        return stringBuilder.toString()
    }

    private fun getFileName(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        result = cursor.getString(index)
                    }
                }
            } catch (e: Exception) { /* Ignore */ } finally { cursor?.close() }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/')
            if (cut != null && cut != -1) {
                result = result.substring(cut + 1)
            }
        }
        return result
    }

    private fun processPilContent(fileName: String, content: String): String {
        val lowerName = fileName.lowercase()
        return if (lowerName.contains("python") || lowerName.contains("c++")) {
            parseAndExecuteCode(content)
        } else {
            content
        }
    }

    private fun parseAndExecuteCode(content: String): String {
        val output = java.lang.StringBuilder()
        val lines = content.lines()
        var hasCode = false
        
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            
            val printRegex = Regex("""^print\s*\(\s*["'](.*)["']\s*\)$""")
            val match = printRegex.find(trimmed)
            if (match != null) {
                output.appendLine(match.groupValues[1])
                hasCode = true
            } else {
                return "Syntax Error: Unknown command at -> '$trimmed'\nSupported commands: print(\"text\")"
            }
        }
        
        if (!hasCode) {
            return "No executable code found in file."
        }
        
        return output.toString().trimEnd()
    }
}

@Composable
fun CustomHeader(onOpenFile: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(ThemeBackground)
            .border(width = 1.dp, color = ThemeBorder, shape = RoundedCornerShape(bottomStart = 0.dp, bottomEnd = 0.dp))
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(ThemePrimary),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Code, contentDescription = "Logo", tint = Color.White)
            }
            Column {
                Text(
                    text = "PIL Runner",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ThemeTextPrimary,
                    lineHeight = 22.sp
                )
                Text(
                    text = "v1.0.4 • Android Native",
                    fontSize = 12.sp,
                    color = ThemeTextSecondary
                )
            }
        }
        
        IconButton(onClick = onOpenFile) {
            Icon(Icons.Filled.FolderOpen, contentDescription = "Open File", tint = ThemeTextPrimary)
        }
    }
}

@Composable
fun FileBadge(fileName: String, mode: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier = Modifier
                .background(ThemePrimaryContainer, RoundedCornerShape(50))
                .border(1.dp, ThemePrimaryContainerBorder, RoundedCornerShape(50))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(ThemePrimary)
                    .shadow(elevation = 4.dp, spotColor = ThemePrimary, ambientColor = ThemePrimary)
            )
            Text(
                text = "File: $fileName $mode",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = ThemeFabIcon
            )
        }
    }
}

@Composable
fun ConsoleBox(lines: List<ConsoleLine>) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(28.dp))
            .background(Color.White)
            .border(1.dp, ThemeBorderDark, RoundedCornerShape(28.dp))
    ) {
        // Output Console Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ThemeSurfaceHeader)
                .border(width = 1.dp, color = ThemeBorder, shape = RoundedCornerShape(topStart = 0.dp, topEnd = 0.dp))
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "OUTPUT CONSOLE",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = ThemeTextSecondary,
                letterSpacing = 1.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(ThemeBorder))
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(ThemeBorder))
            }
        }
        
        // Output Details
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
        ) {
            items(lines) { line ->
                Row(modifier = Modifier.padding(bottom = 8.dp)) {
                    Text(
                        text = ">",
                        color = ThemeTextSecondary.copy(alpha = if (line.isHighlight) 1f else 0.5f),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(
                        text = line.text,
                        color = if (line.isHighlight) ThemePrimary else if (line.isDim) ThemeTextSecondary.copy(alpha = 0.5f) else ThemeTextPrimary,
                        fontWeight = if (line.isHighlight) FontWeight.Bold else FontWeight.Normal,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )
                }
            }
        }
    }
}

@Composable
fun BottomNavBar() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp)
            .background(ThemeNavBackground)
            .border(width = 1.dp, color = ThemeBorder, shape = RoundedCornerShape(0.dp))
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        NavItem("Home", Icons.Filled.Home, selected = false)
        NavItem("Interpreter", Icons.Filled.Code, selected = true)
        NavItem("Recent", Icons.Filled.History, selected = false)
    }
}

@Composable
fun NavItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
    ) {
        Box(
            modifier = Modifier
                .width(64.dp)
                .height(32.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(if (selected) ThemePrimaryContainer else Color.Transparent),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (selected) Color(0xFF1D192B) else ThemeTextSecondary.copy(alpha = 0.5f),
                modifier = Modifier.size(24.dp)
            )
        }
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) Color(0xFF1D192B) else ThemeTextSecondary.copy(alpha = 0.5f)
        )
    }
}

