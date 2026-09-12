package dev.alexstyl.sketchbook

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList
import com.onyx.android.sdk.rx.RxManager
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * A deliberately small BOOX raw-ink sample.
 *
 * The SurfaceView is only the firmware's live-ink target. SketchView is the canonical bitmap that
 * owns committed strokes. Keeping those two layers separate is the important part: firmware ink is
 * instant but transient; Android's canvas is persistent and always safe to redraw.
 */
class MainActivity : AppCompatActivity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var inputSurface: SurfaceView
    private lateinit var sketchView: SketchView
    private lateinit var toolBar: ComposeView

    private var helper: TouchHelper? = null
    private var systemInsets = WindowInsetsCompat.CONSUMED
    private var attached = false
    private var resumed = false
    private var strokeInProgress = false
    private var activeTool by mutableStateOf<Tool>(Tool.Pen)
    private val pendingStroke = ArrayList<Sample>(512)

    private val unfreeze = Runnable {
        if (strokeInProgress) return@Runnable
        val currentHelper = helper ?: return@Runnable
        runCatching {
            // ForestNote's post-stroke release: drop only the preview briefly, then restore it.
            // This gives Android/system gestures a clean panel without interrupting live writing.
            currentHelper.setRawDrawingRenderEnabled(false)
            sketchView.invalidate()
            mainHandler.postDelayed({
                if (!strokeInProgress && resumed) {
                    currentHelper.setRawDrawingRenderEnabled(true)
                }
            }, PANEL_SETTLE_MS)
        }
    }

    private val rawCallback = object : RawInputCallback() {
        override fun onBeginRawDrawing(isEraser: Boolean, point: TouchPoint?) {
            strokeInProgress = true
            mainHandler.removeCallbacks(unfreeze)
            pendingStroke.clear()
            point?.let(::addPoint)
        }

        override fun onRawDrawingTouchPointMoveReceived(point: TouchPoint?) {
            point?.let(::addPoint)
        }

        override fun onRawDrawingTouchPointListReceived(points: TouchPointList?) = Unit

        override fun onEndRawDrawing(isEraser: Boolean, point: TouchPoint?) {
            point?.let(::addPoint)
            val stroke = pendingStroke.toList()
            pendingStroke.clear()
            strokeInProgress = false
            sketchView.post {
                sketchView.commit(stroke)
                // Do not toggle firmware capture on every pen-up: it can erase the preview mid-write.
                // Wait until the writer pauses, then briefly release only the render passthrough.
                mainHandler.removeCallbacks(unfreeze)
                mainHandler.postDelayed(unfreeze, UNFREEZE_IDLE_MS)
            }
        }

        override fun onBeginRawErasing(isEraser: Boolean, point: TouchPoint?) = Unit
        override fun onRawErasingTouchPointMoveReceived(point: TouchPoint?) = Unit
        override fun onRawErasingTouchPointListReceived(points: TouchPointList?) = Unit
        override fun onEndRawErasing(isEraser: Boolean, point: TouchPoint?) = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initializeBooxSdk()

        inputSurface = SurfaceView(this)
        sketchView = SketchView(this)
        toolBar = ComposeView(this).apply {
            setContent {
                InkToolbar(
                    activeTool = activeTool,
                    onToolSelected = ::selectTool,
                    onClear = ::clearSketch,
                )
            }
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(
                inputSurface,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            addView(
                sketchView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            addView(
                toolBar,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.TOP or android.view.Gravity.END,
                ).apply {
                    topMargin = dp(16)
                    marginEnd = dp(16)
                },
            )
        }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            systemInsets = insets
            if (attached) inputSurface.post(::configureRawDrawing)
            insets
        }
        ViewCompat.requestApplyInsets(root)

        inputSurface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = Unit

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                inputSurface.post(::configureRawDrawing)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                disableRawDrawing()
            }
        })
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) inputSurface.post(::configureRawDrawing)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        inputSurface.post(::configureRawDrawing)
    }

    override fun onPause() {
        resumed = false
        mainHandler.removeCallbacks(unfreeze)
        disableRawDrawing()
        super.onPause()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        runCatching {
            helper?.setRawDrawingEnabled(false)
            helper?.closeRawDrawing()
        }
        helper = null
        super.onDestroy()
    }

    private fun initializeBooxSdk() {
        runCatching { HiddenApiBypass.addHiddenApiExemptions("") }
        runCatching { RxManager.Builder.initAppContext(applicationContext) }
        runCatching { EpdController.enablePost(1) }
    }

    private fun configureRawDrawing() {
        if (!resumed || inputSurface.width == 0 || inputSurface.height == 0) return
        val limit = Rect(0, 0, inputSurface.width, inputSurface.height)
        val excludes = systemBarExcludes(inputSurface.width, inputSurface.height)

        runCatching {
            val currentHelper = helper ?: TouchHelper.create(inputSurface, rawCallback).also { helper = it }
            currentHelper.setRawDrawingEnabled(false)
            currentHelper.closeRawDrawing()
            currentHelper.setStrokeWidth(STROKE_WIDTH_PX)
            currentHelper.setStrokeColor(Color.BLACK)
            currentHelper.setLimitRect(mutableListOf(limit)).setExcludeRect(excludes)
            currentHelper.openRawDrawing()
            // Critical: leave finger input to Android. Only the stylus belongs to the BOOX pipeline.
            currentHelper.enableFingerTouch(false)
            currentHelper.setRawDrawingRenderEnabled(true)
            // setStroke* and openRawDrawing can silently reactivate raw ink. Re-assert the active
            // tool at the very end, so a non-pen tool never draws underneath its UI.
            applyFirmwareState(currentHelper)
            EpdController.setViewDefaultUpdateMode(inputSurface, UpdateMode.HAND_WRITING_REPAINT_MODE)
            EpdController.setViewDefaultUpdateMode(sketchView, UpdateMode.HAND_WRITING_REPAINT_MODE)
            attached = true
        }.onFailure {
            // The app remains a normal bitmap sketcher if the BOOX SDK is unavailable.
            attached = false
        }
    }

    private fun disableRawDrawing() {
        runCatching {
            helper?.let(::disableFirmware)
        }
    }

    private fun systemBarExcludes(width: Int, height: Int): MutableList<Rect> {
        val bars = systemInsets.getInsets(WindowInsetsCompat.Type.systemBars())
        return mutableListOf<Rect>().apply {
            if (bars.top > 0) add(Rect(0, 0, width, bars.top))
            if (bars.bottom > 0) add(Rect(0, height - bars.bottom, width, height))
            if (bars.left > 0) add(Rect(0, 0, bars.left, height))
            if (bars.right > 0) add(Rect(width - bars.right, 0, width, height))
            toolBar.takeIf { it.isLaidOut }?.let { bar ->
                val location = IntArray(2)
                bar.getLocationInWindow(location)
                add(Rect(location[0], location[1], location[0] + bar.width, location[1] + bar.height))
            }
            // BOOX ignores an empty exclusion list and can retain stale rectangles from a prior session.
            if (isEmpty()) add(Rect(0, 0, 1, 1))
        }
    }

    private fun addPoint(point: TouchPoint) {
        pendingStroke += Sample(point.getX(), point.getY())
    }

    private fun selectTool(tool: Tool) {
        if (activeTool == tool) return
        activeTool = tool
        mainHandler.removeCallbacks(unfreeze)
        sketchView.eraserEnabled = tool == Tool.Eraser
        helper?.let(::applyFirmwareState)
        // A raw drawing session latches exclusion rectangles. Reconfigure after a UI/tool change,
        // but never in the middle of a firmware stroke.
        if (!strokeInProgress) inputSurface.post(::configureRawDrawing)
    }

    private fun clearSketch() {
        mainHandler.removeCallbacks(unfreeze)
        helper?.let(::disableFirmware)
        sketchView.clear()
        inputSurface.post(::configureRawDrawing)
    }

    private fun applyFirmwareState(currentHelper: TouchHelper) {
        if (activeTool == Tool.Pen && resumed) {
            currentHelper.setRawDrawingRenderEnabled(true)
            currentHelper.setRawDrawingEnabled(true)
        } else {
            disableFirmware(currentHelper)
        }
    }

    private fun disableFirmware(currentHelper: TouchHelper) {
        currentHelper.setRawDrawingRenderEnabled(false)
        currentHelper.setRawDrawingEnabled(false)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    @Composable
    private fun InkToolbar(
        activeTool: Tool,
        onToolSelected: (Tool) -> Unit,
        onClear: () -> Unit,
    ) {
        Surface(shadowElevation = 4.dp) {
            Column(Modifier.width(92.dp)) {
                ToolButton("PEN", activeTool == Tool.Pen) { onToolSelected(Tool.Pen) }
                ToolButton("ERASE", activeTool == Tool.Eraser) { onToolSelected(Tool.Eraser) }
                ToolButton("CLEAR", selected = false, onClick = onClear)
            }
        }
    }

    @Composable
    private fun ToolButton(label: String, selected: Boolean, onClick: () -> Unit) {
        Button(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (selected) ComposeColor.Black else ComposeColor.White,
                contentColor = if (selected) ComposeColor.White else ComposeColor.Black,
            ),
        ) { Text(label) }
    }

    private data class Sample(val x: Float, val y: Float)

    private sealed interface Tool {
        data object Pen : Tool
        data object Eraser : Tool
    }

    private class SketchView(context: android.content.Context) : View(context) {
        var eraserEnabled = false
        private var bitmap: Bitmap? = null
        private var bitmapCanvas: Canvas? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            strokeWidth = STROKE_WIDTH_PX
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            style = Paint.Style.STROKE
        }
        private val eraserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = ERASER_WIDTH_PX
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            style = Paint.Style.STROKE
            xfermode = android.graphics.PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        private var previousX = 0f
        private var previousY = 0f

        override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
            if (width <= 0 || height <= 0) return
            val replacement = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap?.let { old -> Canvas(replacement).drawBitmap(old, 0f, 0f, null); old.recycle() }
            bitmap = replacement
            bitmapCanvas = Canvas(replacement)
        }

        fun commit(samples: List<Sample>) {
            val canvas = bitmapCanvas ?: return
            when (samples.size) {
                0 -> return
                1 -> canvas.drawPoint(samples.first().x, samples.first().y, paint)
                else -> samples.zipWithNext().forEach { (from, to) ->
                    canvas.drawLine(from.x, from.y, to.x, to.y, paint)
                }
            }
            invalidate()
        }

        fun clear() {
            bitmapCanvas?.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            invalidate()
        }

        override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
            val toolType = event.getToolType(0)
            val isStylus = toolType == android.view.MotionEvent.TOOL_TYPE_STYLUS ||
                toolType == android.view.MotionEvent.TOOL_TYPE_ERASER
            if (!eraserEnabled || !isStylus) return false
            val canvas = bitmapCanvas ?: return false
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    previousX = event.x
                    previousY = event.y
                    canvas.drawPoint(previousX, previousY, eraserPaint)
                    invalidate()
                    return true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    canvas.drawLine(previousX, previousY, event.x, event.y, eraserPaint)
                    previousX = event.x
                    previousY = event.y
                    invalidate()
                    return true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    canvas.drawLine(previousX, previousY, event.x, event.y, eraserPaint)
                    invalidate()
                    return true
                }
            }
            return false
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.WHITE)
            bitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        }
    }

    private companion object {
        const val STROKE_WIDTH_PX = 5f
        const val ERASER_WIDTH_PX = 42f
        const val UNFREEZE_IDLE_MS = 700L
        const val PANEL_SETTLE_MS = 300L
    }
}
