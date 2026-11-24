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
 * The floating bubble's character, on a transparent background: a chibi (big head, short limbs, small hands),
 * seen from the side, leaning back against the screen edge with his legs crossed. Drawn in code in a 60 x 108 box
 * (faces right, back against the left edge; mirrored when docked on the right). Bold ink outlines and hard cel
 * shading. His look comes from a [Skin] (see [Skins]): colours, hair or headwear and outfit; the rig is shared.
 *
 *  - [Mode.VIBING]    headphones on, eyes closed, slight nod, toe tap, drawstrings swing; laptop closed at his side.
 *                     Goes still after a while to save battery.
 *  - [Mode.LISTENING] headphones slide to his neck, he raises the laptop and opens the lid (only its back is seen),
 *                     and the screen light falls on his face. He types while [speaking] or after [nudge].
 *  - [Mode.THINKING]  same pose, hands still, the screen light pulses softly.
 *
 * With system animations off he switches between still poses. Visual prototype: .ai-workspace/scratch/skins.html
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

    /** Which character to draw. */
    var skin: Skin = Skins.DEFAULT
        set(v) { if (field !== v) { field = v; gradients(); invalidate() } }

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

    // Shared rig.
    private val head = svg("M40 9.4 C29.4 9.4 22.4 17.6 22.6 28 C22.8 35.4 26.2 41.2 32.4 44.2 L37.6 46 C41.8 46.8 46 46.6 49.6 45.4 L53 43.8 C54.4 43.2 55.2 42.2 55 41 L54.6 39.8 L55.6 39 L55 38 L56.2 37.2 C56.7 36.6 56.6 36 56.1 35.6 L58.6 34.4 C59.1 33.9 58.8 33.2 58.2 32.8 L56.9 30.2 C57 28.8 57.4 27.6 57.6 26.2 C58 18.6 51 9.4 40 9.4 Z")
    private val headShade = svg("M22 14 L40 14 C37 22 36 30 38.6 38 C41 43 46 45.6 50.4 46.6 L22 50 Z")
    private val cheekShade = svg("M46.6 36.4 C49.4 38.4 52.6 38.8 55.4 37.6 L55.6 39.6 C52.6 41.6 49 41.4 46.6 39.6 Z")
    private val noseLight = svg("M57.2 30.6 L58.2 33")
    private val ear = svg("M36.4 27 C39.4 27 40.4 30 40 33 C39.6 35.4 37.6 36.6 36 35.8 C34.6 34 34.6 29 36.4 27 Z")
    private val earIn = svg("M37 29.2 C38.4 30 38.6 32 37.6 33.6")
    private val eyeWhite = svg("M46.8 30.6 Q50.6 27.8 54.4 29.4 L54.2 31 Q50.6 33.2 46.8 30.6 Z")
    private val lash = svg("M46.4 30.4 Q50.4 27.2 54.6 29 L55.6 28.2")
    private val eyeClosed = svg("M46.8 31 Q50.6 33.2 54.4 30.6 L55.4 29.8")
    private val brow = svg("M45.8 27.2 L50.4 24.8 L55.4 25.2 L55.2 26.6 L50.6 26.4 L46.4 28.2 Z")
    private val mouth = svg("M51.8 40.2 Q53.6 40.8 55 39.6")
    private val neck = svg("M34 42 L42 42.6 L42.6 48.4 L34.6 48.6 Z")
    private val neckline = svg("M37.6 47.6 C40.6 47.8 43.6 49 46.2 51.4")
    private val body = svg("M33 46 C28 45.6 24.6 48 24.4 52 C24.2 54.6 25 56.4 26 57.4 C25 62 25 67 25.8 71 L26.2 72.6 L26.4 77 C32 78 40 78 47 76.8 L47.2 72.8 C49.8 72 50.8 70 50.6 67 C50.4 62 50 57.4 48.6 53.4 C47.4 50 45 47.6 41.6 46.8 C38.8 46.2 35.8 46 33 46 Z")
    private val bodyShade = svg("M20 44 L33.6 44 C30.6 52 30 62 31.6 72 L32.6 80 L20 80 Z")
    private val bodyLight = svg("M46.6 54.6 Q49.4 60 48.8 66")
    private val legBack = svg("M28 75 L44 75 C45.6 81 47 88 48.4 95 C48.6 97 48.2 98.6 47.4 99 L41.6 99.4 C41 98 41 96.6 41.4 95.4 C39.8 89 35 82 28 75 Z")
    private val legFront = svg("M34 76 L46 76 C48.8 80 51.2 85 51 89 C50.6 93 46.6 96.4 42 98.6 C41.6 99.4 40.6 99.8 39.6 99.8 L34.4 98.6 C34.2 97.4 34.8 96.4 35.6 96 C39.4 94 43 91.6 43.6 89.4 C43 85 38.6 80 34 76 Z")
    private val legFrontShade = svg("M34 76 L40 76 C43 80 46 86 45.6 90 C44 93 40 96 36 98.4 L34 99 Z")
    private val legFolds = svg("M45.4 85.4 Q48.6 87.4 51 86 M37.4 95 Q39.6 96.4 42 95.6 M36 80 Q40 83 44.6 81.6")
    private val shoeBack = svg("M41 97 L48.6 97.2 C51.8 97.6 55 99.4 56 101.6 L56.2 103.6 L40.6 103.6 L40.4 99.6 Z")
    private val shoeBackLine = svg("M45 101.6 Q48.4 99.8 52.2 100.8")
    private val shoeFront = svg("M31.6 95.6 L38 96.6 C41 98 43.6 100.6 44 103 L43.6 104.6 L35.6 104.4 C33.4 102.6 31.6 99.4 31.6 95.6 Z")
    private val soleFront = svg("M35.6 104.4 L43.6 104.6 L43.8 105.8 L36.6 105.6 Z")
    private val shoeFrontLine = svg("M35 100.4 Q38.2 99 41.2 101.4")

    // Hair and headwear.
    private val spiky = svg("M23 31 C21.2 26.4 21.2 21.6 22.6 17.6 L18.6 15.2 L24.6 12.6 L23.2 7.8 L29.8 9.2 L31 3.6 L36.8 7.4 L41.4 2.4 L44.6 7.6 L51 4.8 L51 9.6 L57.6 9.2 L55.8 13.4 L60.6 15.6 L56.4 18 L57.8 22.4 L53.6 21 L52.4 24.4 L49.6 20.8 L46.4 24.2 L45.6 20.6 L42.6 23 C41.8 24.4 40.8 25 39.6 25.4 L38.4 31.6 C35.8 32.6 33.8 34.6 32.8 37 C30.2 35.6 27.8 35.6 26.4 36.2 C24.6 34.8 23.6 33 23 31 Z")
    private val spikyLight = svg("M27 12 L33.6 8.8 M37.6 7.6 L43 5.6 M47 8.6 L52.6 8.2")
    private val slick = svg("M23.4 30 C22.4 24.6 23.6 19.4 26.6 15.6 L40 16.4 L39.6 24.4 L38.4 31.4 C35.8 32.4 33.8 34.4 32.8 37 C30.4 35.8 28.4 35.6 26.8 36 C25 34.6 23.8 32.4 23.4 30 Z")
    private val longBack = svg("M24.6 22 C19.6 31 19.2 44 21.6 56.6 L25.2 53 L25.8 60 L29.6 52.6 L32.4 56 L33.6 46 C31 40 30.6 32 33 24 Z")
    private val longTop = svg("M23 31 C20.8 21.6 26 11 38.6 9.2 C48.6 7.8 56.6 12.4 58.2 19.6 C58.6 21.6 58 23 56.8 23.4 C55.4 20.6 52.6 19 49.8 19.4 C50.6 21.6 50.2 23.8 48.8 25 C47.8 22.6 46 21.2 43.8 21 C43.2 23.6 41.6 25.2 39.6 25.8 L38.4 31.6 C35.8 32.6 33.8 34.6 32.8 37 C30.2 35.6 27.8 35.6 26.4 36.2 C24.6 34.8 23.6 33 23 31 Z")
    private val longLight = svg("M29 14.4 Q38 9.6 48 11.4 M27.6 19 Q31 15 36 13.6")
    private val hood = svg("M38.6 5.4 C26.4 5.6 19.2 15 19.6 27.2 C20 38 24.8 45.8 31 50.8 L37 48 C34 44.2 32.6 40 33.8 35.6 L40.4 24.4 C44.4 21 50.2 20.2 55.6 21 C57.8 21.4 59 22.6 59.4 24.4 C59.8 13.4 50.8 5.2 38.6 5.4 Z")
    private val hoodShade = svg("M20 26 C22 36 26 44 31.6 50 L36 48.6 C33.4 44 32.4 40 33.8 35.6 L40.4 24.4 C33 23 25 24 20 26 Z")
    private val hoodTrim = svg("M33.6 35.6 L40.4 24.4 C44.4 21 50.2 20.2 55.6 21 C57.8 21.4 59 22.6 59.4 24.4")
    private val faceShadow = svg("M20 10 L60 10 L60 33 C52 31.4 46 32 40 34 L20 40 Z")
    private val hatCrown = svg("M26.6 14.6 C26.8 8.2 32.4 4.4 40.6 4.4 C48.2 4.4 52.8 8 53.4 14.6 Z")
    private val hatBand = svg("M26.6 14.6 L53.4 14.6 L53.1 11.6 L26.9 11.6 Z")
    private val hatDent = svg("M33 6.8 Q40 9.4 47.6 6.4")
    private val hatBrim = svg("M18.4 16.8 C26 13.2 52.4 13.2 61.6 16.6 C60.8 18.4 57.6 18.8 54 18 C44 16.2 34 16.2 25.6 18 C22 18.8 19.2 18.4 18.4 16.8 Z")
    private val earFar = svg("M29.2 14 L27.8 3.4 L36.6 9.8 Z")
    private val earFarIn = svg("M30.2 11.8 L29.4 6.2 L34 9.8 Z")
    private val earNear = svg("M37.4 10.2 L42.2 1.4 L48.4 9.8 Z")
    private val earNearIn = svg("M39.8 9.4 L42.4 4.4 L45.8 9.2 Z")
    private val earTip = svg("M42.2 1.4 L43.4 3.6")
    private val helmet = svg("M39.6 6.4 C27.8 6.4 20.8 15 21.2 27 C21.4 34 24.2 39.6 29.4 43.4 L36.4 44 L36 36.4 L42.6 32.6 L57.9 32.4 C58.8 29.8 58.8 26.6 58.2 23.6 C56.4 13.4 49.6 6.4 39.6 6.4 Z")
    private val helmetShade = svg("M21 24 C21.4 33 24.4 39.4 29.4 43.4 L36.4 44 L36 36.4 L30 30 Z")
    private val helmetLine = svg("M28 14 Q37 9.6 48 10.6 M36 36.4 L36.4 44 M27.4 34 L35.8 36.6")
    private val visor = svg("M43.6 24.4 L58.5 23.6 L58.8 30.4 L44.2 31.6 C43.2 29.2 43 26.8 43.6 24.4 Z")
    private val visorShine = svg("M45 26 L58 25.4")
    private val visorSlit = svg("M46 28.6 L57.6 28")
    private val fin = svg("M37.6 7.2 L43.8 1.4 L46.6 7.8 Z")
    private val bandOn = svg("M35.4 25.4 C33.4 16 36.8 8.6 44.4 7.4")
    private val bandHood = svg("M35.4 25.4 C33 15 36.4 7 44.4 5.6")

    // Outfits.
    private val hoodBack = svg("M33.4 45 C28.6 43.6 23.6 46.4 23.4 51.4 C23.2 56 27 58.6 31.4 58 C35 57.6 37.2 54 37.4 50.4 C37.4 47.8 35.8 45.8 33.4 45 Z")
    private val hoodBackShade = svg("M22 50 Q29 52 37 49 L37 60 L22 60 Z")
    private val hoodFold = svg("M25.6 50.6 Q29.6 48.8 34.2 51.4 M26.2 54.6 Q30 54.4 34.4 55.8")
    private val stripe = svg("M36.6 58.4 L50.2 55.4 L50.6 58.6 L37 61.6 Z")
    private val pocket = svg("M35.6 66.4 L47.6 65.6 L48.6 71.6 L35.2 72.2")
    private val rib = svg("M26 72.6 L47.4 72.4 L47 76.8 C40 78 32 78 26.4 77 Z")
    private val ribLines = svg("M28 73 L28 77 M31 73 L31 77.6 M34 73 L34 77.8 M37 73 L37 77.8 M40 73 L40 77.8 M43 73 L43 77.6 M46 73 L46 77")
    private val cape = svg("M31 46.4 C24.4 51.6 19.8 64 19.4 84.4 C22.6 86.4 26.8 86.4 30 85 L28.6 82 L32.2 84.4 C33.4 72 34 59.6 36.6 50 Z")
    private val capeShade = svg("M31 46.4 C24.4 51.6 19.8 64 19.4 84.4 C21.4 85.4 23.6 85.8 25.6 85.4 C25 72 26.6 58 31 46.4 Z")
    private val capeTrim = svg("M30.4 50 C27 58 25.4 70 25.4 84")
    private val cloakTrim = svg("M41.6 47.4 C44 55 45 64 44.8 76.8")
    private val belt = svg("M25.8 69.4 L50.6 68.2 L50.8 71.8 L26 72.8 Z")
    private val beltLine = svg("M26 70.6 L50.6 69.4")
    private val pouch = svg("M43 70 L48.6 69.8 L48.8 74.6 L43.4 74.8 Z")
    private val cloakCollar = svg("M33.6 44.6 C28.6 43.8 24 47 23.8 51.8 C27.6 50.4 32 50.4 36.4 51.8 L39.4 46.6 Z")
    private val coatSkirt = svg("M26 70 L49.6 69 C51.4 75 52.6 80.6 53 85.6 L45 86.4 C40.6 84.6 33.4 84.8 27.4 86.6 C26.4 81 25.6 75.4 26 70 Z")
    private val coatSkirtShade = svg("M26 70 L33 69.8 C32 75 31.6 80.6 32.4 85 C30.6 85.4 29 86 27.4 86.6 C26.4 81 25.6 75.4 26 70 Z")
    private val coatSplit = svg("M41.6 70.4 L42.8 85")
    private val coatSeam = svg("M44.6 54 L44.8 64.6")
    private val lapel = svg("M37.4 46.6 L43.4 47.2 L48 55.6 L44.4 57 L44.6 52.4 Z")
    private val coatCollar = svg("M33.6 45 C29 44 24.4 47 24 51.6 C27 50.6 30.6 50.4 34.6 51.6 L37.8 47 Z")
    private val shirt = svg("M38.6 46.4 L44 47 L46 52 L44.6 52.6 Z")
    private val tie = svg("M41.6 47.4 L43.4 47.6 L44.6 53.6 L43.6 55.4 L42.6 53.4 Z")
    private val coatBelt = svg("M25.8 66.8 L50.6 65.6 L50.8 69.2 L26 70.2 Z")
    private val buckle = svg("M44 65.8 L47.6 65.6 L47.7 69 L44.1 69.2 Z")
    private val robeCollar = svg("M36.6 46.4 L40.6 46.8 L47.4 60.6 L44.4 62 Z")
    private val robeBack = svg("M33.6 45 C29 44 24.4 47 24 51.4 C27 50.6 31 50.6 35 51.4 L38 47 Z")
    private val sash = svg("M25.8 65.6 L50.4 64.4 L50.8 70.6 L26.2 71.6 Z")
    private val sashKnot = svg("M45.6 64.6 L49.6 64.4 L49.8 70.2 L45.8 70.4 Z")
    private val sashTail = svg("M47 70 L51.4 78.6 L48.6 79.4 L45.6 70.6 Z")
    private val robeHem = svg("M26.2 74.6 L47.2 73.8")
    private val chestPlate = svg("M34 49.4 C38 47.6 44.6 47.8 48 51.6 C49.6 55.6 50 59.6 49.4 63.6 C45 66 37.4 66 33 63.4 C31.6 58.6 32 53.6 34 49.4 Z")
    private val plateShade = svg("M30 48 L38 48 C36 54 36 60 37.6 66 L30 66 Z")
    private val platePanel = svg("M33.8 56.4 Q41 55.2 49.6 56.6 M40.6 48.2 L41.4 65")
    private val abs = svg("M33.4 66.4 L49.4 65.8 M33.8 70.2 L49.8 69.6")
    private val armorHem = svg("M26 72.4 L47.4 72 L47 76.8 C40 78 32 78 26.4 77 Z")
    private val neckRing = svg("M33 44.2 L43.6 44.8 L44.4 48.8 L33.4 48.4 Z")
    private val pauldron = svg("M33 50.6 C35.6 48.4 40 48.6 41.6 51.6 C42.2 54.2 40.8 56.6 37.6 57.2 C34.6 57.4 32.6 55.4 33 50.6 Z")

    // ------------------------------------------------------------------ paints

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        color = INK; strokeWidth = 0.8f
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val shaded = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val p = Path()
    private val p2 = Path()

    private fun lin(x0: Float, y0: Float, x1: Float, y1: Float, vararg c: Int) =
        LinearGradient(x0, y0, x1, y1, c, null, Shader.TileMode.CLAMP)

    /** Hard-edged three-tone gradient: the "cel" shading look. */
    private fun cel(x0: Float, y0: Float, x1: Float, y1: Float, c0: Int, c1: Int, c2: Int, a: Float, b: Float) =
        LinearGradient(x0, y0, x1, y1, intArrayOf(c0, c0, c1, c1, c2, c2), floatArrayOf(0f, a, a, b, b, 1f), Shader.TileMode.CLAMP)

    private lateinit var gLegBack: Shader
    private lateinit var gShoe: Shader
    private lateinit var gHair: Shader
    private lateinit var gHood: Shader
    private lateinit var gHat: Shader
    private lateinit var gHelmet: Shader

    private fun gradients() {
        val k = skin
        gLegBack = cel(28f, 0f, 48f, 0f, k.pants[0], k.pants[1], k.pants[1], 0.5f, 0.9f)
        gShoe = cel(0f, 96f, 0f, 105f, k.shoe[0], k.shoe[0], k.shoe[1], 0.5f, 0.62f)
        gHair = if (k.hairStyle == HairStyle.LONG) cel(0f, 8f, 0f, 36f, k.hair[1], k.hair[0], k.hair[0], 0.3f, 0.9f)
        else cel(0f, 4f, 0f, 36f, k.hair[1], k.hair[0], k.hair[0], 0.28f, 0.9f)
        gHood = cel(20f, 0f, 60f, 0f, k.cloth[0], k.cloth[1], k.cloth[2], 0.4f, 0.85f)
        gHat = if (k.gear.size >= 2) cel(26f, 0f, 54f, 0f, k.gear[0], k.gear[1], k.gear[1], 0.45f, 0.9f) else gHood
        gHelmet = if (k.gear.size >= 2) cel(21f, 0f, 59f, 0f, k.gear[1], k.gear[0], k.gear[0], 0.38f, 0.9f) else gHood
    }

    init { gradients() }

    private fun Canvas.outline(path: Path, w: Float = 0.8f) { ink.strokeWidth = w; drawPath(path, ink) }

    private fun Canvas.solid(path: Path, color: Int, w: Float = 0.8f) {
        fill.color = color; drawPath(path, fill)
        if (w > 0f) outline(path, w)
    }

    private fun Canvas.shade(path: Path, shader: Shader, w: Float = 0.8f) {
        shaded.shader = shader; drawPath(path, shaded)
        if (w > 0f) outline(path, w)
    }

    private fun Canvas.lines(path: Path, color: Int, w: Float) {
        stroke.color = color; stroke.strokeWidth = w; drawPath(path, stroke)
    }

    /** A soft halo under a crisp line (glowing eyes, visor, trims) without shadow layers. */
    private fun Canvas.glowLine(path: Path, color: Int, w: Float, halo: Float = 1f) {
        lines(path, alpha(0.22f * halo, color and 0xFFFFFF), w * 3.2f)
        lines(path, alpha(0.4f * halo, color and 0xFFFFFF), w * 1.9f)
        lines(path, color, w)
    }

    private inline fun Canvas.clipped(clip: Path, block: () -> Unit) {
        save(); clipPath(clip); block(); restore()
    }

    /** Baggy sleeve from shoulder S over elbow E to wrist H: tapered, curved, cel shadow, a fold and a cuff. */
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
        fill.color = color; drawPath(p, fill); outline(p, 0.75f)
        drawCircle(sx, sy + 0.4f, wS * 0.52f, fill) // rounded shoulder
        clipped(p) {
            p2.reset()
            p2.moveTo(ex - nex * wE / 2, ey - ney * wE / 2)
            p2.lineTo(hx - n2x * wH / 2, hy - n2y * wH / 2)
            p2.lineTo(hx - n2x * wH * 0.05f, hy - n2y * wH * 0.05f)
            p2.quadTo(ex - nex * wE * 0.1f, ey - ney * wE * 0.1f, sx - n1x * wS * 0.1f, sy - n1y * wS * 0.1f)
            p2.lineTo(sx - n1x * wS / 2, sy - n1y * wS / 2)
            p2.close()
            fill.color = 0x38000000; drawPath(p2, fill)
            val cx = lerp(ex, hx, 0.55f); val cy = lerp(ey, hy, 0.55f)
            p2.reset()
            p2.moveTo(cx + n2x * wE * 0.55f, cy + n2y * wE * 0.55f)
            p2.quadTo(cx - (hx - ex) * 0.12f, cy - (hy - ey) * 0.12f, cx - n2x * wE * 0.5f, cy - n2y * wE * 0.5f)
            lines(p2, 0x59000000, 0.5f)
        }
        stroke.strokeCap = Paint.Cap.BUTT
        stroke.color = cuff; stroke.strokeWidth = wH * 1.04f
        drawLine(lerp(ex, hx, 0.82f), lerp(ey, hy, 0.82f), hx, hy, stroke)
        stroke.strokeCap = Paint.Cap.ROUND
    }

    private fun Canvas.hand(x: Float, y: Float, angleRad: Float, color: Int) {
        save(); translate(x, y); rotate(Math.toDegrees(angleRad.toDouble()).toFloat())
        fill.color = color
        ink.strokeWidth = 0.6f
        drawOval(-1.2f, -1.9f, 3.6f, 1.9f, fill); drawOval(-1.2f, -1.9f, 3.6f, 1.9f, ink)
        save(); rotate(-28.6f, 0.4f, -1.4f)
        drawOval(-0.7f, -2.2f, 1.5f, -0.6f, fill); drawOval(-0.7f, -2.2f, 1.5f, -0.6f, ink)
        restore()
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
        val lsx = wx(dX * 10f, dY * 10f); val lsy = wy(dX * 10f, dY * 10f) // screen light source

        // 0. Cape and long hair behind everything.
        if (k.outfit == Outfit.CLOAK) {
            canvas.solid(cape, k.cloth[1])
            canvas.clipped(cape) { fill.color = k.cloth[0]; canvas.drawPath(capeShade, fill) }
            canvas.lines(capeTrim, k.trim, 0.7f)
        }
        if (k.hairStyle == HairStyle.LONG) {
            canvas.save(); canvas.rotate(headTilt, HEAD_PX, HEAD_PY); canvas.solid(longBack, k.hair[0]); canvas.restore()
        }

        // 1. Far arm (behind the body): hand at the hip, then on the keyboard.
        val tapY = typeAmt * max(0f, sin(sec * 44f)) * 1.3f
        val sfx = 34f; val sfy = 53.5f
        val hfx = lerp(42f, 55.5f, raise); val hfy = lerp(67f, 60f - tapY, raise)
        val (efx, efy) = ik(sfx, sfy, hfx, hfy, 9.4f, 9.4f)
        canvas.sleeve(sfx, sfy, efx, efy, hfx, hfy, 7.4f, 7f, 5.8f, k.cloth[0], if (k.outfit == Outfit.ARMOR) k.cloth[0] else k.cuff)

        // 2. Legs: back leg and shoe, then the front leg crossing over it.
        canvas.shade(legBack, gLegBack)
        canvas.solid(shoeBack, k.shoe[1])
        fill.color = k.sole; canvas.drawRect(40.2f, 103.2f, 56.4f, 105.4f, fill)
        canvas.lines(shoeBackLine, k.accent, 1f)
        canvas.solid(legFront, k.pants[1])
        canvas.clipped(legFront) {
            fill.color = k.pants[0]; canvas.drawPath(legFrontShade, fill)
            canvas.lines(legFolds, 0x66000000, 0.55f)
        }
        canvas.save()
        canvas.rotate(-tap * 7f, 43.6f, 104.8f) // heel lifts on the beat, toe stays down
        canvas.shade(shoeFront, gShoe)
        fill.color = k.sole; canvas.drawPath(soleFront, fill)
        canvas.lines(shoeFrontLine, k.accent, 1f)
        canvas.restore()

        // 3. Trench coat skirt over the legs.
        if (k.outfit == Outfit.TRENCH) {
            canvas.solid(coatSkirt, k.cloth[1])
            canvas.clipped(coatSkirt) { fill.color = k.cloth[0]; canvas.drawPath(coatSkirtShade, fill) }
            canvas.outline(coatSplit, 0.6f)
        }

        // 4. Torso and outfit details.
        canvas.solid(body, k.cloth[1])
        canvas.clipped(body) {
            fill.color = k.cloth[0]; canvas.drawPath(bodyShade, fill)
            when (k.outfit) {
                Outfit.HOODIE -> {
                    fill.color = k.cloth[0]; canvas.drawPath(rib, fill)
                    canvas.lines(ribLines, 0x59000000, 0.35f)
                    fill.color = k.trim; canvas.drawPath(stripe, fill)
                    canvas.lines(pocket, 0x8C000000.toInt(), 0.6f)
                    canvas.lines(bodyLight, 0x24FFFFFF, 1.1f)
                }
                Outfit.CLOAK -> {
                    canvas.lines(cloakTrim, k.trim, 0.9f)
                    fill.color = k.cloth[0]; canvas.drawPath(belt, fill)
                    canvas.solid(pouch, 0xFF2A2F44.toInt(), 0.5f)
                    canvas.lines(beltLine, k.trim, 0.4f)
                }
                Outfit.TRENCH -> {
                    fill.color = k.cloth[0]; canvas.drawPath(coatBelt, fill)
                    canvas.solid(buckle, 0xFF2A2018.toInt(), 0.5f)
                    canvas.lines(coatSeam, 0x4D000000, 0.5f)
                }
                Outfit.ROBE -> {
                    canvas.solid(sash, k.accent, 0.6f)
                    fill.color = k.trim; canvas.drawPath(robeCollar, fill)
                    canvas.lines(robeHem, k.trim, 0.9f)
                }
                Outfit.ARMOR -> {
                    canvas.solid(chestPlate, k.cloth[2], 0.7f)
                    canvas.clipped(chestPlate) { fill.color = 0x401E325A; canvas.drawPath(plateShade, fill) }
                    canvas.lines(platePanel, 0x80141E3C.toInt(), 0.5f)
                    fill.color = k.accent; canvas.drawCircle(44.6f, 59.4f, 2f, fill)
                    ink.strokeWidth = 0.5f; canvas.drawCircle(44.6f, 59.4f, 2f, ink)
                    canvas.lines(abs, 0x59000000, 0.5f)
                    fill.color = k.cloth[0]; canvas.drawPath(armorHem, fill)
                }
            }
        }
        // Collars (behind the neck and head).
        when (k.outfit) {
            Outfit.HOODIE -> {
                canvas.solid(hoodBack, k.cloth[1])
                canvas.clipped(hoodBack) { fill.color = k.cloth[0]; canvas.drawPath(hoodBackShade, fill) }
                canvas.lines(hoodFold, 0x73000000, 0.6f)
            }
            Outfit.TRENCH -> {
                fill.color = 0xFFF2F2F0.toInt(); canvas.drawPath(shirt, fill)
                canvas.solid(tie, k.trim, 0.5f)
                canvas.solid(lapel, k.cloth[2], 0.7f)
                canvas.solid(coatCollar, k.cloth[2], 0.7f)
            }
            Outfit.ROBE -> canvas.solid(robeBack, k.trim, 0.7f)
            Outfit.ARMOR -> canvas.solid(neckRing, k.cloth[0], 0.6f)
            Outfit.CLOAK -> canvas.solid(cloakCollar, k.cloth[1], 0.7f)
        }
        fill.color = k.skin[1]; canvas.drawPath(neck, fill)
        if (k.outfit == Outfit.HOODIE || k.outfit == Outfit.CLOAK) canvas.lines(neckline, k.cloth[0], 1.5f)

        // 5. Head.
        canvas.save()
        canvas.rotate(headTilt, HEAD_PX, HEAD_PY)
        canvas.solid(head, k.skin[0])
        canvas.clipped(head) {
            fill.color = k.skin[1]; canvas.drawPath(headShade, fill)
            fill.color = 0x1F964632; canvas.drawPath(cheekShade, fill)
            canvas.lines(noseLight, k.skin[2], 0.8f)
            fill.color = 0x29E66E64; canvas.drawCircle(50.4f, 37f, 1.9f, fill)
        }
        canvas.solid(ear, k.skin[1], 0.7f)
        canvas.lines(earIn, 0x8064321E.toInt(), 0.5f)
        val blink = mode != Mode.VIBING && !still && (t - start) % 3400 < 120
        val eyesOpen = q > 0.12f && !blink
        if (k.headwear != Headwear.HELMET) {
            if (eyesOpen) {
                fill.color = if (k.glowEyes) 0xFF0B1020.toInt() else 0xFFFBFAF7.toInt(); canvas.drawPath(eyeWhite, fill)
                canvas.clipped(eyeWhite) {
                    fill.color = k.eye; canvas.drawCircle(51.8f, 30.4f + raise * 0.3f, 1.85f, fill)
                    if (!k.glowEyes) { fill.color = 0xFF0D0D12.toInt(); canvas.drawCircle(52f, 30.5f + raise * 0.3f, 0.9f, fill) }
                    fill.color = 0xFFFFFFFF.toInt(); canvas.drawCircle(52.6f, 29.7f, 0.55f, fill)
                }
                if (k.glowEyes) canvas.glowLine(lash, k.eye, 0.8f) else canvas.lines(lash, LASH, 1.25f)
            } else {
                if (k.glowEyes) canvas.glowLine(eyeClosed, k.eye, 0.8f) else canvas.lines(eyeClosed, LASH, 1.25f)
            }
            canvas.solid(brow, k.hair[0], 0.3f)
        }
        canvas.lines(mouth, 0xD96E3228.toInt(), 0.8f)

        // Hair and headwear.
        when (k.hairStyle) {
            HairStyle.SPIKY -> { canvas.shade(spiky, gHair); canvas.lines(spikyLight, 0x8C8CB4FF.toInt(), 0.9f) }
            HairStyle.SLICK -> canvas.solid(slick, k.hair[0])
            HairStyle.LONG -> { canvas.shade(longTop, gHair); canvas.lines(longLight, 0x73FFBE96, 0.8f) }
            HairStyle.NONE -> Unit
        }
        when (k.headwear) {
            Headwear.HOOD -> {
                canvas.clipped(head) { fill.color = 0x8C080A19.toInt(); canvas.drawPath(faceShadow, fill) }
                if (eyesOpen) {
                    canvas.clipped(eyeWhite) { fill.color = k.eye; canvas.drawCircle(51.8f, 30.4f + raise * 0.3f, 1.85f, fill) }
                    canvas.glowLine(lash, k.eye, 0.8f, 1.3f)
                } else canvas.glowLine(eyeClosed, k.eye, 0.9f, 1.2f)
                canvas.shade(hood, gHood)
                canvas.clipped(hood) { fill.color = 0x4D000000; canvas.drawPath(hoodShade, fill) }
                canvas.glowLine(hoodTrim, k.trim, 0.8f, 0.7f)
            }
            Headwear.FEDORA -> {
                canvas.shade(hatCrown, gHat)
                canvas.lines(hatDent, 0x80000000.toInt(), 0.6f)
                fill.color = k.trim; canvas.drawPath(hatBand, fill); canvas.outline(hatBand, 0.5f)
                canvas.solid(hatBrim, k.gear[0])
            }
            Headwear.FOX -> {
                canvas.solid(earFar, k.gear[1])
                fill.color = 0xFF3A1A10.toInt(); canvas.drawPath(earFarIn, fill)
                canvas.solid(earNear, k.gear[0])
                fill.color = k.gear[2]; canvas.drawPath(earNearIn, fill)
                canvas.lines(earTip, 0xFF2A1208.toInt(), 1.2f)
            }
            Headwear.HELMET -> {
                canvas.shade(helmet, gHelmet)
                canvas.clipped(helmet) { fill.color = 0x38283C6E; canvas.drawPath(helmetShade, fill) }
                canvas.lines(helmetLine, 0x8C1E2D50.toInt(), 0.55f)
                canvas.solid(fin, k.gear[2], 0.6f)
                canvas.solid(visor, k.accent, 0.7f)
                canvas.clipped(visor) { canvas.lines(visorShine, 0x99FFFFFF.toInt(), 0.8f) }
                canvas.glowLine(visorSlit, 0xE6FFF0C8.toInt(), 0.6f, 0.6f + glow)
            }
            Headwear.NONE -> Unit
        }
        if (glow > 0.01f) {
            canvas.clipped(head) {
                shaded.shader = RadialGradient(lsx, lsy, 32f,
                    intArrayOf(light(0.8f * glow), light2(0.42f * glow), light2(0f)), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
                canvas.drawRect(20f, 5f, 60f, 50f, shaded)
            }
        }
        canvas.restore()

        // 6. Headphones: on his head, or resting around the neck.
        val cupX = lerp(36.4f, 36.8f, hpP); val cupY = lerp(31.4f, 48.8f, hpP)
        canvas.save()
        var band: Path? = null
        if (hpP < 0.5f) {
            canvas.rotate(headTilt, HEAD_PX, HEAD_PY)
            if (k.headwear != Headwear.FEDORA && k.headwear != Headwear.HELMET) band = if (k.headwear == Headwear.HOOD) bandHood else bandOn
        } else {
            p.reset(); p.moveTo(cupX - 1f, cupY - 2.4f); p.cubicTo(31.6f, 47f, 30f, 49.6f, 31.6f, 52f); band = p
        }
        band?.let { canvas.lines(it, INK, 3.2f); canvas.lines(it, k.phones[0], 2.3f) }
        canvas.restore()
        canvas.save()
        if (hpP < 0.5f) canvas.rotate(headTilt * (1 - hpP * 2), HEAD_PX, HEAD_PY)
        canvas.translate(cupX, cupY)
        canvas.rotate(lerp(0f, -50f, hpP))
        fill.color = k.phones[0]; canvas.drawOval(-4.3f, -5.7f, 4.3f, 5.7f, fill)
        ink.strokeWidth = 0.7f; canvas.drawOval(-4.3f, -5.7f, 4.3f, 5.7f, ink)
        fill.color = k.phones[1]; canvas.drawOval(-1.4f, -4f, 2.6f, 2.4f, fill)
        fill.color = k.accent; canvas.drawCircle(0.5f, -0.5f, 0.95f, fill)
        canvas.restore()

        // 7. Drawstrings swing with the beat (hoodie, cloak); robe sash knot.
        if (k.outfit == Outfit.HOODIE || k.outfit == Outfit.CLOAK) {
            val cord = if (k.outfit == Outfit.CLOAK) k.trim else STRING
            for ((ax, len, ph) in arrayOf(Triple(43.4f, 8f, 0f), Triple(45f, 7f, 0.8f))) {
                val a = Math.toRadians((sin(sec * 2 * PI / BEAT_S + ph) * vibeAmt * 6 - raise * 8).toDouble())
                val ex = ax + sin(a).toFloat() * len; val ey = 51.4f + cos(a).toFloat() * len
                p.reset(); p.moveTo(ax, 51.4f); p.quadTo(ax + 0.5f, 51.4f + len * 0.5f, ex, ey)
                canvas.lines(p, cord, 0.7f)
                stroke.color = AGLET; stroke.strokeWidth = 1.1f
                canvas.drawLine(ex, ey, ex + sin(a).toFloat() * 1.4f, ey + cos(a).toFloat() * 1.4f, stroke)
            }
        }
        if (k.outfit == Outfit.ROBE) { canvas.solid(sashKnot, k.accent, 0.6f); canvas.solid(sashTail, k.accent, 0.6f) }

        // 8. Laptop: thick base and lid so it reads at bubble size; glowing logo on the lid.
        quad(canvas, wx(0f, 0f), wy(0f, 0f), wx(-LB, 0f), wy(-LB, 0f), wx(-LB, BT), wy(-LB, BT), wx(0f, BT), wy(0f, BT),
            lin(wx(0f, 0f), wy(0f, 0f), wx(0f, BT), wy(0f, BT), LAP_TOP, LAP_BOTTOM))
        p.reset(); p.moveTo(wx(-LB + 1f, BT * 0.5f), wy(-LB + 1f, BT * 0.5f)); p.lineTo(wx(-1f, BT * 0.5f), wy(-1f, BT * 0.5f))
        canvas.lines(p, 0x59282D37, 0.4f)
        quad(canvas, wx(0f, 0f), wy(0f, 0f), wx(dX * LL, dY * LL), wy(dX * LL, dY * LL),
            wx(dX * LL + nX * LT, dY * LL + nY * LT), wy(dX * LL + nX * LT, dY * LL + nY * LT),
            wx(nX * LT, nY * LT), wy(nX * LT, nY * LT),
            lin(wx(0f, 0f), wy(0f, 0f), wx(dX * LL, dY * LL), wy(dX * LL, dY * LL), LID_HINGE, LID_TOP))
        if (lid > 0.15f) {
            val lx = wx(dX * LL * 0.55f + nX * LT * 0.5f, dY * LL * 0.55f + nY * LT * 0.5f)
            val ly = wy(dX * LL * 0.55f + nX * LT * 0.5f, dY * LL * 0.55f + nY * LT * 0.5f)
            fill.color = alpha(0.3f + 0.3f * glow, k.accent and 0xFFFFFF); canvas.drawCircle(lx, ly, 2.2f, fill)
            fill.color = k.accent; canvas.drawCircle(lx, ly, 1.15f, fill)
        }
        if (raise < 0.5f) { fill.color = k.accent; canvas.drawCircle(wx(-LB * 0.5f, -LT * 0.5f), wy(-LB * 0.5f, -LT * 0.5f), 1.1f, fill) }
        if (glow > 0.01f) {
            val tx = wx(dX * (LL - 1f), dY * (LL - 1f)); val ty = wy(dX * (LL - 1f), dY * (LL - 1f))
            val bx = wx(dX * 2f, dY * 2f); val by = wy(dX * 2f, dY * 2f)
            shaded.shader = LinearGradient(lsx, lsy, 54f, 32f, light2(0.16f * glow), light2(0f), Shader.TileMode.CLAMP)
            p.reset(); p.moveTo(tx, ty); p.lineTo(56.6f, 24f); p.lineTo(56f, 40f); p.lineTo(bx, by); p.close()
            canvas.drawPath(p, shaded)
        }

        // 9. Near arm holds the laptop; then both hands.
        val snx = 37f; val sny = 54.4f
        val hnx = lerp(39.4f, 48f, raise); val hny = lerp(72f, 64.4f, raise)
        val (enx, eny) = ik(snx, sny, hnx, hny, 9.6f, 9.4f)
        canvas.sleeve(snx, sny, enx, eny, hnx, hny, 8.2f, 7.8f, 6.4f, if (k.outfit == Outfit.ARMOR) k.cloth[2] else k.cloth[1], k.cuff)
        if (k.outfit == Outfit.ARMOR) canvas.solid(pauldron, k.cloth[1], 0.7f)
        canvas.hand(hnx, hny, lerp(1.4f, 0.1f, raise), k.skin[0])
        if (raise > 0.05f) canvas.hand(hfx, hfy, 0.15f, k.skin[1])
        if (glow > 0.01f) {
            canvas.clipped(body) {
                shaded.shader = RadialGradient(lsx, lsy, 22f, light(0.4f * glow), light(0f), Shader.TileMode.CLAMP)
                canvas.drawRect(20f, 40f, 60f, 80f, shaded)
            }
        }

        // 10. Kitsune's spirit flame bobs by his shoulder.
        if (k.wisp != 0) {
            val wx0 = 24f; val wy0 = 44f + sin(sec * 2.2f) * 1.6f
            p.reset()
            p.moveTo(wx0, wy0 + 4.4f)
            p.cubicTo(wx0 - 3f, wy0 + 3.6f, wx0 - 3.4f, wy0, wx0 - 1f, wy0 - 2.2f)
            p.cubicTo(wx0 - 0.6f, wy0 - 4.6f, wx0 + 1f, wy0 - 6f, wx0 + 0.6f, wy0 - 8f)
            p.cubicTo(wx0 + 3f, wy0 - 5.6f, wx0 + 3.6f, wy0 - 1.6f, wx0 + 2.8f, wy0 + 1.6f)
            p.cubicTo(wx0 + 2.4f, wy0 + 3.4f, wx0 + 1.4f, wy0 + 4.4f, wx0, wy0 + 4.4f)
            p.close()
            canvas.lines(p, alpha(0.25f, k.wisp and 0xFFFFFF), 2.4f)
            fill.color = k.wisp; canvas.drawPath(p, fill)
            fill.color = 0xE6E6FFFA.toInt(); canvas.drawOval(wx0 - 0.7f, wy0 - 0.8f, wx0 + 1.5f, wy0 + 2.8f, fill)
        }
        canvas.restore()

        scheduleNext(t, anim, q)
    }

    private fun quad(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float, x4: Float, y4: Float, shader: Shader) {
        p.reset(); p.moveTo(x1, y1); p.lineTo(x2, y2); p.lineTo(x3, y3); p.lineTo(x4, y4); p.close()
        shaded.shader = shader; c.drawPath(p, shaded)
        ink.strokeWidth = 0.6f; c.drawPath(p, ink)
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
    private fun alpha(a: Float, rgb: Int) = ((a.coerceIn(0f, 1f) * 255).toInt() shl 24) or rgb
    private fun light(a: Float) = alpha(a, 0xD7F2FF)
    private fun light2(a: Float) = alpha(a, 0xAFE1FF)

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
        const val LT = 3.2f

        val INK = 0xEB0E0F14.toInt()
        val LASH = 0xFF120D0A.toInt()
        val STRING = 0xFFEEF1F4.toInt()
        val AGLET = 0xFF9AA0A8.toInt()
        val LAP_TOP = 0xFFD5DAE2.toInt()
        val LAP_BOTTOM = 0xFF7C8492.toInt()
        val LID_HINGE = 0xFF9AA2AF.toInt()
        val LID_TOP = 0xFFC9CFD8.toInt()
    }
}
