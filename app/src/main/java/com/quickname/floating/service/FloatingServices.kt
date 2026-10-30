package com.quickname.floating.service

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.quickname.floating.QuickNameApp
import com.quickname.floating.data.GeneratedName
import com.quickname.floating.data.NameRepository
import com.quickname.floating.data.PreferencesManager
import com.quickname.floating.ui.MainActivity
import com.quickname.floating.ui.TotpGenerator
import kotlin.math.abs

class QuickNameAccessibilityService : AccessibilityService() {

    companion object {
        var instance: QuickNameAccessibilityService? = null
            private set

        val isServiceRunning: Boolean
            get() = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    fun performAutoFill(name: GeneratedName): Boolean {
        val rootNode = rootInActiveWindow ?: return fallbackToClipboard(name.fullName, "No active window")
        val editableNodes = mutableListOf<AccessibilityNodeInfo>()
        findEditableNodes(rootNode, editableNodes)

        if (editableNodes.isEmpty()) {
            return fallbackToClipboard(name.fullName, "No editable fields found")
        }

        var focusedIndex = editableNodes.indexOfFirst { it.isFocused }
        if (focusedIndex == -1) focusedIndex = 0

        var filledCount = 0
        if (name.firstName.isNotEmpty() && focusedIndex < editableNodes.size) {
            if (setText(editableNodes[focusedIndex], name.firstName)) filledCount++
        }

        if (name.lastName.isNotEmpty()) {
            val nextIndex = focusedIndex + 1
            if (nextIndex < editableNodes.size) {
                if (setText(editableNodes[nextIndex], name.lastName)) filledCount++
            } else if (filledCount == 0 && focusedIndex < editableNodes.size) {
                setText(editableNodes[focusedIndex], name.fullName)
                filledCount++
            }
        }

        return if (filledCount > 0) {
            showToast("Filled: ${name.fullName}")
            true
        } else {
            fallbackToClipboard(name.fullName, "Autofill rejected by app")
        }
    }

    fun fill2FACode(code: String): Boolean {
        val rootNode = rootInActiveWindow ?: return fallbackToClipboard(code, "No active window")
        val editableNodes = mutableListOf<AccessibilityNodeInfo>()
        findEditableNodes(rootNode, editableNodes)

        val targetNode = editableNodes.find { it.isFocused } ?: editableNodes.firstOrNull()
        if (targetNode != null && setText(targetNode, code)) {
            showToast("2FA Filled: $code")
            copyToClipboard(code)
            return true
        }
        return fallbackToClipboard(code, "Copied 2FA Code")
    }

    private fun findEditableNodes(node: AccessibilityNodeInfo?, result: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        if (node.isEditable && node.isVisibleToUser) result.add(node)
        for (i in 0 until node.childCount) {
            findEditableNodes(node.getChild(i), result)
        }
    }

    private fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("QuickName", text))
    }

    private fun fallbackToClipboard(text: String, reason: String): Boolean {
        copyToClipboard(text)
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(this, "$reason: '$text'", Toast.LENGTH_SHORT).show()
        }
        return false
    }

    private fun showToast(msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }
}

class FloatingButtonService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var rootContainer: LinearLayout
    private lateinit var floatingButton: TextView
    private lateinit var miniScreenView: LinearLayout

    // UI elements inside mini screen
    private lateinit var keyInputEditText: EditText
    private lateinit var timerBadgeView: TextView
    private lateinit var codeDisplayView: TextView

    private lateinit var layoutParams: WindowManager.LayoutParams
    private lateinit var prefs: PreferencesManager
    private lateinit var repository: NameRepository

    private var currentSecretKey: String = ""
    private val liveUpdateHandler = Handler(Looper.getMainLooper())

    private val liveUpdateRunnable = object : Runnable {
        override fun run() {
            if (miniScreenView.visibility == View.VISIBLE) {
                updateLiveCodeAndTimer()
                liveUpdateHandler.postDelayed(this, 1000)
            }
        }
    }

    companion object {
        var isRunning = false
            private set
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        prefs = PreferencesManager(this)
        repository = NameRepository(prefs)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val sharedPrefs = getSharedPreferences("quickname_prefs", Context.MODE_PRIVATE)
        currentSecretKey = sharedPrefs.getString("saved_2fa_secret", "") ?: ""

        startAsForeground()
        createFloatingView()
    }

    private fun startAsForeground() {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, QuickNameApp.CHANNEL_ID)
            .setContentTitle("QuickName 2FA & AutoFill")
            .setContentText("Tap floating button for 2FA screen")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(1001, notification)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createFloatingView() {
        val density = resources.displayMetrics.density
        val sizePx = (56 * density).toInt()

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // Initially NOT FOCUSABLE so dragging doesn't interfere with system
        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 250
        }

        rootContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
        }

        // 1. Draggable Circular "QN" Button
        floatingButton = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(sizePx, sizePx)
            text = "QN"
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            elevation = 20f
            background = getGradientCircle()
        }

        // 2. The Pop-up Mini Screen
        miniScreenView = createMiniScreen(density)

        rootContainer.addView(floatingButton)
        rootContainer.addView(miniScreenView)

        setupTouchListener()
        windowManager.addView(rootContainer, layoutParams)
    }

    private fun createMiniScreen(density: Float): LinearLayout {
        val cardWidth = (290 * density).toInt()

        return LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(cardWidth, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (10 * density).toInt()
            }
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            elevation = 24f
            setPadding((16 * density).toInt(), (14 * density).toInt(), (16 * density).toInt(), (14 * density).toInt())
            background = getCardBackground()

            // Header Row: Title & Close Button
            val headerRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val titleText = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = "⚡ 2FA Live Code"
                setTextColor(Color.parseColor("#38BDF8"))
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
            }
            val closeBtn = TextView(context).apply {
                text = "✕"
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 16f
                setPadding(14, 0, 14, 0)
                setOnClickListener { toggleMiniScreen() }
            }
            headerRow.addView(titleText)
            headerRow.addView(closeBtn)
            addView(headerRow)

            // Secret Key Editable Input Box
            keyInputEditText = EditText(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (42 * density).toInt()
                ).apply { topMargin = (10 * density).toInt() }
                hint = "Enter or Paste Key here..."
                setHintTextColor(Color.parseColor("#64748B"))
                setTextColor(Color.WHITE)
                textSize = 12f
                setPadding((10 * density).toInt(), 0, (10 * density).toInt(), 0)
                background = getRoundedButtonBackground(Color.parseColor("#090D1A"), Color.parseColor("#334155"))
                setText(currentSecretKey)
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        currentSecretKey = s?.toString()?.trim() ?: ""
                        val sharedPrefs = getSharedPreferences("quickname_prefs", Context.MODE_PRIVATE)
                        sharedPrefs.edit().putString("saved_2fa_secret", currentSecretKey).apply()
                        updateLiveCodeAndTimer()
                    }
                    override fun afterTextChanged(s: Editable?) {}
                })
            }
            addView(keyInputEditText)

            // Direct "Paste Secret Key" Button
            val pasteButton = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (38 * density).toInt()
                ).apply { topMargin = (8 * density).toInt() }
                text = "📋 Paste Secret Key"
                setTextColor(Color.WHITE)
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = getRoundedButtonBackground(Color.parseColor("#1E293B"), Color.parseColor("#38BDF8"))
                setOnClickListener {
                    // Read clipboard directly while focused
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                    if (!clip.isNullOrBlank()) {
                        currentSecretKey = clip.trim().replace(" ", "").replace("-", "")
                        keyInputEditText.setText(currentSecretKey)
                        val sharedPrefs = getSharedPreferences("quickname_prefs", Context.MODE_PRIVATE)
                        sharedPrefs.edit().putString("saved_2fa_secret", currentSecretKey).apply()
                        updateLiveCodeAndTimer()
                        Toast.makeText(context, "Key Pasted!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Clipboard empty! Copy a key first.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            addView(pasteButton)

            // Active Token & Timer Row
            val timerRow = LinearLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (12 * density).toInt() }
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val activeTokenLabel = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = "ACTIVE CODE (30s)"
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
            }
            timerBadgeView = TextView(context).apply {
                text = "● 30s"
                setTextColor(Color.parseColor("#38BDF8"))
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(16, 4, 16, 4)
                background = getRoundedButtonBackground(Color.parseColor("#0D3349"), Color.parseColor("#10B981"))
            }
            timerRow.addView(activeTokenLabel)
            timerRow.addView(timerBadgeView)
            addView(timerRow)

            // 6-Digit Big Code Box
            codeDisplayView = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (54 * density).toInt()
                ).apply { topMargin = (10 * density).toInt() }
                text = "------"
                setTextColor(Color.WHITE)
                textSize = 28f
                typeface = Typeface.MONOSPACE
                gravity = Gravity.CENTER
                letterSpacing = 0.25f
                background = getRoundedButtonBackground(Color.parseColor("#090D1A"), Color.parseColor("#1E293B"))
            }
            addView(codeDisplayView)

            // Action Buttons Row: Copy Code & Name AutoFill
            val actionsRow = LinearLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (42 * density).toInt()
                ).apply { topMargin = (12 * density).toInt() }
                orientation = LinearLayout.HORIZONTAL
            }

            val copyBtn = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                text = "📋 Copy Code"
                setTextColor(Color.WHITE)
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = getRoundedButtonBackground(Color.parseColor("#0066FF"), Color.parseColor("#38BDF8"))
                setOnClickListener {
                    val code = TotpGenerator.generate2FACode(currentSecretKey)
                    if (code != "------" && code.isNotBlank()) {
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("2FA Code", code))
                        Toast.makeText(context, "Copied: $code", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Paste a valid key first!", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            val nameBtn = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.9f).apply {
                    marginStart = (8 * density).toInt()
                }
                text = "👤 Fill Name"
                setTextColor(Color.WHITE)
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = getRoundedButtonBackground(Color.parseColor("#1E293B"), Color.parseColor("#38BDF8"))
                setOnClickListener {
                    val generated = repository.generateName()
                    QuickNameAccessibilityService.instance?.performAutoFill(generated)
                        ?: copyDirectly(generated.fullName, "Accessibility disabled. Copied")
                }
            }

            actionsRow.addView(copyBtn)
            actionsRow.addView(nameBtn)
            addView(actionsRow)
        }
    }

    private fun updateLiveCodeAndTimer() {
        val remaining = TotpGenerator.getRemainingSeconds()
        timerBadgeView.text = "● ${remaining}s"

        if (currentSecretKey.isNotBlank()) {
            val code = TotpGenerator.generate2FACode(currentSecretKey)
            codeDisplayView.text = if (code.length == 6) "${code.substring(0, 3)} ${code.substring(3, 6)}" else code
        } else {
            codeDisplayView.text = "------"
        }
    }

    private fun toggleMiniScreen() {
        if (miniScreenView.visibility == View.VISIBLE) {
            miniScreenView.visibility = View.GONE
            liveUpdateHandler.removeCallbacks(liveUpdateRunnable)

            // 1. When closed: Keep FLAG_NOT_FOCUSABLE so dragging doesn't interfere with other apps
            layoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            windowManager.updateViewLayout(rootContainer, layoutParams)
        } else {
            // 2. When opened: GIVE WINDOW FOCUS so Android allows reading clipboard & keyboard input!
            layoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            windowManager.updateViewLayout(rootContainer, layoutParams)

            val sharedPrefs = getSharedPreferences("quickname_prefs", Context.MODE_PRIVATE)
            currentSecretKey = sharedPrefs.getString("saved_2fa_secret", "") ?: ""
            keyInputEditText.setText(currentSecretKey)

            updateLiveCodeAndTimer()
            miniScreenView.visibility = View.VISIBLE
            liveUpdateHandler.post(liveUpdateRunnable)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchListener() {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        floatingButton.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(100).start()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) isDragging = true
                    if (isDragging) {
                        layoutParams.x = initialX + dx
                        layoutParams.y = initialY + dy
                        windowManager.updateViewLayout(rootContainer, layoutParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
                    if (!isDragging) {
                        toggleMiniScreen()
                    } else {
                        snapToEdge()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun snapToEdge() {
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(metrics)
        val screenWidth = metrics.widthPixels
        val viewWidth = floatingButton.width
        val currentX = layoutParams.x
        val targetX = if (currentX + viewWidth / 2 < screenWidth / 2) 30 else screenWidth - viewWidth - 30

        val animator = ValueAnimator.ofInt(currentX, targetX).apply {
            duration = 250
            interpolator = OvershootInterpolator(1.2f)
            addUpdateListener { animation ->
                layoutParams.x = animation.animatedValue as Int
                if (rootContainer.isAttachedToWindow) {
                    windowManager.updateViewLayout(rootContainer, layoutParams)
                }
            }
        }
        animator.start()
    }

    private fun getGradientCircle(): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor("#0066FF"), Color.parseColor("#0D3349"))
        ).apply {
            shape = GradientDrawable.OVAL
            setStroke(3, Color.parseColor("#38BDF8"))
        }
    }

    private fun getCardBackground(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 28f
            setColor(Color.parseColor("#0F172A"))
            setStroke(2, Color.parseColor("#1E293B"))
        }
    }

    private fun getRoundedButtonBackground(bgColor: Int, strokeColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 20f
            setColor(bgColor)
            setStroke(1, strokeColor)
        }
    }

    private fun copyDirectly(text: String, message: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("QuickName", text))
        Toast.makeText(this, "$message: '$text'", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        liveUpdateHandler.removeCallbacks(liveUpdateRunnable)
        if (::rootContainer.isInitialized && rootContainer.isAttachedToWindow) {
            windowManager.removeView(rootContainer)
        }
    }
}
