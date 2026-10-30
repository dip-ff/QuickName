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
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.quickname.floating.QuickNameApp
import com.quickname.floating.data.GeneratedName
import com.quickname.floating.data.NameRepository
import com.quickname.floating.data.PreferencesManager
import com.quickname.floating.ui.MainActivity
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
        val rootNode = rootInActiveWindow ?: return fallbackToClipboard(name, "No active window")

        val editableNodes = mutableListOf<AccessibilityNodeInfo>()
        findEditableNodes(rootNode, editableNodes)

        if (editableNodes.isEmpty()) {
            return fallbackToClipboard(name, "No editable fields found")
        }

        var focusedIndex = editableNodes.indexOfFirst { it.isFocused }
        if (focusedIndex == -1) focusedIndex = 0

        var filledCount = 0

        if (name.firstName.isNotEmpty() && focusedIndex < editableNodes.size) {
            val firstNode = editableNodes[focusedIndex]
            if (setText(firstNode, name.firstName)) filledCount++
        }

        if (name.lastName.isNotEmpty()) {
            val nextIndex = focusedIndex + 1
            if (nextIndex < editableNodes.size) {
                val secondNode = editableNodes[nextIndex]
                if (setText(secondNode, name.lastName)) filledCount++
            } else if (filledCount == 0 && focusedIndex < editableNodes.size) {
                setText(editableNodes[focusedIndex], name.fullName)
                filledCount++
            }
        }

        return if (filledCount > 0) {
            showToast("Filled: ${name.fullName}")
            true
        } else {
            fallbackToClipboard(name, "Autofill rejected by app")
        }
    }

    private fun findEditableNodes(node: AccessibilityNodeInfo?, result: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        if (node.isEditable && node.isVisibleToUser) {
            result.add(node)
        }
        for (i in 0 until node.childCount) {
            findEditableNodes(node.getChild(i), result)
        }
    }

    private fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    private fun fallbackToClipboard(name: GeneratedName, reason: String): Boolean {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = ClipData.newPlainText("QuickName", name.fullName)
        clipboard?.setPrimaryClip(clip)

        Handler(Looper.getMainLooper()).post {
            Toast.makeText(this, "$reason. Copied '${name.fullName}'", Toast.LENGTH_SHORT).show()
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
    private lateinit var floatingButton: TextView
    private lateinit var layoutParams: WindowManager.LayoutParams
    private lateinit var prefs: PreferencesManager
    private lateinit var repository: NameRepository

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
            .setContentTitle("QuickName Active")
            .setContentText("Tap QN button to autofill")
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
            sizePx,
            sizePx,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 200
        }

        floatingButton = TextView(this).apply {
            text = "QN"
            setTextColor(Color.WHITE)
            textSize = (sizeDp * 0.32f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            elevation = 16f
            background = getButtonBackground(prefs.buttonColorHex)
        }

        setupTouchListener()
        windowManager.addView(floatingButton, layoutParams)
    }

    private fun getButtonBackground(baseColorHex: Long): GradientDrawable {
        val primary = baseColorHex.toInt()
        val darkSecondary = Color.rgb(
            (Color.red(primary) * 0.45).toInt(),
            (Color.green(primary) * 0.45).toInt(),
            (Color.blue(primary) * 0.7).toInt()
        )
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(primary, darkSecondary)
        ).apply {
            shape = GradientDrawable.OVAL
            setStroke(3, Color.parseColor("#44FFFFFF"))
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
                        windowManager.updateViewLayout(floatingButton, layoutParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
                    if (!isDragging) {
                        onButtonClicked()
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
        val targetX = if (currentX + viewWidth / 2 < screenWidth / 2) 20 else screenWidth - viewWidth - 20

        val animator = ValueAnimator.ofInt(currentX, targetX).apply {
            duration = 250
            interpolator = OvershootInterpolator(1.2f)
            addUpdateListener { animation ->
                layoutParams.x = animation.animatedValue as Int
                if (floatingButton.isAttachedToWindow) {
                    windowManager.updateViewLayout(floatingButton, layoutParams)
                }
            }
        }
        animator.start()
    }

    private fun onButtonClicked() {
        floatingButton.animate().scaleX(1.25f).scaleY(1.25f).setDuration(90).withEndAction {
            floatingButton.animate().scaleX(1.0f).scaleY(1.0f).setDuration(90).start()
        }.start()

        val generated = repository.generateName()
        val accessibility = QuickNameAccessibilityService.instance
        if (accessibility != null) {
            accessibility.performAutoFill(generated)
        } else {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("QuickName", generated.fullName))
            Toast.makeText(this, "Copied '${generated.fullName}'", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        if (::floatingButton.isInitialized && floatingButton.isAttachedToWindow) {
            windowManager.removeView(floatingButton)
        }
    }
}
