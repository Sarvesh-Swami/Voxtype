package app.speaker.dictate

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import android.view.animation.OvershootInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The floating bubble's character: a boy in a cap and colourful jacket, holding an open laptop, on a
 * transparent background. Drawn in code (vector shapes in a 100 x 138 box) so each part can move.
 *
 *  - [Mode.VIBING]   (not dictating): headphones on, eyes closed, head bopping, music notes, equalizer on
 *                    the laptop. Settles into a still pose after a while to save battery.
 *  - [Mode.LISTENING] headphones slide down to his neck, eyes open, red camera light on the laptop.
 *                    While [speaking] (or after [nudge]) his hands type and lines appear on the screen.
 *  - [Mode.THINKING]  (AI rewrite / finishing): looks up, dots bounce on the screen.
 *
 * Respects "remove animations": with animators disabled it shows still poses.
 */
class BoyView(context: Context) : View(context) {

    enum class Mode { VIBING, LISTENING, THINKING }

    var mode = Mode.VIBING
        set(v) {
            if (field == v) return
            if (v == Mode.LISTENING && field == Mode.VIBING) progress = 0f
            field = v
            setHeadphones(if (v == Mode.VIBING) 0f else 1f)
            wake()
        }

    /** Mic level says the user is talking. */
    var speaking = false
        set(v) { field = v; if (v) lastNudge = now(); wake() }

    /** Mirror so he faces the middle of the screen when docked on the right edge. */
    var faceLeft = false
        set(v) { if (field != v) { field = v; invalidate() } }

    /** New words arrived: keep typing for a moment even if the level detector missed it. */
    fun nudge() { lastNudge = now(); wake() }

    /** Restart the idle vibe (tap, drag, state change). */
    fun wake() {
        lastActivity = now()
        removeCallbacks(tick)
        invalidate()
    }

    // ---------------------------------------------------------------- timing state

    private fun now() = SystemClock.uptimeMillis()
    private val start = now()
    private var lastFrame = 0L
    private var lastActivity = now()
    private var lastNudge = 0L
    private var hpFrom = 0f
    private var hpTo = 0f
    private var hpStart = 0L
    private var typeAmt = 0f       // 0..1, eased toward "typing"
    private var vibeAmt = 1f       // 0..1, eased toward "vibing"
    private var progress = 0f      // typed amount, in screen-line units
    private val overshoot = OvershootInterpolator(1.8f)
    private val tick = Runnable { invalidate() }

    private fun setHeadphones(target: Float) {
        hpFrom = headphones(now())
        hpTo = target
        hpStart = now()
    }

    private fun headphones(t: Long): Float {
        if (!ValueAnimator.areAnimatorsEnabled()) return hpTo
        val u = ((t - hpStart) / HP_MS.toFloat()).coerceIn(0f, 1f)
        return hpFrom + (hpTo - hpFrom) * overshoot.getInterpolation(u)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    // ---------------------------------------------------------------- paints and static shapes

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        color = INK; strokeWidth = OUTLINE
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val keys = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.3f; color = 0xFF8E97B3.toInt()
        pathEffect = DashPathEffect(floatArrayOf(2.2f, 1.1f), 0f)
    }
    private val p = Path()
    private val r = RectF()

    private val legL = Path().apply {
        moveTo(38f, 102f); lineTo(38.5f, 124f); quadTo(38.6f, 126f, 40.5f, 126f)
        lineTo(47f, 126f); quadTo(48.6f, 126f, 48.7f, 124f); lineTo(49.6f, 102f); close()
    }
    private val legR = mirror(legL)
    private val shoeL = Path().apply {
        moveTo(32.5f, 131f); quadTo(32.5f, 125.4f, 38.5f, 125.4f); lineTo(46f, 125.4f)
        quadTo(49.2f, 125.4f, 49.2f, 129f); lineTo(49.2f, 131f); close()
    }
    private val shoeR = mirror(shoeL)
    private val torso = Path().apply {
        moveTo(34f, 57.5f); quadTo(50f, 54.5f, 66f, 57.5f); quadTo(71f, 58.5f, 71f, 64f)
        lineTo(71f, 100f); quadTo(71f, 104f, 67f, 104f); lineTo(33f, 104f)
        quadTo(29f, 104f, 29f, 100f); lineTo(29f, 64f); quadTo(29f, 58.5f, 34f, 57.5f); close()
    }
    private val capDome = Path().apply {
        moveTo(33.4f, 34f); cubicTo(33.4f, 16.8f, 66.6f, 16.8f, 66.6f, 34f); quadTo(50f, 30.5f, 33.4f, 34f); close()
    }
    private val capBrim = Path().apply {
        moveTo(28.5f, 34f); quadTo(45f, 42.5f, 64.5f, 35.6f); quadTo(48f, 35.4f, 33.4f, 32.4f); close()
    }
    private val hair = Path().apply {
        moveTo(33.6f, 39f); quadTo(32.6f, 30f, 38f, 28f); lineTo(62f, 28f); quadTo(67.4f, 30f, 66.4f, 39f)
        lineTo(63.4f, 37f); lineTo(36.6f, 37f); close()
    }
    private val fringe = Path().apply {
        moveTo(38f, 35f); lineTo(40.5f, 38.8f); lineTo(42.5f, 35.5f); lineTo(45f, 39f); lineTo(47f, 35.5f); close()
    }
    private val lid = RectF(28.5f, 69f, 71.5f, 94f)
    private val screen = RectF(31.5f, 72f, 68.5f, 91f)
    private val base = Path().apply {
        moveTo(25.5f, 94f); lineTo(74.5f, 94f); lineTo(72.2f, 99.6f); quadTo(71.8f, 100.6f, 70.6f, 100.6f)
        lineTo(29.4f, 100.6f); quadTo(28.2f, 100.6f, 27.8f, 99.6f); close()
    }

    private fun mirror(src: Path) = Path(src).apply {
        transform(android.graphics.Matrix().apply { setScale(-1f, 1f, 50f, 0f) })
    }

    private fun Canvas.shape(path: Path, color: Int) {
        fill.color = color; drawPath(path, fill); drawPath(path, ink)
    }

    private fun Canvas.dot(x: Float, y: Float, rad: Float, color: Int, outline: Boolean = true) {
        fill.color = color; drawCircle(x, y, rad, fill)
        if (outline) drawCircle(x, y, rad, ink)
    }

    private fun Canvas.oval(cx: Float, cy: Float, rx: Float, ry: Float, color: Int, outline: Boolean = true) {
        r.set(cx - rx, cy - ry, cx + rx, cy + ry)
        fill.color = color; drawOval(r, fill)
        if (outline) drawOval(r, ink)
    }

    /** Thick outlined stroke (sleeves, headphone band). */
    private fun Canvas.tube(path: Path, width: Float, color: Int) {
        stroke.color = INK; stroke.strokeWidth = width + OUTLINE * 2; drawPath(path, stroke)
        stroke.color = color; stroke.strokeWidth = width; drawPath(path, stroke)
    }

    private fun Canvas.line(x1: Float, y1: Float, x2: Float, y2: Float, color: Int, w: Float) {
        stroke.color = color; stroke.strokeWidth = w; drawLine(x1, y1, x2, y2, stroke)
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        val t = now()
        val dt = if (lastFrame == 0L) 0f else ((t - lastFrame) / 1000f).coerceAtMost(0.1f)
        lastFrame = t
        val anim = ValueAnimator.areAnimatorsEnabled()
        val sec = (t - start) / 1000f

        // Eased amounts.
        val typing = mode == Mode.LISTENING && (speaking || t - lastNudge < NUDGE_MS)
        val typeTarget = if (typing) 1f else 0f
        typeAmt += (typeTarget - typeAmt) * min(1f, dt * if (typing) 14f else 5f)
        if (!anim) typeAmt = 0f
        val vibing = mode == Mode.VIBING && t - lastActivity < VIBE_MS && anim
        vibeAmt += ((if (vibing) 1f else 0f) - vibeAmt) * min(1f, dt * 3f)
        if (!anim) vibeAmt = 0f
        if (vibeAmt < 0.01f) vibeAmt = 0f
        progress += dt * typeAmt * 2.4f

        val hp = headphones(t)
        val beat = (sec % BEAT_S) / BEAT_S
        val tilt = sin(beat * 2 * PI).toFloat() * 4.5f * vibeAmt
        val bob = ((1 - cos(beat * 4 * PI)) / 2).toFloat() * 1.3f * vibeAmt
        val tap = max(0f, sin(beat * 2 * PI).toFloat()) * vibeAmt

        canvas.save()
        val s = min(width / VW, height / VH)
        canvas.translate((width - VW * s) / 2f, height - VH * s)
        canvas.scale(s, s)
        if (faceLeft) canvas.scale(-1f, 1f, VW / 2f, 0f)

        // Head pivot and cup positions (cups follow the head while on it).
        val px = 50f; val py = 56f
        fun rot(x: Float, y: Float, deg: Float): Pair<Float, Float> {
            val a = Math.toRadians(deg.toDouble())
            val c = cos(a).toFloat(); val sn = sin(a).toFloat()
            return (px + (x - px) * c - (y - py) * sn) to (py + (x - px) * sn + (y - py) * c)
        }
        val (hlx, hly) = rot(32.8f, 41f, tilt)
        val (hrx, hry) = rot(67.2f, 41f, tilt)
        val cupLx = lerp(hlx, 39.5f, hp); val cupLy = lerp(hly, 61.5f, hp) + bob
        val cupRx = lerp(hrx, 60.5f, hp); val cupRy = lerp(hry, 61.5f, hp) + bob
        val cupRx2 = lerp(4.2f, 5.2f, hp); val cupRy2 = lerp(5.8f, 3.8f, hp)

        fun band() {
            p.reset()
            p.moveTo(cupLx, cupLy - cupRy2 * 0.6f)
            val cy = lerp(2.5f + bob, 51f + bob, hp)
            p.quadTo((cupLx + cupRx) / 2f, cy, cupRx, cupRy - cupRy2 * 0.6f)
            canvas.tube(p, 2.2f, HP_BAND)
        }

        // Band behind the neck once the headphones are down.
        if (hp >= 0.5f) band()

        // Legs and shoes (right foot taps to the beat).
        canvas.shape(legL, JEANS)
        canvas.shape(legR, JEANS)
        canvas.line(38.8f, 121.5f, 48.6f, 121.5f, JEANS_DARK, 1.4f)
        canvas.line(61.2f, 121.5f, 51.4f, 121.5f, JEANS_DARK, 1.4f)
        drawShoe(canvas, shoeL, right = false)
        canvas.save()
        canvas.rotate(-tap * 9f, 52f, 133f) // heel stays down, toe lifts
        drawShoe(canvas, shoeR, right = true)
        canvas.restore()

        // Everything above the knees bobs.
        canvas.save()
        canvas.translate(0f, bob)

        // Jacket.
        canvas.shape(torso, JACKET)
        p.reset(); p.moveTo(64f, 60f); p.quadTo(70f, 62f, 70f, 70f); p.lineTo(70f, 98f); p.lineTo(66f, 98f); p.close()
        fill.color = JACKET_DARK; canvas.drawPath(p, fill)
        r.set(29f, 99f, 71f, 104f); fill.color = TRIM; canvas.drawRoundRect(r, 2f, 2f, fill); canvas.drawRoundRect(r, 2f, 2f, ink)
        canvas.line(50f, 61f, 50f, 70f, JACKET_DARK, 1.4f)

        // Neck.
        r.set(45.4f, 49f, 54.6f, 60f); fill.color = SKIN; canvas.drawRect(r, fill)
        canvas.line(45.4f, 50f, 45.4f, 58f, INK, OUTLINE); canvas.line(54.6f, 50f, 54.6f, 58f, INK, OUTLINE)
        fill.color = SKIN_SHADE; r.set(45.4f, 53f, 54.6f, 56f); canvas.drawRect(r, fill)
        // Collar.
        p.reset(); p.moveTo(41.5f, 57f); p.quadTo(50f, 62.5f, 58.5f, 57f)
        canvas.tube(p, 2.6f, SLEEVE)

        // Head (tilts to the beat).
        canvas.save()
        canvas.rotate(tilt, px, py)
        canvas.dot(33.6f, 41f, 3.4f, SKIN)
        canvas.dot(66.4f, 41f, 3.4f, SKIN)
        canvas.shape(hair, HAIR)
        canvas.dot(50f, 38.5f, 16.8f, SKIN)
        fill.color = HAIR; canvas.drawPath(fringe, fill)
        drawFace(canvas, t, hp)
        canvas.shape(capDome, CAP)
        canvas.line(50f, 19.6f, 50.6f, 31f, CAP_DARK, 1.2f)
        canvas.dot(43.6f, 26.8f, 3.2f, CAP_LOGO)
        canvas.dot(42.8f, 25.9f, 0.9f, 0xFFFFFFFF.toInt(), outline = false)
        canvas.dot(50f, 19.4f, 1.5f, CAP_DARK)
        canvas.shape(capBrim, CAP_DARK)
        canvas.restore()

        // Band over the cap while the headphones are on.
        if (hp < 0.5f) band()

        // Sleeves (behind the laptop), laptop, then hands on the keyboard.
        val wave = sec * 2 * PI * 7
        val handL = -max(0f, sin(wave).toFloat()) * 2.2f * typeAmt
        val handR = -max(0f, sin(wave + PI).toFloat()) * 2.2f * typeAmt
        p.reset(); p.moveTo(32.5f, 63f); p.quadTo(23.5f, 82f, 37f, 95.5f + handL)
        canvas.tube(p, 9f, SLEEVE)
        p.reset(); p.moveTo(67.5f, 63f); p.quadTo(76.5f, 82f, 63f, 95.5f + handR)
        canvas.tube(p, 9f, SLEEVE)

        fill.color = LAPTOP; canvas.drawRoundRect(lid, 3f, 3f, fill); canvas.drawRoundRect(lid, 3f, 3f, ink)
        fill.color = SCREEN; canvas.drawRoundRect(screen, 1.6f, 1.6f, fill)
        drawScreen(canvas, t, sec, hp)
        val led = when (mode) {
            Mode.LISTENING -> REC.withAlpha(0.6f + 0.4f * sin(sec * 2 * PI * 1.2).toFloat())
            Mode.THINKING -> CAP_LOGO
            Mode.VIBING -> 0xFF9AA2B8.toInt()
        }
        canvas.dot(50f, 70.6f, 0.95f, led, outline = false)
        canvas.shape(base, LAPTOP_DARK)
        canvas.drawLine(31f, 96.8f, 69f, 96.8f, keys)

        canvas.dot(37f, 95.6f + handL, 3.6f, TRIM)
        canvas.dot(63f, 95.6f + handR, 3.6f, TRIM)
        canvas.dot(38.2f, 97.2f + handL, 4f, SKIN)
        canvas.dot(61.8f, 97.2f + handR, 4f, SKIN)
        canvas.restore() // bob

        // Headphone cups (on top: on the ears, or resting on the collar).
        canvas.oval(cupLx, cupLy, cupRx2, cupRy2, HP_CUP)
        canvas.oval(cupLx, cupLy, cupRx2 * 0.55f, cupRy2 * 0.62f, HP_CUP_IN, outline = false)
        canvas.oval(cupRx, cupRy, cupRx2, cupRy2, HP_CUP)
        canvas.oval(cupRx, cupRy, cupRx2 * 0.55f, cupRy2 * 0.62f, HP_CUP_IN, outline = false)

        // Music notes float out of the headphones while he vibes.
        if (hp < 0.35f && vibeAmt > 0f) {
            drawNote(canvas, sec, 0f, cupRx, cupRy, NOTE_A)
            drawNote(canvas, sec, 1.15f, cupRx, cupRy, NOTE_B)
        }
        canvas.restore()

        scheduleNext(t, anim, hp)
    }

    private fun drawShoe(c: Canvas, shoe: Path, right: Boolean) {
        c.shape(shoe, SHOE)
        r.set(if (right) 50.8f else 32.5f, 130.6f, if (right) 67.5f else 49.2f, 134.2f)
        fill.color = SOLE; c.drawRoundRect(r, 1.6f, 1.6f, fill); c.drawRoundRect(r, 1.6f, 1.6f, ink)
        p.reset()
        if (right) { p.moveTo(63f, 128.6f); p.quadTo(59f, 130.2f, 55f, 128.2f) } else { p.moveTo(37f, 128.6f); p.quadTo(41f, 130.2f, 45f, 128.2f) }
        stroke.color = TRIM; stroke.strokeWidth = 1.5f; c.drawPath(p, stroke)
    }

    private fun drawFace(c: Canvas, t: Long, hp: Float) {
        val eyesOpen = mode != Mode.VIBING || hp > 0.45f
        val blink = mode == Mode.LISTENING && (t - start) % 3400 < 120
        val lookUp = if (mode == Mode.THINKING) -1.3f else 0f
        // Cheeks.
        c.dot(39.6f, 46.6f, 2.5f, BLUSH, outline = false)
        c.dot(60.4f, 46.6f, 2.5f, BLUSH, outline = false)
        // Brows.
        val browUp = if (mode == Mode.THINKING) -1.2f else if (eyesOpen) 0f else 0.7f
        p.reset(); p.moveTo(41f, 37.6f + browUp * 0.3f); p.quadTo(43.5f, 36.2f + browUp * 0.3f, 46f, 37.4f + browUp * 0.3f)
        stroke.color = HAIR; stroke.strokeWidth = 1.5f; c.drawPath(p, stroke)
        p.reset(); p.moveTo(54f, 37.4f + browUp); p.quadTo(56.5f, 36.2f + browUp, 59f, 37.6f + browUp)
        c.drawPath(p, stroke)
        // Eyes.
        if (eyesOpen && !blink) {
            for (x in floatArrayOf(43.5f, 56.5f)) {
                c.oval(x, 42.6f + lookUp, 2.3f, 2.9f, INK, outline = false)
                c.dot(x + 0.8f, 41.5f + lookUp, 0.85f, 0xFFFFFFFF.toInt(), outline = false)
            }
        } else {
            stroke.color = INK; stroke.strokeWidth = 1.7f
            for (x in floatArrayOf(43.5f, 56.5f)) {
                p.reset(); p.moveTo(x - 2.4f, 43.2f); p.quadTo(x, 40.6f, x + 2.4f, 43.2f); c.drawPath(p, stroke)
            }
        }
        // Mouth.
        p.reset()
        when {
            !eyesOpen -> { // happy, singing along
                p.moveTo(45.6f, 48f); p.quadTo(50f, 54f, 54.4f, 48f); p.close()
                fill.color = MOUTH; c.drawPath(p, fill); c.drawPath(p, ink)
            }
            mode == Mode.THINKING -> { // "hmm" smirk
                p.moveTo(47f, 49.2f); p.quadTo(50.6f, 50.9f, 53.6f, 48.4f)
                stroke.color = INK; stroke.strokeWidth = 1.5f; c.drawPath(p, stroke)
            }
            else -> {
                p.moveTo(46.4f, 48.8f); p.quadTo(50f, 51.6f, 53.6f, 48.8f)
                stroke.color = INK; stroke.strokeWidth = 1.5f; c.drawPath(p, stroke)
            }
        }
    }

    private fun drawScreen(c: Canvas, t: Long, sec: Float, hp: Float) {
        c.save()
        c.clipRect(screen)
        when {
            mode == Mode.THINKING -> {
                for (i in 0..2) {
                    val up = max(0f, sin(sec * 2 * PI * 1.6 - i * 0.7).toFloat()) * 2.6f
                    c.dot(44f + i * 6f, 83f - up, 1.8f, BAR_COLORS[i * 2], outline = false)
                }
                val tw = 0.6f + 0.4f * sin(sec * 2 * PI * 0.9).toFloat()
                drawSparkle(c, 63.5f, 76f, 2.6f * tw)
            }
            mode == Mode.VIBING || hp < 0.5f -> {
                // Equalizer.
                for (i in 0 until 7) {
                    val wave = 0.5f + 0.5f * sin(sec * 2 * PI * (1.3 + 0.19 * i) + i * 1.3).toFloat()
                    val h = 3f + 13f * (0.25f + 0.75f * wave * vibeAmt)
                    r.set(34f + i * 4.9f, 89.5f - h, 37.2f + i * 4.9f, 89.5f)
                    fill.color = BAR_COLORS[i]; c.drawRoundRect(r, 1f, 1f, fill)
                }
            }
            else -> drawText(c, t)
        }
        c.restore()
    }

    /** Fake text lines that grow while he types; the last four lines are shown. */
    private fun drawText(c: Canvas, t: Long) {
        val lineW = 31f
        val full = floor(progress).toInt()
        val first = max(0, full - 3)
        var y = 74.6f
        var caretX = 34f; var caretY = y
        for (li in first..full) {
            val filled = if (li < full) lineW else (progress - full) * lineW
            var x = 0f; var k = 0
            fill.color = LINE_COLORS[li % LINE_COLORS.size]
            while (x < filled) {
                val w = (((li * 7 + k * 13) % 6) + 3).toFloat()
                val dw = min(w, filled - x)
                r.set(34f + x, y, 34f + x + dw, y + 2.4f)
                c.drawRoundRect(r, 1.2f, 1.2f, fill)
                x += w + 1.6f; k++
            }
            caretX = 34f + min(filled, lineW) + 0.9f; caretY = y
            y += 4.4f
        }
        if (typeAmt > 0.2f || (t - start) % 1000 < 560) {
            r.set(caretX, caretY - 0.6f, caretX + 1.1f, caretY + 3f)
            fill.color = 0xFFFFFFFF.toInt(); c.drawRect(r, fill)
        }
    }

    private fun drawSparkle(c: Canvas, x: Float, y: Float, s: Float) {
        p.reset()
        p.moveTo(x, y - s); p.quadTo(x, y, x + s, y); p.quadTo(x, y, x, y + s); p.quadTo(x, y, x - s, y); p.quadTo(x, y, x, y - s)
        fill.color = CAP_LOGO; c.drawPath(p, fill)
    }

    private fun drawNote(c: Canvas, sec: Float, offset: Float, fromX: Float, fromY: Float, color: Int) {
        val u = ((sec + offset) % 2.3f) / 2.3f
        val a = sin(u * PI).toFloat() * vibeAmt
        if (a <= 0.02f) return
        val x = fromX + 4f + u * 14f + sin(u * 2 * PI * 1.5).toFloat() * 2.4f
        val y = fromY - 6f - u * 30f
        val col = color.withAlpha(a)
        c.save()
        c.rotate(-18f, x, y)
        r.set(x - 2.4f, y - 1.7f, x + 2.4f, y + 1.7f)
        fill.color = col; c.drawOval(r, fill)
        c.restore()
        stroke.color = col; stroke.strokeWidth = 1.2f
        c.drawLine(x + 2.1f, y - 0.6f, x + 2.1f, y - 8f, stroke)
        p.reset(); p.moveTo(x + 2.1f, y - 8f); p.quadTo(x + 5.6f, y - 6.4f, x + 4.4f, y - 3.6f)
        c.drawPath(p, stroke)
    }

    /** Smooth frames only while something moves; slow ticks for the caret; nothing at rest. */
    private fun scheduleNext(t: Long, anim: Boolean, hp: Float) {
        removeCallbacks(tick)
        if (!anim || !isShown) return
        val transition = t - hpStart < HP_MS + 50
        val typingSoon = mode == Mode.LISTENING && (speaking || t - lastNudge < NUDGE_MS)
        when {
            transition || typeAmt > 0.01f || typingSoon || mode == Mode.THINKING -> postInvalidateOnAnimation()
            vibeAmt > 0f -> postOnAnimationDelayed(tick, 33)
            mode == Mode.LISTENING -> postOnAnimationDelayed(tick, 250) // caret blink + eye blink
            else -> Unit // resting: still frame until wake()
        }
    }

    private fun lerp(a: Float, b: Float, u: Float) = a + (b - a) * u
    private fun Int.withAlpha(a: Float): Int = ((a.coerceIn(0f, 1f) * 255).toInt() shl 24) or (this and 0xFFFFFF)

    private companion object {
        const val VW = 100f
        const val VH = 138f
        const val OUTLINE = 2.1f
        const val HP_MS = 650L
        const val NUDGE_MS = 700L
        const val VIBE_MS = 30_000L
        const val BEAT_S = 0.62f

        val INK = 0xFF2A1F3D.toInt()
        val SKIN = 0xFFF1B88B.toInt()
        val SKIN_SHADE = 0xFFDE9C6C.toInt()
        val BLUSH = 0x88FF7FA3.toInt()
        val MOUTH = 0xFFB4234A.toInt()
        val HAIR = 0xFF3B2618.toInt()
        val CAP = 0xFFFF4D6D.toInt()
        val CAP_DARK = 0xFFD92A4E.toInt()
        val CAP_LOGO = 0xFFFFD23F.toInt()
        val JACKET = 0xFFFFB703.toInt()
        val JACKET_DARK = 0xFFF08C00.toInt()
        val SLEEVE = 0xFF7C4DFF.toInt()
        val TRIM = 0xFF4CE0B3.toInt()
        val JEANS = 0xFF3B5BDB.toInt()
        val JEANS_DARK = 0xFF2A44B0.toInt()
        val SHOE = 0xFFFFFFFF.toInt()
        val SOLE = 0xFFFF5CA8.toInt()
        val HP_BAND = 0xFF3A2E5C.toInt()
        val HP_CUP = 0xFF22C3FF.toInt()
        val HP_CUP_IN = 0xFF0E8FD0.toInt()
        val LAPTOP = 0xFFE6EAF4.toInt()
        val LAPTOP_DARK = 0xFFC3CADC.toInt()
        val SCREEN = 0xFF1C1F3A.toInt()
        val REC = 0xFFFF3B3B.toInt()
        val NOTE_A = 0xFFFF5CA8.toInt()
        val NOTE_B = 0xFF7C4DFF.toInt()
        val BAR_COLORS = intArrayOf(0xFFFF5CA8.toInt(), 0xFFFF8A3D.toInt(), 0xFFFFD23F.toInt(), 0xFFB6F25A.toInt(),
            0xFF4CE0B3.toInt(), 0xFF22C3FF.toInt(), 0xFF9B7BFF.toInt())
        val LINE_COLORS = intArrayOf(0xFF4CE0B3.toInt(), 0xFFE9ECFF.toInt(), 0xFFFFD23F.toInt(), 0xFFFF8FC0.toInt())
    }
}
