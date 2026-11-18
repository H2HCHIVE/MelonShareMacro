package com.example.melonshare

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var status: TextView
    private var current = Cat.SONG
    @Volatile private var pageReady = false
    private val logTime = SimpleDateFormat("HH:mm:ss", Locale.US)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val f = File(getExternalFilesDir(null), "melon_collect_log.txt")
            if (f.length() > 200_000) f.delete()
        } catch (e: Exception) {
        }

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
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    pageReady = false
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    pageReady = true
                }
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        val tabs = row(*Cat.values().map { c ->
            btn(c.label) { openTab(c) }
        }.toTypedArray())

        val ctl = row(
            btn("현재 페이지 수집") { collect() },
            btn("초기화") { MacroState.collected.clear(); refresh("초기화됨") }
        )

        val bottom1 = row(
            btn("접근성 설정") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            btn("로그 공유") {
                shareFile("melon_macro_log.txt", "로그가 없습니다. 접근성 서비스를 켜고 '시작'을 한 번 실행한 뒤 다시 눌러주세요")
            },
            btn("수집 로그") {
                shareFile("melon_collect_log.txt", "수집 로그가 없습니다. '현재 페이지 수집'을 한 번 누른 뒤 다시 눌러주세요")
            }
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
        openTab(Cat.SONG)
    }

    /** 탭 전환: UA를 먼저 맞추고 새로 로딩. 로딩이 끝나기 전에는 수집 불가 */
    private fun openTab(c: Cat) {
        current = c
        pageReady = false
        web.stopLoading()
        web.settings.userAgentString = if (c.desktop) MacroState.UA_DESKTOP else MacroState.UA_MOBILE
        web.loadUrl(c.listUrl)
        clog("TAB ${c.label} desktop=${c.desktop} url=${c.listUrl}")
        refresh("${c.label} 로딩 중…")
    }

    private fun collect() {
        val cat = current
        val url = web.url ?: ""
        if (!pageReady && web.progress < 100) {
            toast("페이지 로딩 중입니다. 잠시 후 다시 누르세요")
            return
        }
        val onTabPage = cat.pathMarker.isEmpty() || url.contains(cat.pathMarker)
        if (!onTabPage) {
            clog("COLLECT 주의: ${cat.label} 탭인데 현재 URL=$url")
            if (cat == Cat.MV || cat == Cat.PHOTO) {
                toast("현재 화면이 '${cat.label}' 목록이 아닙니다. 탭을 다시 눌러주세요")
                return
            }
        }
        collectAttempt(cat, 1, LinkedHashSet())
    }

    /** 새 ID가 더 나오지 않을 때까지 0.8초 간격으로 다시 읽는다 (동적 렌더링 대기) */
    private fun collectAttempt(cat: Cat, attempt: Int, acc: LinkedHashSet<String>) {
        val maxAttempts = if (cat == Cat.MV || cat == Cat.PHOTO) 10 else 6
        val scroll = if (cat == Cat.MV || cat == Cat.PHOTO) "window.scrollTo(0,document.body.scrollHeight);" else ""
        val js = "(function(){" + scroll +
            "return JSON.stringify({h:document.documentElement.outerHTML," +
            "t:(document.body?document.body.innerText:'')});})()"

        web.evaluateJavascript(js) { raw ->
            val obj = try {
                JSONObject(JSONTokener(raw ?: "null").nextValue() as String)
            } catch (e: Exception) {
                null
            }
            val html = obj?.optString("h") ?: ""
            val text = obj?.optString("t") ?: ""

            val found = LinkedHashSet<String>()
            val perPattern = cat.patterns.map { r ->
                val s = r.findAll(html).map { it.groupValues[1] }.toSet()
                found.addAll(s)
                s.size
            }
            val before = acc.size
            acc.addAll(found)
            val grew = acc.size > before

            if (attempt < maxAttempts && (acc.isEmpty() || grew)) {
                refresh("${cat.label} 읽는 중… ${acc.size}개 (시도 $attempt)")
                web.postDelayed({ collectAttempt(cat, attempt + 1, acc) }, 800)
                return@evaluateJavascript
            }

            val set = MacroState.collected.getOrPut(cat) { LinkedHashSet() }
            val setBefore = set.size
            set.addAll(acc)
            val added = set.size - setBefore

            val hints = Regex("(?:전체|총)\\s*([0-9,]{1,6})").findAll(text)
                .take(5).map { it.value.replace("\n", " ") }.toList()
            clog(
                "COLLECT ${cat.label} url=${web.url} 시도=$attempt htmlLen=${html.length} textLen=${text.length} " +
                    "화면표시개수힌트=$hints 패턴별(마지막읽기)=$perPattern 이번수집=${acc.size} 신규=$added 누적=${set.size}"
            )
            clog("  ID 샘플: ${acc.take(5)}")
            val samples = Regex("(?:onclick|href|data-[\\w-]+)=[\"']([^\"']*(?:mv|Mv|MV|video|Video|photo|Photo)[^\"']*)[\"']")
                .findAll(html).map { it.groupValues[1].take(150) }.distinct().take(12).toList()
            if (cat == Cat.MV || cat == Cat.PHOTO || acc.isEmpty()) {
                samples.forEach { clog("  후보속성: $it") }
            }
            refresh("${cat.label} ${added}개 추가 (이번 ${acc.size}개 발견)")
        }
    }

    private fun clog(msg: String) {
        Log.d("MelonCollect", msg)
        try {
            File(getExternalFilesDir(null), "melon_collect_log.txt")
                .appendText("${logTime.format(Date())} $msg\n")
        } catch (e: Exception) {
        }
    }

    private fun shareFile(name: String, emptyMsg: String) {
        val f = File(getExternalFilesDir(null), name)
        if (!f.exists() || f.length() == 0L) {
            toast(emptyMsg)
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, name)
                clipData = ClipData.newRawUri("log", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "로그 공유"))
        } catch (e: Exception) {
            toast("로그 공유 실패: ${e.message}")
        }
    }

    private fun refresh(msg: String = "") {
        val c = Cat.values().joinToString(" · ") { "${it.label} ${MacroState.collected[it]?.size ?: 0}" }
        status.text = (if (msg.isNotEmpty()) "$msg\n" else "") + "수집: $c"
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}