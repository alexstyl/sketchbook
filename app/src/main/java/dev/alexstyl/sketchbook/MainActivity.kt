package dev.alexstyl.sketchbook

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode
import com.onyx.android.sdk.device.Device
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList
import com.onyx.android.sdk.pen.style.StrokeStyle
import com.onyx.android.sdk.rx.RxManager
import org.lsposed.hiddenapibypass.HiddenApiBypass
import com.composeunstyled.UnstyledButton
import dev.alexstyl.sketchbook.iconography.Eraser
import dev.alexstyl.sketchbook.iconography.FilePlus
import dev.alexstyl.sketchbook.iconography.Icons
import dev.alexstyl.sketchbook.iconography.PenLine
import kotlin.math.ln

/** Pen-only BOOX baseline: native Fountain preview with a retained bitmap commit. */
class MainActivity : AppCompatActivity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var inputSurface: SurfaceView
    private lateinit var sketchView: SketchView
    private lateinit var toolDock: ComposeView
    private lateinit var newSketchButton: ComposeView
    private var helper: TouchHelper? = null
    private var systemInsets = WindowInsetsCompat.CONSUMED
    private var attached = false
    private var resumed = false
    private var strokeInProgress = false
    private var maxTouchPressure = DEFAULT_MAX_TOUCH_PRESSURE
    private var activeTool by mutableStateOf(Tool.Pen)
    private var strokeTool = Tool.Pen
    private val pendingStroke = ArrayList<Sample>(512)

    private val unfreeze = Runnable {
        if (strokeInProgress) return@Runnable
        val currentHelper = helper ?: return@Runnable
        runCatching {
            currentHelper.setRawDrawingRenderEnabled(false)
            sketchView.invalidate()
            mainHandler.postDelayed({
                if (!strokeInProgress && resumed) currentHelper.setRawDrawingRenderEnabled(true)
            }, PANEL_SETTLE_MS)
        }
    }

    private val rawCallback = object : RawInputCallback() {
        override fun onBeginRawDrawing(isEraser: Boolean, point: TouchPoint?) {
            // BOOX reports erasing through this generic callback on some firmware builds and does
            // not reliably set isEraser. The selected tool is the canonical routing source.
            beginStroke(activeTool, point)
        }

        override fun onRawDrawingTouchPointMoveReceived(point: TouchPoint?) {
            point?.let(::addPoint)
        }

        override fun onRawDrawingTouchPointListReceived(points: TouchPointList?) = Unit

        override fun onEndRawDrawing(isEraser: Boolean, point: TouchPoint?) {
            endStroke(point)
        }

        override fun onBeginRawErasing(isEraser: Boolean, point: TouchPoint?) {
            beginStroke(Tool.Eraser, point)
        }
        override fun onRawErasingTouchPointMoveReceived(point: TouchPoint?) {
            point?.let(::addPoint)
        }
        override fun onRawErasingTouchPointListReceived(points: TouchPointList?) = Unit
        override fun onEndRawErasing(isEraser: Boolean, point: TouchPoint?) {
            endStroke(point)
        }
    }

    private fun beginStroke(tool: Tool, point: TouchPoint?) {
        strokeInProgress = true
        strokeTool = tool
        mainHandler.removeCallbacks(unfreeze)
        pendingStroke.clear()
        point?.let(::addPoint)
    }

    private fun endStroke(point: TouchPoint?) {
        point?.let(::addPoint)
        val stroke = pendingStroke.toList()
        val tool = strokeTool
        pendingStroke.clear()
        strokeInProgress = false
        sketchView.post {
            if (tool == Tool.Eraser) sketchView.erase(stroke) else sketchView.commit(stroke)
            mainHandler.removeCallbacks(unfreeze)
            mainHandler.postDelayed(unfreeze, UNFREEZE_IDLE_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initializeBooxSdk()
        inputSurface = SurfaceView(this)
        sketchView = SketchView(this)
        toolDock = ComposeView(this).apply {
            setContent { ToolDock(activeTool = activeTool, onToolSelected = ::selectTool) }
        }
        newSketchButton = ComposeView(this).apply {
            setContent { NewSketchButton(onNewSketch = ::confirmNewSketch) }
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(inputSurface, FrameLayout.LayoutParams(-1, -1))
            addView(sketchView, FrameLayout.LayoutParams(-1, -1))
            addView(
                toolDock,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.END,
                ).apply { marginEnd = dp(16) },
            )
            addView(
                newSketchButton,
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
        toolDock.doOnLayout { inputSurface.post(::configureRawDrawing) }
        newSketchButton.doOnLayout { inputSurface.post(::configureRawDrawing) }
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
        runCatching { EpdController.getMaxTouchPressure() }
            .getOrNull()?.takeIf { it > 0f }?.let { maxTouchPressure = it }
    }

    private fun configureRawDrawing() {
        if (!resumed || inputSurface.width == 0 || inputSurface.height == 0) return
        val limit = Rect(0, 0, inputSurface.width, inputSurface.height)
        val excludes = systemBarExcludes(inputSurface.width, inputSurface.height)
        runCatching {
            val currentHelper = helper ?: TouchHelper.create(inputSurface, rawCallback).also { helper = it }
            currentHelper.setRawDrawingEnabled(false)
            currentHelper.closeRawDrawing()
            if (activeTool == Tool.Pen) {
                // Keep the known-good pen pipeline exactly as the pen baseline.
                currentHelper.setStrokeWidth(livePenWidth())
                currentHelper.setLimitRect(mutableListOf(limit)).setExcludeRect(excludes)
                currentHelper.openRawDrawing()
                currentHelper.setBrushRawDrawingEnabled(true)
                currentHelper
                    .setStrokeStyle(TouchHelper.STROKE_STYLE_FOUNTAIN)
                    .setStrokeColor(Color.BLACK)
                    .setStrokeWidth(livePenWidth())
            } else {
                currentHelper.setStrokeWidth(ERASER_WIDTH_PX)
                currentHelper.setStrokeColor(Color.BLACK)
                currentHelper.setLimitRect(mutableListOf(limit)).setExcludeRect(excludes)
                currentHelper.openRawDrawing()
                currentHelper.setBrushRawDrawingEnabled(true)
                currentHelper.setEraserRawDrawingEnabled(true, StrokeStyle.SOFT_ERASER)
                currentHelper.setStrokeStyle(StrokeStyle.SOFT_ERASER)
                currentHelper.setStrokeWidth(ERASER_WIDTH_PX)
                Device.currentDevice().setStrokeParameters(
                    StrokeStyle.SOFT_ERASER,
                    floatArrayOf(ERASER_WIDTH_PX, SOFT_ERASER_OPACITY, SOFT_ERASER_BLACK_OPACITY),
                )
            }
            currentHelper.enableFingerTouch(false)
            currentHelper.setRawDrawingRenderEnabled(true)
            currentHelper.setRawDrawingEnabled(true)
            EpdController.setViewDefaultUpdateMode(inputSurface, UpdateMode.HAND_WRITING_REPAINT_MODE)
            EpdController.setViewDefaultUpdateMode(sketchView, UpdateMode.HAND_WRITING_REPAINT_MODE)
            attached = true
        }.onFailure { attached = false }
    }

    private fun disableRawDrawing() = runCatching { helper?.let(::disableFirmware) }

    private fun systemBarExcludes(width: Int, height: Int): MutableList<Rect> {
        val bars = systemInsets.getInsets(WindowInsetsCompat.Type.systemBars())
        return mutableListOf<Rect>().apply {
            if (bars.top > 0) add(Rect(0, 0, width, bars.top))
            if (bars.bottom > 0) add(Rect(0, height - bars.bottom, width, height))
            if (bars.left > 0) add(Rect(0, 0, bars.left, height))
            if (bars.right > 0) add(Rect(width - bars.right, 0, width, height))
            if (toolDock.isLaidOut) {
                val location = IntArray(2)
                toolDock.getLocationInWindow(location)
                add(Rect(location[0], location[1], location[0] + toolDock.width, location[1] + toolDock.height))
            }
            if (newSketchButton.isLaidOut) {
                val location = IntArray(2)
                newSketchButton.getLocationInWindow(location)
                add(
                    Rect(
                        location[0],
                        location[1],
                        location[0] + newSketchButton.width,
                        location[1] + newSketchButton.height,
                    ),
                )
            }
            if (isEmpty()) add(Rect(0, 0, 1, 1))
        }
    }

    private fun addPoint(point: TouchPoint) {
        pendingStroke += Sample(
            point.getX(), point.getY(),
            (point.getPressure() / maxTouchPressure).coerceIn(0f, 1f),
        )
    }

    private fun disableFirmware(currentHelper: TouchHelper) {
        currentHelper.setRawDrawingRenderEnabled(false)
        currentHelper.setRawDrawingEnabled(false)
    }

    private fun livePenWidth(): Float = pressureWidth(LIVE_PEN_REFERENCE_PRESSURE)

    private fun selectTool(tool: Tool) {
        if (activeTool == tool) return
        activeTool = tool
        if (!strokeInProgress) {
            runCatching {
                helper?.let(::disableFirmware)
                helper?.closeRawDrawing()
            }
            // A fresh helper ensures the Fountain pen never inherits native eraser state.
            helper = null
            inputSurface.post(::configureRawDrawing)
        }
    }

    private fun confirmNewSketch() {
        AlertDialog.Builder(this)
            .setTitle("New sketch?")
            .setMessage("This starts a new blank sketch.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("New sketch") { _, _ -> clearSketch() }
            .show()
    }

    private fun clearSketch() {
        mainHandler.removeCallbacks(unfreeze)
        helper?.let(::disableFirmware)
        sketchView.clear()
        inputSurface.post(::configureRawDrawing)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    @Composable
    private fun ToolDock(activeTool: Tool, onToolSelected: (Tool) -> Unit) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ToolButton(Icons.PenLine, "Pen", activeTool == Tool.Pen) { onToolSelected(Tool.Pen) }
            ToolButton(Icons.Eraser, "Eraser", activeTool == Tool.Eraser) { onToolSelected(Tool.Eraser) }
        }
    }

    @Composable
    private fun ToolButton(
        icon: ImageVector,
        contentDescription: String,
        selected: Boolean,
        onClick: () -> Unit,
    ) {
        val shape = RoundedCornerShape(14.dp)
        val interactions = remember { MutableInteractionSource() }
        val pressed by interactions.collectIsPressedAsState()
        val scale by animateFloatAsState(
            targetValue = if (pressed) 0.9f else 1f,
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
            label = "tool button scale",
        )
        UnstyledButton(
            onClick = onClick,
            contentPadding = PaddingValues(14.dp),
            interactionSource = interactions,
            indication = LocalIndication.current,
            modifier = Modifier
                .size(64.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(shape)
                .background(if (selected) ComposeColor.Black else ComposeColor.White)
                .border(1.dp, ComposeColor.Black, shape),
        ) {
            Image(
                painter = rememberVectorPainter(icon),
                contentDescription = contentDescription,
                colorFilter = ColorFilter.tint(if (selected) ComposeColor.White else ComposeColor.Black),
                modifier = Modifier.size(32.dp),
            )
        }
    }

    @Composable
    private fun NewSketchButton(onNewSketch: () -> Unit) {
        ToolButton(
            icon = Icons.FilePlus,
            contentDescription = "New sketch",
            selected = false,
            onClick = onNewSketch,
        )
    }

    private data class Sample(val x: Float, val y: Float, val pressure: Float)

    private enum class Tool { Pen, Eraser }

    private class SketchView(context: android.content.Context) : View(context) {
        private var bitmap: Bitmap? = null
        private var bitmapCanvas: Canvas? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            strokeWidth = MAX_STROKE_WIDTH_PX
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            style = Paint.Style.STROKE
        }
        private val eraserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = ERASER_WIDTH_PX
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            style = Paint.Style.STROKE
        }

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
                1 -> {
                    val sample = samples.first()
                    paint.strokeWidth = pressureWidth(sample.pressure)
                    canvas.drawPoint(sample.x, sample.y, paint)
                }
                else -> samples.zipWithNext().forEach { (from, to) ->
                    paint.strokeWidth = pressureWidth((from.pressure + to.pressure) / 2f)
                    canvas.drawLine(from.x, from.y, to.x, to.y, paint)
                }
            }
            invalidate()
        }

        fun erase(samples: List<Sample>) {
            val canvas = bitmapCanvas ?: return
            when (samples.size) {
                0 -> return
                1 -> canvas.drawPoint(samples.first().x, samples.first().y, eraserPaint)
                else -> samples.zipWithNext().forEach { (from, to) ->
                    canvas.drawLine(from.x, from.y, to.x, to.y, eraserPaint)
                }
            }
            invalidate()
        }

        fun clear() {
            bitmapCanvas?.drawColor(Color.WHITE)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.WHITE)
            bitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        }
    }

    private companion object {
        const val MIN_STROKE_WIDTH_PX = 2f
        const val MAX_STROKE_WIDTH_PX = 5f
        const val LIVE_PEN_REFERENCE_PRESSURE = 0.25f
        const val DEFAULT_MAX_TOUCH_PRESSURE = 4095f
        const val LN_4 = 1.3862944f
        const val UNFREEZE_IDLE_MS = 700L
        const val PANEL_SETTLE_MS = 300L
        const val ERASER_WIDTH_PX = 42f
        const val SOFT_ERASER_OPACITY = 0.5f
        const val SOFT_ERASER_BLACK_OPACITY = 0.1f

        fun pressureWidth(pressure: Float): Float =
            MIN_STROKE_WIDTH_PX + (MAX_STROKE_WIDTH_PX - MIN_STROKE_WIDTH_PX) *
                (ln(3f * pressure.coerceIn(0f, 1f) + 1f) / LN_4)
    }
}
