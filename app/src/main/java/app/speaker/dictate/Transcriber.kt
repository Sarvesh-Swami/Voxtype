package app.speaker.dictate

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

/** Calls the configured speech-to-text API and (optionally) an LLM to polish the text. Blocking. */
class Transcriber(private val prefs: Prefs) {

    fun transcribe(wav: File): String {
        val p = SttProvider.from(prefs.sttProvider)
        val key = prefs.apiKey(p.vendor)
        val model = prefs.sttModel(p)
        return when (p) {
            SttProvider.OPENAI -> openAiCompatible("https://api.openai.com/v1", key, model, wav)
            SttProvider.GROQ -> openAiCompatible("https://api.groq.com/openai/v1", key, model, wav)
            SttProvider.CUSTOM -> openAiCompatible(prefs.customBaseUrl, key, model, wav)
            SttProvider.GEMINI -> gemini(key, model, wav)
            SttProvider.DEEPGRAM -> deepgram(key, model, wav)
            SttProvider.BUILTIN -> error("The built-in recognizer does not use audio files")
        }
    }

    private fun openAiCompatible(base: String, key: String, model: String, wav: File): String {
        val fields = linkedMapOf("model" to model, "response_format" to "json")
        if (prefs.language.isNotBlank()) fields["language"] = prefs.language
        val json = Http.postMultipart(
            "$base/audio/transcriptions", bearer(key), fields, "file", wav, "audio/wav"
        )
        return json.optString("text")
    }

    private fun gemini(key: String, model: String, wav: File): String {
        val lang = if (prefs.language.isNotBlank()) " The audio is in language '${prefs.language}'." else ""
        val instruction = "Transcribe this audio verbatim.$lang " +
            "Return only the transcript text, with no preamble, labels or quotes."
        val parts = JSONArray()
            .put(JSONObject().put("text", instruction))
            .put(JSONObject().put("inline_data", JSONObject()
                .put("mime_type", "audio/wav")
                .put("data", Base64.encodeToString(wav.readBytes(), Base64.NO_WRAP))))
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", parts)))
        return geminiText(Http.postJson(geminiUrl(model), mapOf("x-goog-api-key" to key), body))
    }

    private fun deepgram(key: String, model: String, wav: File): String {
        val lang = if (prefs.language.isNotBlank())
            "&language=" + URLEncoder.encode(prefs.language, "UTF-8") else "&detect_language=true"
        val url = "https://api.deepgram.com/v1/listen?model=" +
            URLEncoder.encode(model, "UTF-8") + "&smart_format=true&punctuate=true$lang"
        val json = Http.postFile(url, mapOf("Authorization" to "Token $key"), "audio/wav", wav)
        return json.getJSONObject("results").getJSONArray("channels").getJSONObject(0)
            .getJSONArray("alternatives").getJSONObject(0).optString("transcript")
    }

    /** Sends the raw transcript to the chosen LLM with the polish prompt. */
    fun polish(text: String): String {
        val p = LlmProvider.from(prefs.llmProvider)
        val key = prefs.apiKey(p.vendor)
        val model = prefs.llmModel(p)
        if (key.isBlank()) error("No API key for ${p.label} (needed for polishing)")
        if (model.isBlank()) error("No model set for ${p.label} polishing")
        val system = prefs.polishPrompt
        return when (p) {
            LlmProvider.OPENAI -> chatCompletions("https://api.openai.com/v1", key, model, system, text)
            LlmProvider.GROQ -> chatCompletions("https://api.groq.com/openai/v1", key, model, system, text)
            LlmProvider.CUSTOM -> chatCompletions(prefs.customBaseUrl, key, model, system, text)
            LlmProvider.ANTHROPIC -> anthropic(key, model, system, text)
            LlmProvider.GEMINI -> geminiChat(key, model, system, text)
        }
    }

    private fun chatCompletions(base: String, key: String, model: String, system: String, text: String): String {
        val body = JSONObject()
            .put("model", model)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", text)))
        val json = Http.postJson("$base/chat/completions", bearer(key), body)
        return json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
    }

    private fun anthropic(key: String, model: String, system: String, text: String): String {
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 4096)
            .put("system", system)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", text)))
        val json = Http.postJson(
            "https://api.anthropic.com/v1/messages",
            mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"), body
        )
        val content = json.getJSONArray("content")
        return (0 until content.length()).map { content.getJSONObject(it) }
            .filter { it.optString("type") == "text" }.joinToString("") { it.optString("text") }
    }

    private fun geminiChat(key: String, model: String, system: String, text: String): String {
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", text)))))
        return geminiText(Http.postJson(geminiUrl(model), mapOf("x-goog-api-key" to key), body))
    }

    private fun geminiUrl(model: String) =
        "https://generativelanguage.googleapis.com/v1beta/models/${URLEncoder.encode(model, "UTF-8")}:generateContent"

    private fun geminiText(json: JSONObject): String {
        val candidates = json.optJSONArray("candidates") ?: return ""
        if (candidates.length() == 0) return ""
        val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts") ?: return ""
        return (0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text") }
    }

    private fun bearer(key: String) = mapOf("Authorization" to "Bearer $key")
}
