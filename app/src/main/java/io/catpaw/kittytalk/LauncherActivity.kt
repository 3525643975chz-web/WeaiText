package io.catpaw.kittytalk

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.color.DynamicColors
import io.catpaw.kittytalk.data.* // 导入数据库相关的类
import io.catpaw.kittytalk.databinding.ActivityMainBinding
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.lang.Exception

class LauncherActivity : AppCompatActivity() {

    internal lateinit var binding: ActivityMainBinding
    internal lateinit var cfg: SettingsRepository.Config

    internal val maxVisible = 3
    internal var suffixesExpanded = false
    internal var rulesExpanded = false
    internal var wholeRulesExpanded = false

    internal var exportTarget: List<Category>? = null
    internal val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                contentResolver.openOutputStream(uri)?.use {
                    val src = exportTarget ?: cfg.categories
                    it.write(SettingsRepository.toExportJson(SettingsRepository.Config(src.toMutableList())).toByteArray())
                }
                toast("已导出规则")
            } catch (e: Exception) {
                toast("导出失败：" + e.message)
            }
            exportTarget = null
        }
    }

    internal val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                val imported = if (text != null) SettingsRepository.parseImport(text) else null
                if (imported == null) {
                    toast("导入失败：文件格式无效")
                } else {
                    mergeImport(imported)
                }
            } catch (e: Exception) {
                toast("导入失败：" + e.message)
            }
        }
    }

    internal val themeLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (PaletteStore.load(this).version != lastThemeVersion) {
            recreate()
        }
    }

    internal val funcKey = "func_enabled"
    internal val punctKey = "punctuation_trigger"
    internal val punctEatPeriodKey = "punct_eat_period"
    internal val sentenceBlockKey = "sentence_block_chars"
    internal val sentenceTriggerKey = "sentence_trigger"
    internal val atSkipKey = "at_skip"
    internal val shizukuKey = "shizuku_keepalive"
    internal val prefWarningVersion = "warning_version"
    internal val prefLogVisible = "log_visible"
    internal val prefCurrentTab = "current_tab"

    internal val shizukuListener = object : Shizuku.OnRequestPermissionResultListener {
        override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
            if (requestCode == PrivilegeBridge.REQUEST_CODE) {
                runOnUiThread { refreshShizukuStatus() }
            }
        }
    }
    internal val shizukuBinderListener = Shizuku.OnBinderReceivedListener {
        runOnUiThread { refreshShizukuStatus() }
    }

    internal var logFollowBottom = true
    internal var logProgrammaticScroll = false

    internal var thanksClickCount = 0
    internal var lastThanksClick = 0L

    internal var lastThemeVersion = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        val themeCfg = PaletteStore.load(this)
        AppCompatDelegate.setDefaultNightMode(PaletteStore.appcompatNightMode(themeCfg.themeMode))
        setTheme(PaletteStore.themeStyleRes(themeCfg.palette))
        super.onCreate(savedInstanceState)
        if (themeCfg.palette == Palette.SYSTEM) {
            DynamicColors.applyToActivityIfAvailable(this)
        }
        setupEdgeToEdge()
        
        // 1. 依然初始化原生 binding，防止其他文件报错
        binding = ActivityMainBinding.inflate(layoutInflater)
        
        // ==========================================
        // 2. 核心改动：将原生界面替换为你的 WebView
        // ==========================================
        val webView = WebView(this)
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        
        // 注入桥接，让网页可以调用 Android 原生功能
        webView.addJavascriptInterface(WebBridge(), "AndroidBridge")
        
        // 加载你的 HTML 页面
        webView.loadUrl("file:///android_asset/index.html")
        
        // 显示网页
        setContentView(webView)
        // ==========================================

        applySystemBarInsets()

        lastThemeVersion = themeCfg.version

        cfg = SettingsRepository.load(this)
        
        // 3. 保持原版的各种初始化（虽然不显示了，但后台服务依然在运转）
        setupBottomNav()
        setupServiceCard()
        setupFuncSwitch()
        setupPunctuationSwitch()
        setupSentenceSuffixSwitch()
        setupAtSkipSwitch()
        setupSuffixControls()
        setupCategoryControls()
        setupListToggles()
        setupWhitelist()
        setupFab()
        setupSettingsButtons()
        setupShizuku()
        setupDebugLog()
        setupInfoButton()
        setupVersionBadge()
        updateServiceStatus()
        updateThemeStatus()
    }

    // 4. 写给网页调用的桥接类
    inner class WebBridge {
        @JavascriptInterface
        fun openBatterySettings() {
            runOnUiThread {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }

        @JavascriptInterface
        fun openAccessibilitySettings() {
            runOnUiThread {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        @JavascriptInterface
        fun openAutoStartSettings() {
            runOnUiThread {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            }
        }

        @JavascriptInterface
        fun openNotificationSettings() {
            runOnUiThread {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                intent.putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                startActivity(intent)
            }
        }

        // 🌟 新增：获取手机上真实安装的应用（排除系统应用）
        @JavascriptInterface
        fun getInstalledApps(): String {
            val pm = packageManager
            // 获取所有应用列表
            val packages = pm.getInstalledApplications(0)
            val jsonArray = JSONArray()
            
            for (pkg in packages) {
                // 过滤掉系统应用（只保留用户自己下载安装的）
                if ((pkg.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0) {
                    try {
                        val obj = JSONObject()
                        obj.put("name", pm.getApplicationLabel(pkg).toString())
                        obj.put("pkg", pkg.packageName)
                        jsonArray.put(obj)
                    } catch (e: Exception) {}
                }
            }
            return jsonArray.toString()
        }

        // ✅ 终极核心：将网页传过来的 JSON 解析并注入 Room 数据库！
        @JavascriptInterface
        fun saveConfig(json: String) {
            Thread {
                try {
                    val root = JSONObject(json)
                    val dao = KittyDatabase.get(this@LauncherActivity).phrases()

                    // 1. 保存全局开关到 SharedPreferences
                    val enabled = root.optBoolean("enabled", true)
                    darkPrefs().edit().putBoolean(funcKey, enabled).apply()

                    // 2. 构造分类
                    val defaultCid = 1L
                    val categories = listOf(CategoryEntity(cid = defaultCid, name = "网页配置", active = 1))

                    // 3. 解析规则并转换
                    val rulesList = mutableListOf<RuleEntity>()
                    val rulesJson = root.optJSONArray("rules")
                    if (rulesJson != null) {
                        for (i in 0 until rulesJson.length()) {
                            val ruleObj = rulesJson.getJSONObject(i)
                            rulesList.add(
                                RuleEntity(
                                    cid = defaultCid,
                                    trigger = ruleObj.optString("from"),
                                    replacement = ruleObj.optString("to"),
                                    active = 1,
                                    whole = 0,
                                    exclude = 0,
                                    seq = i
                                )
                            )
                        }
                    }

                    // 4. 解析后缀附加
                    val suffixesList = mutableListOf<SuffixEntity>()
                    val appendMagic = root.optJSONObject("appendMagic")
                    if (appendMagic != null && appendMagic.optBoolean("enabled")) {
                        val suffixText = appendMagic.optString("suffix", "喵")
                        if (suffixText.isNotEmpty()) {
                            suffixesList.add(
                                SuffixEntity(
                                    cid = defaultCid,
                                    content = suffixText,
                                    active = 1,
                                    seq = 0
                                )
                            )
                        }
                    }

                    // 写入数据库
                    dao.replaceCategories(categories, rulesList, suffixesList)

                    // 5. 解析生效应用白名单
                    val allowlist = mutableListOf<AllowlistEntity>()
                    val targetsJson = root.optJSONArray("targets")
                    if (targetsJson != null) {
                        for (i in 0 until targetsJson.length()) {
                            val pkg = targetsJson.getString(i)
                            allowlist.add(
                                AllowlistEntity(
                                    pkg = pkg,
                                    active = 1,
                                    clipboard = 0,
                                    label = ""
                                )
                            )
                        }
                    }
                    dao.replaceAllowlist(allowlist)

                    // 6. 通知后台核心服务重新读取配置！
                    AutomationBridge.instance?.reload()

                    // ❌ 已删掉 Toast 提示，让网页静默保存
                    
                } catch (e: Exception) {
                    e.printStackTrace()
                    runOnUiThread {
                        toast("同步失败：" + e.message)
                    }
                }
            }.start()
        }

        @JavascriptInterface
        fun loadConfig(): String {
            return "" 
        }
    }

    internal fun maybeShowFirstWarning() {
        // 逻辑保留，但目前不会调用
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
        updateThemeStatus()
    }

    internal fun updateThemeStatus() {
        val t = PaletteStore.load(this)
        binding.tabSettings.themeStatus.text =
            "${PaletteStore.modeLabel(t.themeMode)} / ${PaletteStore.paletteLabel(t.palette)}"
    }

    override fun onDestroy() {
        AutomationBridge.logListener = null
        try { Shizuku.removeRequestPermissionResultListener(shizukuListener) } catch (_: Throwable) {}
        PrivilegeBridge.removeBinderListener(shizukuBinderListener)
        super.onDestroy()
    }

    internal fun setupEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    internal fun applySystemBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomNav) { v, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            v.setPadding(0, 0, 0, bottom)
            insets
        }
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = !dark
        controller.isAppearanceLightNavigationBars = !dark
    }

    internal fun setupBottomNav() {
        val item = binding.bottomNav.menu.findItem(R.id.nav_logs)
        item.isVisible = isLogVisible()
        binding.bottomNav.setOnItemSelectedListener { item ->
            showTab(item.itemId)
            true
        }
    }

    internal fun showTab(menuId: Int) {
        if (binding.bottomNav.selectedItemId != menuId) {
            binding.bottomNav.setOnItemSelectedListener(null)
            binding.bottomNav.selectedItemId = menuId
            binding.bottomNav.setOnItemSelectedListener { item ->
                showTab(item.itemId)
                true
            }
        }
        darkPrefs().edit().putInt(prefCurrentTab, menuId).apply()
        val main = menuId == R.id.nav_home
        val rules = menuId == R.id.nav_rules
        val whitelist = menuId == R.id.nav_whitelist
        val logs = menuId == R.id.nav_logs
        val settings = menuId == R.id.nav_settings
        binding.tabMain.root.visibility = if (main) android.view.View.VISIBLE else android.view.View.GONE
        binding.tabRules.root.visibility = if (rules) android.view.View.VISIBLE else android.view.View.GONE
        binding.tabWhitelist.root.visibility = if (whitelist) android.view.View.VISIBLE else android.view.View.GONE
        binding.tabLogs.root.visibility = if (logs) android.view.View.VISIBLE else android.view.View.GONE
        binding.tabSettings.root.visibility = if (settings) android.view.View.VISIBLE else android.view.View.GONE
        if (rules) refreshRulesUi()
        if (whitelist) refreshWhitelistUi()
        if (logs) refreshDebugLog()
        if (settings) refreshDebugLog()
    }

    internal fun darkPrefs() = getSharedPreferences("qq_settings", Context.MODE_PRIVATE)

    internal fun setupVersionBadge() {
        try {
            val info = packageManager.getPackageInfo(packageName, 0)
            binding.tabMain.versionBadge.text = "v${info.versionName}"
            binding.tabSettings.aboutAppVersion.text = "v ${info.versionName}"
        } catch (e: Exception) {
            binding.tabMain.versionBadge.text = "v1.0"
            binding.tabSettings.aboutAppVersion.text = "v 1.0"
        }
    }

    internal fun isLogVisible(): Boolean = darkPrefs().getBoolean(prefLogVisible, false)
    internal fun saveLogVisible(v: Boolean) = darkPrefs().edit().putBoolean(prefLogVisible, v).apply()

    internal fun applyConfig() {
        SettingsRepository.save(this, cfg)
        AutomationBridge.instance?.reload()
        refreshRulesUi()
    }

    internal fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}
