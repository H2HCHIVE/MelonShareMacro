package com.example.melonshare

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONTokener
import java.io.File

class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var status: TextView
    private var current = Cat.SONG

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("p", MODE_PRIVATE)
        val pad = (8 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 4, pad, pad)
        }

        fun btn(t: String, f: () -> Unit) = Button(this).apply {
            text = t
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { f() }
        }
        fun row(vararg b: Button) = LinearLayout(this).apply { b.forEach { addView(it) } }

        val room = EditText(this).apply {
            hint = "카톡 목록에 보이는 채팅방 이름 (예: 나와의 채팅)"
            setText(prefs.getString("room", "나와의 채팅"))
        }
        status = TextView(this)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.userAgentString =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            webViewClient = WebViewClient()
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            loadUrl(current.listUrl)
        }

        val tabs = row(*Cat.values().map { c ->
            btn(c.label) { current = c; web.loadUrl(c.listUrl); refresh() }
        }.toTypedArray())

        val ctl = row(
            btn("현재 페이지 수집") { collect() },
            btn("초기화") { MacroState.collected.clear(); refresh("초기화됨") }
        )

        val bottom1 = row(
            btn("접근성 설정") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            btn("로그 공유") { shareLog() }
        )

        val bottom2 = row(
            btn("시작") {
                val name = room.text.toString().trim()
                val svc = MacroService.instance
                val q = MacroState.buildQueue()
                when {
                    name.isEmpty() -> toast("채팅방 이름을 입력하세요")
                    svc == null -> toast("접근성 서비스를 먼저 켜세요")
                    q.isEmpty() -> toast("먼저 목록을 수집하세요")
                    else -> {
                        prefs.edit().putString("room", name).apply()
                        MacroState.roomName = name
                        MacroState.queue = q
                        MacroState.index = 0
                        MacroState.running = true
                        svc.startNext()
                    }
                }
            },
            btn("중지") {
                MacroService.instance?.stop("중지됨") ?: run { MacroState.running = false }
            }
        )

        root.addView(room)
        root.addView(tabs)
        root.addView(ctl)
        root.addView(web)
        root.addView(status)
        root.addView(bottom1)
        root.addView(bottom2)
        setContentView(root)
        refresh()
    }

    private fun shareLog() {
        val f = File(getExternalFilesDir(null), "melon_macro_log.txt")
        if (!f.exists() || f.length() == 0L) {
            toast("로그가 없습니다. 접근성 서비스를 켜고 '시작'을 한 번 실행한 뒤 다시 눌러주세요")
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "melon_macro_log.txt")
                clipData = ClipData.newRawUri("log", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "로그 공유"))
        } catch (e: Exception) {
            toast("로그 공유 실패: ${e.message}")
        }
    }

    private fun collect() {
        val cat = current
        web.evaluateJavascript("(function(){return document.documentElement.outerHTML;})()") { raw ->
            val html = try {
                JSONTokener(raw).nextValue() as? String ?: ""
            } catch (e: Exception) {
                raw ?: ""
            }
            val set = MacroState.collected.getOrPut(cat) { LinkedHashSet() }
            val before = set.size
            cat.patterns.forEach { r -> r.findAll(html).forEach { set.add(it.groupValues[1]) } }
            refresh("${cat.label} ${set.size - before}개 추가")
        }
    }

    private fun refresh(msg: String = "") {
        val c = Cat.values().joinToString(" · ") { "${it.label} ${MacroState.collected[it]?.size ?: 0}" }
        status.text = (if (msg.isNotEmpty()) "$msg\n" else "") + "수집: $c"
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
