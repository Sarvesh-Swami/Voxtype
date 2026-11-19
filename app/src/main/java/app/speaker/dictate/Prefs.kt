package app.speaker.dictate

import android.content.Context
import android.net.Uri

/** Speech-to-text engines. `vendor` is the key slot the API key is stored under. */
enum class SttProvider(val id: String, val label: String, val vendor: String, val defaultModel: String) {
    BUILTIN("builtin", "Android built-in (free, no key)", "", ""),
    OPENAI("openai", "OpenAI", "openai", "gpt-4o-mini-transcribe"),
    GROQ("groq", "Groq Whisper (fast, free tier)", "groq", "whisper-large-v3-turbo"),
    GEMINI("gemini", "Google Gemini", "gemini", "gemini-2.5-flash"),
    DEEPGRAM("deepgram", "Deepgram", "deepgram", "nova-3"),
    CUSTOM("custom", "Custom (OpenAI-compatible URL)", "custom", "whisper-1");

    val needsKey get() = vendor.isNotEmpty()

    companion object {
        fun from(id: String) = entries.firstOrNull { it.id == id } ?: BUILTIN
    }
}

/** LLMs used for the optional "polish the transcript" step. */
enum class LlmProvider(val id: String, val label: String, val vendor: String, val defaultModel: String) {
    OPENAI("openai", "OpenAI", "openai", "gpt-4o-mini"),
    ANTHROPIC("anthropic", "Anthropic Claude", "anthropic", "claude-haiku-5-5"),
    GEMINI("gemini", "Google Gemini", "gemini", "gemini-2.5-flash"),
    GROQ("groq", "Groq", "groq", "llama-3.3-70b-versatile"),
    CUSTOM("custom", "Custom (OpenAI-compatible URL)", "custom", "");

    companion object {
        fun from(id: String) = entries.firstOrNull { it.id == id } ?: OPENAI
    }
}

/** Vendors that can hold an API key, in the order shown in settings. */
val KEY_VENDORS = listOf(
    "openai" to "OpenAI API key",
    "groq" to "Groq API key",
    "gemini" to "Google Gemini API key",
    "deepgram" to "Deepgram API key",
    "anthropic" to "Anthropic API key",
    "custom" to "Custom provider API key",
)

const val DEFAULT_POLISH_PROMPT =
    "You clean up dictated text. Fix punctuation, capitalization and obvious speech-recognition " +
        "mistakes, and remove filler words (um, uh, like). Do not add content, do not answer " +
        "questions, do not add quotes, explanations or commentary. Return only the cleaned text."

/** All settings live in app-private SharedPreferences; keys only leave the device as API auth headers. */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("speaker", Context.MODE_PRIVATE)

    private fun str(key: String, def: String = "") = sp.getString(key, def) ?: def
    private fun put(key: String, value: String) = sp.edit().putString(key, value).apply()

    var sttProvider: String
        get() = str("stt_provider", SttProvider.BUILTIN.id)
        set(v) = put("stt_provider", v)

    var llmProvider: String
        get() = str("llm_provider", LlmProvider.OPENAI.id)
        set(v) = put("llm_provider", v)

    var language: String
        get() = str("language")
        set(v) = put("language", v.trim())

    var customBaseUrl: String
        get() = str("custom_base_url", "https://api.openai.com/v1")
        set(v) = put("custom_base_url", v.trim().trimEnd('/'))

    var polishPrompt: String
        get() = str("polish_prompt", DEFAULT_POLISH_PROMPT).ifBlank { DEFAULT_POLISH_PROMPT }
        set(v) = put("polish_prompt", v)

    var polishEnabled: Boolean
        get() = sp.getBoolean("polish_enabled", false)
        set(v) = sp.edit().putBoolean("polish_enabled", v).apply()

    /** Sound streams Speaker muted during dictation (restored at app start if a crash left them). */
    var mutedStreams: String
        get() = str("muted_streams")
        set(v) = put("muted_streams", v)

    /** Publish a media session so the phone's island (realme Fluid Cloud / Live Alerts) shows a play/pause control. */
    var islandMedia: Boolean
        get() = sp.getBoolean("island_media", true)
        set(v) = sp.edit().putBoolean("island_media", v).apply()

    var showBubble: Boolean
        get() = sp.getBoolean("show_bubble", true)
        set(v) = sp.edit().putBoolean("show_bubble", v).apply()

    var stopOnFocusLoss: Boolean
        get() = sp.getBoolean("stop_on_focus_loss", true)
        set(v) = sp.edit().putBoolean("stop_on_focus_loss", v).apply()

    var autoInsert: Boolean
        get() = sp.getBoolean("auto_insert", true)
        set(v) = sp.edit().putBoolean("auto_insert", v).apply()

    /**
     * Where RemoteLog sends logs. Blank = the URL built into the app (gradle.properties
     * speakerLogUrl). Typing "off" disables sending.
     */
    var logUrl: String
        get() {
            val v = str("log_url")
            val url = when {
                v.equals("off", true) -> ""
                v.isBlank() -> BuildConfig.DEFAULT_LOG_URL
                else -> v
            }
            // "https://host" or "https://host/" -> "https://host/log" (the receiver's path).
            if (url.isEmpty()) return url
            val path = Uri.parse(url).path
            return if (path.isNullOrEmpty() || path == "/") url.trimEnd('/') + "/log" else url
        }
        set(v) {
            val t = v.trim()
            val normalized = when {
                t.isEmpty() || t == BuildConfig.DEFAULT_LOG_URL -> "" // follow the built-in URL
                t.equals("off", true) || t.startsWith("http") -> t
                else -> "https://$t"
            }
            put("log_url", normalized)
        }

    /** What to show in the settings field (raw value, so "off" stays visible). */
    val logUrlField: String
        get() = str("log_url").ifBlank { BuildConfig.DEFAULT_LOG_URL }

    var bubbleX: Int
        get() = sp.getInt("bubble_x", 24)
        set(v) = sp.edit().putInt("bubble_x", v).apply()

    var bubbleY: Int
        get() = sp.getInt("bubble_y", 400)
        set(v) = sp.edit().putInt("bubble_y", v).apply()

    fun apiKey(vendor: String) = str("key_$vendor").trim()
    fun setApiKey(vendor: String, key: String) = put("key_$vendor", key.trim())

    fun sttModel(p: SttProvider) = str("stt_model_${p.id}").ifBlank { p.defaultModel }
    fun setSttModel(p: SttProvider, m: String) = put("stt_model_${p.id}", m.trim())

    fun llmModel(p: LlmProvider) = str("llm_model_${p.id}").ifBlank { p.defaultModel }
    fun setLlmModel(p: LlmProvider, m: String) = put("llm_model_${p.id}", m.trim())

    /** Model names last fetched from a provider's API (see ModelCatalog), keyed like "stt_openai". */
    fun fetchedModels(slot: String): List<String> =
        str("models_$slot").split('\n').filter { it.isNotBlank() }
    fun setFetchedModels(slot: String, models: List<String>) = put("models_$slot", models.joinToString("\n"))
}
