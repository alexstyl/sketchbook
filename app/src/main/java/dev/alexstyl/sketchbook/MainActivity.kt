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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.Executors
import com.composeunstyled.UnstyledButton
import dev.alexstyl.sketchbook.iconography.Eraser
import dev.alexstyl.sketchbook.iconography.FilePlus
import dev.alexstyl.sketchbook.iconography.Icons
import dev.alexstyl.sketchbook.iconography.PenLine
import kotlin.math.ln

/** Pen-only BOOX baseline: native Fountain preview with a retained bitmap commit. */
class MainActivity : AppCompatActivity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val persistenceExecutor = Executors.newSingleThreadExecutor()
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
    private val documentFile by lazy { File(filesDir, DOCUMENT_FILE_NAME) }
    private val saveDocument = Runnable {
        if (!::sketchView.isInitialized) return@Runnable
        val document = sketchView.snapshot()
        persistenceExecutor.execute { SketchStore.write(documentFile, document) }
    }

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
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()
        initializeBooxSdk()
        inputSurface = SurfaceView(this)
        sketchView = SketchView(this, ::scheduleDocumentSave).apply {
            SketchStore.read(documentFile)?.let(::restore)
        }
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
        if (hasFocus) {
            hideSystemBars()
            inputSurface.post(::configureRawDrawing)
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        hideSystemBars()
        inputSurface.post(::configureRawDrawing)
    }

    override fun onPause() {
        resumed = false
        persistDocumentNow()
        mainHandler.removeCallbacks(unfreeze)
        disableRawDrawing()
        super.onPause()
    }

    override fun onDestroy() {
        persistDocumentNow()
        mainHandler.removeCallbacksAndMessages(null)
        runCatching {
            helper?.setRawDrawingEnabled(false)
            helper?.closeRawDrawing()
        }
        helper = null
        persistenceExecutor.shutdown()
        super.onDestroy()
    }

    private fun initializeBooxSdk() {
        runCatching { HiddenApiBypass.addHiddenApiExemptions("") }
        runCatching { RxManager.Builder.initAppContext(applicationContext) }
        runCatching { EpdController.enablePost(1) }
        runCatching { EpdController.getMaxTouchPressure() }
            .getOrNull()?.takeIf { it > 0f }?.let { maxTouchPressure = it }
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
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
        pendingStroke += sketchView.documentPoint(
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

    private fun scheduleDocumentSave() {
        mainHandler.removeCallbacks(saveDocument)
        mainHandler.postDelayed(saveDocument, SAVE_DEBOUNCE_MS)
    }

    private fun persistDocumentNow() {
        if (!::sketchView.isInitialized) return
        mainHandler.removeCallbacks(saveDocument)
        val document = sketchView.snapshot()
        runCatching {
            persistenceExecutor.submit { SketchStore.write(documentFile, document) }.get()
        }
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

    private class SketchView(
        context: android.content.Context,
        private val onDocumentChanged: () -> Unit,
    ) : View(context) {
        private var bitmap: Bitmap? = null
        private var bitmapCanvas: Canvas? = null
        private val strokes = mutableListOf<Stroke>()
        private var viewportOffsetX = 0f
        private var viewportOffsetY = 0f
        private var viewportScale = 1f
        private var cachedOffsetX = Float.NaN
        private var cachedOffsetY = Float.NaN
        private var cachedScale = Float.NaN
        private var trackingFingerGesture = false
        private var panning = false
        private var gestureStartDistance = 1f
        private var gestureStartScale = 1f
        private var gestureFocusDocumentX = 0f
        private var gestureFocusDocumentY = 0f
        private var twoFingerTapCandidate = false
        private var lastTwoFingerTapAt = 0L
        private var lastTwoFingerTapX = 0f
        private var lastTwoFingerTapY = 0f
        private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
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
            bitmap?.recycle()
            bitmap = replacement
            bitmapCanvas = Canvas(replacement)
            cachedOffsetX = Float.NaN
            cachedOffsetY = Float.NaN
            cachedScale = Float.NaN
        }

        fun documentPoint(screenX: Float, screenY: Float, pressure: Float): Sample =
            Sample(
                (screenX - viewportOffsetX) / viewportScale,
                (screenY - viewportOffsetY) / viewportScale,
                pressure,
            )

        fun commit(samples: List<Sample>) {
            appendStroke(Stroke(samples, isEraser = false, scaleAtCreation = viewportScale))
        }

        fun erase(samples: List<Sample>) {
            appendStroke(Stroke(samples, isEraser = true, scaleAtCreation = viewportScale))
        }

        private fun appendStroke(stroke: Stroke) {
            if (stroke.samples.isEmpty()) return
            // Reconcile a moved viewport before adding the new stroke. That keeps the finished
            // stroke on the direct bitmap path exactly once, rather than replaying it at pen-up.
            rebuildDocumentBitmapIfNeeded()
            strokes += stroke
            bitmapCanvas?.let { canvas ->
                canvas.save()
                canvas.translate(viewportOffsetX, viewportOffsetY)
                canvas.scale(viewportScale, viewportScale)
                drawStroke(canvas, stroke)
                canvas.restore()
            }
            invalidate()
            onDocumentChanged()
        }

        fun clear() {
            strokes.clear()
            bitmapCanvas?.drawColor(Color.WHITE)
            cachedOffsetX = viewportOffsetX
            cachedOffsetY = viewportOffsetY
            cachedScale = viewportScale
            invalidate()
            onDocumentChanged()
        }

        fun snapshot(): SketchDocument = SketchDocument(
            viewportOffsetX = viewportOffsetX,
            viewportOffsetY = viewportOffsetY,
            viewportScale = viewportScale,
            strokes = strokes.toList(),
        )

        fun restore(document: SketchDocument) {
            viewportOffsetX = document.viewportOffsetX
            viewportOffsetY = document.viewportOffsetY
            viewportScale = document.viewportScale.coerceIn(MIN_ZOOM, MAX_ZOOM)
            strokes.clear()
            strokes += document.strokes
            cachedOffsetX = Float.NaN
            cachedOffsetY = Float.NaN
            cachedScale = Float.NaN
            invalidate()
        }

        override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    trackingFingerGesture = event.getToolType(0) == android.view.MotionEvent.TOOL_TYPE_FINGER
                    return trackingFingerGesture
                }
                android.view.MotionEvent.ACTION_POINTER_DOWN -> {
                    if (event.pointerCount == 2 && bothPointersAreFingers(event)) {
                        panning = true
                        trackingFingerGesture = true
                        val focusX = (event.getX(0) + event.getX(1)) / 2f
                        val focusY = (event.getY(0) + event.getY(1)) / 2f
                        gestureStartDistance = pointerDistance(event).coerceAtLeast(1f)
                        gestureStartScale = viewportScale
                        gestureFocusDocumentX = (focusX - viewportOffsetX) / viewportScale
                        gestureFocusDocumentY = (focusY - viewportOffsetY) / viewportScale
                        twoFingerTapCandidate = true
                    }
                    return trackingFingerGesture
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (panning && event.pointerCount >= 2) {
                        val focusX = (event.getX(0) + event.getX(1)) / 2f
                        val focusY = (event.getY(0) + event.getY(1)) / 2f
                        if (twoFingerTapCandidate && gestureMoved(event, focusX, focusY)) {
                            twoFingerTapCandidate = false
                        }
                        viewportScale = (gestureStartScale * pointerDistance(event) / gestureStartDistance)
                            .coerceIn(MIN_ZOOM, MAX_ZOOM)
                        viewportOffsetX = focusX - gestureFocusDocumentX * viewportScale
                        viewportOffsetY = focusY - gestureFocusDocumentY * viewportScale
                        invalidate()
                        onDocumentChanged()
                    }
                    return trackingFingerGesture
                }
                android.view.MotionEvent.ACTION_POINTER_UP -> {
                    if (event.pointerCount == 2) {
                        if (twoFingerTapCandidate) registerTwoFingerTap(event)
                        twoFingerTapCandidate = false
                        panning = false
                    }
                    return trackingFingerGesture
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    val handled = trackingFingerGesture
                    panning = false
                    trackingFingerGesture = false
                    twoFingerTapCandidate = false
                    return handled
                }
            }
            return trackingFingerGesture
        }

        override fun onDraw(canvas: Canvas) {
            rebuildDocumentBitmapIfNeeded()
            canvas.drawColor(Color.WHITE)
            bitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        }

        private fun rebuildDocumentBitmapIfNeeded() {
            val canvas = bitmapCanvas ?: return
            if (
                cachedOffsetX == viewportOffsetX &&
                cachedOffsetY == viewportOffsetY &&
                cachedScale == viewportScale
            ) return
            canvas.drawColor(Color.WHITE)
            canvas.save()
            canvas.translate(viewportOffsetX, viewportOffsetY)
            canvas.scale(viewportScale, viewportScale)
            strokes.forEach { drawStroke(canvas, it) }
            canvas.restore()
            cachedOffsetX = viewportOffsetX
            cachedOffsetY = viewportOffsetY
            cachedScale = viewportScale
        }

        private fun drawStroke(canvas: Canvas, stroke: Stroke) {
            val strokePaint = if (stroke.isEraser) eraserPaint else paint
            val widthScale = stroke.scaleAtCreation
            when (stroke.samples.size) {
                0 -> Unit
                1 -> {
                    val sample = stroke.samples.first()
                    strokePaint.strokeWidth = if (stroke.isEraser) {
                        ERASER_WIDTH_PX / widthScale
                    } else {
                        pressureWidth(sample.pressure) / widthScale
                    }
                    canvas.drawPoint(sample.x, sample.y, strokePaint)
                }
                else -> stroke.samples.zipWithNext().forEach { (from, to) ->
                    strokePaint.strokeWidth = if (stroke.isEraser) {
                        ERASER_WIDTH_PX / widthScale
                    } else {
                        pressureWidth((from.pressure + to.pressure) / 2f) / widthScale
                    }
                    canvas.drawLine(from.x, from.y, to.x, to.y, strokePaint)
                }
            }
        }

        private fun bothPointersAreFingers(event: android.view.MotionEvent): Boolean =
            event.getToolType(0) == android.view.MotionEvent.TOOL_TYPE_FINGER &&
                event.getToolType(1) == android.view.MotionEvent.TOOL_TYPE_FINGER

        private fun pointerDistance(event: android.view.MotionEvent): Float {
            val x = event.getX(1) - event.getX(0)
            val y = event.getY(1) - event.getY(0)
            return kotlin.math.sqrt(x * x + y * y)
        }

        private fun gestureMoved(
            event: android.view.MotionEvent,
            focusX: Float,
            focusY: Float,
        ): Boolean =
            kotlin.math.hypot(
                focusX - (gestureFocusDocumentX * gestureStartScale + viewportOffsetX),
                focusY - (gestureFocusDocumentY * gestureStartScale + viewportOffsetY),
            ) > touchSlop || kotlin.math.abs(pointerDistance(event) - gestureStartDistance) > touchSlop

        private fun registerTwoFingerTap(event: android.view.MotionEvent) {
            val focusX = (event.getX(0) + event.getX(1)) / 2f
            val focusY = (event.getY(0) + event.getY(1)) / 2f
            val isDoubleTap = event.eventTime - lastTwoFingerTapAt <=
                android.view.ViewConfiguration.getDoubleTapTimeout() &&
                kotlin.math.hypot(focusX - lastTwoFingerTapX, focusY - lastTwoFingerTapY) <= touchSlop * 2
            if (isDoubleTap) {
                resetZoomAt(focusX, focusY)
                lastTwoFingerTapAt = 0L
            } else {
                lastTwoFingerTapAt = event.eventTime
                lastTwoFingerTapX = focusX
                lastTwoFingerTapY = focusY
            }
        }

        private fun resetZoomAt(focusX: Float, focusY: Float) {
            val documentX = (focusX - viewportOffsetX) / viewportScale
            val documentY = (focusY - viewportOffsetY) / viewportScale
            viewportScale = 1f
            viewportOffsetX = focusX - documentX
            viewportOffsetY = focusY - documentY
            invalidate()
            onDocumentChanged()
        }
    }

    private data class Stroke(
        val samples: List<Sample>,
        val isEraser: Boolean,
        val scaleAtCreation: Float,
    )

    private data class SketchDocument(
        val viewportOffsetX: Float,
        val viewportOffsetY: Float,
        val viewportScale: Float,
        val strokes: List<Stroke>,
    )

    private object SketchStore {
        private const val MAGIC = 0x534B4554 // SKET
        private const val VERSION = 1
        private const val MAX_STROKES = 100_000
        private const val MAX_SAMPLES_PER_STROKE = 100_000

        fun read(file: File): SketchDocument? = runCatching {
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
                check(input.readInt() == MAGIC)
                check(input.readInt() == VERSION)
                val offsetX = input.readFloat()
                val offsetY = input.readFloat()
                val scale = input.readFloat()
                val strokeCount = input.readInt()
                check(strokeCount in 0..MAX_STROKES)
                val strokes = ArrayList<Stroke>(strokeCount)
                repeat(strokeCount) {
                    val isEraser = input.readBoolean()
                    val scaleAtCreation = input.readFloat()
                    val sampleCount = input.readInt()
                    check(sampleCount in 0..MAX_SAMPLES_PER_STROKE)
                    val samples = ArrayList<Sample>(sampleCount)
                    repeat(sampleCount) {
                        samples += Sample(input.readFloat(), input.readFloat(), input.readFloat())
                    }
                    strokes += Stroke(samples, isEraser, scaleAtCreation)
                }
                SketchDocument(offsetX, offsetY, scale, strokes)
            }
        }.getOrNull()

        fun write(file: File, document: SketchDocument) {
            val temporary = File(file.parentFile, "${file.name}.tmp")
            DataOutputStream(BufferedOutputStream(FileOutputStream(temporary))).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(VERSION)
                output.writeFloat(document.viewportOffsetX)
                output.writeFloat(document.viewportOffsetY)
                output.writeFloat(document.viewportScale)
                output.writeInt(document.strokes.size)
                document.strokes.forEach { stroke ->
                    output.writeBoolean(stroke.isEraser)
                    output.writeFloat(stroke.scaleAtCreation)
                    output.writeInt(stroke.samples.size)
                    stroke.samples.forEach { sample ->
                        output.writeFloat(sample.x)
                        output.writeFloat(sample.y)
                        output.writeFloat(sample.pressure)
                    }
                }
            }
            check(temporary.renameTo(file)) { "Could not replace ${file.name}" }
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
        const val MIN_ZOOM = 0.25f
        const val MAX_ZOOM = 4f
        const val SAVE_DEBOUNCE_MS = 250L
        const val DOCUMENT_FILE_NAME = "sketchbook-document-v2.bin"

        fun pressureWidth(pressure: Float): Float =
            MIN_STROKE_WIDTH_PX + (MAX_STROKE_WIDTH_PX - MIN_STROKE_WIDTH_PX) *
                (ln(3f * pressure.coerceIn(0f, 1f) + 1f) / LN_4)
    }
}
