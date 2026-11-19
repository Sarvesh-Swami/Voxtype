package app.speaker.dictate

import org.json.JSONObject
import java.net.URLEncoder

/**
 * The models offered in the Model dropdowns. Each provider has a short built-in list (shown before any
 * key is entered). Once a key is set, the provider's own "list models" API gives the full, current list,
 * which is cached in Prefs. Users can still type any name with "Other".
 */
object ModelCatalog {

    private val builtinStt = mapOf(
        SttProvider.OPENAI to listOf("gpt-4o-mini-transcribe", "gpt-4o-transcribe", "whisper-1"),
        SttProvider.GROQ to listOf("whisper-large-v3-turbo", "whisper-large-v3"),
        SttProvider.GEMINI to listOf("gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-2.5-pro"),
        SttProvider.DEEPGRAM to listOf("nova-3", "nova-2", "enhanced", "base"),
        SttProvider.CUSTOM to listOf("whisper-1"),
    )

    private val builtinLlm = mapOf(
        LlmProvider.OPENAI to listOf("gpt-4o-mini", "gpt-4.1-mini", "gpt-4.1-nano", "gpt-4.1", "gpt-4o", "gpt-5-mini", "gpt-5-nano", "gpt-5"),
        LlmProvider.ANTHROPIC to listOf("claude-haiku-5-5", "claude-sonnet-5-5", "claude-opus-5-5"),
        LlmProvider.GEMINI to listOf("gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-2.5-pro"),
        LlmProvider.GROQ to listOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant", "openai/gpt-oss-120b", "openai/gpt-oss-20b"),
        LlmProvider.CUSTOM to emptyList(),
    )

    fun sttSlot(p: SttProvider) = "stt_${p.id}"
    fun llmSlot(p: LlmProvider) = "llm_${p.id}"

    fun sttChoices(prefs: Prefs, p: SttProvider) = merge(builtinStt[p].orEmpty(), prefs.fetchedModels(sttSlot(p)))
    fun llmChoices(prefs: Prefs, p: LlmProvider) = merge(builtinLlm[p].orEmpty(), prefs.fetchedModels(llmSlot(p)))

    /** Built-in names the provider still offers come first (they're the sensible picks), then the rest A-Z. */
    private fun merge(builtin: List<String>, fetched: List<String>): List<String> {
        if (fetched.isEmpty()) return builtin
        val set = fetched.toSet()
        return builtin.filter { it in set } + fetched.filter { it !in builtin }.sorted()
    }

    /** Asks the provider for its models and caches them. Blocking: call off the main thread. */
    fun fetchStt(prefs: Prefs, p: SttProvider): List<String> {
        val key = prefs.apiKey(p.vendor)
        val models = when (p) {
            SttProvider.BUILTIN -> emptyList()
            SttProvider.OPENAI -> openAiIds("https://api.openai.com/v1", key).filter(::isOpenAiStt)
            SttProvider.GROQ -> openAiIds("https://api.groq.com/openai/v1", key).filter { "whisper" in it }
            SttProvider.GEMINI -> geminiIds(key)
            SttProvider.DEEPGRAM -> deepgramIds(key)
            SttProvider.CUSTOM -> openAiIds(prefs.customBaseUrl, key)
        }.distinct()
        prefs.setFetchedModels(sttSlot(p), models)
        return models
    }

    fun fetchLlm(prefs: Prefs, p: LlmProvider): List<String> {
        val key = prefs.apiKey(p.vendor)
        val models = when (p) {
            LlmProvider.OPENAI -> openAiIds("https://api.openai.com/v1", key).filter(::isOpenAiChat)
            LlmProvider.GROQ -> openAiIds("https://api.groq.com/openai/v1", key).filter(::isGroqChat)
            LlmProvider.GEMINI -> geminiIds(key)
            LlmProvider.ANTHROPIC -> anthropicIds(key)
            LlmProvider.CUSTOM -> openAiIds(prefs.customBaseUrl, key)
        }.distinct()
        prefs.setFetchedModels(llmSlot(p), models)
        return models
    }

    // ---- provider "list models" endpoints ----

    private fun openAiIds(base: String, key: String): List<String> {
        val headers = if (key.isEmpty()) emptyMap() else mapOf("Authorization" to "Bearer $key")
        val data = Http.getJson("$base/models", headers).optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { i ->
            val m = data.optJSONObject(i) ?: return@mapNotNull null
            if (!m.optBoolean("active", true)) return@mapNotNull null // Groq marks retired models inactive
            m.optString("id").takeIf { it.isNotBlank() }
        }
    }

    private fun geminiIds(key: String): List<String> {
        val out = mutableListOf<String>()
        var page = ""
        do {
            val url = "https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000" +
                (if (page.isEmpty()) "" else "&pageToken=" + URLEncoder.encode(page, "UTF-8"))
            val json = Http.getJson(url, mapOf("x-goog-api-key" to key))
            val models = json.optJSONArray("models")
            for (i in 0 until (models?.length() ?: 0)) {
                val m = models!!.optJSONObject(i) ?: continue
                val methods = m.optJSONArray("supportedGenerationMethods")?.toString().orEmpty()
                val id = m.optString("name").removePrefix("models/")
                if ("generateContent" in methods && id.startsWith("gemini") && GEMINI_SKIP.none { it in id }) out += id
            }
            page = json.optString("nextPageToken")
        } while (page.isNotEmpty())
        return out
    }

    private fun anthropicIds(key: String): List<String> {
        val out = mutableListOf<String>()
        var after = ""
        while (true) {
            val url = "https://api.anthropic.com/v1/models?limit=1000" +
                (if (after.isEmpty()) "" else "&after_id=" + URLEncoder.encode(after, "UTF-8"))
            val json = Http.getJson(url, mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"))
            val data = json.optJSONArray("data")
            for (i in 0 until (data?.length() ?: 0)) data!!.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }?.let { out += it }
            after = json.optString("last_id")
            if (!json.optBoolean("has_more") || after.isEmpty()) return out
        }
    }

    /** Deepgram lists one entry per model + language; both "nova-3" and "nova-3-general" are valid names. */
    private fun deepgramIds(key: String): List<String> {
        val stt = Http.getJson("https://api.deepgram.com/v1/models", mapOf("Authorization" to "Token $key"))
            .optJSONArray("stt") ?: return emptyList()
        val out = linkedSetOf<String>()
        for (i in 0 until stt.length()) {
            val m: JSONObject = stt.optJSONObject(i) ?: continue
            m.optString("architecture").takeIf { it.isNotBlank() }?.let { out += it }
            m.optString("canonical_name").takeIf { it.isNotBlank() }?.let { out += it }
        }
        return out.toList()
    }

    // ---- filters: keep only models that work with the endpoint the app calls ----

    private val SNAPSHOT = Regex("-\\d{4}-\\d{2}-\\d{2}$") // dated copies of the same model
    private val GEMINI_SKIP = listOf("tts", "image", "embedding", "live", "native-audio", "computer-use", "robotics")
    private val OPENAI_CHAT_SKIP = listOf(
        "audio", "realtime", "transcribe", "tts", "image", "search", "embedding", "instruct",
        "codex", "deep-research", "-pro", "moderation",
    )

    private fun isOpenAiStt(id: String) =
        ("transcribe" in id || id.startsWith("whisper")) && !SNAPSHOT.containsMatchIn(id)

    private fun isOpenAiChat(id: String) =
        (id.startsWith("gpt-") || id.startsWith("chatgpt-") || Regex("^o\\d").containsMatchIn(id)) &&
            OPENAI_CHAT_SKIP.none { it in id } && !SNAPSHOT.containsMatchIn(id)

    private fun isGroqChat(id: String) =
        listOf("whisper", "tts", "playai", "guard", "orpheus").none { it in id }
}
