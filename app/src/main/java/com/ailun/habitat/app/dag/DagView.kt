package com.ailun.habitat.app.dag

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.PathInterpolator
import com.ailun.habitat.app.ui.theme.NodeCategory
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** DagView 的配色（由 Compose 主题派生，深浅色自动适配）。均为 ARGB。 */
data class DagPalette(
    val background: Int,
    val gridDot: Int,
    val nodeSurface: Int,
    val nodeBorder: Int,
    val selectedBorder: Int,
    val startBorder: Int,
    val label: Int,
    val type: Int,
    val edgeNext: Int,
    val edgeTrue: Int,
    val edgeFalse: Int,
    val edgeBack: Int,
    val edgeJump: Int,
    val badgeBg: Int,
    val badgeFg: Int,
    val categoryAccent: Map<NodeCategory, Int>,
    val categoryContainer: Map<NodeCategory, Int>,
)

/**
 * 工作流结构图：纵向分层（自上而下），边从底部中点连到顶部中点。
 * 所有几何（节点矩形、截断文本、边 Path、箭头）在 [setGraph] 时预计算，onDraw 不分配对象。
 */
class DagView(context: Context) : View(context) {

    var graph: DagGraph? = null
        private set

    var onNodeClicked: ((DagNode) -> Unit)? = null
    var onNodeLongPressed: ((DagNode) -> Unit)? = null
    var onBackgroundClicked: (() -> Unit)? = null

    /** 类别图标（24×24 视口 Path），由 Compose 侧提供。 */
    var categoryIcons: Map<NodeCategory, Path> = emptyMap()
        set(value) { field = value; invalidate() }

    var palette: DagPalette? = null
        set(value) {
            if (field == value) return
            field = value
            applyPalette()
            invalidate()
        }

    var selectedNodeId: String? = null
        set(value) { if (field != value) { field = value; invalidate() } }

    /** false 时为静态预览：不拦截触摸（嵌在列表里时父级可正常滚动）。 */
    var interactive: Boolean = true

    private val density = resources.displayMetrics.density
    private val nodeW = DagMetrics.NODE_WIDTH_DP * density
    private val nodeH = DagMetrics.NODE_HEIGHT_DP * density
    private val corner = 14f * density
    private val arrow = 7f * density
    private val barW = 4f * density
    private val iconTile = 32f * density
    private val pad = 12f * density
    private val lane = DagMetrics.BACK_EDGE_LANE_DP * density

    private var scale = 1f
    private var tx = 0f
    private var ty = 0f
    private val minScale = 0.3f
    private val fitMinScale = 0.75f
    private val maxScale = 3f
    private var userMoved = false
    private var animator: ValueAnimator? = null

    // ── Paints（字段复用） ──
    private val bgPaint = Paint()
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeWidth = 1.6f * density }
    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val typePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10.5f * density
        typeface = Typeface.MONOSPACE
    }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val edgeLabelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f * density
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val edgeLabelBg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgeText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 9.5f * density
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val dash = DashPathEffect(floatArrayOf(6f * density, 4f * density), 0f)

    // ── 预计算几何 ──
    private class NodeGeom(
        val node: DagNode,
        val rect: RectF,
        val label: String,
        val type: String,
        val iconMatrix: Matrix,
        val tile: RectF,
    )

    private class EdgeGeom(
        val path: Path,
        val arrow: Path,
        val color: Int,
        val dashed: Boolean,
        val label: String,
        val labelX: Float,
        val labelY: Float,
    )

    private var nodeGeoms: List<NodeGeom> = emptyList()
    private var edgeGeoms: List<EdgeGeom> = emptyList()
    private var byId: Map<String, NodeGeom> = emptyMap()
    private val bounds = RectF()
    private val tmpPath = Path()
    private val badgeRect = RectF()
    private val labelRect = RectF()
    private var dots = FloatArray(0)
    private var layerRight = FloatArray(0)
    private var layerLeft = FloatArray(0)
    private var pendingFocus: String? = null

    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private val gestureDetector = GestureDetector(context, GestureListener())

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "工作流结构图，双指缩放，单指拖动，点按节点查看详情"
    }

    /** 传入已布局的图（来自 [DagLayoutEngine.layout]）。 */
    fun setGraph(newGraph: DagGraph) {
        val needsLayout = newGraph.nodes.isNotEmpty() && newGraph.width == 0f
        val g = if (needsLayout) DagLayoutEngine.layout(newGraph, density) else newGraph
        val first = graph == null || graph?.nodes?.map { it.stepId }?.toSet() != g.nodes.map { it.stepId }.toSet()
        graph = g
        rebuildGeometry()
        if (width > 0 && height > 0) {
            if (first || !userMoved) fitToView(animate = false) else clampTranslate()
        }
        pendingFocus?.let { focusNode(it) }
        invalidate()
    }

    /** 居中并高亮某个节点。 */
    fun focusNode(id: String) {
        val geom = byId[id]
        if (geom == null || width == 0 || height == 0) {
            // 图尚未布局/视图尚未测量：记下，setGraph / onSizeChanged 后再聚焦
            pendingFocus = id
            return
        }
        pendingFocus = null
        selectedNodeId = id
        val targetScale = scale.coerceAtLeast(1f).coerceAtMost(maxScale)
        val targetTx = width / 2f - geom.rect.centerX() * targetScale
        val targetTy = height / 2f - geom.rect.centerY() * targetScale
        animateTo(targetScale, targetTx, targetTy)
        userMoved = true
    }

    fun zoomToFit() = fitToView(animate = true)

    private fun applyPalette() {
        val p = palette ?: return
        bgPaint.color = p.background
        dotPaint.color = p.gridDot
        nodePaint.color = p.nodeSurface
        labelPaint.color = p.label
        typePaint.color = p.type
        edgeLabelBg.color = p.background
        badgePaint.color = p.badgeBg
        badgeText.color = p.badgeFg
        if (graph != null) rebuildGeometry()
    }

    private fun rebuildGeometry() {
        val g = graph ?: return
        val maxText = nodeW - barW - pad * 2 - iconTile - pad * 0.75f
        val geoms = g.nodes.map { n ->
            val rect = RectF(n.x, n.y, n.x + nodeW, n.y + nodeH)
            val tile = RectF(
                rect.left + barW + pad,
                rect.centerY() - iconTile / 2f,
                rect.left + barW + pad + iconTile,
                rect.centerY() + iconTile / 2f,
            )
            val iconSize = iconTile * 0.6f
            val m = Matrix().apply {
                setScale(iconSize / 24f, iconSize / 24f)
                postTranslate(tile.centerX() - iconSize / 2f, tile.centerY() - iconSize / 2f)
            }
            NodeGeom(
                node = n,
                rect = rect,
                label = TextUtils.ellipsize(n.label, labelPaint, maxText, TextUtils.TruncateAt.END).toString(),
                type = TextUtils.ellipsize(n.moduleId, typePaint, maxText, TextUtils.TruncateAt.MIDDLE).toString(),
                iconMatrix = m,
                tile = tile,
            )
        }
        byId = geoms.associateBy { it.node.stepId }
        nodeGeoms = geoms

        // 每层左右边界：回边/上行边从经过的所有层之外绕行，避免穿过节点
        val layers = (geoms.maxOfOrNull { it.node.layer } ?: 0) + 1
        layerRight = FloatArray(layers) { Float.NEGATIVE_INFINITY }
        layerLeft = FloatArray(layers) { Float.POSITIVE_INFINITY }
        for (ng in geoms) {
            val l = ng.node.layer
            layerRight[l] = max(layerRight[l], ng.rect.right)
            layerLeft[l] = min(layerLeft[l], ng.rect.left)
        }

        // 回边按目标分组错开，避免多条回边重叠
        val backIndex = HashMap<String, Int>()
        edgeGeoms = g.edges.mapNotNull { e ->
            val from = byId[e.fromNodeId] ?: return@mapNotNull null
            val to = byId[e.toNodeId] ?: return@mapNotNull null
            val idx = if (e.type == DagEdgeType.BACK_EDGE) backIndex.merge(e.toNodeId, 1, Int::plus)!! - 1 else 0
            buildEdge(e, from, to, idx)
        }

        // 内容边界 = 布局尺寸 ∪ 所有边（绕行的回边可能超出节点范围）
        bounds.set(0f, 0f, max(g.width, 1f), max(g.height, 1f))
        val eb = RectF()
        for (eg in edgeGeoms) {
            eg.path.computeBounds(eb, true)
            bounds.union(eb)
        }
    }

    private fun spanRight(l1: Int, l2: Int): Float {
        var m = Float.NEGATIVE_INFINITY
        for (l in min(l1, l2)..max(l1, l2)) if (l in layerRight.indices) m = max(m, layerRight[l])
        return m
    }

    private fun spanLeft(l1: Int, l2: Int): Float {
        var m = Float.POSITIVE_INFINITY
        for (l in min(l1, l2)..max(l1, l2)) if (l in layerLeft.indices) m = min(m, layerLeft[l])
        return m
    }

    private fun buildEdge(e: DagEdge, fromGeom: NodeGeom, toGeom: NodeGeom, backIdx: Int): EdgeGeom {
        val a = fromGeom.rect
        val b = toGeom.rect
        val la = fromGeom.node.layer
        val lb = toGeom.node.layer
        val path = Path()
        val tipX: Float
        val tipY: Float
        val ctrlX: Float
        val ctrlY: Float
        val labelX: Float
        val labelY: Float

        if (e.type == DagEdgeType.BACK_EDGE || (e.fromNodeId == e.toNodeId)) {
            // 回边：从源节点右侧绕行到目标节点右侧
            val off = lane * 0.55f + backIdx * 10f * density
            val sx = a.right
            val sy = a.centerY()
            val ex = b.right
            val ey = b.centerY()
            val cx = max(spanRight(la, lb), max(a.right, b.right)) + off
            path.moveTo(sx, sy)
            path.cubicTo(cx, sy, cx, ey, ex + arrow * 0.2f, ey)
            tipX = ex; tipY = ey; ctrlX = cx; ctrlY = ey
            labelX = cx - 4f * density; labelY = (sy + ey) / 2f
        } else if (b.top >= a.bottom - 1f) {
            // 常规向下边：底部中点 → 顶部中点，纵向贝塞尔
            val sx = a.centerX()
            val sy = a.bottom
            val ex = b.centerX()
            val ey = b.top
            val dy = max((ey - sy) * 0.5f, 24f * density)
            path.moveTo(sx, sy)
            path.cubicTo(sx, sy + dy, ex, ey - dy, ex, ey - arrow * 0.2f)
            tipX = ex; tipY = ey; ctrlX = ex; ctrlY = ey - dy
            labelX = (sx + ex) / 2f; labelY = (sy + ey) / 2f
        } else {
            // 同层/向上的非回边（少见，如 JUMP 指向同层）：从左侧绕行
            val off = lane * 0.55f
            val sx = a.left
            val sy = a.centerY()
            val ex = b.left
            val ey = b.centerY()
            val cx = min(spanLeft(la, lb), min(a.left, b.left)) - off
            path.moveTo(sx, sy)
            path.cubicTo(cx, sy, cx, ey, ex - arrow * 0.2f, ey)
            tipX = ex; tipY = ey; ctrlX = cx; ctrlY = ey
            labelX = cx + 4f * density; labelY = (sy + ey) / 2f
        }

        val angle = atan2((tipY - ctrlY).toDouble(), (tipX - ctrlX).toDouble())
        val arrowPath = Path().apply {
            moveTo(tipX, tipY)
            lineTo((tipX - arrow * cos(angle - Math.PI / 7)).toFloat(), (tipY - arrow * sin(angle - Math.PI / 7)).toFloat())
            lineTo((tipX - arrow * cos(angle + Math.PI / 7)).toFloat(), (tipY - arrow * sin(angle + Math.PI / 7)).toFloat())
            close()
        }

        // 边标签放在曲线中点
        val pm = PathMeasure(path, false)
        val mid = FloatArray(2)
        val lx: Float
        val ly: Float
        if (pm.length > 0f && pm.getPosTan(pm.length / 2f, mid, null)) {
            lx = mid[0]; ly = mid[1]
        } else {
            lx = labelX; ly = labelY
        }

        val p = palette
        val base = if (e.type == DagEdgeType.BACK_EDGE) e.baseType else e.type
        val color = when {
            p == null -> 0xFF888888.toInt()
            e.type == DagEdgeType.BACK_EDGE -> when (base) {
                DagEdgeType.BRANCH_TRUE -> p.edgeTrue
                DagEdgeType.BRANCH_FALSE -> p.edgeFalse
                else -> p.edgeBack
            }
            else -> when (base) {
                DagEdgeType.BRANCH_TRUE -> p.edgeTrue
                DagEdgeType.BRANCH_FALSE -> p.edgeFalse
                DagEdgeType.JUMP, DagEdgeType.EXIT_EDGE -> p.edgeJump
                else -> p.edgeNext
            }
        }
        val label = when {
            e.label.isNotEmpty() -> e.label
            e.type == DagEdgeType.BACK_EDGE -> "循环"
            else -> ""
        }
        val dashed = e.type == DagEdgeType.BACK_EDGE || e.type == DagEdgeType.JUMP || e.type == DagEdgeType.EXIT_EDGE
        return EdgeGeom(path, arrowPath, color, dashed, label, lx, ly)
    }

    // ── 视口 ──

    private fun fitTarget(): Triple<Float, Float, Float> {
        val w = width.toFloat()
        val h = height.toFloat()
        val margin = 12f * density
        // 适配缩放不低于 fitMinScale：再小节点文字就读不清了，宁可顶端对齐、让用户拖动查看其余部分
        val s = min((w - margin * 2) / bounds.width(), (h - margin * 2) / bounds.height())
            .coerceIn(fitMinScale, 1.25f)
        val ntx = if (bounds.width() * s > w - margin * 2) margin - bounds.left * s
        else (w - bounds.width() * s) / 2f - bounds.left * s
        // 内容比视口高时顶端对齐（起始节点在上），否则垂直居中
        val nty = if (bounds.height() * s > h - margin * 2) margin - bounds.top * s
        else (h - bounds.height() * s) / 2f - bounds.top * s
        return Triple(s, ntx, nty)
    }

    private fun fitToView(animate: Boolean) {
        if (graph == null || width == 0 || height == 0) return
        val (s, ntx, nty) = fitTarget()
        userMoved = false
        if (animate) animateTo(s, ntx, nty) else { scale = s; tx = ntx; ty = nty; invalidate() }
    }

    private fun animateTo(s: Float, ntx: Float, nty: Float) {
        animator?.cancel()
        val s0 = scale; val x0 = tx; val y0 = ty
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 320
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener {
                val f = it.animatedValue as Float
                scale = s0 + (s - s0) * f
                tx = x0 + (ntx - x0) * f
                ty = y0 + (nty - y0) * f
                invalidate()
            }
            start()
        }
    }

    /** 平移范围基于真实内容 bounds（min/max），保证至少留 1/3 内容在视口内。 */
    private fun clampTranslate() {
        if (width == 0 || height == 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        val cw = bounds.width() * scale
        val ch = bounds.height() * scale
        val keepX = min(cw, w) / 3f
        val keepY = min(ch, h) / 3f
        val minTx = keepX - bounds.right * scale
        val maxTx = w - keepX - bounds.left * scale
        val minTy = keepY - bounds.bottom * scale
        val maxTy = h - keepY - bounds.top * scale
        tx = tx.coerceIn(min(minTx, maxTx), max(minTx, maxTx))
        ty = ty.coerceIn(min(minTy, maxTy), max(minTy, maxTy))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (graph != null) {
            if (!userMoved || oldw == 0) fitToView(animate = false) else clampTranslate()
            pendingFocus?.let { focusNode(it) }
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    // ── 绘制 ──

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val p = palette
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        drawGrid(canvas)
        if (graph == null || p == null) return

        canvas.save()
        canvas.translate(tx, ty)
        canvas.scale(scale, scale)

        for (e in edgeGeoms) {
            edgePaint.color = e.color
            edgePaint.pathEffect = if (e.dashed) dash else null
            canvas.drawPath(e.path, edgePaint)
            arrowPaint.color = e.color
            canvas.drawPath(e.arrow, arrowPaint)
        }
        for (e in edgeGeoms) {
            if (e.label.isEmpty()) continue
            val tw = edgeLabelPaint.measureText(e.label)
            val fh = edgeLabelPaint.textSize
            labelRect.set(e.labelX - tw / 2f - 5f * density, e.labelY - fh * 0.75f, e.labelX + tw / 2f + 5f * density, e.labelY + fh * 0.55f)
            canvas.drawRoundRect(labelRect, fh, fh, edgeLabelBg)
            edgeLabelPaint.color = e.color
            canvas.drawText(e.label, e.labelX, e.labelY + fh * 0.3f, edgeLabelPaint)
        }

        val sel = selectedNodeId
        for (g in nodeGeoms) drawNode(canvas, g, p, g.node.stepId == sel)

        canvas.restore()
    }

    private fun drawNode(canvas: Canvas, g: NodeGeom, p: DagPalette, selected: Boolean) {
        val r = g.rect
        val accent = p.categoryAccent[g.node.category] ?: p.edgeNext
        canvas.drawRoundRect(r, corner, corner, nodePaint)

        // 左侧类别色条（裁剪在圆角内）
        canvas.save()
        tmpPath.reset()
        tmpPath.addRoundRect(r, corner, corner, Path.Direction.CW)
        canvas.clipPath(tmpPath)
        barPaint.color = accent
        canvas.drawRect(r.left, r.top, r.left + barW, r.bottom, barPaint)
        canvas.restore()

        borderPaint.color = when {
            selected -> p.selectedBorder
            g.node.isTrigger -> p.startBorder
            else -> p.nodeBorder
        }
        borderPaint.strokeWidth = (if (selected) 2.4f else if (g.node.isTrigger) 1.8f else 1f) * density
        canvas.drawRoundRect(r, corner, corner, borderPaint)

        tilePaint.color = p.categoryContainer[g.node.category] ?: 0
        canvas.drawRoundRect(g.tile, 9f * density, 9f * density, tilePaint)
        categoryIcons[g.node.category]?.let { icon ->
            iconPaint.color = accent
            canvas.save()
            canvas.concat(g.iconMatrix)
            canvas.drawPath(icon, iconPaint)
            canvas.restore()
        }

        val textX = g.tile.right + pad * 0.75f
        canvas.drawText(g.label, textX, r.centerY() - 2f * density, labelPaint)
        canvas.drawText(g.type, textX, r.centerY() + 14f * density, typePaint)

        if (g.node.isTrigger) {
            val text = "开始"
            val tw = badgeText.measureText(text)
            val bh = 16f * density
            badgeRect.set(r.left + pad, r.top - bh / 2f, r.left + pad + tw + 12f * density, r.top + bh / 2f)
            canvas.drawRoundRect(badgeRect, bh / 2f, bh / 2f, badgePaint)
            canvas.drawText(text, badgeRect.centerX(), badgeRect.centerY() + badgeText.textSize * 0.35f, badgeText)
        }
    }

    /** 背景点阵：屏幕空间绘制，间距随缩放，过密时跳过。 */
    private fun drawGrid(canvas: Canvas) {
        val step = 24f * density * scale
        if (step < 10f * density) return
        val w = width.toFloat()
        val h = height.toFloat()
        val startX = tx - floor(tx / step) * step
        val startY = ty - floor(ty / step) * step
        val cols = (w / step).toInt() + 2
        val rows = (h / step).toInt() + 2
        val need = cols * rows * 2
        if (dots.size < need) dots = FloatArray(need)
        var i = 0
        var y = startY
        for (rI in 0 until rows) {
            var x = startX
            for (cI in 0 until cols) {
                dots[i++] = x; dots[i++] = y
                x += step
            }
            y += step
        }
        canvas.drawPoints(dots, 0, i, dotPaint)
    }

    // ── 手势 ──

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!interactive) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            animator?.cancel()
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val newScale = (scale * detector.scaleFactor).coerceIn(minScale, maxScale)
            val f = newScale / scale
            tx = detector.focusX - (detector.focusX - tx) * f
            ty = detector.focusY - (detector.focusY - ty) * f
            scale = newScale
            userMoved = true
            clampTranslate()
            invalidate()
            return true
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scaleDetector.isInProgress) return false
            tx -= dx
            ty -= dy
            if (abs(dx) + abs(dy) > 0f) userMoved = true
            clampTranslate()
            invalidate()
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            val hit = hitTest(e.x, e.y)
            if (hit != null) {
                selectedNodeId = hit.stepId
                performClick()
                onNodeClicked?.invoke(hit)
            } else {
                selectedNodeId = null
                onBackgroundClicked?.invoke()
            }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            val hit = hitTest(e.x, e.y) ?: return
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            selectedNodeId = hit.stepId
            onNodeLongPressed?.invoke(hit)
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            fitToView(animate = true)
            return true
        }
    }

    override fun performClick(): Boolean = super.performClick()

    private fun hitTest(sx: Float, sy: Float): DagNode? {
        val cx = (sx - tx) / scale
        val cy = (sy - ty) / scale
        for (i in nodeGeoms.indices.reversed()) {
            if (nodeGeoms[i].rect.contains(cx, cy)) return nodeGeoms[i].node
        }
        return null
    }
}
