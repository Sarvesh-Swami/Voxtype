package app.speaker.dictate

/**
 * Characters for the floating bubble (the in-app "Characters" store). All share BoyView's rig and animations
 * (same chibi proportions, pose, headphones, laptop); a skin only changes colours, hair or headwear and the outfit.
 * Original designs. Visual prototype: .ai-workspace/scratch/skins.html (same shapes and colours).
 */
class Skin(
    val id: String,
    val name: String,
    val tagline: String,
    /** Skin tone: base, shadow, light. */
    val skin: IntArray,
    /** Hair: base, light. */
    val hair: IntArray,
    val eye: Int,
    /** Outfit: shadow, base, light. */
    val cloth: IntArray,
    val trim: Int,
    val cuff: Int,
    /** Pants: shadow, base, light. */
    val pants: IntArray,
    /** Shoes: base, shadow. */
    val shoe: IntArray,
    val sole: Int,
    /** Accent: shoe stripe, headphone light, laptop logo, visor. */
    val accent: Int,
    /** Headphones: shell, cushion. */
    val phones: IntArray,
    val hairStyle: HairStyle,
    val headwear: Headwear,
    val outfit: Outfit,
    /** Headwear colours (hat: base, light / fox ears: outer, far, inner / helmet: light, shade, fin). */
    val gear: IntArray = intArrayOf(),
    val glowEyes: Boolean = false,
    /** Floating spirit flame colour, 0 = none. */
    val wisp: Int = 0,
)

enum class HairStyle { SPIKY, SLICK, LONG, NONE }
enum class Headwear { NONE, HOOD, FEDORA, FOX, HELMET }
enum class Outfit { HOODIE, CLOAK, TRENCH, ROBE, ARMOR }

object Skins {
    private fun c(v: Long) = v.toInt()
    private fun a(vararg v: Long) = IntArray(v.size) { v[it].toInt() }

    val VOX = Skin(
        "vox", "Vox", "Streetwear with a neon edge",
        skin = a(0xFFE2AE88, 0xFFBE8160, 0xFFF2C8A6), hair = a(0xFF191B24, 0xFF33405E), eye = c(0xFF2A6EF0),
        cloth = a(0xFF1E2128, 0xFF2C313B, 0xFF3E4553), trim = c(0xFF2DE2FF), cuff = c(0xFF2DE2FF),
        pants = a(0xFF15171C, 0xFF22252D, 0xFF30343F), shoe = a(0xFFF3F5F8, 0xFFC5CBD4), sole = c(0xFF1B1D22),
        accent = c(0xFF2DE2FF), phones = a(0xFF121318, 0xFF2B2E38),
        hairStyle = HairStyle.SPIKY, headwear = Headwear.NONE, outfit = Outfit.HOODIE,
    )
    val SHADE = Skin(
        "shade", "Shade", "Hood up, eyes glowing",
        skin = a(0xFFD7A383, 0xFFA9714F, 0xFFE6BC9C), hair = a(0xFF0E1220, 0xFF1E2740), eye = c(0xFF3FF2FF),
        cloth = a(0xFF101A33, 0xFF1A2A52, 0xFF26407A), trim = c(0xFF3FF2FF), cuff = c(0xFF0B1226),
        pants = a(0xFF0E1220, 0xFF182035, 0xFF222C47), shoe = a(0xFF2A3046, 0xFF1A1F30), sole = c(0xFF0B0E16),
        accent = c(0xFF3FF2FF), phones = a(0xFF0B0E16, 0xFF22283A),
        hairStyle = HairStyle.NONE, headwear = Headwear.HOOD, outfit = Outfit.CLOAK, glowEyes = true,
    )
    val NOIR = Skin(
        "noir", "Noir", "Fedora, trench coat, cold case",
        skin = a(0xFFE6B392, 0xFFC08463, 0xFFF4CDAE), hair = a(0xFF14110F, 0xFF3A302A), eye = c(0xFF3A2A20),
        cloth = a(0xFF8A6A44, 0xFFB08A5C, 0xFFCBA676), trim = c(0xFFC8282E), cuff = c(0xFF7A5C3A),
        pants = a(0xFF2C2E35, 0xFF3B3E47, 0xFF4C505B), shoe = a(0xFF4A2E1C, 0xFF2E1C10), sole = c(0xFF1A120C),
        accent = c(0xFFC8282E), phones = a(0xFF17181C, 0xFF34363E),
        hairStyle = HairStyle.SLICK, headwear = Headwear.FEDORA, outfit = Outfit.TRENCH,
        gear = a(0xFF26272D, 0xFF3A3C44),
    )
    val KITSUNE = Skin(
        "kitsune", "Kitsune", "Fox ears and a spirit flame",
        skin = a(0xFFF0C6A8, 0xFFCF9A7C, 0xFFFBDCC4), hair = a(0xFF5A1E14, 0xFF9A3A22), eye = c(0xFFE8A21E),
        cloth = a(0xFF1E1E4A, 0xFF2C2C6E, 0xFF3D3D92), trim = c(0xFFF3E6C8), cuff = c(0xFFF3E6C8),
        pants = a(0xFF1A1A3C, 0xFF26265A, 0xFF33336E), shoe = a(0xFFF3E6C8, 0xFFCDBE9C), sole = c(0xFF3A2A1C),
        accent = c(0xFFFF8A2A), phones = a(0xFF2A1610, 0xFF4A2A1E),
        hairStyle = HairStyle.LONG, headwear = Headwear.FOX, outfit = Outfit.ROBE,
        gear = a(0xFFFF8A2A, 0xFFC85A12, 0xFFFFE6C8), wisp = c(0xFF45F0D2),
    )
    val VOLT = Skin(
        "volt", "Volt", "Armored, visor charged",
        skin = a(0xFFE2AE88, 0xFFBE8160, 0xFFF2C8A6), hair = a(0xFF191B24, 0xFF33405E), eye = c(0xFFFF8A1E),
        cloth = a(0xFF1C3D8C, 0xFF2A5BD0, 0xFFE9EEF6), trim = c(0xFFFF8A1E), cuff = c(0xFFE9EEF6),
        pants = a(0xFF1A2236, 0xFF253048, 0xFF33405E), shoe = a(0xFFE9EEF6, 0xFFB8C2D4), sole = c(0xFF1A2236),
        accent = c(0xFFFF8A1E), phones = a(0xFFE9EEF6, 0xFFAEB9CC),
        hairStyle = HairStyle.NONE, headwear = Headwear.HELMET, outfit = Outfit.ARMOR,
        gear = a(0xFFE9EEF6, 0xFFAEB9CC, 0xFF2A5BD0),
    )

    val ALL = listOf(VOX, SHADE, NOIR, KITSUNE, VOLT)
    val DEFAULT = VOX

    fun byId(id: String?) = ALL.firstOrNull { it.id == id } ?: DEFAULT
}
