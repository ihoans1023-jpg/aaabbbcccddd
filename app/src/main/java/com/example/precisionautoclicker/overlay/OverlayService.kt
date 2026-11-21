package com.example.precisionautoclicker.overlay

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.example.precisionautoclicker.R
import com.example.precisionautoclicker.data.PresetRepository
import com.example.precisionautoclicker.engine.SequenceExecutionEngine
import com.example.precisionautoclicker.model.ActionNode
import com.example.precisionautoclicker.model.ExecutionConfig
import com.example.precisionautoclicker.service.AutoClickAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs

class OverlayService : Service() {

    companion object {
        const val EXTRA_GLOBAL_CYCLES = "extra_global_cycles"
        const val EXTRA_MIN_COOLDOWN_MS = "extra_min_cooldown_ms"
        const val EXTRA_PAUSE_ON_EXT_TOUCH = "extra_pause_on_ext_touch"
        const val EXTRA_EXT_RESUME_DELAY_MS = "extra_ext_resume_delay_ms"
        private const val CHANNEL_ID = "auto_clicker_overlay_channel"
        private const val NOTIF_ID = 1001
    }

    private lateinit var windowManager: WindowManager
    private lateinit var engine: SequenceExecutionEngine
    private lateinit var presetRepo: PresetRepository
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var controlBarView: View? = null
    private var dragLineView: DragLineOverlayView? = null
    private var dragLineAttached = false

    private val nodes = mutableListOf<ActionNode>()
    private val markerViews = mutableMapOf<Long, MutableList<Pair<View, WindowManager.LayoutParams>>>()
    private var nextNodeId = 1L

    private var executionConfig = ExecutionConfig()
    private var isRunning = false
    private var isMarkersHidden = false

    // 화면 꺼짐(ACTION_SCREEN_OFF) 감지 시 즉시 자동 정지하는 리시버
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF && isRunning) {
                val btnPlayStop = controlBarView?.findViewById<Button>(R.id.btnPlayStop)
                stopExecution(btnPlayStop)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        engine = SequenceExecutionEngine(this)
        presetRepo = PresetRepository(this)
        startForegroundWithNotification()
        setupControlBar()
        observeEngineStatus()
        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            executionConfig = ExecutionConfig(
                globalCycles = it.getIntExtra(EXTRA_GLOBAL_CYCLES, 0).coerceAtLeast(0),
                minSafeCooldownMs = it.getLongExtra(EXTRA_MIN_COOLDOWN_MS, 12L).coerceAtLeast(0L),
                pauseOnExternalTouch = it.getBooleanExtra(EXTRA_PAUSE_ON_EXT_TOUCH, true),
                externalTouchResumeDelayMs = it.getLongExtra(EXTRA_EXT_RESUME_DELAY_MS, 1500L).coerceAtLeast(200L)
            )
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun observeEngineStatus() {
        uiScope.launch {
            engine.status.collect { st ->
                val tvState = controlBarView?.findViewById<TextView>(R.id.tvEngineState) ?: return@collect
                tvState.text = when {
                    !st.isRunning -> "대기"
                    st.isPausedByExternalTouch -> "외부터치 일시정지"
                    else -> "실행 #${st.currentNodeOrder}"
                }
            }
        }
    }

    private fun startForegroundWithNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "오토마우스 플로팅 제어",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val notif = builder
            .setContentTitle("정밀 오토마우스 실행 중")
            .setContentText("플로팅 제어가 활성화되어 있습니다.")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()

        startForeground(NOTIF_ID, notif)
    }

    private fun overlayWindowType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private fun syncDragLineOverlay() {
        val shouldShow = !isRunning && !isMarkersHidden && nodes.any { it.isDrag }
        if (shouldShow) {
            if (!dragLineAttached) {
                val view = dragLineView ?: DragLineOverlayView(this).also { dragLineView = it }
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    overlayWindowType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                )
                runCatching {
                    windowManager.addView(view, params)
                    dragLineAttached = true
                }
            }
            dragLineView?.updateNodes(nodes)
        } else {
            if (dragLineAttached) {
                dragLineView?.let { runCatching { windowManager.removeView(it) } }
                dragLineAttached = false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupControlBar() {
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_control_bar, null)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 220
        }

        val btnPlayStop = view.findViewById<Button>(R.id.btnPlayStop)
        val btnAddClick = view.findViewById<Button>(R.id.btnAddClick)
        val btnAddDrag = view.findViewById<Button>(R.id.btnAddDrag)
        val btnRemoveLast = view.findViewById<Button>(R.id.btnRemoveLast)
        val btnToggleHide = view.findViewById<Button>(R.id.btnToggleHide)
        val btnSavePreset = view.findViewById<Button>(R.id.btnSavePreset)
        val btnLoadPreset = view.findViewById<Button>(R.id.btnLoadPreset)
        val btnClose = view.findViewById<Button>(R.id.btnCloseOverlay)
        val dragHandle = view.findViewById<TextView>(R.id.tvDragHandle)

        var initX = 0
        var initY = 0
        var touchX = 0f
        var touchY = 0f
        dragHandle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = params.x
                    initY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initX + (event.rawX - touchX).toInt()
                    params.y = initY + (event.rawY - touchY).toInt()
                    windowManager.updateViewLayout(view, params)
                    true
                }
                else -> false
            }
        }

        btnAddClick.setOnClickListener {
            if (isRunning) return@setOnClickListener
            if (isMarkersHidden) toggleMarkersVisibility(btnToggleHide)
            addClickNode()
        }

        btnAddDrag.setOnClickListener {
            if (isRunning) return@setOnClickListener
            if (isMarkersHidden) toggleMarkersVisibility(btnToggleHide)
            addDragNode()
        }

        btnRemoveLast.setOnClickListener {
            if (isRunning) return@setOnClickListener
            removeLastNode()
        }

        btnToggleHide.setOnClickListener {
            toggleMarkersVisibility(btnToggleHide)
        }

        btnSavePreset.setOnClickListener {
            if (isRunning) return@setOnClickListener
            showSavePresetDialog()
        }

        btnLoadPreset.setOnClickListener {
            if (isRunning) return@setOnClickListener
            showLoadPresetDialog()
        }

        btnPlayStop.setOnClickListener {
            if (!isRunning) {
                if (!AutoClickAccessibilityService.isServiceEnabled()) {
                    Toast.makeText(this, "접근성 서비스가 켜져 있지 않습니다.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (nodes.isEmpty()) {
                    Toast.makeText(this, "먼저 클릭 또는 드래그 타겟을 추가해 주세요.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                startExecution(btnPlayStop)
            } else {
                stopExecution(btnPlayStop)
            }
        }

        btnClose.setOnClickListener {
            stopExecution(btnPlayStop)
            stopSelf()
        }

        windowManager.addView(view, params)
        controlBarView = view
    }

    private fun toggleMarkersVisibility(btnToggleHide: Button) {
        isMarkersHidden = !isMarkersHidden
        btnToggleHide.text = if (isMarkersHidden) "보이기" else "숨기기"
        syncMarkersAttachedState()
        syncDragLineOverlay()
    }

    private fun syncMarkersAttachedState() {
        val shouldDetach = isRunning || isMarkersHidden
        if (shouldDetach) {
            markerViews.keys.toList().forEach { removeNodeMarkers(it) }
        } else {
            nodes.forEach { renderNodeMarkers(it) }
        }
    }

    private fun removeLastNode() {
        if (nodes.isEmpty()) {
            Toast.makeText(this, "제거할 타겟이 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        val last = nodes.removeAt(nodes.lastIndex)
        removeNodeMarkers(last.id)
        syncDragLineOverlay()
    }

    private fun showSavePresetDialog() {
        if (nodes.isEmpty()) {
            Toast.makeText(this, "저장할 타겟 노드가 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        val input = EditText(this).apply {
            hint = "예: 출석체크 매크로"
        }
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
            .setTitle("프리셋 저장")
            .setView(input)
            .setPositiveButton("저장") { _, _ ->
                val name = input.text.toString()
                presetRepo.savePreset(name, nodes.toList(), executionConfig)
                Toast.makeText(this, "프리셋이 저장되었습니다.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
            .create()
        dialog.window?.setType(overlayWindowType())
        dialog.show()
    }

    private fun showLoadPresetDialog() {
        val presets = presetRepo.getAllPresets()
        if (presets.isEmpty()) {
            Toast.makeText(this, "저장된 프리셋이 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        val names = presets.map { "${it.name} (${it.nodes.size}개 노드)" }.toTypedArray()
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
            .setTitle("프리셋 불러오기")
            .setItems(names) { _, which ->
                val selected = presets[which]
                applyLoadedPreset(selected.nodes, selected.config)
                Toast.makeText(this, "'${selected.name}' 불러옴", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("닫기", null)
            .create()
        dialog.window?.setType(overlayWindowType())
        dialog.show()
    }

    private fun applyLoadedPreset(loadedNodes: List<ActionNode>, loadedConfig: ExecutionConfig) {
        markerViews.keys.toList().forEach { removeNodeMarkers(it) }
        nodes.clear()
        nodes.addAll(loadedNodes)
        nextNodeId = (loadedNodes.maxOfOrNull { it.id } ?: 0L) + 1L
        executionConfig = loadedConfig
        syncMarkersAttachedState()
        syncDragLineOverlay()
    }

    private fun startExecution(btnPlayStop: Button) {
        isRunning = true
        btnPlayStop.text = "정지"
        syncMarkersAttachedState()
        syncDragLineOverlay()

        engine.start(
            nodesProvider = { nodes.toList() },
            configProvider = { executionConfig },
            onFinished = {
                isRunning = false
                btnPlayStop.text = "실행"
                syncMarkersAttachedState()
                syncDragLineOverlay()
            }
        )
    }

    private fun stopExecution(btnPlayStop: Button?) {
        engine.stop()
        isRunning = false
        btnPlayStop?.text = "실행"
        syncMarkersAttachedState()
        syncDragLineOverlay()
    }

    private fun addClickNode() {
        val (w, h) = getScreenCenter()
        val id = nextNodeId++
        val order = nodes.size + 1
        val node = ActionNode(
            id = id,
            orderIndex = order,
            isDrag = false,
            startX = w,
            startY = h
        )
        nodes.add(node)
        renderNodeMarkers(node)
    }

    private fun addDragNode() {
        val (w, h) = getScreenCenter()
        val id = nextNodeId++
        val order = nodes.size + 1
        val node = ActionNode(
            id = id,
            orderIndex = order,
            isDrag = true,
            startX = (w - 150f).coerceAtLeast(100f),
            startY = h,
            endX = w + 150f,
            endY = h,
            holdDurationMs = 350L
        )
        nodes.add(node)
        renderNodeMarkers(node)
        syncDragLineOverlay()
    }

    private fun renderNodeMarkers(node: ActionNode) {
        removeNodeMarkers(node.id)
        if (isRunning || isMarkersHidden) return

        val created = mutableListOf<Pair<View, WindowManager.LayoutParams>>()

        if (!node.isDrag) {
            created.add(
                createMarkerView(
                    label = "${node.orderIndex}",
                    colorHex = "#0D9488",
                    xPx = node.startX,
                    yPx = node.startY,
                    onMoved = { nx, ny ->
                        updateNode(node.id) { it.copy(startX = nx, startY = ny) }
                    },
                    onTapped = { openConfigFor(node.id) }
                )
            )
        } else {
            created.add(
                createMarkerView(
                    label = "${node.orderIndex}A",
                    colorHex = "#2563EB",
                    xPx = node.startX,
                    yPx = node.startY,
                    onMoved = { nx, ny ->
                        updateNode(node.id) { it.copy(startX = nx, startY = ny) }
                        dragLineView?.updateNodes(nodes)
                    },
                    onTapped = { openConfigFor(node.id) }
                )
            )
            created.add(
                createMarkerView(
                    label = "${node.orderIndex}B",
                    colorHex = "#1E3A8A",
                    xPx = node.endX,
                    yPx = node.endY,
                    onMoved = { nx, ny ->
                        updateNode(node.id) { it.copy(endX = nx, endY = ny) }
                        dragLineView?.updateNodes(nodes)
                    },
                    onTapped = { openConfigFor(node.id) }
                )
            )
        }
        markerViews[node.id] = created
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createMarkerView(
        label: String,
        colorHex: String,
        xPx: Float,
        yPx: Float,
        onMoved: (Float, Float) -> Unit,
        onTapped: () -> Unit
    ): Pair<View, WindowManager.LayoutParams> {
        val sizePx = (44 * resources.displayMetrics.density).toInt()
        val tv = TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(colorHex))
                setStroke(4, Color.WHITE)
            }
            alpha = 0.92f
        }

        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (xPx - sizePx / 2f).toInt()
            y = (yPx - sizePx / 2f).toInt()
        }

        var initX = 0
        var initY = 0
        var downX = 0f
        var downY = 0f
        var isDragging = false

        tv.setOnTouchListener { _, event ->
            if (isRunning) return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = params.x
                    initY = params.y
                    downX = event.rawX
                    downY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (abs(dx) > 8 || abs(dy) > 8) {
                        isDragging = true
                    }
                    params.x = initX + dx.toInt()
                    params.y = initY + dy.toInt()
                    windowManager.updateViewLayout(tv, params)
                    onMoved(params.x + sizePx / 2f, params.y + sizePx / 2f)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        onTapped()
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(tv, params)
        return tv to params
    }

    private fun openConfigFor(nodeId: Long) {
        val current = nodes.find { it.id == nodeId } ?: return
        NodeConfigDialog.show(
            context = this,
            node = current,
            onSave = { updated ->
                updateNode(nodeId) { updated }
                renderNodeMarkers(updated)
                syncDragLineOverlay()
            },
            onDelete = { toDelete ->
                nodes.removeAll { it.id == toDelete.id }
                removeNodeMarkers(toDelete.id)
                syncDragLineOverlay()
            }
        )
    }

    private fun updateNode(nodeId: Long, transform: (ActionNode) -> ActionNode) {
        val idx = nodes.indexOfFirst { it.id == nodeId }
        if (idx >= 0) {
            nodes[idx] = transform(nodes[idx])
        }
    }

    private fun removeNodeMarkers(nodeId: Long) {
        markerViews.remove(nodeId)?.forEach { (v, _) ->
            runCatching { windowManager.removeView(v) }
        }
    }

    private fun getScreenCenter(): Pair<Float, Float> {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(dm)
        return dm.widthPixels / 2f to dm.heightPixels / 2f
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenOffReceiver) }
        engine.stop()
        uiScope.cancel()
        if (dragLineAttached) {
            dragLineView?.let { runCatching { windowManager.removeView(it) } }
            dragLineAttached = false
        }
        controlBarView?.let { runCatching { windowManager.removeView(it) } }
        markerViews.keys.toList().forEach { removeNodeMarkers(it) }
        super.onDestroy()
    }
}
