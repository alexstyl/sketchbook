package dev.alexstyl.sketchbook

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnLayout
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import dev.alexstyl.sketchbook.iconography.Icons
import dev.alexstyl.sketchbook.iconography.Eraser
import dev.alexstyl.sketchbook.iconography.PenLine
import dev.alexstyl.sketchbook.iconography.FilePlus
import com.composeunstyled.UnstyledButton
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList
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

/**
 * A deliberately small BOOX raw-ink sample.
 *
 * The SurfaceView is only the firmware's live-ink target. SketchView is the canonical document that
 * owns committed strokes. Keeping those two layers separate is the important part: firmware ink is
 * instant but transient; Android's canvas is persistent and always safe to redraw.
 */
class MainActivity : AppCompatActivity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val persistenceExecutor = Executors.newSingleThreadExecutor()
    private lateinit var inputSurface: SurfaceView
    private lateinit var sketchView: SketchView
    private lateinit var toolRail: ComposeView
    private lateinit var clearButton: ComposeView

    private var helper: TouchHelper? = null
    private var systemInsets = WindowInsetsCompat.CONSUMED
    private var attached = false
    private var resumed = false
    private var strokeInProgress = false
    private var activeTool by mutableStateOf<Tool>(Tool.Pen)
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
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()
        initializeBooxSdk()

        inputSurface = SurfaceView(this)
        sketchView = SketchView(this, ::scheduleDocumentSave).apply {
            SketchStore.read(documentFile)?.let(::restore)
        }
        toolRail = ComposeView(this).apply {
            setContent {
                ToolRail(
                    activeTool = activeTool,
                    onToolSelected = ::selectTool,
                )
            }
        }
        clearButton = ComposeView(this).apply {
            setContent { NewSketchButton(onNewSketch = ::confirmNewSketch) }
        }
        toolRail.doOnLayout { inputSurface.post(::configureRawDrawing) }
        clearButton.doOnLayout { inputSurface.post(::configureRawDrawing) }
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
                toolRail,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.END,
                ).apply {
                    marginEnd = dp(16)
                },
            )
            addView(
                clearButton,
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
            currentHelper.setStrokeWidth(STROKE_WIDTH_PX)
            currentHelper.setStrokeColor(Color.BLACK)
            currentHelper.setLimitRect(mutableListOf(limit)).setExcludeRect(excludes)
            currentHelper.openRawDrawing()
            currentHelper.setStrokeStyle(TouchHelper.STROKE_STYLE_FOUNTAIN)
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
            listOf(toolRail, clearButton).filter { it.isLaidOut }.forEach { control ->
                val location = IntArray(2)
                control.getLocationInWindow(location)
                add(
                    Rect(
                        location[0],
                        location[1],
                        location[0] + control.width,
                        location[1] + control.height,
                    ),
                )
            }
            // BOOX ignores an empty exclusion list and can retain stale rectangles from a prior session.
            if (isEmpty()) add(Rect(0, 0, 1, 1))
        }
    }

    private fun addPoint(point: TouchPoint) {
        pendingStroke += sketchView.documentPoint(point.getX(), point.getY(), point.getPressure())
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

    private fun confirmNewSketch() {
        AlertDialog.Builder(this)
            .setTitle("New sketch?")
            .setMessage("This starts a new blank sketch.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("New sketch") { _, _ -> clearSketch() }
            .show()
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
    private fun ToolRail(
        activeTool: Tool,
        onToolSelected: (Tool) -> Unit,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ToolIconButton(
                icon = Icons.PenLine,
                contentDescription = "Pen",
                selected = activeTool == Tool.Pen,
            ) { onToolSelected(Tool.Pen) }
            ToolIconButton(
                icon = Icons.Eraser,
                contentDescription = "Eraser",
                selected = activeTool == Tool.Eraser,
            ) { onToolSelected(Tool.Eraser) }
        }
    }

    @Composable
    private fun NewSketchButton(onNewSketch: () -> Unit) {
        ToolIconButton(
            icon = Icons.FilePlus,
            contentDescription = "New sketch",
            selected = false,
            onClick = onNewSketch,
        )
    }

    @Composable
    private fun ToolIconButton(
        icon: ImageVector,
        contentDescription: String,
        selected: Boolean,
        onClick: () -> Unit,
    ) {
        val shape = RoundedCornerShape(14.dp)
        val interactionSource = remember { MutableInteractionSource() }
        val pressed by interactionSource.collectIsPressedAsState()
        val scale by animateFloatAsState(
            targetValue = if (pressed) 0.9f else 1f,
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
            label = "tool button scale",
        )
        UnstyledButton(
            onClick = onClick,
            contentPadding = PaddingValues(14.dp),
            interactionSource = interactionSource,
            indication = LocalIndication.current,
            modifier = Modifier
                .size(64.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(shape)
                .background(if (selected) ComposeColor.Black else ComposeColor.White)
                .border(1.dp, ComposeColor.Black, shape),
        ) {
            Image(
                painter = rememberVectorPainter(icon),
                contentDescription = contentDescription,
                colorFilter = ColorFilter.tint(
                    if (selected) ComposeColor.White else ComposeColor.Black,
                ),
                modifier = Modifier.size(32.dp),
            )
        }
    }

    private data class Sample(
        val x: Float,
        val y: Float,
        val pressure: Float = DEFAULT_PRESSURE,
    )

    private data class Stroke(val samples: List<Sample>, val isEraser: Boolean)

    private data class SketchDocument(
        val viewportOffsetX: Float,
        val viewportOffsetY: Float,
        val viewportScale: Float,
        val strokes: List<Stroke>,
    )

    private sealed interface Tool {
        data object Pen : Tool
        data object Eraser : Tool
    }

    private class SketchView(
        context: android.content.Context,
        private val onDocumentChanged: () -> Unit,
    ) : View(context) {
        var eraserEnabled = false
        @Volatile private var viewportOffsetX = 0f
        @Volatile private var viewportOffsetY = 0f
        @Volatile private var viewportScale = 1f
        private val strokes = mutableListOf<Stroke>()
        private var activeEraserStroke: MutableList<Sample>? = null
        private var panning = false
        private var trackingFingerGesture = false
        private var gestureStartDistance = 0f
        private var gestureStartScale = 1f
        private var gestureStartFocusX = 0f
        private var gestureStartFocusY = 0f
        private var gestureFocusDocumentX = 0f
        private var gestureFocusDocumentY = 0f
        private var twoFingerTapCandidate = false
        private var lastTwoFingerTapAt = 0L
        private var lastTwoFingerTapX = 0f
        private var lastTwoFingerTapY = 0f
        private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            strokeWidth = STROKE_WIDTH_PX
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

        fun documentPoint(screenX: Float, screenY: Float, pressure: Float = DEFAULT_PRESSURE): Sample =
            Sample(
                (screenX - viewportOffsetX) / viewportScale,
                (screenY - viewportOffsetY) / viewportScale,
                normalizePressure(pressure),
            )

        fun commit(samples: List<Sample>) {
            if (samples.isEmpty()) return
            strokes += Stroke(samples, isEraser = false)
            invalidate()
            onDocumentChanged()
        }

        fun clear() {
            strokes.clear()
            activeEraserStroke = null
            invalidate()
            onDocumentChanged()
        }

        fun restore(document: SketchDocument) {
            viewportOffsetX = document.viewportOffsetX
            viewportOffsetY = document.viewportOffsetY
            viewportScale = document.viewportScale.coerceIn(MIN_ZOOM, MAX_ZOOM)
            strokes.clear()
            strokes += document.strokes
            invalidate()
        }

        fun snapshot(): SketchDocument = SketchDocument(
            viewportOffsetX = viewportOffsetX,
            viewportOffsetY = viewportOffsetY,
            viewportScale = viewportScale,
            strokes = strokes + listOfNotNull(
                activeEraserStroke?.let { Stroke(it.toList(), isEraser = true) },
            ),
        )

        override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
            if (handleFingerPan(event)) return true

            val isStylus = event.getToolType(0).let {
                it == android.view.MotionEvent.TOOL_TYPE_STYLUS ||
                    it == android.view.MotionEvent.TOOL_TYPE_ERASER
            }
            if (!eraserEnabled || !isStylus) return false
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    activeEraserStroke = mutableListOf(documentPoint(event.x, event.y))
                    invalidate()
                    return true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    activeEraserStroke?.add(documentPoint(event.x, event.y))
                    invalidate()
                    return true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    activeEraserStroke?.apply {
                        add(documentPoint(event.x, event.y))
                        strokes += Stroke(toList(), isEraser = true)
                    }
                    activeEraserStroke = null
                    invalidate()
                    onDocumentChanged()
                    return true
                }
            }
            return false
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.WHITE)
            canvas.save()
            canvas.translate(viewportOffsetX, viewportOffsetY)
            canvas.scale(viewportScale, viewportScale)
            strokes.forEach { drawStroke(canvas, it) }
            activeEraserStroke?.let { drawStroke(canvas, Stroke(it, isEraser = true)) }
            canvas.restore()
        }

        private fun handleFingerPan(event: android.view.MotionEvent): Boolean {
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    trackingFingerGesture = event.getToolType(0) == android.view.MotionEvent.TOOL_TYPE_FINGER
                    return trackingFingerGesture
                }
                android.view.MotionEvent.ACTION_POINTER_DOWN -> {
                    if (event.pointerCount == 2 && bothPointersAreFingers(event)) {
                        panning = true
                        trackingFingerGesture = true
                        activeEraserStroke = null
                        setGestureAnchor(event)
                        twoFingerTapCandidate = true
                    } else {
                        twoFingerTapCandidate = false
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
                        val scaleChange = pointerDistance(event) / gestureStartDistance
                        viewportScale = (gestureStartScale * scaleChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
                        viewportOffsetX = focusX - gestureFocusDocumentX * viewportScale
                        viewportOffsetY = focusY - gestureFocusDocumentY * viewportScale
                        invalidate()
                        onDocumentChanged()
                    }
                    return trackingFingerGesture
                }
                android.view.MotionEvent.ACTION_POINTER_UP -> {
                    if (panning && event.pointerCount == 2) {
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

        private fun bothPointersAreFingers(event: android.view.MotionEvent): Boolean =
            event.getToolType(0) == android.view.MotionEvent.TOOL_TYPE_FINGER &&
                event.getToolType(1) == android.view.MotionEvent.TOOL_TYPE_FINGER

        private fun setGestureAnchor(event: android.view.MotionEvent) {
            val focusX = (event.getX(0) + event.getX(1)) / 2f
            val focusY = (event.getY(0) + event.getY(1)) / 2f
            gestureStartFocusX = focusX
            gestureStartFocusY = focusY
            gestureStartDistance = pointerDistance(event).coerceAtLeast(1f)
            gestureStartScale = viewportScale
            gestureFocusDocumentX = (focusX - viewportOffsetX) / viewportScale
            gestureFocusDocumentY = (focusY - viewportOffsetY) / viewportScale
        }

        private fun gestureMoved(event: android.view.MotionEvent, focusX: Float, focusY: Float): Boolean {
            val focusDistance = kotlin.math.hypot(
                focusX - gestureStartFocusX,
                focusY - gestureStartFocusY,
            )
            return focusDistance > touchSlop ||
                kotlin.math.abs(pointerDistance(event) - gestureStartDistance) > touchSlop
        }

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

        private fun pointerDistance(event: android.view.MotionEvent): Float {
            val x = event.getX(1) - event.getX(0)
            val y = event.getY(1) - event.getY(0)
            return kotlin.math.sqrt(x * x + y * y)
        }

        private fun drawStroke(canvas: Canvas, stroke: Stroke) {
            when (stroke.samples.size) {
                0 -> Unit
                1 -> {
                    val sample = stroke.samples.first()
                    canvas.drawPoint(sample.x, sample.y, paintFor(stroke, sample.pressure))
                }
                else -> stroke.samples.zipWithNext().forEach { (from, to) ->
                    canvas.drawLine(
                        from.x,
                        from.y,
                        to.x,
                        to.y,
                        paintFor(stroke, (from.pressure + to.pressure) / 2f),
                    )
                }
            }
        }

        private fun paintFor(stroke: Stroke, pressure: Float): Paint =
            if (stroke.isEraser) {
                eraserPaint
            } else {
                paint.apply { strokeWidth = MIN_STROKE_WIDTH_PX + pressure * STROKE_WIDTH_RANGE_PX }
            }

        private fun normalizePressure(pressure: Float): Float {
            val normalized = if (pressure > 1f) pressure / EpdController.MAX_TOUCH_PRESSURE else pressure
            return normalized.coerceIn(0f, 1f)
        }
    }

    private object SketchStore {
        private const val MAGIC = 0x534B4554 // SKET
        private const val VERSION = 3
        private const val MAX_STROKES = 100_000
        private const val MAX_SAMPLES_PER_STROKE = 100_000

        fun read(file: File): SketchDocument? = runCatching {
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
                check(input.readInt() == MAGIC)
                val version = input.readInt()
                check(version in 1..VERSION)
                val offsetX = input.readFloat()
                val offsetY = input.readFloat()
                val scale = if (version >= 2) input.readFloat() else 1f
                val strokeCount = input.readInt()
                check(strokeCount in 0..MAX_STROKES)
                val strokes = ArrayList<Stroke>(strokeCount)
                repeat(strokeCount) {
                    val isEraser = input.readBoolean()
                    val sampleCount = input.readInt()
                    check(sampleCount in 0..MAX_SAMPLES_PER_STROKE)
                    val samples = ArrayList<Sample>(sampleCount)
                    repeat(sampleCount) {
                        val x = input.readFloat()
                        val y = input.readFloat()
                        val pressure = if (version >= 3) input.readFloat() else DEFAULT_PRESSURE
                        samples += Sample(x, y, pressure)
                    }
                    strokes += Stroke(samples, isEraser)
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
        const val STROKE_WIDTH_PX = 5f
        const val MIN_STROKE_WIDTH_PX = 2f
        const val STROKE_WIDTH_RANGE_PX = 6f
        const val ERASER_WIDTH_PX = 42f
        const val UNFREEZE_IDLE_MS = 700L
        const val PANEL_SETTLE_MS = 300L
        const val SAVE_DEBOUNCE_MS = 250L
        const val DOCUMENT_FILE_NAME = "sketchbook-document.bin"
        const val MIN_ZOOM = 0.25f
        const val MAX_ZOOM = 4f
        const val DEFAULT_PRESSURE = 0.5f
    }
}
