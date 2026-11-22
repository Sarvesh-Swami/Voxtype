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
 * The floating bubble's character, on a transparent background: a guy in a baggy green hoodie and loose joggers,
 * seen from the side, leaning back against the screen edge with his legs crossed. Drawn in code in a 70 x 150 box
 * (faces right, back against the left edge; mirrored when docked on the right). Fabric folds, the bunched hood,
 * ribbed hem and cuffs and swaying drawstrings keep him from looking stiff.
 *
 *  - [Mode.VIBING]    headphones on, eyes closed, slight nod, toe tap, drawstrings swing; laptop closed at his side.
 *                     Goes still after a while to save battery.
 *  - [Mode.LISTENING] headphones slide to his neck, he raises the laptop and opens the lid (only its back is seen),
 *                     and the screen light falls on his face. He types while [speaking] or after [nudge].
 *  - [Mode.THINKING]  same pose, hands still, the screen light pulses softly.
 *
 * With system animations off he switches between still poses. Visual prototype: .ai-workspace/scratch/lean-boy2.html
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
    private val neck = svg("M44.4 25 L51.6 27 L52.4 33.4 L45.4 33.6 Z")
    private val neckline = svg("M46.6 32.4 C49.4 32.6 52.4 33.8 55.2 36.4")
    private val body = svg("M44 30.5 C40.5 29.5 37 31.5 36.6 35.5 C36.2 38.5 37.4 40.4 38.4 41.6 C37.2 47 36.8 56 38 64 C38.8 70 39.4 74 39.2 78.5 C39.2 80.2 40 81.2 41.4 81.6 L41.8 85.4 C47 86.4 54 86.4 58.8 85.2 L59 81.6 C61.2 80.8 62 78.6 61.6 75.6 C61.2 68 61.4 60 60.4 52 C59.6 45.6 58.6 40 55.6 35.8 C54 33.4 51.6 32.2 49.2 32 C47.6 31.6 45.8 30.8 44 30.5 Z")
    private val bodyFolds = svg("M49.6 62.4 C53 61.4 57.4 61.4 61 62.8 M49.6 62.4 C49.2 67 49.4 71 50 74.6 M42 76.6 Q46 79.6 50.4 77.6 M51.4 78.2 Q55.4 80.8 59.6 77.8 M44.6 66 Q46.2 72 44.2 78.4 M43.6 47 Q48.6 50.4 54 47.6 M41.2 55 Q44 58 43.4 63")
    private val bodyLights = svg("M57.6 44 Q60.4 52 59.6 60 M47 78.8 Q51 80.2 55 79.4")
    private val hood = svg("M44.6 29.6 C40.4 28 36.2 30.4 36 35 C35.8 39.4 39.2 42.4 43.4 41.8 C46.6 41.4 48.6 38 48.8 34.6 C48.9 32.2 47.2 30.4 44.6 29.6 Z")
    private val hoodFolds = svg("M38 34.6 Q41.6 33 45.6 35.6 M38.6 38.6 Q42 38.4 45.8 39.8")
    private val hoodLight = svg("M40 31.4 Q43.6 30.6 47 32.6")
    private val legBack = svg("M41 82 L58 82 C59 90 60.6 98 61.2 106 C61.6 114 63 124 63.8 131 C64 133 63.6 134.6 62.8 135.2 L55.4 135.6 C54.6 134 54.6 132.6 55 131 C53.6 123 52 114 50.6 107 C48.4 98 43.6 91 41 82 Z")
    private val legBackFolds = svg("M56 127 Q59.4 128.6 63 127.4 M55.4 130.2 Q59 131.8 63.4 130.4 M51.6 110 Q55 112 59.6 110.6")
    private val legFront = svg("M48 83 L60.4 83 C63.2 91 65.4 98 65.2 105 C64.8 112 59.6 122 54.8 130.4 C54.4 132 53.4 133.2 52.2 133.4 L46.6 132 C46.2 130.6 46.6 129.4 47.4 128.6 C50.6 121 55 112 56.6 105.4 C56.2 97 51.4 90 48 83 Z")
    private val legFrontFolds = svg("M58 99.6 Q61.6 102.4 65 100.6 M57.4 104.2 Q60.8 106 64.4 105 M50.6 125.6 Q53.2 127.4 55.8 126.4 M49 128.8 Q51.6 130.6 54.4 129.6 M49.4 88 Q53.6 92 58.4 90.6")
    private val legFrontLights = svg("M62.6 98.8 Q64 102 62.8 106.6 M51 92 Q55 97 55.8 103")
    private val shoeBack = svg("M55 133 L62.6 133.2 C66.4 133.6 70 135.8 71.2 138.4 L71.4 140.6 L54.6 140.6 L54.4 136 Z")
    private val shoeBackLine = svg("M59 138.4 Q62.6 136.4 66.6 137.6")
    private val shoeFront = svg("M45 130.4 L51.6 131.6 C54.8 133.2 57.8 136.6 58.2 139.6 L57.8 141.4 L49.2 141 C46.8 139 45 134.8 45 130.4 Z")
    private val soleFront = svg("M49.2 141 L57.8 141.4 L58 142.6 L50.2 142.4 Z")
    private val shoeFrontLine = svg("M48.8 136 Q52.2 134.6 55.4 137.4")

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
    private val p2 = Path()

    private fun lin(x0: Float, y0: Float, x1: Float, y1: Float, vararg c: Int) =
        LinearGradient(x0, y0, x1, y1, c, null, Shader.TileMode.CLAMP)

    private val gLegBack = lin(41f, 0f, 63f, 0f, P1, P2)
    private val gLegFront = lin(47f, 0f, 66f, 0f, P2, P3, P2)
    private val gShoe = lin(0f, 131f, 0f, 141f, SHOE, SHOE_D)
    private val gBody = lin(37f, 0f, 62f, 0f, H1, H2, H3)
    private val gBodyShade = lin(0f, 60f, 0f, 86f, 0x00000000, 0x2E000000)
    private val gHood = lin(36f, 0f, 49f, 0f, H1, H2)
    private val gNeck = lin(44f, 0f, 52f, 0f, SKIN_D, SKIN)
    private val gHead = lin(39f, 0f, 59f, 0f, SKIN_D, SKIN, SKIN_L)
    private val gHair = lin(0f, 6f, 0f, 24f, HAIR_L, HAIR)

    private fun Canvas.shade(path: Path, shader: Shader, outline: Boolean = true) {
        shaded.shader = shader; drawPath(path, shaded)
        if (outline) drawPath(path, rim)
    }

    private fun Canvas.lines(path: Path, color: Int, w: Float) {
        stroke.color = color; stroke.strokeWidth = w; drawPath(path, stroke)
    }

    /** Fabric folds and highlights clipped to a shape. */
    private fun Canvas.folds(clip: Path, folds: Path?, lights: Path?, foldColor: Int, lightColor: Int, w: Float = 0.65f) {
        save(); clipPath(clip)
        folds?.let { lines(it, foldColor, w) }
        lights?.let { lines(it, lightColor, 1.1f) }
        restore()
    }

    /** Baggy sleeve from shoulder S over elbow E to wrist H: tapered, curved, with folds and a ribbed cuff. */
    private fun Canvas.sleeve(sx: Float, sy: Float, ex: Float, ey: Float, hx: Float, hy: Float,
                              wS: Float, wE: Float, wH: Float, color: Int, cuff: Int) {
        val l1 = hypot(ex - sx, ey - sy).coerceAtLeast(0.01f); val n1x = -(ey - sy) / l1; val n1y = (ex - sx) / l1
        val l2 = hypot(hx - ex, hy - ey).coerceAtLeast(0.01f); val n2x = -(hy - ey) / l2; val n2y = (hx - ex) / l2
        var nex = (n1x + n2x) / 2; var ney = (n1y + n2y) / 2
        val nl = hypot(nex, ney).coerceAtLeast(0.01f); nex /= nl; ney /= nl
        p.reset()
        p.moveTo(sx + n1x * wS / 2, sy + n1y * wS / 2)
        p.quadTo(ex + nex * wE * 0.54f, ey + ney * wE * 0.54f, hx + n2x * wH / 2, hy + n2y * wH / 2)
        p.lineTo(hx - n2x * wH / 2, hy - n2y * wH / 2)
        p.quadTo(ex - nex * wE / 2, ey - ney * wE / 2, sx - n1x * wS / 2, sy - n1y * wS / 2)
        p.close()
        fill.color = color; drawPath(p, fill); drawPath(p, rim)
        drawCircle(sx, sy + 0.4f, wS * 0.52f, fill) // rounded shoulder
        save(); clipPath(p)
        stroke.color = SLEEVE_FOLD; stroke.strokeWidth = 0.55f
        for ((u, k) in arrayOf(0.58f to 0.5f, 0.8f to 0.35f)) {
            val cx = lerp(ex, hx, u); val cy = lerp(ey, hy, u)
            p2.reset()
            p2.moveTo(cx + n2x * wE * 0.55f, cy + n2y * wE * 0.55f)
            p2.quadTo(cx - (hx - ex) * 0.09f, cy - (hy - ey) * 0.09f, cx - n2x * wE * k, cy - n2y * wE * k)
            drawPath(p2, stroke)
        }
        p2.reset()
        p2.moveTo(ex - nex * wE / 2, ey - ney * wE / 2)
        p2.quadTo(ex + (n2x - n1x) * 2, ey + (n2y - n1y) * 2, lerp(ex, hx, 0.25f) - n2x * wE * 0.2f, lerp(ey, hy, 0.25f) - n2y * wE * 0.2f)
        drawPath(p2, stroke)
        val ax = sx + n1x * wS / 2; val ay = sy + n1y * wS / 2
        val bx = ex + nex * wE * 0.54f; val by = ey + ney * wE * 0.54f
        p2.reset(); p2.moveTo(lerp(ax, bx, 0.2f), lerp(ay, by, 0.2f))
        p2.quadTo(bx, by, lerp(bx, hx + n2x * wH / 2, 0.5f), lerp(by, hy + n2y * wH / 2, 0.5f))
        lines(p2, HOOD_LIGHT, 1f)
        restore()
        stroke.strokeCap = Paint.Cap.BUTT
        stroke.color = cuff; stroke.strokeWidth = wH * 1.02f
        drawLine(lerp(ex, hx, 0.9f), lerp(ey, hy, 0.9f), hx, hy, stroke)
        stroke.strokeCap = Paint.Cap.ROUND
    }

    private fun Canvas.hand(x: Float, y: Float, angleRad: Float, color: Int) {
        save(); translate(x, y); rotate(Math.toDegrees(angleRad.toDouble()).toFloat())
        fill.color = color
        rim.strokeWidth = 0.6f
        drawOval(-1.3f, -2.1f, 4.5f, 2.1f, fill); drawOval(-1.3f, -2.1f, 4.5f, 2.1f, rim)
        save(); rotate(-28.6f, 0.6f, -1.6f)
        drawOval(-0.8f, -2.5f, 2f, -0.7f, fill); drawOval(-0.8f, -2.5f, 2f, -0.7f, rim)
        restore()
        rim.strokeWidth = 0.7f
        restore()
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
        val headTilt = lerp(nod, 6f, raise)
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
        val hx = lerp(52.4f, 84f, raise); val hy = lerp(96f, 58.6f, raise)
        val ang = Math.toRadians(lerp(90f, 0f, raise).toDouble())
        val ca = cos(ang).toFloat(); val sa = sin(ang).toFloat()
        fun wx(lx: Float, ly: Float) = hx + lx * ca - ly * sa
        fun wy(lx: Float, ly: Float) = hy + lx * sa + ly * ca
        val th = Math.toRadians(lerp(0f, 106f, lid).toDouble())
        val dX = -cos(th).toFloat(); val dY = -sin(th).toFloat()      // lid direction (local)
        val nX = sin(th).toFloat(); val nY = -cos(th).toFloat()       // lid outer normal (local)
        val lsx = wx(dX * 11.5f, dY * 11.5f); val lsy = wy(dX * 11.5f, dY * 11.5f) // screen light source

        // Far arm (behind the body): hand in the hoodie pocket, then on the keyboard.
        val tapY = typeAmt * max(0f, sin(sec * 44f)) * 1.6f
        val sfx = 45f; val sfy = 40f
        val hfx = lerp(55f, 73f, raise); val hfy = lerp(69f, 55.2f - tapY, raise)
        val (efx, efy) = ik(sfx, sfy, hfx, hfy, 15.5f, 15.5f)
        canvas.sleeve(sfx, sfy, efx, efy, hfx, hfy, 8f, 7.4f, 5.6f, H1, CUFF_FAR)

        // Joggers: back leg and shoe, then the front leg crossing over it.
        canvas.shade(legBack, gLegBack)
        canvas.folds(legBack, legBackFolds, null, PANT_FOLD, PANT_LIGHT, 0.7f)
        canvas.shade(shoeBack, gShoe)
        fill.color = SOLE; canvas.drawRect(54.2f, 140.2f, 71.6f, 142.3f, fill)
        canvas.lines(shoeBackLine, ACCENT, 1f)
        canvas.shade(legFront, gLegFront)
        canvas.folds(legFront, legFrontFolds, legFrontLights, PANT_FOLD, PANT_LIGHT)
        canvas.save()
        canvas.rotate(-tap * 6f, 57.6f, 141.4f) // heel lifts on the beat, toe stays down
        canvas.shade(shoeFront, gShoe)
        fill.color = SOLE; canvas.drawPath(soleFront, fill)
        canvas.lines(shoeFrontLine, ACCENT, 1f)
        canvas.restore()

        // Hoodie body: shading, ribbed hem, pocket and drape folds.
        canvas.shade(body, gBody)
        canvas.save(); canvas.clipPath(body)
        shaded.shader = gBodyShade; canvas.drawRect(30f, 60f, 70f, 90f, shaded)
        fill.color = RIB; canvas.drawRect(38f, 81.4f, 62f, 86.4f, fill)
        for (i in 0 until 8) canvas.drawLine(41f + i * 2.4f, 81.6f, 41.2f + i * 2.4f, 86f, stroke.apply { color = 0x40000000; strokeWidth = 0.35f })
        canvas.lines(bodyFolds, HOOD_FOLD, 0.65f)
        canvas.lines(bodyLights, HOOD_LIGHT, 1.1f)
        canvas.restore()
        // Hood bunched behind the neck.
        canvas.shade(hood, gHood)
        canvas.lines(hoodFolds, HOOD_FOLD, 0.65f)
        canvas.lines(hoodLight, HOOD_LIGHT, 0.8f)
        canvas.shade(neck, gNeck, outline = false)
        canvas.lines(neckline, H1, 1.6f)

        // Head.
        canvas.save()
        canvas.rotate(headTilt, 47.5f, 28f)
        canvas.shade(head, gHead)
        canvas.save(); canvas.clipPath(head)
        fill.color = 0x40784629; canvas.save(); canvas.rotate(-17f, 46f, 26f); canvas.drawOval(39f, 22.8f, 53f, 29.2f, fill); canvas.restore()
        canvas.restore()
        fill.color = SKIN_D; canvas.drawPath(ear, fill); canvas.drawPath(ear, rim)
        drawFace(canvas, t, q, raise)
        canvas.shade(hair, gHair)
        p.reset(); p.moveTo(44f, 9.4f); p.quadTo(50f, 6.8f, 55.6f, 8.8f)
        canvas.lines(p, 0x1FFFFFFF, 0.8f)
        if (glow > 0.01f) {
            canvas.save(); canvas.clipPath(head)
            shaded.shader = RadialGradient(lsx, lsy, 27f,
                intArrayOf(light(0.95f * glow), light2(0.55f * glow), light2(0f)), floatArrayOf(0f, 0.62f, 1f), Shader.TileMode.CLAMP)
            canvas.drawRect(30f, 0f, 70f, 40f, shaded)
            canvas.lines(faceRim, rimLight(0.8f * glow), 1.3f)
            canvas.restore()
        }
        canvas.restore()

        // Headphones: on his head, or resting around the neck.
        val cupX = lerp(47.2f, 47.4f, hpP); val cupY = lerp(19.6f, 33.6f, hpP)
        canvas.save()
        p.reset()
        if (hpP < 0.5f) {
            canvas.rotate(headTilt, 47.5f, 28f)
            p.moveTo(46.8f, 15.4f); p.cubicTo(45.6f, 9.6f, 47.4f, 4.8f, 51.4f, 4.4f)
        } else {
            p.moveTo(cupX - 1f, cupY - 2f); p.cubicTo(43.4f, 32.4f, 42f, 34.6f, 43.6f, 36.6f)
        }
        canvas.lines(p, RIM, 2.6f)
        canvas.lines(p, HP, 1.8f)
        canvas.restore()
        canvas.save()
        canvas.translate(cupX, cupY)
        canvas.rotate(lerp(0f, -50f, hpP))
        fill.color = HP; canvas.drawOval(-3.3f, -4.5f, 3.3f, 4.5f, fill)
        rim.strokeWidth = 0.6f; canvas.drawOval(-3.3f, -4.5f, 3.3f, 4.5f, rim); rim.strokeWidth = 0.7f
        fill.color = HP_L; canvas.drawOval(-1.1f, -3.2f, 2.1f, 2f, fill)
        fill.color = ACCENT; canvas.drawCircle(0.4f, -0.4f, 0.7f, fill)
        canvas.restore()

        // Drawstrings: swing with the beat, swept back as the laptop comes up.
        for ((ax, len, ph) in arrayOf(Triple(54f, 13f, 0f), Triple(55.8f, 11.5f, 0.8f))) {
            val a = Math.toRadians((sin(sec * 2 * PI / BEAT_S + ph) * vibeAmt * 5 - raise * 8).toDouble())
            val ex = ax + sin(a).toFloat() * len; val ey = 36.6f + cos(a).toFloat() * len
            p.reset(); p.moveTo(ax, 36.6f); p.quadTo(ax + 0.6f, 36.6f + len * 0.5f, ex, ey)
            canvas.lines(p, STRING, 0.7f)
            stroke.color = AGLET; stroke.strokeWidth = 1.1f
            canvas.drawLine(ex, ey, ex + sin(a).toFloat() * 1.6f, ey + cos(a).toFloat() * 1.6f, stroke)
        }

        // Laptop base, then the lid (only its outside is ever visible).
        quad(canvas, wx(0f, 0f), wy(0f, 0f), wx(-24f, 0f), wy(-24f, 0f), wx(-24f, 1.9f), wy(-24f, 1.9f), wx(0f, 1.9f), wy(0f, 1.9f),
            lin(wx(0f, 0f), wy(0f, 0f), wx(0f, 1.9f), wy(0f, 1.9f), LAP1, LAP2))
        quad(canvas, wx(0f, 0f), wy(0f, 0f), wx(dX * 23f, dY * 23f), wy(dX * 23f, dY * 23f),
            wx(dX * 23f + nX * 1.4f, dY * 23f + nY * 1.4f), wy(dX * 23f + nX * 1.4f, dY * 23f + nY * 1.4f),
            wx(nX * 1.4f, nY * 1.4f), wy(nX * 1.4f, nY * 1.4f), null, LAP2)
        if (glow > 0.01f) {
            val tx = wx(dX * 22f, dY * 22f); val ty = wy(dX * 22f, dY * 22f)
            val bx = wx(dX * 2f, dY * 2f); val by = wy(dX * 2f, dY * 2f)
            shaded.shader = LinearGradient(lsx, lsy, 57f, 20f, light2(0.16f * glow), light2(0f), Shader.TileMode.CLAMP)
            p.reset(); p.moveTo(tx, ty); p.lineTo(58f, 13f); p.lineTo(58f, 28f); p.lineTo(bx, by); p.close()
            canvas.drawPath(p, shaded)
        }

        // Near arm holds the laptop; then both hands.
        val snx = 48.4f; val sny = 40.4f
        val hnx = lerp(51.6f, 63.4f, raise); val hny = lerp(75f, 60.6f, raise)
        val (enx, eny) = ik(snx, sny, hnx, hny, 16.4f, 15.6f)
        canvas.sleeve(snx, sny, enx, eny, hnx, hny, 9f, 8.2f, 6f, H2, RIB)
        canvas.hand(hnx, hny, lerp(1.4f, 0.1f, raise), SKIN)
        if (raise > 0.05f) canvas.hand(hfx, hfy, 0.15f, SKIN_D)
        if (glow > 0.01f) {
            canvas.save(); canvas.clipPath(body)
            shaded.shader = RadialGradient(lsx, lsy, 24f, light(0.4f * glow), light(0f), Shader.TileMode.CLAMP)
            canvas.drawRect(30f, 25f, 70f, 85f, shaded)
            canvas.restore()
        }
        canvas.restore()

        scheduleNext(t, anim, q)
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
            c.lines(p, IRIS, 0.75f)
        } else {
            p.reset(); p.moveTo(53.3f, 17.7f); p.quadTo(55.1f, 18.6f, 56.8f, 17.6f)
            c.lines(p, IRIS, 0.8f)
        }
        p.reset(); p.moveTo(52.8f, 15.3f); p.quadTo(55f, 14.4f, 57.5f, 15f)
        c.lines(p, HAIR, 1.05f)
        stroke.color = LIPS; stroke.strokeWidth = 0.65f; c.drawLine(55.3f, 25.7f, 56.7f, 25.4f, stroke)
        p.reset(); p.moveTo(57.2f, 22.6f); p.quadTo(57.9f, 23.2f, 58.4f, 22.4f)
        c.lines(p, NOSTRIL, 0.5f)
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
        const val LEAN = -6f         // degrees: top leans back against the edge
        const val SHIFT = -17f       // moves his back flush with the edge
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
        val H1 = 0xFF1B5420.toInt()
        val H2 = 0xFF2C8E33.toInt()
        val H3 = 0xFF45B24A.toInt()
        val RIB = 0xFF226B28.toInt()
        val CUFF_FAR = 0xFF123A16.toInt()
        val HOOD_FOLD = 0x8C0A280C.toInt()
        val SLEEVE_FOLD = 0x6B0A280C.toInt()
        val HOOD_LIGHT = 0x47BEFFB4.toInt()
        val P1 = 0xFF1F2229.toInt()
        val P2 = 0xFF2E323B.toInt()
        val P3 = 0xFF3E4350.toInt()
        val PANT_FOLD = 0x80000000.toInt()
        val PANT_LIGHT = 0x1AFFFFFF
        val SHOE = 0xFFF1F2F4.toInt()
        val SHOE_D = 0xFFC3C7CE.toInt()
        val SOLE = 0xFF2B2D31.toInt()
        val ACCENT = 0xFF2E9B34.toInt()
        val STRING = 0xFFE9ECEF.toInt()
        val AGLET = 0xFF9AA0A8.toInt()
        val LAP1 = 0xFFA9AEB7.toInt()
        val LAP2 = 0xFF5C626C.toInt()
        val LAP_EDGE = 0xFF2D3138.toInt()
        val HP = 0xFF1B1C20.toInt()
        val HP_L = 0xFF3A3C44.toInt()
    }
}
