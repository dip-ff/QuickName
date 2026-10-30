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
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.OvershootInterpolator
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
    private lateinit var menuContainer: LinearLayout
    private lateinit var nameActionButton: TextView
    private lateinit var twoFactorActionButton: TextView
    private lateinit var layoutParams: WindowManager.LayoutParams
    private lateinit var prefs: PreferencesManager
    private lateinit var repository: NameRepository

    private val autoCloseHandler = Handler(Looper.getMainLooper())
    private val closeMenuRunnable = Runnable { collapseMenu() }

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
            .setContentTitle("QuickName & 2FA Active")
            .setContentText("Tap QN to fill Name or 2FA")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(1001, notification)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createFloatingView() {
        val sizeDp = prefs.buttonSizeDp
        val density = resources.displayMetrics.density
        val sizePx = (sizeDp * density).toInt()

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

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
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // 1. Main Circular Floating QN Button
        floatingButton = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(sizePx, sizePx)
            text = "QN"
            setTextColor(Color.WHITE)
            textSize = (sizeDp * 0.32f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            elevation = 16f
            background = getPillBackground(prefs.buttonColorHex.toInt(), isCircle = true)
        }

        // 2. Expandable Menu Container
        menuContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            setPadding(12, 0, 0, 0)
        }

        // Name Button Option
        nameActionButton = TextView(this).apply {
            text = "👤 Name"
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(28, 20, 28, 20)
            elevation = 14f
            background = getPillBackground(Color.parseColor("#1E293B"), isCircle = false)
            setOnClickListener {
                collapseMenu()
                val generated = repository.generateName()
                QuickNameAccessibilityService.instance?.performAutoFill(generated)
                    ?: copyDirectly(generated.fullName, "Accessibility disabled. Copied Name")
            }
        }

        // 2FA Button Option
        twoFactorActionButton = TextView(this).apply {
            val marginParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(12, 0, 0, 0) }
            layoutParams = marginParams
            text = "🔑 2FA"
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(28, 20, 28, 20)
            elevation = 14f
            background = getPillBackground(Color.parseColor("#0F172A"), isCircle = false)
            setOnClickListener {
                collapseMenu()
                val sharedPrefs = getSharedPreferences("quickname_prefs", Context.MODE_PRIVATE)
                val secretKey = sharedPrefs.getString("saved_2fa_secret", "") ?: ""
                val code = TotpGenerator.generate2FACode(secretKey)

                if (code == "------" || code.isBlank()) {
                    Toast.makeText(this@FloatingButtonService, "2FA Key not set! Enter key in app first.", Toast.LENGTH_SHORT).show()
                } else {
                    QuickNameAccessibilityService.instance?.fill2FACode(code)
                        ?: copyDirectly(code, "Copied 2FA Code")
                }
            }
        }

        menuContainer.addView(nameActionButton)
        menuContainer.addView(twoFactorActionButton)

        rootContainer.addView(floatingButton)
        rootContainer.addView(menuContainer)

        setupTouchListener()
        windowManager.addView(rootContainer, layoutParams)
    }

    private fun getPillBackground(color: Int, isCircle: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            shape = if (isCircle) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
            if (!isCircle) cornerRadius = 30f
            setColor(color)
            setStroke(2, Color.parseColor("#44FFFFFF"))
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
                        toggleMenu()
                    } else {
                        snapToEdge()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun toggleMenu() {
        if (menuContainer.visibility == View.VISIBLE) {
            collapseMenu()
        } else {
            // Update 2FA code preview text
            val sharedPrefs = getSharedPreferences("quickname_prefs", Context.MODE_PRIVATE)
            val secretKey = sharedPrefs.getString("saved_2fa_secret", "") ?: ""
            val code = TotpGenerator.generate2FACode(secretKey)
            twoFactorActionButton.text = if (code != "------") "🔑 2FA: $code" else "🔑 2FA"

            menuContainer.visibility = View.VISIBLE
            menuContainer.alpha = 0f
            menuContainer.animate().alpha(1f).setDuration(200).start()

            // Auto close after 4 seconds
            autoCloseHandler.removeCallbacks(closeMenuRunnable)
            autoCloseHandler.postDelayed(closeMenuRunnable, 4500)
        }
    }

    private fun collapseMenu() {
        if (menuContainer.visibility == View.VISIBLE) {
            menuContainer.animate().alpha(0f).setDuration(150).withEndAction {
                menuContainer.visibility = View.GONE
            }.start()
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

    private fun copyDirectly(text: String, message: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("QuickName", text))
        Toast.makeText(this, "$message: '$text'", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        autoCloseHandler.removeCallbacks(closeMenuRunnable)
        if (::rootContainer.isInitialized && rootContainer.isAttachedToWindow) {
            windowManager.removeView(rootContainer)
        }
    }
}
