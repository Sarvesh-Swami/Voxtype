package app.speaker.dictate

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The floating bubble's character, on a transparent background: a young guy in a green jacket, seen from
 * the side, leaning his back against the screen edge with his legs crossed. Drawn in code in a 70 x 150 box
 * (faces right, back against the left edge; mirrored when docked on the right).
 *
 *  - [Mode.VIBING]    headphones on, eyes closed, a slight nod and toe tap; the laptop hangs closed at his side.
 *                     Goes still after a while to save battery.
 *  - [Mode.LISTENING] headphones slide down to his neck, he raises the laptop and opens the lid (we only see its
 *                     back), and the screen light falls on his face. He types while [speaking] or after [nudge].
 *  - [Mode.THINKING]  same pose, hands still, the screen light pulses softly.
 *
 * With system animations off he switches between still poses.
 */
class BoyView(context: Context) : View(context) {

    enum class Mode { VIBING, LISTENING, THINKING }

    var mode = Mode.VIBING
        set(v) {
            if (field == v) return
            field = v
            qFrom = q(now()); qStart = now()
            qTo = if (v == Mode.VIBING) 0f else 1f
            wake()
        }

    /** Mic level says the user is talking. */
    var speaking = false
        set(v) { field = v; if (v) lastNudge = now(); wake() }

    /** Mirror so he leans on the right edge and faces left. */
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

    // ------------------------------------------------------------------ timing

    private fun now() = SystemClock.uptimeMillis()
    private val start = now()
    private var lastFrame = 0L
    private var lastActivity = now()
    private var lastNudge = 0L
    private var qFrom = 0f
    private var qTo = 0f
    private var qStart = 0L
    private var typeAmt = 0f
    private var vibeAmt = 1f
    private val tick = Runnable { invalidate() }

    /** Activation progress 0 (vibing) .. 1 (laptop open). Phases: headphones, raise, lid. */
    private fun q(t: Long): Float {
        if (!ValueAnimator.areAnimatorsEnabled()) return qTo
        val dur = if (qTo > qFrom) OPEN_MS else CLOSE_MS
        val u = ((t - qStart) / dur.toFloat()).coerceIn(0f, 1f)
        return qFrom + (qTo - qFrom) * u
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    // ------------------------------------------------------------------ shapes (viewport units)

    private val head = svg("M49 7.5 C43.2 7.5 39.2 11.6 39.2 17.4 C39.2 21.8 40.9 25.2 43.6 26.8 C45.2 27.8 46.4 28.4 47.6 28.8 C50 29.6 52.4 29.8 54 29.2 C55.2 28.8 55.8 28.2 55.8 27.4 L56.2 25.8 L57.1 24.6 L56.8 23.6 L57.4 23 L59.3 21.8 C59.4 21.2 58.4 20 57.8 18.8 L58.2 16 C58.2 12.6 56.4 9.4 53.4 8.2 C52 7.7 50.5 7.5 49 7.5 Z")
    private val faceRim = svg("M57.8 13 L58.2 16 L57.8 18.8 C58.4 20 59.4 21.2 59.3 21.8 L57.4 23 L57.1 24.6 L56.2 25.8 L55.8 27.4")
    private val hair = svg("M39.4 19.5 C38.6 13 41.5 7.2 48.5 6.2 C53.5 5.6 57.6 7.6 58.6 11 C59 12.4 58.6 13.6 57.6 14 C56.6 12.6 55 12 53.6 12.6 C52 13.2 50.2 12.8 49.2 12 C48.6 14 47.6 15.4 46.2 16 L45.4 21.2 C43.8 21.8 42.4 23 41.8 24.6 C40.6 23.2 39.6 21.4 39.4 19.5 Z")
    private val ear = svg("M46.6 16.2 C48.6 16.2 49.4 18.4 49 20.6 C48.6 22.6 47.2 23.4 46 22.8 C45 21.4 45 17.6 46.6 16.2 Z")
    private val neck = svg("M44.4 25 L51.6 27 L52.6 34 L44 35 Z")
    private val collar = svg("M44.6 30.6 C47.8 29.6 51.4 30.2 53.6 31.8 L54.2 35.6 C51.2 34.4 48 34.2 44.6 34.8 Z")
    private val torso = svg("M45 32.8 C42 33.2 40.2 35.4 39.8 39 C39.2 46 39.6 56 40.8 66 L41.4 73.2 L56.8 73.4 C57.6 66 57.8 60 58 54 C58.4 48 58.8 43 57.2 38.4 C56.2 35.8 54.8 34 53 33.4 C50.4 32.6 47.4 32.5 45 32.8 Z")
    private val legBack = svg("M42.5 72 L55.5 72 C56.6 86 57.6 100 58.4 112 L61.4 134 L55 134.8 L51.8 112 C48.6 100 44.2 86 42.5 72 Z")
    private val legFront = svg("M47 73.6 L57.6 73.6 C60.6 85 62.6 95 62.4 104.6 C61 113 55.6 123.4 50.6 133.2 L44.4 131.8 C48 122 53.4 112 55 104 C54.4 96 50.6 86 47 73.6 Z")
    private val shoeBack = svg("M53.2 132.8 L61.6 133 C64.8 133.4 68.2 135.6 69.4 138.2 L69.6 140.6 L52.8 140.6 L52.6 135.6 Z")
    private val shoeFront = svg("M43.6 130.6 L50.4 131.8 C53.4 133.4 56.2 136.6 56.6 139.4 L56.2 141.2 L47.8 140.8 C45.4 138.8 43.6 134.8 43.6 130.6 Z")
    private val soleFront = svg("M47.8 140.8 L56.2 141.2 L56.4 142.4 L48.8 142.2 Z")

    // ------------------------------------------------------------------ paints

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        color = RIM; strokeWidth = 0.7f
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val shaded = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val p = Path()

    private fun lin(x0: Float, y0: Float, x1: Float, y1: Float, vararg c: Int) =
        LinearGradient(x0, y0, x1, y1, c, null, Shader.TileMode.CLAMP)

    private val gLegBack = lin(42f, 0f, 60f, 0f, DEN1, DEN2)
    private val gLegFront = lin(46f, 0f, 63f, 0f, DEN2, DEN3, DEN2)
    private val gShoe = lin(0f, 131f, 0f, 141f, SHOE, SHOE_D)
    private val gTorso = lin(40f, 0f, 58f, 0f, JKT1, JKT2, JKT3)
    private val gNeck = lin(44f, 0f, 52f, 0f, SKIN_D, SKIN)
    private val gHead = lin(39f, 0f, 59f, 0f, SKIN_D, SKIN, SKIN_L)
    private val gHair = lin(0f, 6f, 0f, 24f, HAIR_L, HAIR)

    private fun Canvas.shade(path: Path, shader: Shader, outline: Boolean = true) {
        shaded.shader = shader; drawPath(path, shaded)
        if (outline) drawPath(path, rim)
    }

    private fun Canvas.solid(path: Path, color: Int, outline: Boolean = true) {
        fill.color = color; drawPath(path, fill)
        if (outline) drawPath(path, rim)
    }

    private fun Canvas.seg(x1: Float, y1: Float, x2: Float, y2: Float, color: Int, w: Float) {
        stroke.color = color; stroke.strokeWidth = w; drawLine(x1, y1, x2, y2, stroke)
    }

    /** Sleeve: two-bone arm with a soft dark edge. */
    private fun Canvas.arm(sx: Float, sy: Float, ex: Float, ey: Float, hx: Float, hy: Float, w: Float, color: Int) {
        p.reset(); p.moveTo(sx, sy); p.lineTo(ex, ey); p.lineTo(hx, hy)
        stroke.color = RIM; stroke.strokeWidth = w + 1.6f; drawPath(p, stroke)
        stroke.color = color; stroke.strokeWidth = w; drawPath(p, stroke)
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(canvas: Canvas) {
        val t = now()
        val dt = if (lastFrame == 0L) 0f else ((t - lastFrame) / 1000f).coerceAtMost(0.1f)
        lastFrame = t
        val anim = ValueAnimator.areAnimatorsEnabled()
        val sec = (t - start) / 1000f

        val typing = mode == Mode.LISTENING && (speaking || t - lastNudge < NUDGE_MS)
        typeAmt += ((if (typing) 1f else 0f) - typeAmt) * min(1f, dt * if (typing) 14f else 5f)
        val vibing = mode == Mode.VIBING && t - lastActivity < VIBE_MS && anim
        vibeAmt += ((if (vibing) 1f else 0f) - vibeAmt) * min(1f, dt * 3f)
        if (!anim) { typeAmt = 0f; vibeAmt = 0f }
        if (vibeAmt < 0.01f) vibeAmt = 0f

        val q = q(t)
        val hpP = smooth(0f, 0.35f, q)
        val raise = smooth(0.22f, 0.7f, q)
        val lid = smooth(0.6f, 1f, q)
        val beat = (sec % BEAT_S) / BEAT_S
        val nod = sin(beat * 2 * PI).toFloat() * 2.5f * vibeAmt * (1 - hpP)
        val tap = max(0f, sin(beat * 2 * PI).toFloat()) * vibeAmt
        val headTilt = lerp(nod, 5f, raise)
        val flicker = if (typeAmt > 0.05f) 0.92f + 0.08f * sin(sec * 37f) else 1f
        val pulse = if (mode == Mode.THINKING) 0.82f + 0.18f * sin(sec * 2 * PI.toFloat() * 0.8f) else 1f
        val glow = smooth(0.25f, 0.9f, lid) * flicker * pulse

        canvas.save()
        val s = min(width / VW, height / VH)
        canvas.translate((width - VW * s) / 2f, height - VH * s)
        canvas.scale(s, s)
        if (faceLeft) canvas.scale(-1f, 1f, VW / 2f, 0f)
        canvas.translate(SHIFT, 0f)
        canvas.rotate(LEAN, 50f, 142f)

        // Laptop frame: hinge position and rotation, lid opening angle.
        val hx = lerp(51.8f, 82f, raise); val hy = lerp(90f, 56.4f, raise)
        val ang = Math.toRadians(lerp(90f, 0f, raise).toDouble())
        val ca = cos(ang).toFloat(); val sa = sin(ang).toFloat()
        fun wx(lx: Float, ly: Float) = hx + lx * ca - ly * sa
        fun wy(lx: Float, ly: Float) = hy + lx * sa + ly * ca
        val th = Math.toRadians(lerp(0f, 106f, lid).toDouble())
        val dX = -cos(th).toFloat(); val dY = -sin(th).toFloat()      // lid direction (local)
        val nX = sin(th).toFloat(); val nY = -cos(th).toFloat()       // lid outer normal (local)
        val lsx = wx(dX * 11.5f, dY * 11.5f); val lsy = wy(dX * 11.5f, dY * 11.5f) // screen light source

        // Far arm (behind the body): hand in the pocket, then on the keyboard.
        val tapY = typeAmt * max(0f, sin(sec * 44f)) * 1.6f
        val sfx = 45.5f; val sfy = 38f
        val hfx = lerp(46.5f, 69.5f, raise); val hfy = lerp(63f, 53.6f - tapY, raise)
        val (efx, efy) = ik(sfx, sfy, hfx, hfy, 15.5f, 15f)
        canvas.arm(sfx, sfy, efx, efy, hfx, hfy, 5.6f, JKT1)

        // Legs: weight-bearing back leg, then the front leg crossing over it.
        canvas.shade(legBack, gLegBack)
        canvas.shade(shoeBack, gShoe)
        fill.color = SOLE; canvas.drawRect(52.4f, 140.2f, 69.8f, 142.2f, fill)
        p.reset(); p.moveTo(57.4f, 138.4f); p.quadTo(61f, 136.4f, 65f, 137.6f)
        stroke.color = ACCENT; stroke.strokeWidth = 1f; canvas.drawPath(p, stroke)
        canvas.shade(legFront, gLegFront)
        p.reset(); p.moveTo(59.8f, 96f); p.quadTo(61.6f, 102f, 60.4f, 108f)
        stroke.color = 0x14FFFFFF; stroke.strokeWidth = 1.4f; canvas.drawPath(p, stroke)
        canvas.save()
        canvas.rotate(-tap * 6f, 56f, 141.2f) // heel lifts on the beat, toe stays down
        canvas.shade(shoeFront, gShoe)
        canvas.solid(soleFront, SOLE, outline = false)
        p.reset(); p.moveTo(47.4f, 136f); p.quadTo(50.8f, 134.6f, 54f, 137.4f)
        stroke.color = ACCENT; stroke.strokeWidth = 1f; canvas.drawPath(p, stroke)
        canvas.restore()

        // Jacket.
        canvas.shade(torso, gTorso)
        canvas.save()
        canvas.clipPath(torso)
        fill.color = BLACK; canvas.drawRect(38f, 70.4f, 60f, 74.4f, fill)
        canvas.seg(38f, 70.6f, 60f, 70.6f, WHITE, 0.6f)
        p.reset(); p.moveTo(56.8f, 38f); p.quadTo(57.8f, 56f, 56.6f, 70.4f)
        stroke.color = JKT_D; stroke.strokeWidth = 0.7f; canvas.drawPath(p, stroke)
        p.reset(); p.moveTo(50.5f, 34f); p.quadTo(53.8f, 48f, 51.6f, 70.4f)
        stroke.color = WHITE; stroke.strokeWidth = 1.2f; canvas.drawPath(p, stroke)
        p.reset(); p.moveTo(48.6f, 34f); p.quadTo(51.8f, 48f, 49.6f, 70.4f)
        stroke.color = BLACK; stroke.strokeWidth = 1.4f; canvas.drawPath(p, stroke)
        canvas.restore()

        canvas.shade(neck, gNeck, outline = false)
        canvas.solid(collar, BLACK)
        p.reset(); p.moveTo(44.8f, 31.6f); p.quadTo(49.4f, 30.4f, 53.8f, 32.8f)
        stroke.color = WHITE; stroke.strokeWidth = 0.55f; canvas.drawPath(p, stroke)

        // Head.
        canvas.save()
        canvas.rotate(headTilt, 47.5f, 28f)
        canvas.shade(head, gHead)
        canvas.save(); canvas.clipPath(head)
        fill.color = 0x40784629; canvas.save(); canvas.rotate(-17f, 46f, 26f); canvas.drawOval(39f, 22.8f, 53f, 29.2f, fill); canvas.restore()
        canvas.restore()
        canvas.solid(ear, SKIN_D)
        drawFace(canvas, t, q, raise)
        canvas.shade(hair, gHair)
        p.reset(); p.moveTo(44f, 9.4f); p.quadTo(50f, 6.8f, 55.6f, 8.8f)
        stroke.color = 0x1FFFFFFF; stroke.strokeWidth = 0.8f; canvas.drawPath(p, stroke)
        if (glow > 0.01f) {
            // Screen light on his face (only where the head is).
            canvas.save(); canvas.clipPath(head)
            shaded.shader = RadialGradient(lsx, lsy, 26f,
                intArrayOf(light(0.95f * glow), light2(0.55f * glow), light2(0f)), floatArrayOf(0f, 0.62f, 1f), Shader.TileMode.CLAMP)
            canvas.drawRect(30f, 0f, 70f, 40f, shaded)
            stroke.color = rimLight(0.8f * glow); stroke.strokeWidth = 1.3f; canvas.drawPath(faceRim, stroke)
            canvas.restore()
        }
        canvas.restore()

        // Headphones: on his head, or resting around the neck.
        val cupX = lerp(47.2f, 46.8f, hpP); val cupY = lerp(19.6f, 32.4f, hpP)
        canvas.save()
        p.reset()
        if (hpP < 0.5f) {
            canvas.rotate(headTilt, 47.5f, 28f)
            p.moveTo(46.8f, 15.4f); p.cubicTo(45.6f, 9.6f, 47.4f, 4.8f, 51.4f, 4.4f)
        } else {
            p.moveTo(cupX - 1f, cupY - 2f); p.cubicTo(43f, 31f, 41f, 33f, 42.6f, 35.4f)
        }
        stroke.color = RIM; stroke.strokeWidth = 2.6f; canvas.drawPath(p, stroke)
        stroke.color = HP; stroke.strokeWidth = 1.8f; canvas.drawPath(p, stroke)
        stroke.color = HP_L; stroke.strokeWidth = 0.5f; canvas.drawPath(p, stroke)
        canvas.restore()
        canvas.save()
        canvas.translate(cupX, cupY)
        canvas.rotate(lerp(0f, -50f, hpP))
        fill.color = HP; canvas.drawOval(-3.3f, -4.5f, 3.3f, 4.5f, fill)
        rim.strokeWidth = 0.6f; canvas.drawOval(-3.3f, -4.5f, 3.3f, 4.5f, rim); rim.strokeWidth = 0.7f
        fill.color = HP_L; canvas.drawOval(-1.1f, -3.2f, 2.1f, 2f, fill)
        fill.color = ACCENT; canvas.drawCircle(0.4f, -0.4f, 0.7f, fill)
        canvas.restore()

        // Laptop base, then the lid (only its outside is ever visible).
        quad(canvas, wx(0f, 0f), wy(0f, 0f), wx(-24f, 0f), wy(-24f, 0f), wx(-24f, 1.9f), wy(-24f, 1.9f), wx(0f, 1.9f), wy(0f, 1.9f),
            lin(wx(0f, 0f), wy(0f, 0f), wx(0f, 1.9f), wy(0f, 1.9f), LAP1, LAP2))
        quad(canvas, wx(0f, 0f), wy(0f, 0f), wx(dX * 23f, dY * 23f), wy(dX * 23f, dY * 23f),
            wx(dX * 23f + nX * 1.4f, dY * 23f + nY * 1.4f), wy(dX * 23f + nX * 1.4f, dY * 23f + nY * 1.4f),
            wx(nX * 1.4f, nY * 1.4f), wy(nX * 1.4f, nY * 1.4f), null, LAP2)
        if (glow > 0.01f) {
            // A faint beam from the screen toward his face.
            val tx = wx(dX * 22f, dY * 22f); val ty = wy(dX * 22f, dY * 22f)
            val bx = wx(dX * 2f, dY * 2f); val by = wy(dX * 2f, dY * 2f)
            shaded.shader = LinearGradient(lsx, lsy, 57f, 20f, light2(0.16f * glow), light2(0f), Shader.TileMode.CLAMP)
            p.reset(); p.moveTo(tx, ty); p.lineTo(58f, 13f); p.lineTo(58f, 28f); p.lineTo(bx, by); p.close()
            canvas.drawPath(p, shaded)
        }

        // Near arm holds the laptop.
        val snx = 48.6f; val sny = 38.4f
        val hnx = lerp(50.6f, 61.5f, raise); val hny = lerp(69.5f, 58.4f, raise)
        val (enx, eny) = ik(snx, sny, hnx, hny, 16f, 15.2f)
        canvas.arm(snx, sny, enx, eny, hnx, hny, 6.6f, JKT2)
        canvas.seg(snx + 1.4f, sny, enx + 1.2f, eny, 0x2EFFFFFF, 1.4f)
        cuff(canvas, enx, eny, hnx, hny, 6.2f, 0.76f, 0.84f)
        fill.color = SKIN; canvas.drawOval(hnx - 2.6f, hny - 2f, hnx + 3f, hny + 2.4f, fill)
        canvas.drawOval(hnx - 2.6f, hny - 2f, hnx + 3f, hny + 2.4f, rim)
        if (raise > 0.05f) {
            fill.color = SKIN_D; canvas.drawOval(hfx - 2.6f, hfy - 2f, hfx + 3f, hfy + 2f, fill)
            canvas.drawOval(hfx - 2.6f, hfy - 2f, hfx + 3f, hfy + 2f, rim)
            cuff(canvas, efx, efy, hfx, hfy, 5f, 0.78f, 0.85f)
        }
        if (glow > 0.01f) {
            canvas.save(); canvas.clipPath(torso)
            shaded.shader = RadialGradient(lsx, lsy, 24f, light(0.4f * glow), light(0f), Shader.TileMode.CLAMP)
            canvas.drawRect(30f, 25f, 70f, 80f, shaded)
            canvas.restore()
        }
        canvas.restore()

        scheduleNext(t, anim, q)
    }

    private fun cuff(c: Canvas, ex: Float, ey: Float, hx: Float, hy: Float, w: Float, a: Float, b: Float) {
        val ax = lerp(ex, hx, a); val ay = lerp(ey, hy, a); val bx = lerp(ex, hx, b); val by = lerp(ey, hy, b)
        c.seg(ax, ay, bx, by, BLACK, w)
        c.seg(ax, ay, bx, by, WHITE, 0.5f)
    }

    private fun quad(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float, x4: Float, y4: Float,
                     shader: Shader?, color: Int = 0) {
        p.reset(); p.moveTo(x1, y1); p.lineTo(x2, y2); p.lineTo(x3, y3); p.lineTo(x4, y4); p.close()
        if (shader != null) { shaded.shader = shader; c.drawPath(p, shaded) } else { fill.color = color; c.drawPath(p, fill) }
        stroke.color = LAP_EDGE; stroke.strokeWidth = 0.5f; c.drawPath(p, stroke)
    }

    private fun drawFace(c: Canvas, t: Long, q: Float, raise: Float) {
        val blink = mode != Mode.VIBING && (t - start) % 3400 < 120
        val open = q > 0.12f && !blink
        if (open) {
            p.reset(); p.moveTo(53.4f, 17.6f); p.quadTo(55.1f, 16.7f, 56.7f, 17.4f); p.quadTo(55.2f, 18.5f, 53.4f, 17.6f)
            fill.color = EYE_WHITE; c.drawPath(p, fill)
            fill.color = IRIS; c.drawCircle(55.7f, 17.75f + raise * 0.25f, 0.9f, fill)
            p.reset(); p.moveTo(53.2f, 17.5f); p.quadTo(55.1f, 16.3f, 56.9f, 17.2f)
            stroke.color = IRIS; stroke.strokeWidth = 0.75f; c.drawPath(p, stroke)
        } else {
            p.reset(); p.moveTo(53.3f, 17.7f); p.quadTo(55.1f, 18.6f, 56.8f, 17.6f)
            stroke.color = IRIS; stroke.strokeWidth = 0.8f; c.drawPath(p, stroke)
        }
        val by = if (open) -0.1f else 0.2f
        p.reset(); p.moveTo(52.8f, 15.3f + by); p.quadTo(55f, 14.4f, 57.5f, 15f)
        stroke.color = HAIR; stroke.strokeWidth = 1.05f; c.drawPath(p, stroke)
        c.seg(55.3f, 25.7f, 56.7f, 25.4f, LIPS, 0.65f)
        p.reset(); p.moveTo(57.2f, 22.6f); p.quadTo(57.9f, 23.2f, 58.4f, 22.4f)
        stroke.color = NOSTRIL; stroke.strokeWidth = 0.5f; c.drawPath(p, stroke)
    }

    /** Smooth frames only while something moves; slow ticks for blinking; nothing at rest. */
    private fun scheduleNext(t: Long, anim: Boolean, q: Float) {
        removeCallbacks(tick)
        if (!anim || !isShown) return
        val moving = q != qTo
        val typingSoon = mode == Mode.LISTENING && (speaking || t - lastNudge < NUDGE_MS)
        when {
            moving || typeAmt > 0.01f || typingSoon || mode == Mode.THINKING -> postInvalidateOnAnimation()
            vibeAmt > 0f -> postOnAnimationDelayed(tick, 33)
            mode == Mode.LISTENING -> postOnAnimationDelayed(tick, 120) // blinks
            else -> Unit // resting: still frame until wake()
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun ik(sx: Float, sy: Float, hx: Float, hy: Float, a: Float, b: Float): Pair<Float, Float> {
        val dx = hx - sx; val dy = hy - sy
        val len = hypot(dx, dy).coerceAtLeast(0.001f)
        val d = min(len, a + b - 0.01f)
        val ux = dx / len; val uy = dy / len
        val al = acos(((a * a + d * d - b * b) / (2 * a * d)).coerceIn(-1f, 1f))
        val c = cos(al); val s = sin(al)
        return (sx + a * (ux * c - uy * s)) to (sy + a * (ux * s + uy * c))
    }

    private fun smooth(e0: Float, e1: Float, v: Float): Float {
        val x = ((v - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return x * x * (3 - 2 * x)
    }

    private fun lerp(a: Float, b: Float, u: Float) = a + (b - a) * u
    private fun alpha(a: Float, rgb: Int) = ((a.coerceIn(0f, 1f) * 255).toInt() shl 24) or rgb
    private fun light(a: Float) = alpha(a, 0xD7F2FF)
    private fun light2(a: Float) = alpha(a, 0xAFE1FF)
    private fun rimLight(a: Float) = alpha(a, 0xE1F6FF)

    /** Minimal SVG path reader (absolute M, L, C, Q, Z), so shapes can be written as path strings. */
    private fun svg(d: String): Path {
        val path = Path()
        val tok = Regex("[MLCQZ]|-?\\d*\\.?\\d+").findAll(d).map { it.value }.toList()
        var i = 0; var cmd = 'M'
        fun n() = tok[i++].toFloat()
        while (i < tok.size) {
            val tkn = tok[i]
            if (tkn[0].isLetter()) { cmd = tkn[0]; i++; if (cmd == 'Z') { path.close(); continue } }
            when (cmd) {
                'M' -> { path.moveTo(n(), n()); cmd = 'L' }
                'L' -> path.lineTo(n(), n())
                'C' -> path.cubicTo(n(), n(), n(), n(), n(), n())
                'Q' -> path.quadTo(n(), n(), n(), n())
                else -> i++
            }
        }
        return path
    }

    private companion object {
        const val VW = 70f
        const val VH = 150f
        const val LEAN = -8f         // degrees: top leans back against the edge
        const val SHIFT = -19f       // moves his back flush with the edge
        const val OPEN_MS = 1400L
        const val CLOSE_MS = 1100L
        const val NUDGE_MS = 700L
        const val VIBE_MS = 30_000L
        const val BEAT_S = 0.62f

        val RIM = 0x8C0A0C10.toInt()
        val SKIN = 0xFFD7A07C.toInt()
        val SKIN_D = 0xFFB47A58.toInt()
        val SKIN_L = 0xFFE8BC9A.toInt()
        val HAIR = 0xFF231813.toInt()
        val HAIR_L = 0xFF4A3428.toInt()
        val EYE_WHITE = 0xFFF3EEE8.toInt()
        val IRIS = 0xFF2B1E17.toInt()
        val LIPS = 0xCC783C2D.toInt()
        val NOSTRIL = 0x99784632.toInt()
        val JKT1 = 0xFF1E5A1E.toInt()
        val JKT2 = 0xFF2E9B34.toInt()
        val JKT3 = 0xFF4CC043.toInt()
        val JKT_D = 0xFF174A17.toInt()
        val BLACK = 0xFF18191C.toInt()
        val WHITE = 0xFFEDEFF2.toInt()
        val DEN1 = 0xFF1E2840.toInt()
        val DEN2 = 0xFF2E3D5E.toInt()
        val DEN3 = 0xFF3B4D74.toInt()
        val SHOE = 0xFFF1F2F4.toInt()
        val SHOE_D = 0xFFC9CCD2.toInt()
        val SOLE = 0xFF2B2D31.toInt()
        val ACCENT = 0xFF2E9B34.toInt()
        val LAP1 = 0xFFA9AEB7.toInt()
        val LAP2 = 0xFF5C626C.toInt()
        val LAP_EDGE = 0xFF2D3138.toInt()
        val HP = 0xFF1B1C20.toInt()
        val HP_L = 0xFF3A3C44.toInt()
    }
}
