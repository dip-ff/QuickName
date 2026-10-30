package com.quickname.floating.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quickname.floating.data.PreferencesManager
import com.quickname.floating.service.FloatingButtonService
import com.quickname.floating.service.QuickNameAccessibilityService
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

val DarkBackground = Color(0xFF090D1A)
val SurfaceDark = Color(0xFF10172A)
val CardBorder = Color(0xFF1E293B)
val ElectricBlue = Color(0xFF0066FF)
val ElectricBlueLight = Color(0xFF38BDF8)
val AccentGreen = Color(0xFF10B981)
val TextPrimary = Color(0xFFF1F5F9)
val TextSecondary = Color(0xFF94A3B8)

object TotpGenerator {
    private const val BASE32_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun decodeBase32(encoded: String): ByteArray {
        val cleaned = encoded.trim().replace(" ", "").replace("-", "").uppercase()
        var buffer = 0
        var bitsLeft = 0
        val out = ByteArrayOutputStream()
        for (c in cleaned) {
            if (c == '=') break
            val valChar = BASE32_CHARS.indexOf(c)
            if (valChar < 0) continue
            buffer = (buffer shl 5) or valChar
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                out.write((buffer shr bitsLeft) and 0xFF)
            }
        }
        return out.toByteArray()
    }

    fun generate2FACode(secretKey: String): String {
        return try {
            val keyBytes = decodeBase32(secretKey)
            if (keyBytes.isEmpty()) return "------"

            val timeStep = (System.currentTimeMillis() / 1000) / 30
            val data = ByteBuffer.allocate(8).putLong(timeStep).array()

            val mac = Mac.getInstance("HmacSHA1")
            mac.init(SecretKeySpec(keyBytes, "RAW"))
            val hash = mac.doFinal(data)

            val offset = (hash[hash.size - 1].toInt() and 0x0F)
            val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
                    ((hash[offset + 1].toInt() and 0xFF) shl 16) or
                    ((hash[offset + 2].toInt() and 0xFF) shl 8) or
                    (hash[offset + 3].toInt() and 0xFF)

            val otp = binary % 1_000_000
            String.format("%06d", otp)
        } catch (e: Exception) {
            "------"
        }
    }

    fun getRemainingSeconds(): Int {
        return 30 - ((System.currentTimeMillis() / 1000) % 30).toInt()
    }
}

class MainActivity : ComponentActivity() {
    private lateinit var prefs: PreferencesManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PreferencesManager(this)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = ElectricBlue,
                    background = DarkBackground,
                    surface = SurfaceDark
                )
            ) {
                MainAppScreen(prefs = prefs)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScreen(prefs: PreferencesManager) {
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            Column(modifier = Modifier.background(DarkBackground)) {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Brush.linearGradient(listOf(ElectricBlue, ElectricBlueLight))),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("QN", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("Easy Work Assistant", fontWeight = FontWeight.Bold, color = TextPrimary)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBackground)
                )

                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = SurfaceDark,
                    contentColor = ElectricBlueLight
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("AutoFill & Settings", fontWeight = FontWeight.SemiBold) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("2FA Live Code", fontWeight = FontWeight.SemiBold) }
                    )
                }
            }
        },
        containerColor = DarkBackground
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            if (selectedTab == 0) {
                AutoFillDashboard(prefs = prefs)
            } else {
                TwoFactorLiveScreen()
            }
        }
    }
}

@Composable
fun TwoFactorLiveScreen() {
    val context = LocalContext.current
    val sharedPrefs = context.getSharedPreferences("quickname_prefs", Context.MODE_PRIVATE)

    var secretKey by remember { mutableStateOf(sharedPrefs.getString("saved_2fa_secret", "") ?: "") }
    var currentCode by remember { mutableStateOf("------") }
    var remainingSeconds by remember { mutableIntStateOf(TotpGenerator.getRemainingSeconds()) }

    LaunchedEffect(secretKey) {
        while (true) {
            remainingSeconds = TotpGenerator.getRemainingSeconds()
            if (secretKey.isNotBlank()) {
                currentCode = TotpGenerator.generate2FACode(secretKey)
            } else {
                currentCode = "------"
            }
            delay(1000)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                "AUTHENTICATION SECRET KEY",
                color = ElectricBlueLight,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = secretKey,
                onValueChange = {
                    secretKey = it
                    sharedPrefs.edit().putString("saved_2fa_secret", it).apply()
                },
                placeholder = { Text("e.g. GAXG 243E MR2X QZ...", color = TextSecondary) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ElectricBlue,
                    unfocusedBorderColor = CardBorder,
                    focusedContainerColor = SurfaceDark,
                    unfocusedContainerColor = SurfaceDark
                )
            )

            Spacer(modifier = Modifier.height(10.dp))

            Button(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                    if (!clip.isNullOrBlank()) {
                        secretKey = clip.trim().replace(" ", "").replace("-", "")
                        sharedPrefs.edit().putString("saved_2fa_secret", secretKey).apply()
                        Toast.makeText(context, "Key Saved for Floating Button!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Clipboard empty", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark),
                border = androidx.compose.foundation.BorderStroke(1.dp, CardBorder)
            ) {
                Icon(Icons.Default.ContentPaste, contentDescription = null, tint = ElectricBlueLight)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Paste & Save Key", color = TextPrimary)
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, CardBorder, RoundedCornerShape(16.dp))
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Bolt, contentDescription = null, tint = ElectricBlueLight)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                "ACTIVE TOKEN",
                                color = TextSecondary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                letterSpacing = 1.sp
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = Color(0xFF0D3349)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(AccentGreen)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    "${remainingSeconds}s",
                                    color = ElectricBlueLight,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    LinearProgressIndicator(
                        progress = { remainingSeconds / 30f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = ElectricBlueLight,
                        trackColor = Color(0xFF1E293B)
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    val displayFormattedCode = if (currentCode.length == 6) {
                        "${currentCode.substring(0, 3)} ${currentCode.substring(3, 6)}"
                    } else currentCode

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF090D1A),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, CardBorder, RoundedCornerShape(12.dp))
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(vertical = 18.dp)
                        ) {
                            Text(
                                text = displayFormattedCode,
                                fontSize = 34.sp,
                                fontWeight = FontWeight.ExtraBold,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 6.sp,
                                color = if (currentCode != "------") Color.White else TextSecondary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    Button(
                        onClick = {
                            if (currentCode != "------" && currentCode.isNotBlank()) {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("2FA Code", currentCode))
                                Toast.makeText(context, "Copied: $currentCode", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Copy Code", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
fun AutoFillDashboard(prefs: PreferencesManager) {
    val context = LocalContext.current
    val sharedPrefs = context.getSharedPreferences("quickname_prefs", Context.MODE_PRIVATE)

    var isFloatingServiceRunning by remember { mutableStateOf(FloatingButtonService.isRunning) }
    var customPasswordInput by remember { mutableStateOf(sharedPrefs.getString("saved_custom_password", "") ?: "") }
    var customFirstInput by remember { mutableStateOf("") }
    var customLastInput by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            PermissionCard(
                title = "Display Over Other Apps",
                isGranted = Settings.canDrawOverlays(context),
                onAction = {
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                    context.startActivity(intent)
                }
            )
        }

        item {
            PermissionCard(
                title = "Accessibility AutoFill",
                isGranted = QuickNameAccessibilityService.isServiceRunning,
                onAction = {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    context.startActivity(intent)
                }
            )
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.border(1.dp, CardBorder, RoundedCornerShape(12.dp))
            ) {
                Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (isFloatingServiceRunning) "Overlay Running" else "Overlay Stopped", color = TextPrimary)
                    Button(
                        onClick = {
                            if (!Settings.canDrawOverlays(context)) {
                                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                                context.startActivity(intent)
                                return@Button
                            }
                            val serviceIntent = Intent(context, FloatingButtonService::class.java)
                            if (isFloatingServiceRunning) {
                                context.stopService(serviceIntent)
                                isFloatingServiceRunning = false
                            } else {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    context.startForegroundService(serviceIntent)
                                } else {
                                    context.startService(serviceIntent)
                                }
                                isFloatingServiceRunning = true
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = if (isFloatingServiceRunning) Color(0xFFEF4444) else ElectricBlue)
                    ) {
                        Text(if (isFloatingServiceRunning) "Stop" else "Start")
                    }
                }
            }
        }

        // Saved Custom Password Section
        item {
            Text("Set Custom Password (পাসওয়ার্ড সংরক্ষণ)", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = ElectricBlueLight)
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.border(1.dp, CardBorder, RoundedCornerShape(12.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "আপনার পছন্দের পাসওয়ার্ড এখানে লিখে সেভ করুন। ফ্লোটিং স্ক্রিনের মাঝের 🔑 Pass বাটনে চাপলে সরাসরি এটি বসে যাবে।",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )

                    OutlinedTextField(
                        value = customPasswordInput,
                        onValueChange = { customPasswordInput = it },
                        label = { Text("Your Password") },
                        placeholder = { Text("e.g. MyP@ssw0rd!2026") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Button(
                        onClick = {
                            if (customPasswordInput.isNotBlank()) {
                                sharedPrefs.edit().putString("saved_custom_password", customPasswordInput).apply()
                                Toast.makeText(context, "Password Saved Successfully!", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Password cannot be empty!", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706))
                    ) {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Save Password", color = Color.White)
                    }
                }
            }
        }

        // Custom Names Section
        item {
            Text("Import Custom Names", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = ElectricBlueLight)
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.border(1.dp, CardBorder, RoundedCornerShape(12.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = customFirstInput,
                        onValueChange = { customFirstInput = it },
                        label = { Text("First Names (কমা বা লাইনে লিখুন)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = customLastInput,
                        onValueChange = { customLastInput = it },
                        label = { Text("Last Names (কমা বা লাইনে লিখুন)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            var added = 0
                            if (customFirstInput.isNotBlank()) {
                                customFirstInput.split(",", "\n").map { it.trim() }.filter { it.isNotEmpty() }.forEach { prefs.addCustomFirstName(it); added++ }
                                customFirstInput = ""
                            }
                            if (customLastInput.isNotBlank()) {
                                customLastInput.split(",", "\n").map { it.trim() }.filter { it.isNotEmpty() }.forEach { prefs.addCustomLastName(it); added++ }
                                customLastInput = ""
                            }
                            Toast.makeText(context, "$added টি নাম সেভ হয়েছে!", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
                    ) {
                        Text("Import Names")
                    }
                }
            }
        }
    }
}

@Composable
fun PermissionCard(title: String, isGranted: Boolean, onAction: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, CardBorder, RoundedCornerShape(12.dp))
    ) {
        Row(
            modifier = Modifier.padding(14.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(title, fontWeight = FontWeight.Bold, color = TextPrimary)
            if (isGranted) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = AccentGreen)
            } else {
                Button(onClick = onAction, colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)) {
                    Text("Grant", fontSize = 12.sp)
                }
            }
        }
    }
}
