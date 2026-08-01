package com.scan2cell.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

class WordOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Kind { WORD, BARCODE }

    data class DetectedItem(
        val text: String,
        val bounds: Rect,
        val kind: Kind
    )

    private val items = mutableListOf<DetectedItem>()
    private var imageWidth = 1
    private var imageHeight = 1
    private var selectedIndex = -1
    private var selectionListener: ((DetectedItem) -> Unit)? = null

    private val normalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.8f)
        color = 0xE6FFFFFF.toInt()
    }
    private val barcodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.2f)
        color = 0xFF67E8F9.toInt()
    }
    private val selectedStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        color = 0xFF6C8CFF.toInt()
    }
    private val selectedFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x553F6EF5
    }

    fun setOnItemSelected(listener: (DetectedItem) -> Unit) {
        selectionListener = listener
    }

    fun setItems(newItems: List<DetectedItem>, width: Int, height: Int) {
        items.clear()
        items.addAll(newItems)
        imageWidth = width.coerceAtLeast(1)
        imageHeight = height.coerceAtLeast(1)
        selectedIndex = -1
        invalidate()
    }

    fun select(index: Int) {
        if (index !in items.indices) return
        selectedIndex = index
        invalidate()
        selectionListener?.invoke(items[index])
    }

    fun clearItems() {
        items.clear()
        selectedIndex = -1
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (items.isEmpty()) return
        val transform = transform()

        items.forEachIndexed { index, item ->
            val mapped = mapRect(item.bounds, transform)
            val radius = dp(5f)
            if (index == selectedIndex) {
                canvas.drawRoundRect(mapped, radius, radius, selectedFill)
                canvas.drawRoundRect(mapped, radius, radius, selectedStroke)
            } else {
                canvas.drawRoundRect(
                    mapped,
                    radius,
                    radius,
                    if (item.kind == Kind.BARCODE) barcodePaint else normalPaint
                )
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (items.isEmpty()) return false
        if (event.action != MotionEvent.ACTION_UP) return true

        val transform = transform()
        val imageX = (event.x - transform.offsetX) / transform.scale
        val imageY = (event.y - transform.offsetY) / transform.scale
        val touchPadding = dp(10f) / transform.scale

        val containing = items.indices.filter { index ->
            val rect = RectF(items[index].bounds)
            rect.inset(-touchPadding, -touchPadding)
            rect.contains(imageX, imageY)
        }

        val selected = containing.minByOrNull { index ->
            val rect = items[index].bounds
            rect.width().toLong() * rect.height().toLong()
        } ?: nearestIndex(imageX, imageY, dp(46f) / transform.scale)

        if (selected != null) select(selected)
        performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun nearestIndex(x: Float, y: Float, maxDistance: Float): Int? {
        var bestIndex: Int? = null
        var bestDistance = Float.MAX_VALUE
        items.forEachIndexed { index, item ->
            val centerX = item.bounds.exactCenterX()
            val centerY = item.bounds.exactCenterY()
            val distance = hypot(centerX - x, centerY - y)
            if (distance < bestDistance && distance <= maxDistance) {
                bestDistance = distance
                bestIndex = index
            }
        }
        return bestIndex
    }

    private data class Transform(val scale: Float, val offsetX: Float, val offsetY: Float)

    private fun transform(): Transform {
        val scale = min(width.toFloat() / imageWidth, height.toFloat() / imageHeight)
        val displayedWidth = imageWidth * scale
        val displayedHeight = imageHeight * scale
        return Transform(
            scale = scale,
            offsetX = (width - displayedWidth) / 2f,
            offsetY = (height - displayedHeight) / 2f
        )
    }

    private fun mapRect(rect: Rect, transform: Transform): RectF {
        return RectF(
            transform.offsetX + rect.left * transform.scale,
            transform.offsetY + rect.top * transform.scale,
            transform.offsetX + rect.right * transform.scale,
            transform.offsetY + rect.bottom * transform.scale
        )
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
