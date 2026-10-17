package io.catpaw.kittytalk

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.color.DynamicColors
import io.catpaw.kittytalk.data.* 
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
    internal var logFollowBottom = true
    internal var logProgrammaticScroll = false
    internal var thanksClickCount = 0
    internal var lastThanksClick = 0L
    internal var lastThemeVersion = -1
    
    internal val shizukuListener = object : Shizuku.OnRequestPermissionResultListener {
        override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) { }
    }
    internal val shizukuBinderListener = Shizuku.OnBinderReceivedListener { }

    internal val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { }
    internal val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { }
    internal val themeLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

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

    override fun onCreate(savedInstanceState: Bundle?) {
        val themeCfg = PaletteStore.load(this)
        AppCompatDelegate.setDefaultNightMode(PaletteStore.appcompatNightMode(themeCfg.themeMode))
        setTheme(PaletteStore.themeStyleRes(themeCfg.palette))
        
        super.onCreate(savedInstanceState) 
        
        // 设置窗口透明
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        if (themeCfg.palette == Palette.SYSTEM) {
            DynamicColors.applyToActivityIfAvailable(this)
        }
        setupEdgeToEdge()
        
        val webView = WebView(this)
        
        // 强制 WebView 渲染层透明，但不再使用 SOFTWARE 软件渲染（这会导致毛玻璃卡死）
        webView.setBackgroundColor(Color.TRANSPARENT) 
        
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        
        webView.addJavascriptInterface(WebBridge(), "AndroidBridge")
        webView.loadUrl("file:///android_asset/index.html")
        
        setContentView(webView)

        window.navigationBarColor = Color.TRANSPARENT
        window.statusBarColor = Color.TRANSPARENT

        lastThemeVersion = themeCfg.version
        cfg = SettingsRepository.load(this)

        requestNotificationPermissionIfNeeded()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
            } else {
                LifelineService.start(this)
            }
        } else {
            LifelineService.start(this)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) {
            LifelineService.start(this)
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (darkPrefs().getBoolean("bg_mode_hide", false)) {
            finishAndRemoveTask()
        }
    }

    inner class WebBridge {
        @JavascriptInterface
        fun openBatterySettings() {
            runOnUiThread { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }

        @JavascriptInterface
        fun openAccessibilitySettings() {
            runOnUiThread { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
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

        @JavascriptInterface
        fun setBackgroundMode(enabled: Boolean) {
            darkPrefs().edit().putBoolean("bg_mode_hide", enabled).apply()
        }

        @JavascriptInterface
        fun getInstalledApps(): String {
            try {
                val pm = packageManager
                val intent = Intent(Intent.ACTION_MAIN, null)
                intent.addCategory(Intent.CATEGORY_LAUNCHER)
                val resolveInfos = pm.queryIntentActivities(intent, 0)
                
                val jsonArray = JSONArray()
                val addedPkgs = mutableSetOf<String>()
                
                for (info in resolveInfos) {
                    val pkgName = info.activityInfo.packageName
                    if (addedPkgs.contains(pkgName)) continue
                    
                    val appInfo = pm.getApplicationInfo(pkgName, 0)
                    if ((appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0) {
                        val obj = JSONObject()
                        obj.put("name", pm.getApplicationLabel(appInfo).toString())
                        obj.put("pkg", pkgName)
                        jsonArray.put(obj)
                        addedPkgs.add(pkgName)
                    }
                }
                return jsonArray.toString()
            } catch (e: Exception) {
                e.printStackTrace()
                return "[]"
            }
        }

        @JavascriptInterface
        fun saveConfig(json: String) {
            Thread {
                try {
                    val root = JSONObject(json)
                    val dao = KittyDatabase.get(this@LauncherActivity).phrases()

                    val enabled = root.optBoolean("enabled", true)
                    darkPrefs().edit().putBoolean(funcKey, enabled).apply()

                    val defaultCid = 1L
                    val categories = listOf(CategoryEntity(cid = defaultCid, name = "网页配置", active = 1))

                    val rulesList = mutableListOf<RuleEntity>()
                    val rulesJson = root.optJSONArray("rules")
                    if (rulesJson != null) {
                        for (i in 0 until rulesJson.length()) {
                            val ruleObj = rulesJson.getJSONObject(i)
                            rulesList.add(RuleEntity(cid = defaultCid, trigger = ruleObj.optString("from"), replacement = ruleObj.optString("to"), active = 1, whole = 0, exclude = 0, seq = i))
                        }
                    }

                    val suffixesList = mutableListOf<SuffixEntity>()
                    val appendMagic = root.optJSONObject("appendMagic")
                    if (appendMagic != null && appendMagic.optBoolean("enabled")) {
                        val suffixText = appendMagic.optString("suffix", "喵")
                        if (suffixText.isNotEmpty()) {
                            suffixesList.add(SuffixEntity(cid = defaultCid, content = suffixText, active = 1, seq = 0))
                        }
                    }

                    dao.replaceCategories(categories, rulesList, suffixesList)

                    val allowlist = mutableListOf<AllowlistEntity>()
                    val targetsJson = root.optJSONArray("targets")
                    if (targetsJson != null) {
                        for (i in 0 until targetsJson.length()) {
                            val pkg = targetsJson.getString(i)
                            allowlist.add(AllowlistEntity(pkg = pkg, active = 1, clipboard = 0, label = ""))
                        }
                    }
                    dao.replaceAllowlist(allowlist)

                    AutomationBridge.instance?.reload()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }.start()
        }

        @JavascriptInterface
        fun loadConfig(): String {
            return "" 
        }
    }

    internal fun updateThemeStatus() {}
    internal fun updateServiceStatus() {}
    internal fun showTab(menuId: Int) {}
    internal fun applyConfig() {}
    internal fun isLogVisible(): Boolean = false
    internal fun saveLogVisible(v: Boolean) {}
    internal fun mergeImport(imported: SettingsRepository.Config) {}
    internal fun refreshRulesUi() {}
    internal fun refreshWhitelistUi() {}
    internal fun refreshDebugLog() {}
    internal fun setupBottomNav() {}
    internal fun setupServiceCard() {}
    internal fun setupFuncSwitch() {}
    internal fun setupPunctuationSwitch() {}
    internal fun setupSentenceSuffixSwitch() {}
    internal fun setupAtSkipSwitch() {}
    internal fun setupSuffixControls() {}
    internal fun setupCategoryControls() {}
    internal fun setupListToggles() {}
    internal fun setupWhitelist() {}
    internal fun setupFab() {}
    internal fun setupSettingsButtons() {}
    internal fun setupShizuku() {}
    internal fun setupDebugLog() {}
    internal fun setupInfoButton() {}
    internal fun setupVersionBadge() {}
    internal fun refreshShizukuStatus() {}

    override fun onDestroy() {
        AutomationBridge.logListener = null
        super.onDestroy()
    }

    internal fun setupEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    internal fun darkPrefs() = getSharedPreferences("qq_settings", Context.MODE_PRIVATE)

    internal fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}