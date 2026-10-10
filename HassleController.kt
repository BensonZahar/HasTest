package com.ynk.virtualdisplay.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.ynk.virtualdisplay.data.repository.IDisplayRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Одно плавающее окно поверх всех приложений, в котором рисуется один виртуальный дисплей.
 *
 * Это обычное оверлей-окно нашего приложения (TYPE_APPLICATION_OVERLAY), а не freeform-окно системы,
 * поэтому лимит MIUI "не больше двух плавающих окон" на него не распространяется.
 *
 * Закрытие окна НЕ убивает дисплей и игру: дисплей получает запасную поверхность (ImageReader в сервисе)
 * и продолжает работать в фоне. Окно можно показать заново.
 */
@SuppressLint("ClickableViewAccessibility", "SetTextI18n")
class OverlayWindow(
    private val context: Context,
    private val repository: IDisplayRepository,
    private val scope: CoroutineScope,
    val displayId: Int,
    title: String,
    private val vdWidth: Int,
    private val vdHeight: Int,
    startX: Int,
    startY: Int,
    startWidthPx: Int,
    private val onClosed: (Int) -> Unit,
) {

    companion object {
        private const val TAG = "OverlayWindow"
    }

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * density).toInt()

    private val headerHeight = dp(28)
    private val minWidth = dp(150)
    private val maxWidth = context.resources.displayMetrics.widthPixels

    private var widthPx = startWidthPx.coerceIn(minWidth, maxWidth)
    private var collapsed = false
    private var touchEnabled = true
    private var attached = false
    private var surface: Surface? = null

    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.BLACK)
    }
    private val header = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(0xE6202020.toInt())
    }
    private val titleView = TextView(context)
    private val touchButton = TextView(context)
    private val collapseButton = TextView(context)
    private val closeButton = TextView(context)
    private val content = FrameLayout(context)
    private val textureView = TextureView(context)
    private val resizeHandle = View(context)

    private val params = WindowManager.LayoutParams(
        widthPx,
        headerHeight + contentHeight(widthPx),
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = startX
        y = startY
    }

    private fun contentHeight(w: Int): Int = (w.toFloat() * vdHeight / vdWidth).toInt().coerceAtLeast(dp(40))

    init {
        titleView.apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 12f
            maxLines = 1
            setPadding(dp(8), 0, dp(8), 0)
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        setupButton(touchButton, "ТАЧ") { toggleTouch() }
        setupButton(collapseButton, "—") { toggleCollapsed() }
        setupButton(closeButton, "✕") { close() }
        refreshTouchButton()

        header.addView(touchButton, buttonParams())
        header.addView(collapseButton, buttonParams())
        header.addView(closeButton, buttonParams())
        setupHeaderDrag()

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                st.setDefaultBufferSize(vdWidth, vdHeight)
                val s = Surface(st)
                surface = s
                scope.launch(Dispatchers.Main.immediate) {
                    val r = repository.setDisplaySurface(displayId, s)
                    r.exceptionOrNull()?.let { Log.e(TAG, "setDisplaySurface($displayId) failed", it) }
                }
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                val old = surface
                surface = null
                scope.launch(Dispatchers.Main.immediate) {
                    try {
                        // null -> сервис вернёт дисплею запасную поверхность, игра продолжит работать
                        repository.setDisplaySurface(displayId, null)
                    } finally {
                        old?.release()
                    }
                }
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        textureView.setOnTouchListener { v, event -> forwardTouch(v, event) }

        content.addView(
            textureView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        resizeHandle.setBackgroundColor(0x80FFFFFF.toInt())
        content.addView(
            resizeHandle,
            FrameLayout.LayoutParams(dp(26), dp(26), Gravity.BOTTOM or Gravity.END),
        )
        setupResizeHandle()

        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, headerHeight))
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun buttonParams() = LinearLayout.LayoutParams(dp(40), ViewGroup.LayoutParams.MATCH_PARENT)

    private fun setupButton(view: TextView, label: String, onClick: () -> Unit) {
        view.apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 11f
            gravity = Gravity.CENTER
            setOnClickListener { onClick() }
        }
    }

    private fun refreshTouchButton() {
        touchButton.setTextColor(if (touchEnabled) 0xFF66BB6A.toInt() else 0xFF888888.toInt())
    }

    private fun toggleTouch() {
        touchEnabled = !touchEnabled
        refreshTouchButton()
    }

    private fun toggleCollapsed() {
        collapsed = !collapsed
        // GONE уничтожает SurfaceTexture -> дисплей уходит на запасную поверхность (экономит батарею),
        // при разворачивании окно снова подключается к дисплею.
        content.visibility = if (collapsed) View.GONE else View.VISIBLE
        params.height = if (collapsed) headerHeight else headerHeight + contentHeight(widthPx)
        updateLayout()
    }

    private fun setupHeaderDrag() {
        var downRawX = 0f
        var downRawY = 0f
        var startPosX = 0
        var startPosY = 0
        header.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = e.rawX
                    downRawY = e.rawY
                    startPosX = params.x
                    startPosY = params.y
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startPosX + (e.rawX - downRawX).toInt()
                    params.y = startPosY + (e.rawY - downRawY).toInt()
                    updateLayout()
                }
            }
            true
        }
    }

    private fun setupResizeHandle() {
        var downRawX = 0f
        var startWidth = 0
        resizeHandle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = e.rawX
                    startWidth = widthPx
                }
                MotionEvent.ACTION_MOVE -> {
                    widthPx = (startWidth + (e.rawX - downRawX).toInt()).coerceIn(minWidth, maxWidth)
                    params.width = widthPx
                    params.height = if (collapsed) headerHeight else headerHeight + contentHeight(widthPx)
                    updateLayout()
                }
            }
            true
        }
    }

    /** Координаты касания в окне -> координаты виртуального дисплея, затем инъекция через Shizuku. */
    private fun forwardTouch(view: View, event: MotionEvent): Boolean {
        if (!touchEnabled) return true
        val vw = view.width.toFloat()
        val vh = view.height.toFloat()
        if (vw <= 0f || vh <= 0f) return true
        val sx = vdWidth / vw
        val sy = vdHeight / vh

        val count = event.pointerCount
        val props = Array(count) { MotionEvent.PointerProperties() }
        val coords = Array(count) { MotionEvent.PointerCoords() }
        for (i in 0 until count) {
            event.getPointerProperties(i, props[i])
            event.getPointerCoords(i, coords[i])
            coords[i].x = (coords[i].x * sx).coerceIn(0f, vdWidth.toFloat())
            coords[i].y = (coords[i].y * sy).coerceIn(0f, vdHeight.toFloat())
        }
        val mapped = MotionEvent.obtain(
            event.downTime,
            event.eventTime,
            event.action,
            count,
            props,
            coords,
            event.metaState,
            event.buttonState,
            event.xPrecision,
            event.yPrecision,
            0,
            event.edgeFlags,
            InputDevice.SOURCE_TOUCHSCREEN,
            event.flags,
        )
        scope.launch(Dispatchers.Main.immediate) {
            try {
                repository.injectInputWithDisplayId(mapped, displayId)
            } finally {
                mapped.recycle()
            }
        }
        return true
    }

    private fun updateLayout() {
        if (!attached) return
        try {
            wm.updateViewLayout(root, params)
        } catch (e: Exception) {
            Log.w(TAG, "updateViewLayout failed", e)
        }
    }

    fun show(): Boolean {
        if (attached) return true
        return try {
            wm.addView(root, params)
            attached = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "addView failed (нет разрешения 'Поверх других окон'?)", e)
            false
        }
    }

    /** Убрать окно с экрана (дисплей и игра продолжают работать). */
    fun remove() {
        if (!attached) return
        attached = false
        try {
            wm.removeView(root)
        } catch (e: Exception) {
            Log.w(TAG, "removeView failed", e)
        }
    }

    private fun close() {
        remove()
        onClosed(displayId)
    }
}
