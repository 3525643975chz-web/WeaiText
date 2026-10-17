package io.catpaw.kittytalk

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

class AutomationBridge : AccessibilityService() {

    companion object {
        var instance: AutomationBridge? = null
        const val TAG = "QQReply"

        private const val PKG_MOBILEQQ = "com.tencent.mobileqq"
        private const val PKG_QQ = "com.tencent.qq"
        private const val PKG_TIM = "com.tencent.tim"
        private const val PKG_WECHAT = "com.tencent.mm" 
        private const val QQ_ID_PREFIX = "com.tencent.mobileqq:id/"
        private const val QQ2_ID_PREFIX = "com.tencent.qq:id/"

        private const val LOG_MAX = 400
        private data class LogEntry(val message: String, var firstTs: String, var lastTs: String, var count: Int = 1)
        private val logList = ArrayDeque<LogEntry>()
        
        @Volatile
        var logListener: (() -> Unit)? = null

        @Synchronized
        fun addLog(line: String) {
            val ts = android.text.format.DateFormat.format("HH:mm:ss", java.util.Date()).toString()
            val last = logList.lastOrNull()
            if (last != null && last.message == line) {
                last.lastTs = ts
                last.count += 1
            } else {
                logList.addLast(LogEntry(line, ts, ts))
                while (logList.size > LOG_MAX) logList.removeFirst()
            }
            instance?.onNewLog()
        }

        @Synchronized
        fun getLogs(): List<String> = logList.map { e -> "[${e.firstTs}] ${e.message}" }
        @Synchronized
        fun clearLogs() { logList.clear(); instance?.onNewLog() }
    }

    private fun onNewLog() { logListener?.let { mainHandler.post { it() } } }

    private var isQQ = false
    private var isWechat = false 
    private var whitelist: MutableList<AppEntry> = mutableListOf()
    private var allAppsEnabled = false
    var floatCollapseOnCopy = true

    private var activeRules: List<Rule> = emptyList()
    private var activeSuffixes: List<Suffix> = emptyList()
    private var funcEnabled = true
    private var punctuationTrigger = false
    private var punctEatPeriod = false
    private var sentenceTrigger = false
    private var shizukuKeepAlive = false
    
    private val bgExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val TEXT_CHANGE_DELAY_MS = 150L 
    private var pendingTextRunnable: Runnable? = null
    private val CONTENT_CHANGE_DELAY_MS = 150L
    private var pendingContentRunnable: Runnable? = null
    
    private var verifyPending = false
    private var lastSet = ""
    private var userOriginal = ""
    private var processing = false
    private var processingStart = 0L
    private val watchdogTask = object : Runnable {
        override fun run() {
            if (processing) {
                val elapsed = System.currentTimeMillis() - processingStart
                if (elapsed >= 5000L) {
                    processing = false
                } else {
                    mainHandler.postDelayed(this, 5000L - elapsed)
                }
            }
        }
    }
    
    private var lastPasteLike = false
    private var tailPending = false
    private var substringHitForRun = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var textChangeCount = 0
    private var lastEventPkg = ""
    private var justSwitchedWindow = false

    private val syncInputStateTask = Runnable {
        try {
            val root = rootInActiveWindow ?: return@Runnable
            val inputNode = findInputField(root) ?: return@Runnable
            val raw = inputNode.text?.toString() ?: ""
            val hint = if (Build.VERSION.SDK_INT >= 26) runCatching { inputNode.hintText?.toString() }.getOrNull() else null
            val isHint = raw.isNotEmpty() && !hint.isNullOrEmpty() && raw.trim() == hint.trim()
            if (raw.isNotEmpty() && !isHint && inputNode.isFocused && inputNode.isEditable) {
                lastSet = raw
                userOriginal = raw
            }
        } catch (_: Exception) {
        } finally {
            justSwitchedWindow = false
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            instance = this
            reload()
        } catch (e: Throwable) {
            Log.e(TAG, "onCreate 异常: " + e.message)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            reload()
        } catch (e: Throwable) {}
    }

    fun setFuncEnabled(enabled: Boolean) {
        funcEnabled = enabled
    }

    fun reload() {
        PhraseProcessor.reload(this)
        val cfg = SettingsRepository.load(this)
        val active = cfg.categories.firstOrNull { it.active } ?: cfg.categories.firstOrNull()
        activeRules = active?.rules ?: emptyList()
        activeSuffixes = (active?.suffixes ?: emptyList()).filter { it.enabled && it.text.isNotBlank() }
        val prefs = getSharedPreferences("qq_settings", Context.MODE_PRIVATE)
        funcEnabled = prefs.getBoolean("func_enabled", true)
        punctuationTrigger = prefs.getBoolean("punctuation_trigger", false)
        punctEatPeriod = prefs.getBoolean("punct_eat_period", false)
        sentenceTrigger = prefs.getBoolean("sentence_trigger", false)
        shizukuKeepAlive = prefs.getBoolean("shizuku_keepalive", false)
        allAppsEnabled = prefs.getBoolean("all_apps_enabled", false)
        whitelist = WhitelistStore.load(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            handleAccessibilityEvent(event)
        } catch (e: Throwable) {
            Log.e(TAG, "onAccessibilityEvent 异常: " + e.message)
        }
    }

    private fun handleAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val funcOn = getSharedPreferences("qq_settings", Context.MODE_PRIVATE).getBoolean("func_enabled", true)
        if (!funcOn) return
        
        val pkg = event.packageName?.toString() ?: return
        lastEventPkg = pkg
        if (pkg == packageName) return
        if (isInputMethodPackage(pkg)) return
        if (!isSupportedPackage(pkg)) return
        
        isQQ = pkg.startsWith(PKG_MOBILEQQ) || pkg == PKG_QQ || pkg == PKG_TIM
        isWechat = pkg == PKG_WECHAT

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                textChangeCount = 0
                processing = false
                cancelPending()
                justSwitchedWindow = true
                mainHandler.removeCallbacks(syncInputStateTask)
                mainHandler.postDelayed(syncInputStateTask, 150)
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                verifyPending = false
                lastPasteLike = isPasteLikeEvent(event)
                scheduleTextChange()
                textChangeCount++
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (pendingTextRunnable == null && pendingContentRunnable == null) {
                    val r = Runnable {
                        pendingContentRunnable = null
                        doProcess(false, quietNoInput = true)
                    }
                    pendingContentRunnable = r
                    mainHandler.postDelayed(r, CONTENT_CHANGE_DELAY_MS)
                }
            }
        }
    }

    private fun scheduleTextChange() {
        cancelPending()
        val r = Runnable {
            pendingTextRunnable = null
            doProcess(true)
        }
        pendingTextRunnable = r
        mainHandler.postDelayed(r, TEXT_CHANGE_DELAY_MS)
    }

    private fun isPasteLikeEvent(event: AccessibilityEvent): Boolean {
        return try {
            val added = event.addedCount
            val removed = event.removedCount
            val from = event.fromIndex
            val textNow = event.text?.map { it?.toString() ?: "" }?.joinToString("") ?: ""
            val slice = if (added > 0 && from >= 0) {
                val s = from.coerceIn(0, textNow.length)
                val e = (from + added).coerceIn(0, textNow.length)
                if (e > s) textNow.substring(s, e) else ""
            } else ""
            if (slice.isEmpty()) return removed > 0 && added > 0
            val punctSet = PhraseProcessor.pastePunctChars()
            val allDigit = slice.all { it.isDigit() }
            val allPunct = slice.all { it == '\n' || it in punctSet }
            if (allDigit || allPunct) return false
            val hasPunct = slice.any { it == '\n' || it in punctSet }
            val hasDigit = slice.any { it.isDigit() }
            if (hasPunct) return slice.any { it.isLetter() }
            if (hasDigit) return false
            val special = slice.any { !it.isLetter() && !it.isDigit() && it != '\n' && it !in punctSet && !it.isWhitespace() }
            if (special) return false
            slice.length >= 30
        } catch (_: Exception) {
            false
        }
    }

    private fun cancelPending() {
        pendingTextRunnable?.let { mainHandler.removeCallbacks(it); pendingTextRunnable = null }
        pendingContentRunnable?.let { mainHandler.removeCallbacks(it); pendingContentRunnable = null }
    }

    private fun isSupportedPackage(pkg: String): Boolean =
        allAppsEnabled || pkg.startsWith(PKG_MOBILEQQ) || pkg == PKG_QQ ||
            pkg == PKG_TIM || pkg == PKG_WECHAT || whitelist.any { it.pkg == pkg && it.enabled }

    private fun isInputMethodPackage(pkg: String?): Boolean {
        if (pkg.isNullOrEmpty()) return false
        val p = pkg.lowercase()
        return p.contains("inputmethod") || p.startsWith("com.baidu.input") || p.startsWith("com.sohu.inputmethod")
    }

    private fun acquireRootForInput(): AccessibilityNodeInfo? {
        runCatching {
            val r = rootInActiveWindow
            if (r != null) {
                val p = r.packageName?.toString() ?: ""
                if (p != packageName && !isInputMethodPackage(p)) return r
                r.recycle()
            }
        }
        runCatching {
            val wins = windows ?: return@runCatching
            val ordered = wins.sortedByDescending { w -> when { w.isFocused -> 3; w.isActive -> 2; else -> 1 } }
            for (w in ordered) {
                val r = w.root ?: continue
                val p = r.packageName?.toString() ?: ""
                if (p == packageName || isInputMethodPackage(p)) { r.recycle(); continue }
                return r
            }
        }
        return null
    }

    private fun findGlobalFocusedInput(): AccessibilityNodeInfo? {
        return try {
            val f = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
            try {
                val p = f.packageName?.toString() ?: ""
                val ok = runCatching { f.isEditable || f.className?.toString()?.endsWith("EditText") == true }.getOrDefault(false)
                if (ok && p != packageName && !isInputMethodPackage(p)) AccessibilityNodeInfo.obtain(f) else null
            } finally { f.recycle() }
        } catch (_: Throwable) { null }
    }

    private fun doProcess(isTextChange: Boolean, quietNoInput: Boolean = false) {
        val funcOn = getSharedPreferences("qq_settings", Context.MODE_PRIVATE).getBoolean("func_enabled", true)
        if (!funcOn) return
        val pasteLike = lastPasteLike
        lastPasteLike = false
        if (processing) return
        processing = true
        substringHitForRun = false
        processingStart = System.currentTimeMillis()
        mainHandler.postDelayed(watchdogTask, 5000L)
        try {
            val root = acquireRootForInput() ?: return
            if (root.packageName == packageName) return
            try {
                val inputNode = findInputField(root) ?: findGlobalFocusedInput() ?: run {
                    if (isTextChange) countInputFailure()
                    return
                }
                try {
                    var raw = inputNode.text?.toString() ?: ""
                    if (preCheckSkip(raw, inputNode, isTextChange, pasteLike)) return
                    
                    if (raw.contains("@") && getSharedPreferences("qq_settings", Context.MODE_PRIVATE).getBoolean("at_skip", true)) {
                        lastSet = raw
                        return
                    }
                    if (isPureNumberText(raw)) {
                        lastSet = raw
                        return
                    }
                    
                    var isDeleting = false
                    if (lastSet.isNotEmpty() && raw.length < lastSet.length) {
                        if (lastSet.startsWith(raw)) {
                            isDeleting = true
                        } else {
                            lastSet = ""
                            userOriginal = ""
                        }
                    }
                    
                    val (newText, cursorIndex) = PhraseProcessor.transformFloat(raw, lastSet, isDeleting)

                    if (newText == raw) {
                        lastSet = raw
                        return
                    }

                    if (isWechat) {
                        // 🌟 微信无感替换最终杀招：全选+强塞剪贴板，不弹悬浮窗！
                        addLog("微信替换连招启动")
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("weaitext", newText)
                        clipboard.setPrimaryClip(clip)
                        
                        // 1. 强制获取焦点
                        inputNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                        // 2. 全选
                        inputNode.performAction(AccessibilityNodeInfo.ACTION_SELECT_ALL)
                        val selectArgs = Bundle()
                        selectArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                        selectArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, raw.length)
                        inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectArgs)
                        // 3. 粘贴
                        inputNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                        
                        lastSet = newText
                        
                    } else {
                        // 其他应用正常写入
                        if (setText(inputNode, newText)) {
                            lastSet = newText
                            setCursorEnd(inputNode, cursorIndex)
                            OverlayPrefs.recordWriteSuccess(this, lastEventPkg)
                            addLog("替换成功")
                        } else {
                            OverlayPrefs.resetWriteSuccessCount(this, lastEventPkg)
                            if (OverlayPrefs.isNoPopupPackage(this, lastEventPkg) || OverlayPrefs.isInCooldown(this) || OverlayInput.isCollapsed()) {
                                addLog("写入失败")
                            } else {
                                addLog("写入失败，启用悬浮窗")
                                try { OverlayInput.show(this) } catch (e: Exception) {}
                            }
                        }
                    }
                } finally {
                    inputNode.recycle()
                }
            } finally {
                root.recycle()
            }
        } finally {
            processing = false
            mainHandler.removeCallbacks(watchdogTask)
        }
    }

    private fun isPureNumberText(raw: String): Boolean {
        val body = PhraseProcessor.restoreUserInput(raw)
        return body.isNotEmpty() && body.all { it in '0'..'9' }
    }

    private fun preCheckSkip(raw: String, inputNode: AccessibilityNodeInfo, isTextChange: Boolean, pasteLike: Boolean): Boolean {
        val hint = if (Build.VERSION.SDK_INT >= 26) runCatching { inputNode.hintText?.toString() }.getOrNull() else null
        if (raw.isNotEmpty() && !hint.isNullOrEmpty() && raw.trim() == hint.trim()) return true
        if (raw.isNotEmpty() && !inputNode.isFocused && (isTextChange || !isQQ)) return true
        if (raw.isEmpty()) {
            if (isTextChange && (!inputNode.isEditable || !inputNode.isFocused)) countInputFailure()
            userOriginal = ""
            lastSet = ""
            tailPending = false
            return true
        }
        if (raw.isBlank()) {
            lastSet = raw
            return true
        }
        if (justSwitchedWindow && lastSet.isEmpty() && raw.contains("\n") && !pasteLike) {
            justSwitchedWindow = false
            lastSet = raw
            userOriginal = raw
            return true
        }
        justSwitchedWindow = false
        if (lastSet.isNotEmpty() && raw != lastSet) {
            val rawNorm = PhraseProcessor.restoreUserInput(raw)
            val lastNorm = PhraseProcessor.restoreUserInput(lastSet)
            if (rawNorm == lastNorm && rawNorm.isNotEmpty()) {
                lastSet = raw
                userOriginal = rawNorm
                return true
            }
        }
        if (lastSet.isNotEmpty() && raw.length < lastSet.length && activeSuffixes.isNotEmpty()) {
            val rawLines = raw.split("\n")
            val lastLines = lastSet.split("\n")
            if (rawLines.size == lastLines.size) {
                var allMatch = true
                for (i in rawLines.indices) {
                    if (PhraseProcessor.stripSuffixAll(lastLines[i]) != rawLines[i]) {
                        allMatch = false
                        break
                    }
                }
                if (allMatch) {
                    setText(inputNode, lastSet)
                    return true
                }
            }
        }
        return false
    }

    private var inputFailureCount = 0
    private var lastInputFailureTime = 0L

    private fun countInputFailure() {
        val now = System.currentTimeMillis()
        if (now - lastInputFailureTime > 30000L) inputFailureCount = 0
        lastInputFailureTime = now
        inputFailureCount++
        if (inputFailureCount >= 1) {
            inputFailureCount = 0
            if (OverlayPrefs.isNoPopupPackage(this, lastEventPkg) || OverlayPrefs.isInCooldown(this) || OverlayInput.isCollapsed()) {
            } else {
                try { OverlayInput.show(this) } catch (e: Exception) {}
            }
        }
    }

    private fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        return try {
            val args = Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (_: Exception) {
            false
        }
    }

    private fun setCursorEnd(node: AccessibilityNodeInfo, index: Int) {
        try {
            val sel = Bundle()
            sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, index)
            sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, index)
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
        } catch (_: Exception) {}
    }

    private fun findInputField(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (isQQ) {
            val ids = listOf(
                QQ_ID_PREFIX + "inputBar", QQ_ID_PREFIX + "chat_input", QQ_ID_PREFIX + "message_edit",
                QQ_ID_PREFIX + "sendMessageEditText", QQ_ID_PREFIX + "msg_input_et", QQ_ID_PREFIX + "footer_et_msg",
                QQ_ID_PREFIX + "input_edit", QQ_ID_PREFIX + "input", QQ_ID_PREFIX + "edtInput",
                QQ_ID_PREFIX + "et_input", QQ_ID_PREFIX + "inputEdt", QQ_ID_PREFIX + "inputbar",
                QQ2_ID_PREFIX + "input", QQ2_ID_PREFIX + "edtInput"
            )
            for (id in ids) {
                try {
                    val nodes = root.findAccessibilityNodeInfosByViewId(id)
                    if (!nodes.isNullOrEmpty()) {
                        for (n in nodes) {
                            try {
                                if (n.isEditable) return AccessibilityNodeInfo.obtain(n)
                            } finally { n.recycle() }
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        if (isWechat) {
            val wxNode = findWechatInput(root)
            if (wxNode != null) return wxNode
        }

        findFocusedEditable(root)?.let { return it }
        findEditable(root)?.let { return it }
        findEditTextByClassName(root)?.let { return it }
        
        val focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val focusOk = focus != null && runCatching { focus.isEditable || (focus.className?.toString()?.endsWith("EditText") == true) }.getOrDefault(false)
        return if (focusOk) AccessibilityNodeInfo.obtain(focus) else {
            focus?.recycle()
            null
        }
    }

    private fun findWechatInput(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        val cls = root.className?.toString() ?: ""
        if (cls.contains("EditText") && root.isFocused) {
            return AccessibilityNodeInfo.obtain(root)
        }
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findWechatInput(child)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun findFocusedEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        try { if (node.isEditable && node.isFocused) return AccessibilityNodeInfo.obtain(node) } catch (_: Throwable) {}
        for (i in 0 until node.childCount) {
            try {
                val child = node.getChild(i) ?: continue
                val found = findFocusedEditable(child)
                child.recycle()
                if (found != null) return found
            } catch (_: Throwable) {}
        }
        return null
    }

    private fun findEditTextByClassName(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        try {
            val cls = node.className?.toString() ?: ""
            if (cls.isNotEmpty() && cls.endsWith("EditText")) return AccessibilityNodeInfo.obtain(node)
        } catch (_: Throwable) {}
        for (i in 0 until node.childCount) {
            try {
                val child = node.getChild(i) ?: continue
                val found = findEditTextByClassName(child)
                child.recycle()
                if (found != null) return found
            } catch (_: Throwable) {}
        }
        return null
    }

    private fun findEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        try { if (node.isEditable) return AccessibilityNodeInfo.obtain(node) } catch (_: Throwable) {}
        for (i in 0 until node.childCount) {
            try {
                val child = node.getChild(i) ?: continue
                val found = findEditable(child)
                child.recycle()
                if (found != null) return found
            } catch (_: Throwable) {}
        }
        return null
    }

    override fun onInterrupt() {}
    override fun onGesture(gestureId: Int): Boolean = false
}
