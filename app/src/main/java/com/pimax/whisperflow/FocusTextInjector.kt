package com.pimax.whisperflow

import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Injects dictated text into the focused editable field via accessibility service.
 */
object FocusTextInjector {
    private const val TAG = "⌨️ FocusInject"

    @Volatile
    var accessibilityService: WhisperAccessibilityService? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private var fieldPrefix: String = ""
    private var sessionActive = false
    private var targetNode: AccessibilityNodeInfo? = null
    private var targetPackage: String? = null
    private val targetBounds = Rect()

    fun beginSession(): Boolean {
        val service = accessibilityService ?: run {
            Log.w(TAG, "beginSession failed — no accessibility service")
            return false
        }
        val node = findFocusedEditable(service) ?: run {
            Log.w(TAG, "beginSession failed — no focused editable field")
            return false
        }
        fieldPrefix = node.text?.toString() ?: ""
        targetPackage = node.packageName?.toString()
        node.getBoundsInScreen(targetBounds)
        targetNode?.recycle()
        targetNode = AccessibilityNodeInfo.obtain(node)
        node.recycle()
        sessionActive = true
        Log.d(
            TAG,
            "Session started — pkg=$targetPackage prefix=${fieldPrefix.length} class=${targetNode?.className} bounds=$targetBounds"
        )
        return true
    }

    fun updateDictation(dictation: String): Boolean {
        if (!sessionActive) return false
        val service = accessibilityService ?: return false
        val text = composeText(dictation)

        if (WhisperInputBridge.commitComposedText(fieldPrefix, dictation)) {
            Log.d(TAG, "Injected via IME: ${dictation.take(40)}...")
            return true
        }

        val node = relinkTargetNode(service) ?: run {
            Log.w(TAG, "Inject failed — target field not found in any window (pkg=$targetPackage)")
            return false
        }
        val ok = injectTextBlocking(service, node, text)
        node.recycle()
        if (ok) Log.d(TAG, "Injected ${dictation.take(40)}...")
        else Log.w(TAG, "Inject failed for ${dictation.take(40)}...")
        return ok
    }

    suspend fun updateDictationAsync(dictation: String): Boolean {
        if (!sessionActive) return false
        val service = accessibilityService ?: return false
        val text = composeText(dictation)

        if (WhisperInputBridge.commitComposedText(fieldPrefix, dictation)) {
            Log.d(TAG, "Injected via IME: ${dictation.take(40)}...")
            return true
        }

        val node = relinkTargetNode(service) ?: return false
        val clicked = tapNode(service, node)
        if (!clicked) {
            node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        }
        val ok = injectTextBlocking(service, node, text)
        node.recycle()
        if (ok) Log.d(TAG, "Injected ${dictation.take(40)}...")
        else Log.w(TAG, "Inject failed for ${dictation.take(40)}...")
        return ok
    }

    fun endSession() {
        sessionActive = false
        fieldPrefix = ""
        targetPackage = null
        targetBounds.setEmpty()
        targetNode?.recycle()
        targetNode = null
    }

    fun hasActiveSession(): Boolean = sessionActive

    private fun injectTextBlocking(
        service: WhisperAccessibilityService,
        node: AccessibilityNodeInfo,
        text: String
    ): Boolean {
        node.refresh()
        node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

        val setArgs = Bundle()
        setArgs.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setArgs)) {
            Log.d(TAG, "SET_TEXT succeeded")
            return true
        }

        Log.w(TAG, "SET_TEXT failed — trying select-all + paste")
        if (pasteReplaceAll(service, node, text)) {
            Log.d(TAG, "Paste replace succeeded")
            return true
        }

        Log.w(TAG, "Paste replace failed — trying cursor paste")
        if (pasteAtCursor(service, node, text)) {
            Log.d(TAG, "Cursor paste succeeded")
            return true
        }

        return false
    }

    private suspend fun tapNode(service: WhisperAccessibilityService, node: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() <= 0 || rect.height() <= 0) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }

        return suspendCancellableCoroutine { cont ->
            val path = Path().apply { moveTo(rect.exactCenterX(), rect.exactCenterY()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
                .build()
            service.dispatchGesture(
                gesture,
                object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
                mainHandler
            )
        }
    }

    private fun pasteReplaceAll(service: WhisperAccessibilityService, node: AccessibilityNodeInfo, text: String): Boolean {
        val clipboard = service.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        clipboard.setPrimaryClip(ClipData.newPlainText("WhisperFlow", text))
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val len = (node.text?.length ?: fieldPrefix.length).coerceAtLeast(0)
        val selArgs = Bundle()
        selArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
        selArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, len)
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs)
        return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    private fun pasteAtCursor(service: WhisperAccessibilityService, node: AccessibilityNodeInfo, text: String): Boolean {
        val clipboard = service.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        clipboard.setPrimaryClip(ClipData.newPlainText("WhisperFlow", text))
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    private fun relinkTargetNode(service: WhisperAccessibilityService): AccessibilityNodeInfo? {
        val pkg = targetPackage
        if (pkg != null) {
            for (window in service.windows) {
                val root = window.root ?: continue
                if (root.packageName?.toString() != pkg) {
                    root.recycle()
                    continue
                }
                val found = findBestEditableInWindow(root)
                root.recycle()
                if (found != null) {
                    targetNode?.recycle()
                    targetNode = AccessibilityNodeInfo.obtain(found)
                    found.recycle()
                    return obtainTargetNode()
                }
            }
        }
        val focused = findFocusedEditable(service)
        if (focused != null) {
            targetNode?.recycle()
            targetNode = AccessibilityNodeInfo.obtain(focused)
            focused.recycle()
            return obtainTargetNode()
        }
        return obtainTargetNode()
    }

    private fun findBestEditableInWindow(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val focused = findEditableFocusedInTree(root)
        if (focused != null) return focused
        return findEditableNearBounds(root)
    }

    private fun findEditableNearBounds(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (isEditable(node)) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.intersect(targetBounds) || targetBounds.isEmpty) {
                return AccessibilityNodeInfo.obtain(node)
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditableNearBounds(child)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun obtainTargetNode(): AccessibilityNodeInfo? {
        val cached = targetNode ?: return null
        return try {
            AccessibilityNodeInfo.obtain(cached)
        } catch (e: Exception) {
            Log.w(TAG, "Cached node stale: ${e.message}")
            null
        }
    }

    private fun composeText(dictation: String): String {
        val trimmed = dictation.trim()
        if (fieldPrefix.isEmpty()) return trimmed
        if (trimmed.isEmpty()) return fieldPrefix
        return if (fieldPrefix.endsWith(" ")) "$fieldPrefix$trimmed" else "$fieldPrefix $trimmed"
    }

    fun findFocusedEditable(service: WhisperAccessibilityService): AccessibilityNodeInfo? {
        var focused = service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && isEditable(focused)) return focused
        focused?.recycle()

        for (window in service.windows) {
            val root = window.root ?: continue
            val editable = findEditableFocusedInTree(root)
            root.recycle()
            if (editable != null) return editable
        }
        return null
    }

    private fun isEditable(node: AccessibilityNodeInfo): Boolean {
        return node.isEditable ||
            node.className?.toString()?.contains("EditText", ignoreCase = true) == true ||
            node.className?.toString()?.contains("AutoCompleteTextView", ignoreCase = true) == true
    }

    private fun findEditableFocusedInTree(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isFocused && isEditable(node)) {
            return AccessibilityNodeInfo.obtain(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditableFocusedInTree(child)
            child.recycle()
            if (found != null) return found
        }
        return null
    }
}

/** Commits text when WhisperFlow is the active IME. */
object WhisperInputBridge {
    @Volatile
    var activeIme: WhisperInputService? = null

    fun commitComposedText(prefix: String, dictation: String): Boolean {
        val ic = activeIme?.currentInputConnection ?: return false
        val trimmed = dictation.trim()
        val text = when {
            prefix.isEmpty() -> trimmed
            trimmed.isEmpty() -> prefix
            prefix.endsWith(" ") -> "$prefix$trimmed"
            else -> "$prefix $trimmed"
        }
        return try {
            ic.beginBatchEdit()
            val before = ic.getTextBeforeCursor(10_000, 0)?.length ?: 0
            val after = ic.getTextAfterCursor(10_000, 0)?.length ?: 0
            ic.deleteSurroundingText(before, after)
            ic.commitText(text, 1)
            ic.endBatchEdit()
            true
        } catch (e: Exception) {
            Log.w("⌨️ FocusInject", "IME commit failed: ${e.message}")
            false
        }
    }
}
