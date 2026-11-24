package app.speaker.dictate

/**
 * Characters for the floating bubble (the in-app "Characters" store). All share BoyView's rig, shapes and
 * animations; a skin is the colour palette. Original designs only (never copy characters from games or brands).
 * Visual prototype: .ai-workspace/scratch/vox.html (same shapes and colours; render it before porting changes).
 */
class Skin(
    val id: String,
    val name: String,
    val tagline: String,
    /** Skin tone: base, shade, light, deep. */
    val skin: IntArray,
    /** Hair: base, mid, highlight, deep. */
    val hair: IntArray,
    /** Iris: light centre, dark rim. */
    val eye: IntArray,
    /** Hoodie: deep, base, light, highlight. */
    val cloth: IntArray,
    /** Neon trim (chest stripe, cuff line). */
    val trim: Int,
    /** Joggers: deep, base, light. */
    val pants: IntArray,
    /** Sneakers: white, shade, deep shade. */
    val shoe: IntArray,
    val sole: Int,
    /** Accent: shoe stripe, headphone ring, laptop logo. */
    val accent: Int,
    /** Headphones: shell dark, mid, light. */
    val phones: IntArray,
)

object Skins {
    private fun c(v: Long) = v.toInt()
    private fun a(vararg v: Long) = IntArray(v.size) { v[it].toInt() }

    val VOX = Skin(
        "vox", "Vox", "Streetwear with a neon edge",
        skin = a(0xFFE8B48E, 0xFFC4856A, 0xFFF7D2B4, 0xFFA8684E),
        hair = a(0xFF1C2030, 0xFF2E3A5C, 0xFF5B7BC4, 0xFF121521),
        eye = a(0xFF3C8CFF, 0xFF123A8C),
        cloth = a(0xFF1B1E26, 0xFF2A2F3A, 0xFF3C4352, 0xFF4C5466),
        trim = c(0xFF33E4FF),
        pants = a(0xFF121419, 0xFF1F2229, 0xFF2D313B),
        shoe = a(0xFFFFFFFF, 0xFFD9DEE6, 0xFFAEB6C2), sole = c(0xFF22252C),
        accent = c(0xFF33E4FF),
        phones = a(0xFF16171C, 0xFF2C2F38, 0xFF4A4E5A),
    )

    /** More characters get redrawn in Vox's polished style once it's approved. */
    val ALL = listOf(VOX)
    val DEFAULT = VOX

    fun byId(id: String?) = ALL.firstOrNull { it.id == id } ?: DEFAULT
}
