package io.catpaw.kittytalk // 注意：千万不要删掉这一行！必须保留！

import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebSettings
import androidx.appcompat.app.AppCompatActivity

class LauncherActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val webView = WebView(this)
        val settings: WebSettings = webView.settings
        
        // 开启 JS 权限、存储权限、文件读取权限
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        
        // 加载你上传的本地网页
        webView.loadUrl("file:///android_asset/index.html")
        
        // 把网页设为 App 的界面
        setContentView(webView)
    }
}
