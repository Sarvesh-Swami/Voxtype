package app.speaker.dictate

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
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
 * The floating bubble's character, on a transparent background: a chibi (big head, short limbs, small hands),
 * seen from the side, leaning back against the screen edge with his legs crossed. Drawn in code in a 60 x 108 box
 * (faces right, back against the left edge; mirrored when docked on the right). Polished 2D game-art look: smooth
 * curved shapes, soft gradient shading with highlights and ambient shadows, heavier outer lines and thin detail
 * lines (hair locks with sheen, shaded iris, padded headphones with a glowing ring, ribbed cuffs, stitched pocket,
 * sneakers with laces and midsole, rounded aluminium laptop with hinge and glowing logo). Colours come from a [Skin].
 *
 *  - [Mode.VIBING]    headphones on, eyes closed, slight nod and breathing, toe tap, drawstrings swing; laptop
 *                     closed at his side. Goes still after a while to save battery.
 *  - [Mode.LISTENING] headphones slide to his neck, he raises the laptop and opens the lid (only its back is seen),
 *                     the screen light falls on his face with a cyan rim light. He types while [speaking] or after [nudge].
 *  - [Mode.THINKING]  same pose, hands still, the screen light pulses softly.
 *
 * With system animations off he switches between still poses. Visual prototype: .ai-workspace/scratch/vox.html
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

    /** Which character (colour palette) to draw. */
    var skin: Skin = Skins.DEFAULT
        set(v) { if (field !== v) { field = v; invalidate() } }

    /** Store previews: no idle loops (no vibing, no blinking), just the pose changes. */
    var still = false

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

    private val head = svg("M40 9.4 C29.4 9.4 22.4 17.6 22.6 28 C22.8 35.4 26.4 41.2 32.6 44.2 C36.4 46 41.6 46.9 46.2 46.2 C49.4 45.7 52.2 44.6 54 43.2 C55.2 42.2 55.5 41 55 40 C54.8 39.6 55.3 39.2 55.7 38.9 C55.3 38.4 55.2 38 55.9 37.6 C56.6 37.1 56.7 36.3 56.2 35.8 C57.4 35.4 58.5 34.9 58.9 34.2 C59.2 33.6 58.6 32.9 57.9 32.4 C57.3 31.4 57 30.4 57.1 29.3 C57.4 27.8 57.7 26.6 57.7 25.4 C57.6 17.4 50.6 9.4 40 9.4 Z")
    private val jawAO = svg("M30 40 C34 45 41 47.4 48 46.6 L48 50 L30 50 Z")
    private val ear = svg("M36.2 26.6 C39.6 26.4 41 29.6 40.6 32.8 C40.2 35.6 38 37.2 36 36.2 C34.4 34.4 34.2 28.8 36.2 26.6 Z")
    private val earIn = svg("M37 29 C38.8 29.6 39.2 32 38 34.2 M37.4 31.4 C38 31.6 38.2 32.4 37.8 33")
    private val eyeWhite = svg("M46.6 30.8 C48 28.9 51.6 28 54.5 29.4 C54.8 30 54.7 30.6 54.3 31.1 C51.4 33 48.4 32.6 46.6 30.8 Z")
    private val lashUp = svg("M46 30.7 C47.6 28.3 51.8 27.3 54.8 29 C55.4 28.6 55.9 28 56.1 27.5 C56 28.5 55.6 29.4 54.9 29.9 C51.8 28.6 48.2 29.2 46.6 31 Z")
    private val lidLow = svg("M47.6 31.6 C49.6 32.6 52.2 32.6 54 31.4")
    private val eyeClosed = svg("M46.4 31.1 C48.6 33 52.4 33.1 55 31 C55.6 30.6 56 30.1 56.2 29.6 C56.1 30.6 55.7 31.4 55 31.9 C52.4 34 48.4 33.6 46.4 31.1 Z")
    private val lashTips = svg("M48.6 32.6 L48.1 33.6 M51 33 L50.8 34.1")
    private val brow = svg("M45.6 27.4 C47.4 25.6 50.8 24.6 55.6 25.2 C55.9 25.6 55.8 26.1 55.4 26.3 C51.6 26.1 48.6 26.8 46.4 28.2 C45.9 28.3 45.4 27.9 45.6 27.4 Z")
    private val noseShade = svg("M56.4 33 C57.2 34 57.8 34.6 58.8 34.3 C58 35.2 57 35.6 56.2 35.6 Z")
    private val noseLight = svg("M57.4 30.6 C57.9 31.6 58.3 32.4 58.6 33.2")
    private val foreheadSheen = svg("M47 21.2 C51 20.6 54.6 21.6 56.6 23.6")
    private val profileRim = svg("M57.7 25.4 C57.6 27 57.2 28 57.1 29.3 C57 30.4 57.3 31.4 57.9 32.4 C58.6 32.9 59.2 33.6 58.9 34.2")
    private val mouth = svg("M51.6 40.2 C52.8 40.8 54 40.7 55.1 39.8")
    private val lip = svg("M52.6 41.4 C53.4 41.7 54.2 41.6 54.7 41.2")
    private val neck = svg("M34 41.6 C36.6 42.4 39.6 42.6 42.2 42.4 L42.8 48.6 C40 49 37 49 34.4 48.6 Z")
    private val neckline = svg("M37.6 47.6 C40.6 47.8 43.6 49 46.2 51.4")

    private val body = svg("M33 46 C28 45.6 24.6 48 24.4 52 C24.2 54.6 25 56.4 26 57.4 C25 62 25 67 25.8 71 C26 72.8 26.2 74.8 26.4 77 C32 78.2 40.4 78.2 47 76.8 C47 75.4 47.1 74 47.2 72.8 C49.8 72 50.8 70 50.6 67 C50.4 62 50 57.4 48.6 53.4 C47.4 50 45 47.6 41.6 46.8 C38.8 46.2 35.8 46 33 46 Z")
    private val rib = svg("M25.9 72.6 C32 73.4 40 73.4 47.3 72.4 L47 76.8 C40.4 78.2 32 78.2 26.4 77 Z")
    private val ribLines = svg("M28 73.2 L28 77.4 M30.4 73.4 L30.4 77.6 M32.8 73.4 L32.8 77.8 M35.2 73.5 L35.2 77.8 M37.6 73.5 L37.6 77.8 M40 73.4 L40 77.8 M42.4 73.3 L42.4 77.6 M44.8 73 L44.8 77.2")
    private val ribTop = svg("M25.9 72.6 C32 73.4 40 73.4 47.3 72.4")
    private val pocket = svg("M35.4 66.2 C39.4 65.2 44 65.1 47.6 65.6 C48.2 67.6 48.5 69.6 48.6 71.6 C44 72.2 39.4 72.3 35.2 72.2")
    private val stitch = svg("M35.9 67.2 C39.6 66.3 43.8 66.2 47 66.6")
    private val stripe = svg("M36.6 58.6 C41 57.2 45.6 56 50.2 55.4 L50.6 58.6 C46 59.2 41.4 60.4 37 61.8 Z")
    private val foldA = svg("M28.6 70.4 C31.4 72.4 34.6 72.6 36.6 71.2 M37.6 71.8 C40.6 73.6 43.6 73.4 45.8 71.6")
    private val foldALight = svg("M29 69.6 C31.6 71.4 34.4 71.6 36.2 70.4 M38 71 C40.6 72.6 43.4 72.4 45.4 70.8")
    private val foldB = svg("M30.6 60.6 C31.8 64.4 31.6 67.6 30.6 70.2 M30 55.6 C33.4 57.6 37.4 57.8 40.6 56.2")
    private val bodyLight = svg("M46.6 54.6 C48.4 58 49 62 48.8 66")
    private val hood = svg("M33.4 44.8 C28.6 43.4 23.4 46.2 23.2 51.4 C23 56.2 26.8 58.8 31.4 58.2 C35.2 57.8 37.4 54.2 37.6 50.4 C37.6 47.8 35.8 45.6 33.4 44.8 Z")
    private val hoodIn = svg("M33.2 46.4 C30 45.6 26.6 47.6 26.4 51 C26.4 53.4 28.4 54.8 30.6 54.6 C33.4 54.4 35.2 52 35.4 49.6 C35.4 48 34.6 46.8 33.2 46.4 Z")
    private val hoodLight = svg("M25 50.4 C27.6 49.6 30 49.8 32 50.8")

    private val legBack = svg("M28 75 L44 75 C45.6 81 47 88 48.4 95 C48.6 97 48.2 98.6 47.4 99 C45.4 99.4 43.4 99.5 41.6 99.4 C41 98 41 96.6 41.4 95.4 C39.8 89 35 82 28 75 Z")
    private val legFront = svg("M34 76 L46 76 C48.8 80 51.2 85 51 89 C50.6 93 46.6 96.4 42 98.6 C41.6 99.4 40.6 99.8 39.6 99.8 C37.8 99.5 36 99.1 34.4 98.6 C34.2 97.4 34.8 96.4 35.6 96 C39.4 94 43 91.6 43.6 89.4 C43 85 38.6 80 34 76 Z")
    private val legFrontShade = svg("M34 76 C38 79.6 42 84.6 43.6 89.4 C43 91.6 39.4 94 35.6 96 C34.8 96.4 34.2 97.4 34.4 98.6 L30 99 L30 76 Z")
    private val legFolds = svg("M45.4 85.2 C47.2 86.6 49.2 86.9 51 86 M44.4 88.8 C46.4 90 48.6 90.1 50.4 89.2 M36.4 80.2 C39 82.4 41.8 82.6 44.6 81.6")
    private val legFoldsLight = svg("M45.6 84.4 C47.4 85.6 49.2 85.9 50.8 85.2 M37 79.4 C39.4 81.4 42 81.6 44.4 80.8")
    private val cuffFront = svg("M35.6 96 C39.2 94.2 42.4 92.2 43.6 89.8 C44.6 92.2 45.6 94.4 46.4 96 C44.4 97.4 42 98.4 40 99.6 C38.4 99.4 36.8 99 35.4 98.6 C35 97.6 35 96.6 35.6 96 Z")
    private val cuffFrontRibs = svg("M37 95.6 L38.6 99.4 M39.4 94.6 L41 99 M41.8 93.2 L43.4 98.2 M43.6 91.6 L45.2 97")
    private val cuffBack = svg("M41.4 95.2 C43.6 95.6 45.8 95.4 48 94.8 C48.4 96.2 48.4 97.8 47.6 98.9 C45.6 99.3 43.4 99.4 41.6 99.3 C41 98 41 96.6 41.4 95.2 Z")
    private val cuffBackRibs = svg("M42.4 95.6 L42.4 99 M44.4 95.6 L44.4 99.2 M46.4 95.3 L46.4 99")
    private val shoeBack = svg("M41 97.2 C43.6 96.8 46.4 96.8 48.8 97.2 C51.8 97.6 54.8 99.2 55.9 101.4 C56.2 102.2 56.2 103 56 103.6 L40.8 103.6 C40.4 101.6 40.4 99.4 41 97.2 Z")
    private val shoeFront = svg("M31.8 95.4 C33.8 95.6 36 95.9 38 96.6 C41 97.8 43.4 100.2 44 102.6 C44.1 103.3 44 104 43.6 104.6 L35.8 104.4 C33.4 102.8 31.6 99.6 31.8 95.4 Z")
    private val shoeShade = svg("M31 99 C34 101 38 102 44 102 L44 106 L31 106 Z")
    private val midFront = svg("M35.8 104.4 L43.6 104.6 C43.8 105 43.8 105.4 43.6 105.8 L36.8 105.6 C36.4 105.2 36 104.8 35.8 104.4 Z")
    private val laces = svg("M36.4 97 L37.6 98.4 M38.2 97.6 L39.2 99.2 M39.8 98.6 L40.6 100.2")
    private val swooshFront = svg("M34.6 101.6 C37 101.4 39.6 100.6 41.6 100.2")
    private val swooshBack = svg("M44.6 101.8 C47.4 100.6 50.4 100.2 53.2 100.8")
    private val tongue = svg("M31.8 95.4 C32.4 94.4 33.6 94 34.6 94.4 C34.8 95 34.6 95.6 34 96")

    private val bandOn = svg("M35.4 25.4 C33 15 36.4 6.4 44.6 5")
    private val palm = svg("M-1.4 -1.6 C0.4 -2.3 2.6 -2.1 3.8 -1.2 C4.4 -0.4 4.4 0.8 3.6 1.6 C2.2 2.4 0 2.3 -1.4 1.6 Z")
    private val fingers = svg("M1.6 -0.6 L3.4 -0.6 M1.6 0.6 L3.4 0.7")
    private val thumb = svg("M-0.6 -1.6 C0 -2.6 1.2 -3 1.9 -2.4 C2.1 -1.9 1.6 -1.5 0.8 -1.2 Z")

    /** Tapered, curved hair lock from root to tip (width, bend) with a rounded tip and a highlight streak. */
    private class Lock(val path: Path, val shine: Path, val rx: Float, val ry: Float, val tx: Float, val ty: Float)

    private fun lock(rx: Float, ry: Float, tx: Float, ty: Float, w: Float, b: Float): Lock {
        val dx = tx - rx; val dy = ty - ry; val l = hypot(dx, dy); val ux = dx / l; val uy = dy / l; val nx = -uy; val ny = ux
        val mx = (rx + tx) / 2 + nx * b; val my = (ry + ty) / 2 + ny * b
        val p = Path()
        p.moveTo(rx + nx * w / 2, ry + ny * w / 2)
        p.quadTo(mx + nx * w * 0.36f, my + ny * w * 0.36f, tx + nx * 0.8f, ty + ny * 0.8f)
        p.quadTo(tx + ux * 1.1f, ty + uy * 1.1f, tx - nx * 0.8f, ty - ny * 0.8f)
        p.quadTo(mx - nx * w * 0.3f, my - ny * w * 0.3f, rx - nx * w / 2, ry - ny * w / 2)
        p.close()
        val h = Path()
        h.moveTo(lerp(rx, tx, 0.25f) + nx * w * 0.12f, lerp(ry, ty, 0.25f) + ny * w * 0.12f)
        h.quadTo(mx + nx * w * 0.12f, my + ny * w * 0.12f, lerp(rx, tx, 0.8f) + nx * 0.25f + nx * b * 0.2f, lerp(ry, ty, 0.8f) + ny * 0.25f + ny * b * 0.2f)
        return Lock(p, h, rx, ry, tx, ty)
    }

    private val locksBack = listOf(
        lock(27.4f, 30f, 20.2f, 25.4f, 7.4f, -2.6f), lock(27f, 22.4f, 20.8f, 15.6f, 8.4f, -2.6f),
        lock(31.4f, 16f, 26.6f, 8.4f, 8.8f, -2.4f), lock(37.2f, 13.6f, 34.4f, 5.8f, 9f, -1.8f),
        lock(43f, 13.2f, 43.4f, 5.2f, 8.6f, 1.8f), lock(48.2f, 14.6f, 53.2f, 8.2f, 8f, 2.4f),
        lock(51.2f, 17.6f, 57.6f, 14.8f, 7f, 2f),
    )
    private val locksFront = listOf(
        lock(49.4f, 18.4f, 57.2f, 23.6f, 6f, -1.6f), lock(45.4f, 18.6f, 50.4f, 25.2f, 5.6f, -1.4f),
        lock(41.2f, 19.2f, 42.6f, 25.8f, 4.6f, -1f),
    )
    private val hairMass = svg("M23 31 C21 22 25 13 34 10 C42 7.4 52 9.6 56.4 16.6 C57.6 18.6 57.4 20.4 56 21 C52 19.6 47 19.8 43 21.4 C41.6 22 40.6 23.4 40.2 25.4 C39.8 27.4 39.6 29.4 39.4 31.4 C36.6 32.4 34.4 34.4 33.2 37 C30.4 35.6 28 35.6 26.4 36.2 C24.6 34.8 23.6 33 23 31 Z")
    private val hairShine = svg("M28.6 15.8 C33.6 11.6 42 10.2 50 12.8 C49 13.8 47.6 14.2 46.4 13.8 C40.6 12.6 35 13.4 30.4 16.6 C29.6 17 28.8 16.6 28.6 15.8 Z")
    private val hairStrands = svg("M26 24 C27.6 20 30.4 17.4 33.6 16 M28.6 30 C29.4 26 31.6 22.6 35 20.4 M34.4 33.6 C35 30 36.4 26.6 38.6 24")

    // ------------------------------------------------------------------ paints

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND; color = INK
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val dashed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 0.25f; color = 0x2EFFFFFF; pathEffect = DashPathEffect(floatArrayOf(0.6f, 0.5f), 0f)
    }
    private val shaded = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val p = Path()
    private val p2 = Path()
    private val rect = RectF()

    private fun lg(x0: Float, y0: Float, x1: Float, y1: Float, vararg c: Int): Shader =
        LinearGradient(x0, y0, x1, y1, c, null, Shader.TileMode.CLAMP)

    private fun rg(cx: Float, cy: Float, r: Float, c0: Int, c1: Int): Shader =
        RadialGradient(cx, cy, r.coerceAtLeast(0.01f), c0, c1, Shader.TileMode.CLAMP)

    private fun Canvas.outline(path: Path, w: Float = 0.75f) { ink.strokeWidth = w; drawPath(path, ink) }

    private fun Canvas.solid(path: Path, color: Int, w: Float = 0.75f) {
        fill.color = color; drawPath(path, fill)
        if (w > 0f) outline(path, w)
    }

    private fun Canvas.paint(path: Path, shader: Shader, w: Float = 0.75f) {
        shaded.shader = shader; drawPath(path, shaded)
        if (w > 0f) outline(path, w)
    }

    private fun Canvas.lines(path: Path, color: Int, w: Float) {
        stroke.color = color; stroke.strokeWidth = w; drawPath(path, stroke)
    }

    private inline fun Canvas.clipped(clip: Path, block: () -> Unit) {
        save(); clipPath(clip); block(); restore()
    }

    /** Soft glow without shadow layers: translucent rings under the shape. */
    private fun Canvas.glowDot(x: Float, y: Float, r: Float, color: Int, strength: Float) {
        val rgb = color and 0xFFFFFF
        fill.color = alpha(0.16f * strength, rgb); drawCircle(x, y, r * 2.6f, fill)
        fill.color = alpha(0.3f * strength, rgb); drawCircle(x, y, r * 1.7f, fill)
        fill.color = color; drawCircle(x, y, r, fill)
    }

    /** Baggy sleeve from shoulder S over elbow E to wrist H: soft shading across, two folds, ribbed cuff with a neon line. */
    private fun Canvas.sleeve(sx: Float, sy: Float, ex: Float, ey: Float, hx: Float, hy: Float,
                              wS: Float, wE: Float, wH: Float, far: Boolean) {
        val k = skin
        val l1 = hypot(ex - sx, ey - sy).coerceAtLeast(0.01f); val n1x = -(ey - sy) / l1; val n1y = (ex - sx) / l1
        val l2 = hypot(hx - ex, hy - ey).coerceAtLeast(0.01f); val n2x = -(hy - ey) / l2; val n2y = (hx - ex) / l2
        var nex = (n1x + n2x) / 2; var ney = (n1y + n2y) / 2
        val nl = hypot(nex, ney).coerceAtLeast(0.01f); nex /= nl; ney /= nl
        val sleeve = Path()
        sleeve.moveTo(sx + n1x * wS / 2, sy + n1y * wS / 2)
        sleeve.quadTo(ex + nex * wE * 0.56f, ey + ney * wE * 0.56f, hx + n2x * wH / 2, hy + n2y * wH / 2)
        sleeve.lineTo(hx - n2x * wH / 2, hy - n2y * wH / 2)
        sleeve.quadTo(ex - nex * wE / 2, ey - ney * wE / 2, sx - n1x * wS / 2, sy - n1y * wS / 2)
        sleeve.close()
        val base = if (far) k.cloth[0] else k.cloth[1]; val lite = if (far) k.cloth[1] else k.cloth[2]
        shaded.shader = lg(ex + nex * wE * 0.6f, ey + ney * wE * 0.6f, ex - nex * wE * 0.6f, ey - ney * wE * 0.6f, lite, base, k.cloth[0])
        drawPath(sleeve, shaded)
        shaded.shader = lg(sx, sy - wS / 2, sx, sy + wS / 2, lite, base)
        drawCircle(sx, sy + 0.4f, wS * 0.52f, shaded) // rounded shoulder
        clipped(sleeve) {
            for (u in floatArrayOf(0.35f, 0.62f)) {
                val cx = lerp(ex, hx, u); val cy = lerp(ey, hy, u)
                p2.reset()
                p2.moveTo(cx + n2x * wE * 0.5f, cy + n2y * wE * 0.5f)
                p2.quadTo(cx - (hx - ex) * 0.1f, cy - (hy - ey) * 0.1f, cx - n2x * wE * 0.1f, cy - n2y * wE * 0.1f)
                lines(p2, 0x61000000, 0.45f)
                save(); translate(-(hx - ex) / l2 * 0.5f, -(hy - ey) / l2 * 0.5f); lines(p2, 0x1FFFFFFF, 0.4f); restore()
            }
            p2.reset()
            p2.moveTo(ex - nex * wE / 2, ey - ney * wE / 2)
            p2.quadTo(ex + (n2x - n1x) * 1.4f, ey + (n2y - n1y) * 1.4f, lerp(ex, hx, 0.3f) - n2x * wE * 0.15f, lerp(ey, hy, 0.3f) - n2y * wE * 0.15f)
            lines(p2, 0x59000000, 0.45f)
        }
        outline(sleeve, 0.7f)
        // Ribbed cuff with a neon line.
        val c0x = lerp(ex, hx, 0.8f); val c0y = lerp(ey, hy, 0.8f)
        val cuff = Path()
        cuff.moveTo(c0x + n2x * wH * 0.55f, c0y + n2y * wH * 0.55f)
        cuff.lineTo(hx + n2x * wH * 0.5f, hy + n2y * wH * 0.5f)
        cuff.lineTo(hx - n2x * wH * 0.5f, hy - n2y * wH * 0.5f)
        cuff.lineTo(c0x - n2x * wH * 0.55f, c0y - n2y * wH * 0.55f)
        cuff.close()
        solid(cuff, if (far) CUFF_FAR else CUFF_NEAR, 0.5f)
        clipped(cuff) {
            for (i in -2..2) {
                val o = i * wH * 0.2f
                p2.reset(); p2.moveTo(c0x + n2x * o, c0y + n2y * o); p2.lineTo(hx + n2x * o, hy + n2y * o)
                lines(p2, 0x14FFFFFF, 0.35f)
            }
            p2.reset(); p2.moveTo(c0x + n2x * wH * 0.6f, c0y + n2y * wH * 0.6f); p2.lineTo(c0x - n2x * wH * 0.6f, c0y - n2y * wH * 0.6f)
            lines(p2, k.trim, 0.55f)
        }
    }

    private fun Canvas.hand(x: Float, y: Float, angleRad: Float, dark: Boolean) {
        val k = skin
        save(); translate(x, y); rotate(Math.toDegrees(angleRad.toDouble()).toFloat())
        paint(palm, lg(0f, -2f, 0f, 2f, if (dark) k.skin[0] else k.skin[2], if (dark) k.skin[3] else k.skin[1]), 0.55f)
        lines(fingers, 0x73783C28, 0.3f)
        solid(thumb, if (dark) k.skin[1] else k.skin[0], 0.5f)
        restore()
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(canvas: Canvas) {
        val k = skin
        val t = now()
        val dt = if (lastFrame == 0L) 0f else ((t - lastFrame) / 1000f).coerceAtMost(0.1f)
        lastFrame = t
        val anim = ValueAnimator.areAnimatorsEnabled()
        val sec = (t - start) / 1000f

        val typing = mode == Mode.LISTENING && (speaking || t - lastNudge < NUDGE_MS)
        typeAmt += ((if (typing) 1f else 0f) - typeAmt) * min(1f, dt * if (typing) 14f else 5f)
        val vibing = mode == Mode.VIBING && t - lastActivity < VIBE_MS && anim && !still
        vibeAmt += ((if (vibing) 1f else 0f) - vibeAmt) * min(1f, dt * 3f)
        if (!anim) { typeAmt = 0f; vibeAmt = 0f }
        if (still) vibeAmt = 0f
        if (vibeAmt < 0.01f) vibeAmt = 0f

        val q = q(t)
        val hpP = smooth(0f, 0.35f, q)
        val raise = smooth(0.22f, 0.7f, q)
        val lid = smooth(0.6f, 1f, q)
        val beat = (sec % BEAT_S) / BEAT_S
        val nod = sin(beat * 2 * PI).toFloat() * 2.5f * vibeAmt * (1 - hpP)
        val tap = max(0f, sin(beat * 2 * PI).toFloat()) * vibeAmt
        val breath = if (anim && !still) sin(sec * 2 * PI.toFloat() / 3.2f) * 0.35f * (1 - typeAmt * 0.5f) else 0f
        val headTilt = lerp(nod, 5f, raise) + breath * 0.6f
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

        // Laptop frame: hinge position and rotation (deg), lid opening angle (deg).
        val hx = lerp(40f, 64f, raise); val hy = lerp(87f, 62.6f, raise)
        val ang = lerp(90f, 0f, raise)
        val th = lerp(0f, 106f, lid)
        val ca = cos(Math.toRadians(ang.toDouble())).toFloat(); val sa = sin(Math.toRadians(ang.toDouble())).toFloat()
        fun wx(lx: Float, ly: Float) = hx + lx * ca - ly * sa
        fun wy(lx: Float, ly: Float) = hy + lx * sa + ly * ca
        val dX = -cos(Math.toRadians(th.toDouble())).toFloat(); val dY = -sin(Math.toRadians(th.toDouble())).toFloat()
        val lsx = wx(dX * 10f, dY * 10f); val lsy = wy(dX * 10f, dY * 10f) // screen light source

        // 1. Far arm (behind the body): hand at the hip, then on the keyboard.
        val tapY = typeAmt * max(0f, sin(sec * 44f)) * 1.3f
        val sfx = 34f; val sfy = 53.5f
        val hfx = lerp(42f, 55.5f, raise); val hfy = lerp(67f, 60f - tapY, raise)
        val (efx, efy) = ik(sfx, sfy, hfx, hfy, 9.4f, 9.4f)
        canvas.sleeve(sfx, sfy, efx, efy, hfx, hfy, 7.4f, 7f, 5.8f, true)

        // 2. Joggers and sneakers: back leg, then the front leg crossing over it.
        canvas.paint(legBack, lg(28f, 0f, 48f, 0f, k.pants[0], k.pants[1]))
        canvas.solid(cuffBack, CUFF_FAR, 0.5f)
        canvas.lines(cuffBackRibs, 0x12FFFFFF, 0.35f)
        canvas.paint(shoeBack, lg(0f, 97f, 0f, 103.6f, k.shoe[1], k.shoe[2]))
        fill.color = k.sole; canvas.drawRect(40.6f, 103.2f, 56.2f, 105.4f, fill)
        canvas.lines(swooshBack, k.accent, 0.9f)
        canvas.paint(legFront, lg(34f, 0f, 52f, 0f, k.pants[1], k.pants[2], k.pants[1]), 0f)
        canvas.clipped(legFront) {
            fill.color = 0x59000000; canvas.drawPath(legFrontShade, fill)
            canvas.lines(legFolds, 0x80000000.toInt(), 0.5f)
            canvas.lines(legFoldsLight, 0x1AFFFFFF, 0.4f)
        }
        canvas.outline(legFront)
        canvas.solid(cuffFront, 0xFF191B21.toInt(), 0.5f)
        canvas.clipped(cuffFront) { canvas.lines(cuffFrontRibs, 0x12FFFFFF, 0.35f) }
        canvas.save()
        canvas.rotate(-tap * 7f, 43.6f, 104.8f) // heel lifts on the beat, toe stays down
        canvas.paint(shoeFront, lg(0f, 95f, 0f, 104f, k.shoe[0], k.shoe[1]), 0f)
        canvas.clipped(shoeFront) {
            fill.color = 0x40788296; canvas.drawPath(shoeShade, fill)
            canvas.lines(swooshFront, k.accent, 1f)
        }
        canvas.outline(shoeFront)
        canvas.solid(tongue, k.shoe[1], 0.45f)
        canvas.lines(laces, 0xFF9AA3B2.toInt(), 0.45f)
        canvas.solid(midFront, 0xFFF1F3F6.toInt(), 0.45f)
        fill.color = k.sole; canvas.drawRect(36.4f, 105.4f, 43.8f, 106.2f, fill)
        canvas.restore()

        // 3. Hoodie: soft shading, shadow under the head, ribbed hem, neon stripe, stitched pocket, folds.
        canvas.paint(body, lg(24f, 0f, 51f, 0f, k.cloth[0], k.cloth[1], k.cloth[2]), 0f)
        canvas.clipped(body) {
            shaded.shader = rg(37f, 47f, 9f, 0x73000000, 0); canvas.drawRect(24f, 44f, 50f, 58f, shaded)
            canvas.paint(rib, lg(0f, 72.4f, 0f, 77f, k.cloth[1], k.cloth[0]), 0f)
            canvas.lines(ribLines, 0x12FFFFFF, 0.35f)
            canvas.lines(ribTop, 0x73000000, 0.45f)
            canvas.paint(stripe, lg(36f, 60f, 50f, 56f, 0xFF1FB8D4.toInt(), k.trim, 0xFF9DF4FF.toInt()), 0f)
            canvas.lines(stripe, 0x66002838, 0.3f)
            canvas.lines(pocket, 0x99000000.toInt(), 0.5f)
            canvas.drawPath(stitch, dashed)
            canvas.lines(foldA, 0x6B000000, 0.5f)
            canvas.lines(foldALight, 0x17FFFFFF, 0.4f)
            canvas.lines(foldB, 0x61000000, 0.45f)
            canvas.lines(bodyLight, 0x24FFFFFF, 1f)
        }
        canvas.outline(body, 0.8f)
        // Hood bunched behind the neck, with its dark inside.
        canvas.paint(hood, lg(23f, 0f, 38f, 0f, k.cloth[0], k.cloth[1], k.cloth[2]), 0f)
        canvas.paint(hoodIn, lg(26f, 46f, 34f, 54f, 0xFF0D0F14.toInt(), 0xFF1A1D24.toInt()), 0f)
        canvas.lines(hoodIn, 0x80000000.toInt(), 0.4f)
        canvas.lines(hoodLight, 0x14FFFFFF, 0.4f)
        canvas.outline(hood)
        // Neck with a soft shadow under the jaw.
        canvas.paint(neck, lg(34f, 0f, 43f, 0f, k.skin[3], k.skin[1]), 0f)
        canvas.clipped(neck) { fill.color = 0x593C140A; canvas.drawRect(33f, 41f, 44f, 44f, fill) }
        canvas.lines(neckline, k.cloth[0], 1.6f)
        // Metal eyelets and drawstrings swinging with the beat.
        for ((ax, len, ph) in arrayOf(Triple(43.4f, 8f, 0f), Triple(45.2f, 7f, 0.8f))) {
            val a = Math.toRadians((sin(sec * 2 * PI / BEAT_S + ph) * vibeAmt * 6 - raise * 8).toDouble())
            val ex = ax + sin(a).toFloat() * len; val ey = 51.6f + cos(a).toFloat() * len
            fill.color = 0xFFB8C0CC.toInt(); canvas.drawCircle(ax, 51.6f, 0.55f, fill)
            ink.strokeWidth = 0.2f; canvas.drawCircle(ax, 51.6f, 0.55f, ink)
            p.reset(); p.moveTo(ax, 51.6f); p.quadTo(ax + 0.5f, 51.6f + len * 0.5f, ex, ey)
            canvas.lines(p, 0x59000000, 1.05f)
            canvas.lines(p, STRING, 0.7f)
            stroke.color = AGLET; stroke.strokeWidth = 1.15f
            canvas.drawLine(ex, ey, ex + sin(a).toFloat() * 1.5f, ey + cos(a).toFloat() * 1.5f, stroke)
        }

        // 4. Head: painted skin, soft shadows, blush, nose and forehead highlights, ear, eyes, brow, mouth, hair.
        canvas.save()
        canvas.rotate(headTilt, HEAD_PX, HEAD_PY)
        canvas.paint(head, lg(22f, 0f, 58f, 0f, k.skin[1], k.skin[0], k.skin[2]), 0f)
        canvas.clipped(head) {
            shaded.shader = rg(30f, 30f, 14f, 0x59965040, 0); canvas.drawRect(16f, 14f, 42f, 44f, shaded)
            fill.color = 0x478C4632; canvas.drawPath(jawAO, fill)
            shaded.shader = rg(50.4f, 37.2f, 3.4f, 0x61EB786E, 0); canvas.drawRect(46f, 33f, 55f, 42f, shaded)
            fill.color = 0x4D965040; canvas.drawPath(noseShade, fill)
            canvas.lines(noseLight, 0xBFFFF0E1.toInt(), 0.6f)
            canvas.lines(foreheadSheen, 0x59FFF0E1, 1.2f)
        }
        canvas.outline(head, 0.8f)
        canvas.paint(ear, lg(35f, 0f, 41f, 0f, k.skin[1], k.skin[0]), 0.6f)
        canvas.lines(earIn, 0x8C783C28.toInt(), 0.4f)
        val blink = mode != Mode.VIBING && !still && (t - start) % 3400 < 120
        if (q > 0.12f && !blink) {
            fill.color = 0xFFFDFCFA.toInt(); canvas.drawPath(eyeWhite, fill)
            canvas.clipped(eyeWhite) {
                fill.color = 0x59788CAA; canvas.drawRect(46f, 28f, 56f, 29.8f, fill) // lid shadow
                val iy = 30.3f + raise * 0.35f
                shaded.shader = rg(51.6f, iy, 2.1f, k.eye[0], k.eye[1]); canvas.drawCircle(51.6f, iy, 2.05f, shaded)
                fill.color = 0xFF0B0D14.toInt(); canvas.drawCircle(51.9f, iy + 0.1f, 0.95f, fill)
                fill.color = 0xFFFFFFFF.toInt(); canvas.drawCircle(52.5f, iy - 0.75f, 0.6f, fill); canvas.drawCircle(50.9f, iy + 0.9f, 0.28f, fill)
            }
            fill.color = LASH; canvas.drawPath(lashUp, fill)
            canvas.lines(lidLow, 0x73783C28, 0.35f)
        } else {
            fill.color = LASH; canvas.drawPath(eyeClosed, fill)
            canvas.lines(lashTips, LASH, 0.4f)
        }
        canvas.paint(brow, lg(45f, 0f, 56f, 0f, k.hair[3], k.hair[0]), 0f)
        canvas.lines(mouth, 0xE66E2D23.toInt(), 0.6f)
        canvas.lines(lip, 0x8CC86E5F.toInt(), 0.45f)
        val shineRgb = k.hair[2] and 0xFFFFFF
        for (l in locksBack) {
            canvas.paint(l.path, lg(l.rx, l.ry, l.tx, l.ty, k.hair[3], k.hair[0], k.hair[1]), 0.6f)
            canvas.lines(l.shine, alpha(0.4f, shineRgb), 0.45f)
        }
        canvas.paint(hairMass, lg(0f, 8f, 0f, 37f, k.hair[1], k.hair[0], k.hair[3]), 0f)
        canvas.clipped(hairMass) {
            canvas.paint(hairShine, lg(28f, 12f, 50f, 13f, alpha(0f, shineRgb), alpha(0.6f, shineRgb), alpha(0.12f, shineRgb)), 0f)
            canvas.lines(hairStrands, 0x59000000, 0.45f)
        }
        canvas.outline(hairMass)
        for (l in locksFront) {
            canvas.paint(l.path, lg(l.rx, l.ry, l.tx, l.ty, k.hair[0], k.hair[1]), 0.55f)
            canvas.lines(l.shine, alpha(0.45f, shineRgb), 0.4f)
        }
        // Screen light on the face and a cyan rim light along the profile.
        if (glow > 0.01f) {
            canvas.clipped(head) {
                shaded.shader = rg(lsx, lsy, 30f, screen(0.55f * glow), screen(0f)); canvas.drawRect(20f, 5f, 60f, 50f, shaded)
                canvas.lines(profileRim, alpha(0.8f * glow, 0x78EBFF), 0.8f)
            }
        }
        canvas.restore()

        // 5. Headphones: padded band, metal slider, cushion, shell, glowing ring.
        val cupX = lerp(36.4f, 36.8f, hpP); val cupY = lerp(31.4f, 48.8f, hpP)
        canvas.save()
        val band: Path
        if (hpP < 0.5f) {
            canvas.rotate(headTilt, HEAD_PX, HEAD_PY); band = bandOn
        } else {
            p.reset(); p.moveTo(cupX - 1f, cupY - 2.4f); p.cubicTo(31.6f, 47f, 30f, 49.6f, 31.6f, 52f); band = p
        }
        canvas.lines(band, INK, 3.4f); canvas.lines(band, k.phones[0], 2.6f); canvas.lines(band, k.phones[2], 0.5f)
        canvas.restore()
        canvas.save()
        if (hpP < 0.5f) canvas.rotate(headTilt * (1 - hpP * 2), HEAD_PX, HEAD_PY)
        canvas.translate(cupX, cupY)
        canvas.rotate(lerp(0f, -50f, hpP))
        fill.color = 0xFF8E97A6.toInt(); canvas.drawRect(-0.7f, -7.4f, 0.7f, -5f, fill)
        ink.strokeWidth = 0.35f; canvas.drawRect(-0.7f, -7.4f, 0.7f, -5f, ink)
        fill.color = 0xFF0D0E12.toInt(); canvas.drawOval(-5f, -5.4f, 3.4f, 5.8f, fill)
        rect.set(-4.3f, -5.7f, 4.3f, 5.7f)
        shaded.shader = lg(-3f, -5f, 3f, 5f, k.phones[2], k.phones[1], k.phones[0]); canvas.drawOval(rect, shaded)
        ink.strokeWidth = 0.7f; canvas.drawOval(rect, ink)
        rect.set(-2.2f, -4.1f, 3f, 3.5f)
        shaded.shader = lg(-2f, -3f, 2f, 3f, k.phones[1], k.phones[0]); canvas.drawOval(rect, shaded)
        val ringRgb = k.accent and 0xFFFFFF
        stroke.color = alpha(0.25f, ringRgb); stroke.strokeWidth = 1.3f; canvas.drawOval(rect, stroke)
        stroke.color = k.accent; stroke.strokeWidth = 0.45f; canvas.drawOval(rect, stroke)
        canvas.save(); canvas.rotate(-23f, -1.6f, -2.6f)
        fill.color = 0x2EFFFFFF; canvas.drawOval(-2.5f, -4.2f, -0.7f, -1f, fill)
        canvas.restore()
        canvas.restore()

        // 6. Laptop: rounded aluminium base and lid, hinge, sheen, glowing logo.
        canvas.save()
        canvas.translate(hx, hy); canvas.rotate(ang)
        p.reset(); rect.set(-LB, 0f, 0f, BT); p.addRoundRect(rect, 0.9f, 0.9f, Path.Direction.CW)
        canvas.paint(p, lg(0f, 0f, 0f, BT, 0xFFE6EAF0.toInt(), 0xFFB4BCC8.toInt(), 0xFF7F8896.toInt()), 0.6f)
        stroke.color = 0xB3FFFFFF.toInt(); stroke.strokeWidth = 0.35f; canvas.drawLine(-LB + 1f, 0.5f, -1f, 0.5f, stroke)
        stroke.color = 0xFF3A3F48.toInt(); stroke.strokeWidth = 0.6f
        canvas.drawLine(-LB + 3f, BT, -LB + 4.2f, BT, stroke); canvas.drawLine(-4.2f, BT, -3f, BT, stroke)
        canvas.save(); canvas.rotate(th) // lid frame: the lid runs along -x from the hinge
        p2.reset(); rect.set(-LL, -LT, 0f, 0f); p2.addRoundRect(rect, 1f, 1f, Path.Direction.CW)
        canvas.paint(p2, lg(-LL, 0f, 0f, 0f, 0xFFD3D9E2.toInt(), 0xFFB9C1CC.toInt(), 0xFF99A2B0.toInt()), 0f)
        canvas.clipped(p2) {
            p.reset(); p.moveTo(-LL * 0.7f, -LT); p.lineTo(-LL * 0.55f, -LT); p.lineTo(-LL * 0.45f, 0f); p.lineTo(-LL * 0.6f, 0f); p.close()
            fill.color = 0x59FFFFFF; canvas.drawPath(p, fill)
            stroke.color = 0x99FFFFFF.toInt(); stroke.strokeWidth = 0.3f; canvas.drawLine(-LL + 0.8f, -LT + 0.4f, -0.8f, -LT + 0.4f, stroke)
        }
        canvas.outline(p2, 0.6f)
        if (lid > 0.15f) {
            canvas.glowDot(-LL * 0.55f, -LT * 0.5f, 1.15f, k.accent, 0.6f + 0.6f * glow)
            fill.color = 0xFFDFFBFF.toInt(); canvas.drawCircle(-LL * 0.55f, -LT * 0.5f, 0.45f, fill)
        }
        canvas.restore()
        fill.color = 0xFF4A505B.toInt(); canvas.drawCircle(0f, 0f, 1.1f, fill)
        ink.strokeWidth = 0.45f; canvas.drawCircle(0f, 0f, 1.1f, ink)
        if (raise < 0.5f) canvas.glowDot(-LB * 0.5f, -LT * 0.5f, 1f, k.accent, 0.6f)
        canvas.restore()
        if (glow > 0.01f) {
            val tx = wx(dX * (LL - 1f), dY * (LL - 1f)); val ty = wy(dX * (LL - 1f), dY * (LL - 1f))
            val bx = wx(dX * 2f, dY * 2f); val by = wy(dX * 2f, dY * 2f)
            shaded.shader = lg(lsx, lsy, 55f, 30f, screen(0.18f * glow), screen(0f))
            p.reset(); p.moveTo(tx, ty); p.lineTo(56.6f, 24f); p.lineTo(56f, 40f); p.lineTo(bx, by); p.close()
            canvas.drawPath(p, shaded)
        }

        // 7. Near arm holds the laptop; then both hands.
        val snx = 37f; val sny = 54.4f
        val hnx = lerp(39.4f, 48f, raise); val hny = lerp(72f, 64.4f, raise)
        val (enx, eny) = ik(snx, sny, hnx, hny, 9.6f, 9.4f)
        canvas.sleeve(snx, sny, enx, eny, hnx, hny, 8.2f, 7.8f, 6.4f, false)
        canvas.hand(hnx, hny, lerp(1.4f, 0.1f, raise), false)
        if (raise > 0.05f) canvas.hand(hfx, hfy, 0.15f, true)
        if (glow > 0.01f) {
            canvas.clipped(body) {
                shaded.shader = rg(lsx, lsy, 22f, screen(0.3f * glow), screen(0f)); canvas.drawRect(20f, 40f, 60f, 80f, shaded)
            }
        }
        canvas.restore()

        scheduleNext(t, anim, q)
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
            still -> Unit
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
    private fun alpha(a: Float, rgb: Int) = ((a.coerceIn(0f, 1f) * 255).toInt() shl 24) or (rgb and 0xFFFFFF)
    private fun screen(a: Float) = alpha(a, 0xAAEBFF)

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
        const val LB = 21f           // laptop base length, lid length, base and lid thickness
        const val LL = 21f
        const val BT = 2.8f
        const val LT = 3f

        val INK = 0xEB16141E.toInt()
        val LASH = 0xFF14100E.toInt()
        val STRING = 0xFFEEF1F5.toInt()
        val AGLET = 0xFF8E97A6.toInt()
        val CUFF_FAR = 0xFF15171D.toInt()
        val CUFF_NEAR = 0xFF23272F.toInt()
    }
}
