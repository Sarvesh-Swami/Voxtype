package app.speaker.dictate

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.InputMethod
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.TargetApi

/**
 * Accessibility service that types dictated text into whatever field has focus, in any app.
 *
 * Android 13+: uses the accessibility *input method* (the same InputConnection a keyboard uses), so it
 * works in non-standard editors like Termux and supports live, revisable text. It also tells us when
 * a text field gains/loses focus (used to auto-stop dictation).
 * Older Android / no input connection: falls back to ACTION_SET_TEXT, ACTION_PASTE, then clipboard.
 */
class TypeAccessibilityService : AccessibilityService() {

    enum class Insert { SET_TEXT, PASTE, FAILED }

    /** Notified on the main thread when a text field gains or loses input focus (Android 13+). */
    fun interface EditorFocusListener {
        fun onEditorFocus(focused: Boolean, packageName: String?)
    }

    companion object {
        private const val TAG = "Speaker.type"

        @Volatile var instance: TypeAccessibilityService? = null
            private set
        @Volatile var editorFocusListener: EditorFocusListener? = null

        fun isEnabled(ctx: Context): Boolean {
            val enabled = Settings.Secure.getString(
                ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.split(':').any {
                it.equals("${ctx.packageName}/${TypeAccessibilityService::class.java.name}", true) ||
                    it.equals("${ctx.packageName}/.TypeAccessibilityService", true)
            }
        }
    }

    override fun onServiceConnected() {
        instance = this
        val imFlag = Build.VERSION.SDK_INT >= 33 &&
            (serviceInfo?.flags ?: 0) and AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR != 0
        Log.i(TAG, "Accessibility service connected (sdk=${Build.VERSION.SDK_INT}, inputMethodFlag=$imFlag)")
        if (Build.VERSION.SDK_INT >= 33 && !imFlag) {
            Log.w(TAG, "Input-method typing not granted yet: turn Speaker auto-type OFF and ON again in Accessibility settings")
        }
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        Log.w(TAG, "Accessibility service unbound (turned off in settings, or app updating)")
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Log.i(TAG, "Accessibility service destroyed")
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() { Log.i(TAG, "Accessibility onInterrupt") }

    // ---------------- Android 13+: keyboard-style input connection ----------------

    @SuppressLint("NewApi")
    override fun onCreateInputMethod(): InputMethod {
        Log.i(TAG, "Creating accessibility input method")
        return SpeakerInputMethod(this)
    }

    @TargetApi(33)
    private class SpeakerInputMethod(service: AccessibilityService) : InputMethod(service) {
        override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
            super.onStartInput(attribute, restarting)
            Log.i(TAG, "Text field FOCUSED: pkg=${attribute.packageName} inputType=0x${Integer.toHexString(attribute.inputType)} " +
                "fieldId=${attribute.fieldId} restarting=$restarting")
            editorFocusListener?.onEditorFocus(true, attribute.packageName)
        }

        override fun onFinishInput() {
            Log.i(TAG, "Text field UNFOCUSED")
            editorFocusListener?.onEditorFocus(false, null)
            super.onFinishInput()
        }
    }

    /** True if a text field currently has input focus (Android 13+ only; false otherwise). */
    fun editorActive(): Boolean =
        Build.VERSION.SDK_INT >= 33 && runCatching { inputMethod?.currentInputStarted == true }.getOrDefault(false)

    /**
     * Replaces [previous] (text we typed earlier for the current phrase, sitting just before the cursor)
     * with [text], touching only the part that changed. Returns false if no input connection is available.
     */
    fun typeViaInputConnection(text: String, previous: String): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        val im = inputMethod ?: return false.also { Log.d(TAG, "IC: no input method object") }
        if (!im.currentInputStarted) return false.also { Log.d(TAG, "IC: no text field focused") }
        val ic = im.currentInputConnection ?: return false.also { Log.d(TAG, "IC: no input connection") }
        val info = im.currentInputEditorInfo
        val common = text.commonPrefixWith(previous).length
        val toDelete = previous.length - common
        val toAdd = text.substring(common)
        val terminalLike = info == null || info.inputType == InputType.TYPE_NULL
        // Note: AccessibilityInputConnection methods return void (unlike a keyboard's InputConnection),
        // so a dead connection can't be detected here; currentInputStarted above is the best signal.
        if (toDelete > 0) {
            if (terminalLike) {
                // Terminals (Termux) expect real key presses for backspace.
                repeat(toDelete) {
                    ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
                    ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
                }
            } else {
                ic.deleteSurroundingText(toDelete, 0)
            }
        }
        if (toAdd.isNotEmpty()) ic.commitText(toAdd, 1, null)
        Log.d(TAG, "IC typed into ${info?.packageName}: -$toDelete +${toAdd.length} ${spoken(toAdd.take(80))} (terminal=$terminalLike)")
        return true
    }

    /** The character just before the cursor in the focused field (Android 13+ input connection), or null. */
    fun charBeforeCursor(): Char? {
        if (Build.VERSION.SDK_INT < 33) return null
        val im = inputMethod ?: return null
        if (!im.currentInputStarted) return null
        val st = im.currentInputConnection?.getSurroundingText(1, 0, 0) ?: return null
        return st.text.getOrNull(st.selectionStart - st.offset - 1)
    }

    // ---------------- fallback for older Android ----------------

    fun insertWithNodeActions(text: String): Insert {
        val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (node == null) {
            Log.i(TAG, "No focused input field")
            return Insert.FAILED
        }
        Log.i(TAG, "Focused node: pkg=${node.packageName} class=${node.className} editable=${node.isEditable}")
        if (node.isEditable) {
            val existing = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
            var start = node.textSelectionStart
            var end = node.textSelectionEnd
            if (start !in 0..existing.length || end !in 0..existing.length) {
                start = existing.length
                end = existing.length
            }
            val s = minOf(start, end)
            val e = maxOf(start, end)
            val newText = existing.substring(0, s) + text + existing.substring(e)
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
            }
            if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                val cursor = s + text.length
                node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
                })
                return Insert.SET_TEXT
            }
            Log.i(TAG, "ACTION_SET_TEXT refused")
        }
        val pasted = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        Log.i(TAG, "ACTION_PASTE ${if (pasted) "accepted" else "refused"}")
        return if (pasted) Insert.PASTE else Insert.FAILED
    }
}

/**
 * Types dictation live. The engine sends the current phrase repeatedly as words arrive (isFinal=false)
 * and once more when the phrase is settled (isFinal=true); only the changed part is retyped.
 */
object LiveTyper {
    private const val TAG = "Speaker.type"
    enum class Result { TYPED, WAITING, COPIED }

    private var typed = "" // what is currently on screen for the in-progress phrase

    fun reset() {
        if (typed.isNotEmpty()) Log.i(TAG, "LiveTyper reset (dropping tracking of ${typed.length} chars)")
        typed = ""
    }

    /**
     * Types a finished block of text (the AI rewrite) into the focused field, with a space first when
     * the cursor sits right after a word. Falls back to node actions, then the clipboard (COPIED).
     */
    fun insert(ctx: Context, text: String): Result {
        typed = ""
        val svc = TypeAccessibilityService.instance
        val autoType = Prefs(ctx).autoInsert
        Log.i(TAG, "Insert block: ${text.length} chars autoType=$autoType a11y=${svc != null} fieldFocused=${svc?.editorActive()}")
        if (autoType && svc != null) {
            val before = runCatching { svc.charBeforeCursor() }.getOrNull()
            val needsSpace = before != null && !before.isWhitespace() && text.firstOrNull()?.let { it !in ".,!?;:)" } == true
            val block = if (needsSpace) " $text" else text
            if (runCatching { svc.typeViaInputConnection(block, "") }.getOrDefault(false)) return Result.TYPED
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Dictation", text)) // ACTION_PASTE pastes the clipboard
            when (runCatching { svc.insertWithNodeActions(text) }.getOrDefault(TypeAccessibilityService.Insert.FAILED)) {
                TypeAccessibilityService.Insert.SET_TEXT, TypeAccessibilityService.Insert.PASTE -> return Result.TYPED
                TypeAccessibilityService.Insert.FAILED -> Unit
            }
        }
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Dictation", text))
        Log.i(TAG, "No text field to type into: copied ${text.length} chars to the clipboard")
        return Result.COPIED
    }

    fun update(ctx: Context, text: String, isFinal: Boolean, sessionText: String): Result {
        val svc = TypeAccessibilityService.instance
        val autoType = Prefs(ctx).autoInsert
        if (isFinal) Log.i(TAG, "Final phrase: ${text.length} chars ${spoken(text)} autoType=$autoType a11y=${svc != null}")

        if (autoType && svc != null) {
            val ok = runCatching { svc.typeViaInputConnection(text, typed) }.getOrElse {
                Log.e(TAG, "Input-connection typing crashed", it); false
            }
            if (ok) {
                typed = if (isFinal) "" else text
                return Result.TYPED
            }
            if (!isFinal) return Result.WAITING // live text needs an input connection; wait for the final
            Log.i(TAG, "No input connection; falling back to node actions for the final phrase")
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Dictation", text)) // ACTION_PASTE pastes the clipboard
            when (runCatching { svc.insertWithNodeActions(text) }.getOrDefault(TypeAccessibilityService.Insert.FAILED)) {
                TypeAccessibilityService.Insert.SET_TEXT, TypeAccessibilityService.Insert.PASTE -> {
                    typed = ""
                    return Result.TYPED
                }
                TypeAccessibilityService.Insert.FAILED -> Unit
            }
        }
        if (!isFinal) return Result.WAITING
        typed = ""
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Dictation", sessionText))
        Log.i(TAG, "Copied session text (${sessionText.length} chars) to clipboard")
        return Result.COPIED
    }
}
