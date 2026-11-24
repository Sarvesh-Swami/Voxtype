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
 * The floating bubble's character, on a transparent background: a little big-headed guy (chibi proportions: short arms and legs, small hands) in a baggy green hoodie and joggers,
 * seen from the side, leaning back against the screen edge with his legs crossed. Drawn in code in a 60 x 108 box
 * (faces right, back against the left edge; mirrored when docked on the right). Fabric folds, the bunched hood,
 * ribbed hem and cuffs and swaying drawstrings keep him from looking stiff.
 *
 *  - [Mode.VIBING]    headphones on, eyes closed, slight nod, toe tap, drawstrings swing; laptop closed at his side.
 *                     Goes still after a while to save battery.
 *  - [Mode.LISTENING] headphones slide to his neck, he raises the laptop and opens the lid (only its back is seen),
 *                     and the screen light falls on his face. He types while [speaking] or after [nudge].
 *  - [Mode.THINKING]  same pose, hands still, the screen light pulses softly.
 *
 * With system animations off he switches between still poses. Visual prototype: .ai-workspace/scratch/chibi-boy.html
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

    private val head = svg("M40 10 C29 10 22.5 18 22.5 28 C22.5 37 28 44.5 37 45.8 C42 46.5 47.5 45.6 51 43.6 C53.4 42.2 54.6 40.6 54.8 39 L55.4 37.6 L56 36 L57.8 34.6 C58 33.6 57 32.6 56.6 31.6 C57.2 28 57.4 25 56.6 21.6 C55 14.6 48.6 10 40 10 Z")
    private val faceRim = svg("M56.6 21.6 C57.4 25 57.2 28 56.6 31.6 C57 32.6 58 33.6 57.8 34.6 L56 36 L55.4 37.6 L54.8 39 C54.6 40.6 53.4 42.2 51 43.6")
    private val hair = svg("M23.2 30 C21.4 19 27.6 9.4 40.4 8 C49 7.2 56 10.6 57.6 16.6 C58.2 19 57.6 21 56.2 21.6 C55 19.6 52.6 18.6 50.4 19.4 C48 20.2 45.2 19.6 43.8 18.4 C43 21.4 41.4 23.6 39.2 24.6 L38 31.4 C35.6 32.4 33.6 34.4 32.6 37 C30.6 35.8 28.6 35.4 26.8 36 C24.8 34.6 23.6 32.4 23.2 30 Z")
    private val hairLight = svg("M30 13.6 Q40 9.2 50 11.4")
    private val ear = svg("M36.4 27 C39.4 27 40.4 30 40 33 C39.6 35.4 37.6 36.6 36 35.8 C34.6 34 34.6 29 36.4 27 Z")
    private val eyeWhite = svg("M47.4 30.6 Q50.2 29 52.8 30.2 Q50.4 32.4 47.4 30.6 Z")
    private val eyeLid = svg("M47.1 30.4 Q50.2 28.4 53 29.9")
    private val eyeClosed = svg("M47.2 30.9 Q50.2 32.2 52.9 30.6")
    private val brow = svg("M46.6 26.6 Q50 25.2 53.6 26")
    private val nostril = svg("M56.6 35.4 Q57.4 36 57.9 35.1")
    private val neck = svg("M34 42 L42 42.6 L42.6 48.4 L34.6 48.6 Z")
    private val neckline = svg("M37.6 47.6 C40.6 47.8 43.6 49 46.2 51.4")
    private val body = svg("M33 46 C28 45.6 24.6 48 24.4 52 C24.2 54.6 25 56.4 26 57.4 C25 62 25 67 25.8 71 L26.2 72.6 L26.4 77 C32 78 40 78 47 76.8 L47.2 72.8 C49.8 72 50.8 70 50.6 67 C50.4 62 50 57.4 48.6 53.4 C47.4 50 45 47.6 41.6 46.8 C38.8 46.2 35.8 46 33 46 Z")
    private val bodyFolds = svg("M39.4 64.2 C42 63.4 45.6 63.4 48.8 64.6 M39.4 64.2 C39 67 39.2 69.6 39.8 72 M28.4 70.6 Q32 73 36 71.4 M37 72 Q41 74 45.8 71.6 M30.6 61 Q32 65.6 30.4 70.4 M30 55.6 Q35 58.4 40.6 56")
    private val bodyLights = svg("M46.6 54.6 Q49.4 60 48.8 66")
    private val hood = svg("M33.4 45 C28.6 43.6 23.6 46.4 23.4 51.4 C23.2 56 27 58.6 31.4 58 C35 57.6 37.2 54 37.4 50.4 C37.4 47.8 35.8 45.8 33.4 45 Z")
    private val hoodFolds = svg("M25.6 50.6 Q29.6 48.8 34.2 51.4 M26.2 54.6 Q30 54.4 34.4 55.8")
    private val hoodLight = svg("M27.6 46.6 Q31.6 45.6 35.4 47.8")
    private val legBack = svg("M28 75 L44 75 C45.6 81 47 88 48.4 95 C48.6 97 48.2 98.6 47.4 99 L41.6 99.4 C41 98 41 96.6 41.4 95.4 C39.8 89 35 82 28 75 Z")
    private val legBackFolds = svg("M42 94 Q45 95.4 48 94.4 M41.6 96.6 Q44.8 97.8 48 96.8")
    private val legFront = svg("M34 76 L46 76 C48.8 80 51.2 85 51 89 C50.6 93 46.6 96.4 42 98.6 C41.6 99.4 40.6 99.8 39.6 99.8 L34.4 98.6 C34.2 97.4 34.8 96.4 35.6 96 C39.4 94 43 91.6 43.6 89.4 C43 85 38.6 80 34 76 Z")
    private val legFrontFolds = svg("M45.4 85.4 Q48.6 87.4 51 86 M44.6 88.6 Q47.4 90 50.4 89.4 M37.4 95 Q39.6 96.4 42 95.6 M36 80 Q40 83 44.6 81.6")
    private val legFrontLights = svg("M49.6 85 Q51 87.6 49.8 90.6")
    private val shoeBack = svg("M41 97 L48.6 97.2 C51.8 97.6 55 99.4 56 101.6 L56.2 103.6 L40.6 103.6 L40.4 99.6 Z")
    private val shoeBackLine = svg("M45 101.6 Q48.4 99.8 52.2 100.8")
    private val shoeFront = svg("M31.6 95.6 L38 96.6 C41 98 43.6 100.6 44 103 L43.6 104.6 L35.6 104.4 C33.4 102.6 31.6 99.4 31.6 95.6 Z")
    private val soleFront = svg("M35.6 104.4 L43.6 104.6 L43.8 105.8 L36.6 105.6 Z")
    private val shoeFrontLine = svg("M35 100.4 Q38.2 99 41.2 101.4")

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

    private val gLegBack = lin(28f, 0f, 48f, 0f, P1, P2)
    private val gLegFront = lin(34f, 0f, 52f, 0f, P2, P3, P2)
    private val gShoe = lin(0f, 96f, 0f, 105f, SHOE, SHOE_D)
    private val gBody = lin(24f, 0f, 51f, 0f, H1, H2, H3)
    private val gBodyShade = lin(0f, 62f, 0f, 78f, 0x00000000, 0x2E000000)
    private val gHood = lin(23f, 0f, 38f, 0f, H1, H2)
    private val gNeck = lin(34f, 0f, 43f, 0f, SKIN_D, SKIN)
    private val gHead = lin(22f, 0f, 58f, 0f, SKIN_D, SKIN, SKIN_L)
    private val gHair = lin(0f, 8f, 0f, 36f, HAIR_L, HAIR)

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
        for ((u, k) in arrayOf(0.55f to 0.5f)) {
            val cx = lerp(ex, hx, u); val cy = lerp(ey, hy, u)
            p2.reset()
            p2.moveTo(cx + n2x * wE * 0.55f, cy + n2y * wE * 0.55f)
            p2.quadTo(cx - (hx - ex) * 0.12f, cy - (hy - ey) * 0.12f, cx - n2x * wE * k, cy - n2y * wE * k)
            drawPath(p2, stroke)
        }
        p2.reset()
        p2.moveTo(ex - nex * wE / 2, ey - ney * wE / 2)
        p2.quadTo(ex + (n2x - n1x) * 1.4f, ey + (n2y - n1y) * 1.4f, lerp(ex, hx, 0.3f) - n2x * wE * 0.2f, lerp(ey, hy, 0.3f) - n2y * wE * 0.2f)
        drawPath(p2, stroke)
        val ax = sx + n1x * wS / 2; val ay = sy + n1y * wS / 2
        val bx = ex + nex * wE * 0.54f; val by = ey + ney * wE * 0.54f
        p2.reset(); p2.moveTo(lerp(ax, bx, 0.2f), lerp(ay, by, 0.2f))
        p2.quadTo(bx, by, lerp(bx, hx + n2x * wH / 2, 0.5f), lerp(by, hy + n2y * wH / 2, 0.5f))
        lines(p2, HOOD_LIGHT, 1f)
        restore()
        stroke.strokeCap = Paint.Cap.BUTT
        stroke.color = cuff; stroke.strokeWidth = wH * 1.04f
        drawLine(lerp(ex, hx, 0.84f), lerp(ey, hy, 0.84f), hx, hy, stroke)
        stroke.strokeCap = Paint.Cap.ROUND
    }

    private fun Canvas.hand(x: Float, y: Float, angleRad: Float, color: Int) {
        save(); translate(x, y); rotate(Math.toDegrees(angleRad.toDouble()).toFloat())
        fill.color = color
        rim.strokeWidth = 0.6f
        drawOval(-1.2f, -1.9f, 3.6f, 1.9f, fill); drawOval(-1.2f, -1.9f, 3.6f, 1.9f, rim)
        save(); rotate(-28.6f, 0.4f, -1.4f)
        drawOval(-0.7f, -2.2f, 1.5f, -0.6f, fill); drawOval(-0.7f, -2.2f, 1.5f, -0.6f, rim)
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
        canvas.rotate(LEAN, PIVOT_X, PIVOT_Y)

        // Laptop frame: hinge position and rotation, lid opening angle.
        val hx = lerp(40f, 64f, raise); val hy = lerp(87f, 62.6f, raise)
        val ang = Math.toRadians(lerp(90f, 0f, raise).toDouble())
        val ca = cos(ang).toFloat(); val sa = sin(ang).toFloat()
        fun wx(lx: Float, ly: Float) = hx + lx * ca - ly * sa
        fun wy(lx: Float, ly: Float) = hy + lx * sa + ly * ca
        val th = Math.toRadians(lerp(0f, 106f, lid).toDouble())
        val dX = -cos(th).toFloat(); val dY = -sin(th).toFloat()      // lid direction (local)
        val nX = sin(th).toFloat(); val nY = -cos(th).toFloat()       // lid outer normal (local)
        val lsx = wx(dX * 8.5f, dY * 8.5f); val lsy = wy(dX * 8.5f, dY * 8.5f) // screen light source

        // Far arm (behind the body): hand in the hoodie pocket, then on the keyboard.
        val tapY = typeAmt * max(0f, sin(sec * 44f)) * 1.3f
        val sfx = 34f; val sfy = 53.5f
        val hfx = lerp(42f, 55.5f, raise); val hfy = lerp(67f, 60f - tapY, raise)
        val (efx, efy) = ik(sfx, sfy, hfx, hfy, 9.4f, 9.4f)
        canvas.sleeve(sfx, sfy, efx, efy, hfx, hfy, 7.4f, 7f, 5.8f, H1, CUFF_FAR)

        // Joggers: back leg and shoe, then the front leg crossing over it.
        canvas.shade(legBack, gLegBack)
        canvas.folds(legBack, legBackFolds, null, PANT_FOLD, PANT_LIGHT, 0.6f)
        canvas.shade(shoeBack, gShoe)
        fill.color = SOLE; canvas.drawRect(40.2f, 103.2f, 56.4f, 105.4f, fill)
        canvas.lines(shoeBackLine, ACCENT, 1f)
        canvas.shade(legFront, gLegFront)
        canvas.folds(legFront, legFrontFolds, legFrontLights, PANT_FOLD, PANT_LIGHT, 0.55f)
        canvas.save()
        canvas.rotate(-tap * 7f, 43.6f, 104.8f) // heel lifts on the beat, toe stays down
        canvas.shade(shoeFront, gShoe)
        fill.color = SOLE; canvas.drawPath(soleFront, fill)
        canvas.lines(shoeFrontLine, ACCENT, 1f)
        canvas.restore()

        // Hoodie body: shading, ribbed hem, pocket and drape folds.
        canvas.shade(body, gBody)
        canvas.save(); canvas.clipPath(body)
        shaded.shader = gBodyShade; canvas.drawRect(20f, 60f, 55f, 80f, shaded)
        fill.color = RIB; canvas.drawRect(24f, 72.6f, 50f, 77.6f, fill)
        for (i in 0 until 8) canvas.drawLine(28f + i * 2.4f, 72.8f, 28.2f + i * 2.4f, 77.4f, stroke.apply { color = 0x40000000; strokeWidth = 0.35f })
        canvas.lines(bodyFolds, HOOD_FOLD, 0.65f)
        canvas.lines(bodyLights, HOOD_LIGHT, 1.1f)
        canvas.restore()
        // Hood bunched behind the neck.
        canvas.shade(hood, gHood)
        canvas.lines(hoodFolds, HOOD_FOLD, 0.65f)
        canvas.lines(hoodLight, HOOD_LIGHT, 0.8f)
        canvas.shade(neck, gNeck, outline = false)
        canvas.lines(neckline, H1, 1.5f)

        // Head.
        canvas.save()
        canvas.rotate(headTilt, HEAD_PX, HEAD_PY)
        canvas.shade(head, gHead)
        canvas.save(); canvas.clipPath(head)
        fill.color = 0x29784629; canvas.save(); canvas.rotate(-20f, 33.4f, 43.6f); canvas.drawOval(26f, 40.8f, 40.8f, 46.4f, fill); canvas.restore()
        fill.color = 0x2EE6786E; canvas.drawCircle(49.6f, 37.4f, 3.2f, fill)
        canvas.restore()
        fill.color = SKIN_D; canvas.drawPath(ear, fill); canvas.drawPath(ear, rim)
        drawFace(canvas, t, q, raise)
        canvas.shade(hair, gHair)
        canvas.lines(hairLight, 0x21FFFFFF, 1f)
        if (glow > 0.01f) {
            canvas.save(); canvas.clipPath(head)
            shaded.shader = RadialGradient(lsx, lsy, 32f,
                intArrayOf(light(0.8f * glow), light2(0.42f * glow), light2(0f)), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
            canvas.drawRect(20f, 5f, 60f, 50f, shaded)
            canvas.lines(faceRim, rimLight(0.55f * glow), 1.2f)
            canvas.restore()
        }
        canvas.restore()

        // Headphones: on his head, or resting around the neck.
        val cupX = lerp(36.4f, 36.8f, hpP); val cupY = lerp(31.4f, 48.8f, hpP)
        canvas.save()
        p.reset()
        if (hpP < 0.5f) {
            canvas.rotate(headTilt, HEAD_PX, HEAD_PY)
            p.moveTo(35.4f, 25.4f); p.cubicTo(33.4f, 16f, 36.8f, 8.6f, 44.4f, 7.4f)
        } else {
            p.moveTo(cupX - 1f, cupY - 2.4f); p.cubicTo(31.6f, 47f, 30f, 49.6f, 31.6f, 52f)
        }
        canvas.lines(p, RIM, 3f)
        canvas.lines(p, HP, 2.2f)
        canvas.restore()
        canvas.save()
        if (hpP < 0.5f) canvas.rotate(headTilt * (1 - hpP * 2), HEAD_PX, HEAD_PY)
        canvas.translate(cupX, cupY)
        canvas.rotate(lerp(0f, -50f, hpP))
        fill.color = HP; canvas.drawOval(-4.2f, -5.6f, 4.2f, 5.6f, fill)
        rim.strokeWidth = 0.6f; canvas.drawOval(-4.2f, -5.6f, 4.2f, 5.6f, rim); rim.strokeWidth = 0.7f
        fill.color = HP_L; canvas.drawOval(-1.4f, -4f, 2.6f, 2.4f, fill)
        fill.color = ACCENT; canvas.drawCircle(0.5f, -0.5f, 0.9f, fill)
        canvas.restore()

        // Drawstrings: swing with the beat, swept back as the laptop comes up.
        for ((ax, len, ph) in arrayOf(Triple(43.4f, 8f, 0f), Triple(45f, 7f, 0.8f))) {
            val a = Math.toRadians((sin(sec * 2 * PI / BEAT_S + ph) * vibeAmt * 6 - raise * 8).toDouble())
            val ex = ax + sin(a).toFloat() * len; val ey = 51.4f + cos(a).toFloat() * len
            p.reset(); p.moveTo(ax, 51.4f); p.quadTo(ax + 0.5f, 51.4f + len * 0.5f, ex, ey)
            canvas.lines(p, STRING, 0.7f)
            stroke.color = AGLET; stroke.strokeWidth = 1.1f
            canvas.drawLine(ex, ey, ex + sin(a).toFloat() * 1.4f, ey + cos(a).toFloat() * 1.4f, stroke)
        }

        // Laptop base, then the lid (only its outside is ever visible).
        quad(canvas, wx(0f, 0f), wy(0f, 0f), wx(-18f, 0f), wy(-18f, 0f), wx(-18f, 1.7f), wy(-18f, 1.7f), wx(0f, 1.7f), wy(0f, 1.7f),
            lin(wx(0f, 0f), wy(0f, 0f), wx(0f, 1.7f), wy(0f, 1.7f), LAP1, LAP2))
        quad(canvas, wx(0f, 0f), wy(0f, 0f), wx(dX * 17f, dY * 17f), wy(dX * 17f, dY * 17f),
            wx(dX * 17f + nX * 1.2f, dY * 17f + nY * 1.2f), wy(dX * 17f + nX * 1.2f, dY * 17f + nY * 1.2f),
            wx(nX * 1.2f, nY * 1.2f), wy(nX * 1.2f, nY * 1.2f), null, LAP2)
        if (glow > 0.01f) {
            val tx = wx(dX * 16f, dY * 16f); val ty = wy(dX * 16f, dY * 16f)
            val bx = wx(dX * 2f, dY * 2f); val by = wy(dX * 2f, dY * 2f)
            shaded.shader = LinearGradient(lsx, lsy, 54f, 32f, light2(0.16f * glow), light2(0f), Shader.TileMode.CLAMP)
            p.reset(); p.moveTo(tx, ty); p.lineTo(56.6f, 24f); p.lineTo(56f, 40f); p.lineTo(bx, by); p.close()
            canvas.drawPath(p, shaded)
        }

        // Near arm holds the laptop; then both hands.
        val snx = 37f; val sny = 54.4f
        val hnx = lerp(39.4f, 48f, raise); val hny = lerp(72f, 64.4f, raise)
        val (enx, eny) = ik(snx, sny, hnx, hny, 9.6f, 9.4f)
        canvas.sleeve(snx, sny, enx, eny, hnx, hny, 8.2f, 7.8f, 6.4f, H2, RIB)
        canvas.hand(hnx, hny, lerp(1.4f, 0.1f, raise), SKIN)
        if (raise > 0.05f) canvas.hand(hfx, hfy, 0.15f, SKIN_D)
        if (glow > 0.01f) {
            canvas.save(); canvas.clipPath(body)
            shaded.shader = RadialGradient(lsx, lsy, 22f, light(0.4f * glow), light(0f), Shader.TileMode.CLAMP)
            canvas.drawRect(20f, 40f, 60f, 80f, shaded)
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
            fill.color = EYE_WHITE; c.drawPath(eyeWhite, fill)
            fill.color = IRIS; c.drawCircle(51.2f, 30.9f + raise * 0.35f, 1.45f, fill)
            fill.color = 0xFFFFFFFF.toInt(); c.drawCircle(51.7f, 30.3f, 0.45f, fill)
            c.lines(eyeLid, IRIS, 0.95f)
        } else {
            c.lines(eyeClosed, IRIS, 0.95f)
        }
        c.lines(brow, HAIR, 1.3f)
        stroke.color = LIPS; stroke.strokeWidth = 0.7f; c.drawLine(52.6f, 40.6f, 54.6f, 40.2f, stroke)
        c.lines(nostril, NOSTRIL, 0.55f)
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
        const val VW = 60f
        const val VH = 108f
        const val LEAN = -5f         // degrees: top leans back against the edge
        const val SHIFT = -13f       // moves his back flush with the edge
        const val PIVOT_X = 44f      // lean pivot at his feet
        const val PIVOT_Y = 105f
        const val HEAD_PX = 37f      // head tilt pivot (neck)
        const val HEAD_PY = 46f
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
