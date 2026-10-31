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
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import com.quickname.floating.data.NameRepository
import com.quickname.floating.data.PreferencesManager
import com.quickname.floating.service.FloatingButtonService
import com.quickname.floating.service.QuickNameAccessibilityService
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// --- Luxury Cyber Dark Color Palette ---
private val BgObsidian = Color(0xFF060913)
private val SurfaceGlass = Color(0xFF0F172A)
private val SurfaceGlassSubtle = Color(0xFF162036)
private val BorderGlass = Color(0xFF22324F)

private val NeonElectric = Color(0xFF0066FF)
private val NeonCyan = Color(0xFF00E5FF)
private val NeonEmerald = Color(0xFF10B981)
private val NeonAmber = Color(0xFFF59E0B)
private val NeonRose = Color(0xFFEF4444)

private val TextLuxuryWhite = Color(0xFFF8FAFC)
private val TextMutedSlate = Color(0xFF94A3B8)

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
                    primary = NeonElectric,
                    background = BgObsidian,
                    surface = SurfaceGlass
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
            Column(
                modifier = Modifier
                    .background(BgObsidian)
                    .statusBarsPadding()
            ) {
                // Sleek Luxury Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Brush.linearGradient(listOf(NeonElectric, NeonCyan)))
                                .border(1.dp, Color(0x66FFFFFF), RoundedCornerShape(14.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "EW",
                                color = Color.White,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 16.sp,
                                letterSpacing = 1.sp
                            )
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column {
                            Text(
                                "EASY WORK PRO",
                                fontWeight = FontWeight.Black,
                                color = TextLuxuryWhite,
                                fontSize = 17.sp,
                                letterSpacing = 1.5.sp
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(NeonEmerald)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    "SYSTEM READY",
                                    color = NeonCyan,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )
                            }
                        }
                    }
                }

                // Modern Pill Segment Switcher
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(SurfaceGlass)
                        .border(1.dp, BorderGlass, RoundedCornerShape(14.dp))
                        .padding(4.dp)
                ) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        PillTabItem(
                            title = "⚡ AutoFill & Vault",
                            isSelected = selectedTab == 0,
                            modifier = Modifier.weight(1f)
                        ) { selectedTab = 0 }

                        PillTabItem(
                            title = "🔑 2FA Authenticator",
                            isSelected = selectedTab == 1,
                            modifier = Modifier.weight(1f)
                        ) { selectedTab = 1 }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
            }
        },
        containerColor = BgObsidian
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (selectedTab == 0) {
                AutoFillDashboard(prefs = prefs)
            } else {
                TwoFactorLiveScreen()
            }
        }
    }
}

@Composable
fun PillTabItem(
    title: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val bgGradient = if (isSelected) {
        Brush.horizontalGradient(listOf(NeonElectric, Color(0xFF1D4ED8)))
    } else {
        Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bgGradient)
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            color = if (isSelected) Color.White else TextMutedSlate,
            fontSize = 12.5.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

// --- 2FA Live Screen (Cyberpunk / Modern TOTP) ---
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
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            // Live OTP Display Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(SurfaceGlassSubtle, SurfaceGlass)
                        )
                    )
                    .border(
                        1.dp,
                        Brush.verticalGradient(listOf(Color(0x5500E5FF), Color(0x111E293B))),
                        RoundedCornerShape(22.dp)
                    )
                    .padding(22.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Bolt, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "REAL-TIME TOTP",
                                color = TextMutedSlate,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                letterSpacing = 1.sp
                            )
                        }

                        // Countdown Pill
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = Color(0x2200E5FF),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x4400E5FF))
                        ) {
                            Text(
                                "● ${remainingSeconds}s remaining",
                                color = NeonCyan,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // Progress Line
                    LinearProgressIndicator(
                        progress = { remainingSeconds / 30f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = NeonCyan,
                        trackColor = Color(0xFF1E293B)
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    val displayCode = if (currentCode.length == 6) {
                        "${currentCode.substring(0, 3)}  ${currentCode.substring(3, 6)}"
                    } else currentCode

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF050811))
                            .border(1.dp, BorderGlass, RoundedCornerShape(16.dp))
                            .padding(vertical = 22.dp)
                    ) {
                        Text(
                            text = displayCode,
                            fontSize = 38.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 4.sp,
                            color = if (currentCode != "------") Color.White else TextMutedSlate
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Copy Button
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Brush.horizontalGradient(listOf(NeonElectric, NeonCyan)))
                            .clickable {
                                if (currentCode != "------" && currentCode.isNotBlank()) {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("2FA Code", currentCode))
                                    Toast.makeText(context, "Copied: $currentCode", Toast.LENGTH_SHORT).show()
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, tint = Color.White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Copy OTP Token", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }
            }
        }

        item {
            // Secret Key Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(SurfaceGlass)
                    .border(1.dp, BorderGlass, RoundedCornerShape(20.dp))
                    .padding(20.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "SECRET AUTHENTICATION KEY",
                        color = TextMutedSlate,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )

                    OutlinedTextField(
                        value = secretKey,
                        onValueChange = {
                            secretKey = it
                            sharedPrefs.edit().putString("saved_2fa_secret", it).apply()
                        },
                        placeholder = { Text("Paste your 2FA Key here (e.g. GAXG 243E...)", color = Color(0xFF64748B), fontSize = 13.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NeonCyan,
                            unfocusedBorderColor = BorderGlass,
                            focusedContainerColor = Color(0xFF070B16),
                            unfocusedContainerColor = Color(0xFF070B16)
                        )
                    )

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
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SurfaceGlassSubtle),
                        border = androidx.compose.foundation.BorderStroke(1.dp, BorderGlass)
                    ) {
                        Icon(Icons.Default.ContentPaste, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Paste Key from Clipboard", color = TextLuxuryWhite, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

// --- AutoFill & Main Dashboard ---
@Composable
fun AutoFillDashboard(prefs: PreferencesManager) {
    val context = LocalContext.current
    val repository = remember { NameRepository(prefs) }
    val sharedPrefs = context.getSharedPreferences("quickname_prefs", Context.MODE_PRIVATE)

    var isFloatingServiceRunning by remember { mutableStateOf(FloatingButtonService.isRunning) }
    var customPasswordInput by remember { mutableStateOf(sharedPrefs.getString("saved_custom_password", "") ?: "") }
    var bulkNamesInput by remember { mutableStateOf("") }
    var remainingCount by remember { mutableIntStateOf(repository.getRemainingCount()) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Hero Overlay Controller
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFF0E1A36), SurfaceGlass)
                        )
                    )
                    .border(
                        1.dp,
                        Brush.linearGradient(listOf(Color(0x440066FF), Color(0x1138BDF8))),
                        RoundedCornerShape(22.dp)
                    )
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("FLOATING CONTROLLER", color = NeonCyan, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            if (isFloatingServiceRunning) "Overlay is Active" else "Overlay is Standby",
                            color = TextLuxuryWhite,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        )
                        Text(
                            if (isFloatingServiceRunning) "Tap QN on screen to auto-fill" else "Press Start to show QN button",
                            color = TextMutedSlate,
                            fontSize = 12.sp
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (isFloatingServiceRunning) Brush.horizontalGradient(listOf(NeonRose, Color(0xFFB91C1C)))
                                else Brush.horizontalGradient(listOf(NeonElectric, NeonCyan))
                            )
                            .clickable {
                                if (!Settings.canDrawOverlays(context)) {
                                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                                    context.startActivity(intent)
                                    return@clickable
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
                            }
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                    ) {
                        Text(
                            text = if (isFloatingServiceRunning) "Stop" else "Start",
                            color = Color.White,
                            fontWeight = FontWeight.Black,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }

        // Permissions Health Tiles
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LuxuryPermissionTile(
                    title = "Overlay Access",
                    isGranted = Settings.canDrawOverlays(context),
                    modifier = Modifier.weight(1f)
                ) {
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                    context.startActivity(intent)
                }

                LuxuryPermissionTile(
                    title = "Accessibility",
                    isGranted = QuickNameAccessibilityService.isServiceRunning,
                    modifier = Modifier.weight(1f)
                ) {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    context.startActivity(intent)
                }
            }
        }

        // Single-Use Name Vault Card
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(SurfaceGlass)
                    .border(1.dp, BorderGlass, RoundedCornerShape(22.dp))
                    .padding(20.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "SINGLE-USE NAME VAULT",
                            color = TextMutedSlate,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.5.sp,
                            letterSpacing = 1.sp
                        )

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (remainingCount > 0) Color(0x2210B981) else Color(0x22EF4444),
                            border = androidx.compose.foundation.BorderStroke(1.dp, if (remainingCount > 0) NeonEmerald else NeonRose)
                        ) {
                            Text(
                                text = if (remainingCount > 0) "$remainingCount Ready" else "Empty",
                                color = if (remainingCount > 0) NeonEmerald else NeonRose,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }

                    OutlinedTextField(
                        value = bulkNamesInput,
                        onValueChange = { bulkNamesInput = it },
                        placeholder = { Text("Paste up to 100 names (one per line)...\ne.g. John Smith\nDavid Brown", color = Color(0xFF64748B), fontSize = 12.5.sp) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(115.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NeonCyan,
                            unfocusedBorderColor = BorderGlass,
                            focusedContainerColor = Color(0xFF070B16),
                            unfocusedContainerColor = Color(0xFF070B16)
                        )
                    )

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Brush.horizontalGradient(listOf(NeonElectric, Color(0xFF2563EB))))
                                .clickable {
                                    if (bulkNamesInput.isNotBlank()) {
                                        val added = repository.addBulkNames(bulkNamesInput)
                                        bulkNamesInput = ""
                                        remainingCount = repository.getRemainingCount()
                                        Toast.makeText(context, "$added টি নাম সফলভাবে ভল্টে যোগ হয়েছে!", Toast.LENGTH_SHORT).show()
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Import to Vault", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }

                        if (remainingCount > 0) {
                            OutlinedButton(
                                onClick = {
                                    prefs.clearQueue()
                                    remainingCount = 0
                                    Toast.makeText(context, "ভল্ট খালি করা হয়েছে!", Toast.LENGTH_SHORT).show()
                                },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonRose),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x55EF4444))
                            ) {
                                Text("Reset")
                            }
                        }
                    }
                }
            }
        }

        // Custom Password Card
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(SurfaceGlass)
                    .border(1.dp, BorderGlass, RoundedCornerShape(22.dp))
                    .padding(20.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "PASSWORD VAULT (🔑 PASS BUTTON)",
                        color = TextMutedSlate,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.5.sp,
                        letterSpacing = 1.sp
                    )

                    OutlinedTextField(
                        value = customPasswordInput,
                        onValueChange = { customPasswordInput = it },
                        placeholder = { Text("Set auto-paste password...", color = Color(0xFF64748B), fontSize = 13.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NeonAmber,
                            unfocusedBorderColor = BorderGlass,
                            focusedContainerColor = Color(0xFF070B16),
                            unfocusedContainerColor = Color(0xFF070B16)
                        )
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Brush.horizontalGradient(listOf(NeonAmber, Color(0xFFD97706))))
                            .clickable {
                                if (customPasswordInput.isNotBlank()) {
                                    sharedPrefs.edit().putString("saved_custom_password", customPasswordInput).apply()
                                    Toast.makeText(context, "Password Saved to Vault!", Toast.LENGTH_SHORT).show()
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Lock, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Save Master Password", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(10.dp)) }
    }
}

@Composable
fun LuxuryPermissionTile(
    title: String,
    isGranted: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(SurfaceGlass)
            .border(1.dp, BorderGlass, RoundedCornerShape(18.dp))
            .clickable { onClick() }
            .padding(16.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, color = TextLuxuryWhite, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isGranted) NeonEmerald else NeonRose)
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                if (isGranted) "Permission Active" else "Tap to Grant Access",
                color = if (isGranted) NeonEmerald else NeonAmber,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
