package com.pimax.whisperflow

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.Build
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import kotlin.math.min

/**
 * CardExpandView — Siri-style animation that expands from the camera punch hole
 * into a floating frosted dark card, then collapses back on dismiss.
 *
 * Replaces SnakeView/TreeView as a third animation option for the assistant overlay.
 *
 * Animation phases:
 *   START: punchHole glow → circle expands to rounded rect → fade to full size
 *   REVERSE: rect shrinks to circle → returns to punch hole position
 */
class CardExpandView(context: Context) : View(context) {

    private val dp = resources.displayMetrics.density
    private val TAG = "🃏 CardExpand"

    // Animation state
    private var expandProgress = 0f  // 0 = punch hole, 1 = full card
    private var glowAlpha = 0f
    private var contentAlpha = 0f
    private var isAnimating = false

    // Punch hole origin (will be set from DisplayCutout or fallback to top-center)
    private var originX = 0f
    private var originY = 0f
    private var punchRadius = 7f * dp  // half of ~14dp punch hole

    // Card target dimensions
    private val cardWidth get() = width - (20f * dp)  // 10dp inset each side
    private val cardHeight = 220f * dp
    private val cardTop = 52f * dp  // below punch hole with gap
    private val cardRadius = 22f * dp

    // Colors (matching Siri card spec + WhisperFlow red theme)
    private val cardBgColor = Color.argb((0.88f * 255).toInt(), 14, 14, 20)
    private val glowColor = Color.argb(180, 255, 51, 51)  // red glow ring
    private val borderColor = Color.argb(15, 255, 255, 255)

    // Paints
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = cardBgColor
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = borderColor
        style = Paint.Style.STROKE
        strokeWidth = 1f * dp
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = glowColor
        style = Paint.Style.STROKE
        strokeWidth = 3f * dp
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(100, 0, 0, 0)
        maskFilter = BlurMaskFilter(20f * dp, BlurMaskFilter.Blur.NORMAL)
        style = Paint.Style.FILL
    }

    var animationSpeed = 1.0f

    // Callback for when the card is fully expanded (waveform should start)
    private var onExpandComplete: (() -> Unit)? = null

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)  // needed for blur/shadow effects
    }

    /**
     * Set the camera punch hole origin explicitly.
     * Call this from WhisperAssistSession.onShow() where the Window is available.
     */
    fun setCutoutOrigin(x: Float, y: Float, radius: Float) {
        originX = x
        originY = y
        punchRadius = radius
        Log.d(TAG, "📍 Origin set: ($x, $y) radius=$radius")
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Only set default if origin hasn't been explicitly set
        if (originX == 0f) {
            originX = w / 2f
            originY = 18f * dp  // Samsung S-series punch hole is very close to top
            detectCutoutFromWindow()
        }
    }

    private fun detectCutoutFromWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                // Try rootWindowInsets first
                val insets = rootWindowInsets
                val cutout = insets?.displayCutout
                val rects = cutout?.boundingRects
                if (!rects.isNullOrEmpty()) {
                    val rect = rects[0]
                    originX = rect.centerX().toFloat()
                    originY = rect.centerY().toFloat()
                    punchRadius = min(rect.width(), rect.height()) / 2f
                    Log.d(TAG, "📍 Cutout from insets: ($originX, $originY) r=$punchRadius")
                }
            } catch (e: Exception) {
                Log.d(TAG, "📍 Using default position (insets unavailable)")
            }
        }
    }

    // Separate progress for width vs height (width leads height — from Apple video)
    private var widthProgress = 0f
    private var heightProgress = 0f

    /**
     * Start the expand animation: punch hole → card
     *
     * Extracted from Apple WWDC video frame-by-frame analysis:
     * - Total expand: 267ms active + 150ms settle = ~417ms
     * - Width leads height by ~15ms
     * - Velocity profile: brief accel (0-66ms) → peak → long decel
     * - Tiny 3.3% overshoot at end (spring bounce-back)
     * - Easing: cubic-bezier(0.22, 0.61, 0.36, 1.0) — NOT simple ease-out
     *
     * @param targetCenterY ignored (API compat with SnakeView/TreeView)
     * @param onComplete called when card is fully expanded
     */
    fun start(targetCenterY: Float, onComplete: () -> Unit) {
        if (isAnimating) return
        isAnimating = true
        this.onExpandComplete = onComplete
        visibility = VISIBLE
        expandProgress = 0f
        widthProgress = 0f
        heightProgress = 0f
        glowAlpha = 0f
        contentAlpha = 0f

        // === From video analysis: the Apple animation has 3 concurrent layers ===

        // Layer 1: Glow ring (starts immediately, fades in over 80ms)
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (80 / animationSpeed).toLong()
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener {
                glowAlpha = it.animatedValue as Float
                invalidate()
            }
            start()
        }

        // Layer 2: WIDTH expansion (starts at 0ms, 267ms duration)
        // Easing: cubic-bezier(0.22, 0.61, 0.36, 1.0) — extracted from video
        // This is: brief acceleration → peak at 25% → long smooth deceleration
        ValueAnimator.ofFloat(0f, 1.033f, 1f).apply {
            // 1.033 = the 3.3% overshoot detected in video, then settles to 1.0
            duration = (417 / animationSpeed).toLong()  // 267ms expand + 150ms settle
            interpolator = android.view.animation.PathInterpolator(0.22f, 0.61f, 0.36f, 1.0f)
            addUpdateListener { anim ->
                widthProgress = anim.animatedValue as Float
                // Combine into single expandProgress for drawing (use width as primary)
                expandProgress = widthProgress.coerceIn(0f, 1.05f)
                invalidate()
            }
            start()
        }

        // Layer 3: HEIGHT expansion (starts 15ms AFTER width — height follows)
        // Slightly different curve — height catches up mid-animation
        ValueAnimator.ofFloat(0f, 1f).apply {
            startDelay = (15 / animationSpeed).toLong()
            duration = (280 / animationSpeed).toLong()
            interpolator = android.view.animation.PathInterpolator(0.25f, 0.55f, 0.40f, 1.0f)
            addUpdateListener { anim ->
                heightProgress = anim.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // Content reveals after height is done
                    contentAlpha = 1f
                    isAnimating = false
                    invalidate()
                    onExpandComplete?.invoke()
                }
            })
            start()
        }
    }

    /**
     * Reverse animation: card → punch hole
     *
     * From video analysis: collapse is FASTER than expand (~220ms vs 417ms).
     * It accelerates into the pill (ease-in feel) — opposite of expansion.
     * Height collapses first (15ms ahead), width follows.
     */
    fun reverse(onComplete: () -> Unit) {
        if (isAnimating) return
        isAnimating = true
        contentAlpha = 0f
        invalidate()

        // Height collapses first (inverse of "width leads height" on expand)
        ValueAnimator.ofFloat(heightProgress, 0f).apply {
            duration = (200 / animationSpeed).toLong()
            interpolator = android.view.animation.PathInterpolator(0.55f, 0f, 1f, 0.45f) // ease-in
            addUpdateListener {
                heightProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }

        // Width collapses 15ms after height starts
        ValueAnimator.ofFloat(widthProgress, 0f).apply {
            startDelay = (15 / animationSpeed).toLong()
            duration = (220 / animationSpeed).toLong()
            interpolator = android.view.animation.PathInterpolator(0.55f, 0f, 0.85f, 0.35f)
            addUpdateListener {
                widthProgress = it.animatedValue as Float
                expandProgress = widthProgress
                invalidate()
            }
            start()
        }

        // Glow fades out (overlaps with collapse)
        ValueAnimator.ofFloat(glowAlpha, 0f).apply {
            startDelay = (80 / animationSpeed).toLong()
            duration = (140 / animationSpeed).toLong()
            addUpdateListener {
                glowAlpha = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    isAnimating = false
                    visibility = INVISIBLE
                    onComplete()
                }
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (expandProgress == 0f && glowAlpha == 0f) return

        val w = width.toFloat()

        // --- Glow ring around punch hole ---
        if (glowAlpha > 0f) {
            glowPaint.alpha = (glowAlpha * 180).toInt()
            canvas.drawCircle(originX, originY, punchRadius + 4f * dp, glowPaint)
        }

        // --- Morphing shape: circle → rounded rect ---
        // Use SEPARATE width/height progress (width leads height = organic feel)
        val wt = widthProgress.coerceIn(0f, 1.05f)  // can overshoot to 1.033
        val ht = heightProgress.coerceIn(0f, 1.0f)
        val t = (wt + ht) / 2f  // combined for alpha/shadow

        if (wt > 0f || ht > 0f) {
            // Interpolate dimensions (width expands 3x, height 2.2x — from video)
            val currentWidth = lerp(punchRadius * 2f, cardWidth, wt.coerceAtMost(1f))
            val currentHeight = lerp(punchRadius * 2f, cardHeight, ht)
            val currentRadius = lerp(punchRadius, cardRadius, t.coerceAtMost(1f))
            val currentCenterX = lerp(originX, w / 2f, t.coerceAtMost(1f))
            val currentCenterY = lerp(originY, cardTop + cardHeight / 2f, ht)

            val left = currentCenterX - currentWidth / 2f
            val top = currentCenterY - currentHeight / 2f
            val right = currentCenterX + currentWidth / 2f
            val bottom = currentCenterY + currentHeight / 2f

            val rect = RectF(left, top, right, bottom)

            // Shadow (fades in after 30% progress)
            if (t > 0.3f) {
                shadowPaint.alpha = ((t - 0.3f) / 0.7f * 100f).toInt().coerceAtMost(100)
                val shadowRect = RectF(left + 2*dp, top + 4*dp, right + 2*dp, bottom + 8*dp)
                canvas.drawRoundRect(shadowRect, currentRadius, currentRadius, shadowPaint)
            }

            // Card background (opacity ramps with progress)
            cardPaint.alpha = (t.coerceAtMost(1f) * 0.88f * 255f).toInt()
            canvas.drawRoundRect(rect, currentRadius, currentRadius, cardPaint)

            // Border
            borderPaint.alpha = (t.coerceAtMost(1f) * 15f).toInt()
            canvas.drawRoundRect(rect, currentRadius, currentRadius, borderPaint)

            // Inset highlight line at top (appears after 50% expansion)
            if (t > 0.5f) {
                val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(((t - 0.5f) * 2f * 10f).toInt().coerceAtMost(10), 255, 255, 255)
                    strokeWidth = 1f * dp
                    style = Paint.Style.STROKE
                }
                val inset = 20f * dp
                canvas.drawLine(left + inset, top + 1f * dp, right - inset, top + 1f * dp, highlightPaint)
            }
        }
    }

    private fun lerp(start: Float, end: Float, t: Float): Float {
        return start + (end - start) * t
    }
}
