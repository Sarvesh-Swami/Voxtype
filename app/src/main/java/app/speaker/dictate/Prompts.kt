package app.speaker.dictate

/** One AI rewrite prompt: a built-in preset or one the user saved under a name. */
data class PromptItem(val name: String, val text: String, val builtIn: Boolean)

/**
 * The prompt library: built-in presets plus the user's saved prompts (stored in [Prefs.savedPrompts]).
 * The active prompt is whatever text is in [Prefs.polishPrompt]; its name is found by matching the text,
 * so editing it by hand simply makes it "Custom".
 */
object Prompts {
    const val MAX_SAVED = 30
    const val MAX_NAME = 40
    const val CUSTOM = "Custom"

    val BUILT_IN = listOf(
        PromptItem("Clean up", DEFAULT_POLISH_PROMPT, true),
        PromptItem("Fix grammar", "Fix grammar, spelling and punctuation. Keep my words and tone; change as little as possible.", true),
        PromptItem("Professional", "Rewrite it as clear, polite, professional writing suitable for work chat or email.", true),
        PromptItem("Casual", "Rewrite it as friendly, casual chat. Fix mistakes, keep it short and natural.", true),
        PromptItem("To English", "Translate it into natural English (it may be Hindi, Hinglish or another language). Fix grammar.", true),
        PromptItem("Concise", "Make it shorter and clearer without losing meaning. Fix grammar.", true),
        PromptItem("Commands", "I am dictating a terminal command. Output only the shell command, in lowercase where appropriate, with correct symbols and no explanation.", true),
    )

    fun all(prefs: Prefs): List<PromptItem> = BUILT_IN + prefs.savedPrompts

    /** Name of the active prompt (saved prompts win over presets with the same text), or [CUSTOM]. */
    fun activeName(prefs: Prefs): String = nameFor(prefs, prefs.polishPrompt)

    /** Name of the prompt whose text matches [text] (blank counts as the default clean-up), or [CUSTOM]. */
    fun nameFor(prefs: Prefs, text: String): String {
        val t = text.trim().ifEmpty { DEFAULT_POLISH_PROMPT }
        return (prefs.savedPrompts + BUILT_IN).firstOrNull { it.text.trim() == t }?.name ?: CUSTOM
    }

    fun use(prefs: Prefs, item: PromptItem) {
        prefs.polishPrompt = item.text
    }

    /** Saves (or replaces, matching the name case-insensitively). Returns false if the list is full. */
    fun save(prefs: Prefs, name: String, text: String): Boolean {
        val clean = name.trim().take(MAX_NAME)
        val list = prefs.savedPrompts.toMutableList()
        val i = list.indexOfFirst { it.name.equals(clean, ignoreCase = true) }
        if (i >= 0) list[i] = PromptItem(clean, text.trim(), false)
        else {
            if (list.size >= MAX_SAVED) return false
            list += PromptItem(clean, text.trim(), false)
        }
        prefs.savedPrompts = list
        return true
    }

    fun exists(prefs: Prefs, name: String) = prefs.savedPrompts.any { it.name.equals(name.trim(), ignoreCase = true) }

    fun rename(prefs: Prefs, old: String, new: String) {
        prefs.savedPrompts = prefs.savedPrompts.map { if (it.name == old) it.copy(name = new.trim().take(MAX_NAME)) else it }
    }

    fun delete(prefs: Prefs, name: String) {
        prefs.savedPrompts = prefs.savedPrompts.filterNot { it.name == name }
    }
}
