package com.example.melonshare

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    private lateinit var photoBtn: Button
    private var current = Cat.SONG
    @Volatile private var pageReady = false
    private val logTime = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val handler = Handler(Looper.getMainLooper())

    // 포토 순회 상태
    private var photoRunning = false
    private var photoLastId: String? = null
    private var photoSame = 0
    private var photoNoId = 0
    private var photoSteps = 0
    private var photoNew = 0

    private val NEXT_SELS = """['.btn_next','.btn-next','.swiper-button-next','.slick-next','.owl-next','a[class*="next"]','button[class*="next"]','[class*="btn_nxt"]','[title*="다음"]','[aria-label*="다음"]','[alt*="다음"]','.next']"""

    private val FIRST_PHOTO_JS = """
(function(){
  var sels=['a[href*="photo_detail"]','[onclick*="photo_detail"]','[onclick*="PhotoDetail"]','[onclick*="goPhoto"]','a[href*="photoId"]','[data-photo-id]','[data-photoid]','[class*="photo"] li a','[class*="photo"] li img'];
  for(var i=0;i<sels.length;i++){
    var els=document.querySelectorAll(sels[i]);
    for(var j=0;j<els.length;j++){
      var e=els[j];
      if(e.offsetParent!==null||e.getClientRects().length>0){
        e.click();
        return 'clicked['+sels[i]+']: '+String(e.outerHTML||'').substring(0,160);
      }
    }
  }
  return 'none: anchors='+document.querySelectorAll('a').length;
})()
"""

    private val NEXT_JS = """
(function(s){
  function vis(e){return e&&(e.offsetParent!==null||e.getClientRects().length>0);}
  var sels=__SELS__;
  if(s===0||s===2){
    for(var i=0;i<sels.length;i++){
      var els=document.querySelectorAll(sels[i]);
      for(var j=0;j<els.length;j++){
        var e=els[j];
        if(vis(e)){
          if(s===2){['mousedown','mouseup','click'].forEach(function(t){e.dispatchEvent(new MouseEvent(t,{bubbles:true,cancelable:true,view:window}));});}
          else{e.click();}
          return 'btn['+sels[i]+']: '+String(e.outerHTML||'').substring(0,120);
        }
      }
    }
    return 'no-button';
  }
  ['keydown','keyup'].forEach(function(t){
    var ev=new KeyboardEvent(t,{key:'ArrowRight',code:'ArrowRight',keyCode:39,which:39,bubbles:true});
    document.dispatchEvent(ev);
    if(document.body){document.body.dispatchEvent(ev);}
  });
  return 'key-ArrowRight';
})(__S__)
"""

    private val DIAG_JS = """
(function(){
  var sels=__SELS__;
  var out=[];
  sels.forEach(function(s){
    var els=document.querySelectorAll(s);
    for(var i=0;i<els.length&&out.length<14;i++){
      var e=els[i];
      out.push(s+' => '+e.tagName+' class='+String(e.getAttribute('class')||'')+' title='+String(e.getAttribute('title')||'')+' vis='+(e.offsetParent!==null));
    }
  });
  return out.length?out.join(' | '):'no-candidates';
})()
"""

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

        photoBtn = btn("포토 순회") { togglePhoto() }

        val ctl = row(
            btn("현재 페이지 수집") { collect() },
            photoBtn,
            btn("초기화") {
                if (photoRunning) finishPhoto("초기화로 중단")
                MacroState.collected.clear()
                refresh("초기화됨")
            }
        )

        val bottom1 = row(
            btn("접근성 설정") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            btn("로그 공유") {
                shareFile("melon_macro_log.txt", "로그가 없습니다. 접근성 서비스를 켜고 '시작'을 한 번 실행한 뒤 다시 눌러주세요")
            },
            btn("수집 로그") {
                shareFile("melon_collect_log.txt", "수집 로그가 없습니다. 수집을 한 번 실행한 뒤 다시 눌러주세요")
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
        if (photoRunning) finishPhoto("탭 전환으로 중단")
        current = c
        pageReady = false
        web.stopLoading()
        web.settings.userAgentString = if (c.desktop) MacroState.UA_DESKTOP else MacroState.UA_MOBILE
        web.loadUrl(c.listUrl)
        clog("TAB ${c.label} desktop=${c.desktop} url=${c.listUrl}")
        refresh("${c.label} 로딩 중…")
    }

    // ================= 곡 / 앨범 / 영상: 목록 HTML 수집 (변경 없음) =================

    private fun collect() {
        val cat = current
        if (cat == Cat.PHOTO) {
            toast("포토는 '포토 순회' 버튼을 사용하세요")
            return
        }
        val url = web.url ?: ""
        if (!pageReady && web.progress < 100) {
            toast("페이지 로딩 중입니다. 잠시 후 다시 누르세요")
            return
        }
        val onTabPage = cat.pathMarker.isEmpty() || url.contains(cat.pathMarker)
        if (!onTabPage) {
            clog("COLLECT 주의: ${cat.label} 탭인데 현재 URL=$url")
            if (cat == Cat.MV) {
                toast("현재 화면이 '${cat.label}' 목록이 아닙니다. 탭을 다시 눌러주세요")
                return
            }
        }
        collectAttempt(cat, 1, LinkedHashSet())
    }

    private fun collectAttempt(cat: Cat, attempt: Int, acc: LinkedHashSet<String>) {
        val maxAttempts = if (cat == Cat.MV) 10 else 6
        val scroll = if (cat == Cat.MV) "window.scrollTo(0,document.body.scrollHeight);" else ""
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
            if (cat == Cat.MV || acc.isEmpty()) {
                samples.forEach { clog("  후보속성: $it") }
            }
            refresh("${cat.label} ${added}개 추가 (이번 ${acc.size}개 발견)")
        }
    }

    // ================= 포토: 상세 페이지 순회로 URL의 photoId 수집 =================

    private fun decode(raw: String?): String = try {
        JSONTokener(raw ?: "null").nextValue() as? String ?: ""
    } catch (e: Exception) {
        raw ?: ""
    }

    private fun readHref(cb: (String) -> Unit) {
        web.evaluateJavascript("(function(){return location.href;})()") { raw ->
            val h = decode(raw)
            cb(if (h.isNotEmpty()) h else (web.url ?: ""))
        }
    }

    private fun togglePhoto() {
        if (photoRunning) {
            finishPhoto("사용자가 중지")
            return
        }
        if (current != Cat.PHOTO) {
            toast("먼저 '포토' 탭을 누르세요")
            return
        }
        if (!pageReady && web.progress < 100) {
            toast("페이지 로딩 중입니다. 잠시 후 다시 누르세요")
            return
        }
        photoRunning = true
        photoLastId = null; photoSame = 0; photoNoId = 0; photoSteps = 0; photoNew = 0
        photoBtn.text = "순회 중지"
        val url = web.url ?: ""
        clog("PHOTO 순회 시작 url=$url")
        when {
            url.contains("photo_detail") -> {
                diagNext()
                handler.postDelayed({ photoStep() }, 300)
            }
            url.contains("/artist/photo.htm") -> clickFirstPhoto()
            else -> finishPhoto("포토 목록/상세 페이지가 아닙니다 (url=$url)")
        }
    }

    private fun clickFirstPhoto() {
        web.evaluateJavascript(FIRST_PHOTO_JS) { raw ->
            val r = decode(raw)
            clog("PHOTO 첫 사진 클릭 결과: $r")
            if (!photoRunning) return@evaluateJavascript
            if (r.startsWith("none")) {
                finishPhoto("목록에서 첫 사진 요소를 찾지 못했습니다. 첫 사진을 직접 눌러 상세로 들어간 뒤 다시 눌러주세요")
            } else {
                waitDetail(0)
            }
        }
    }

    private fun waitDetail(tries: Int) {
        if (!photoRunning) return
        readHref { href ->
            if (!photoRunning) return@readHref
            if (href.contains("photo_detail") && href.contains("photoId=")) {
                clog("PHOTO 상세 진입 확인 href=$href")
                diagNext()
                handler.postDelayed({ photoStep() }, 400)
            } else if (tries >= 12) {
                finishPhoto("상세 페이지로 이동하지 않았습니다 (href=$href)")
            } else {
                handler.postDelayed({ waitDetail(tries + 1) }, 700)
            }
        }
    }

    private fun photoStep() {
        if (!photoRunning) return
        photoSteps++
        if (photoSteps > 600) {
            finishPhoto("최대 단계(600) 도달")
            return
        }
        readHref { href ->
            if (!photoRunning) return@readHref
            val id = Regex("[?&]photoId=(\\d+)").find(href)?.groupValues?.get(1)
            val set = MacroState.collected.getOrPut(Cat.PHOTO) { LinkedHashSet() }
            if (id == null) {
                photoNoId++
                clog("PHOTO step=$photoSteps photoId 없음 href=$href (연속 $photoNoId)")
                if (photoNoId >= 8) {
                    finishPhoto("URL에서 photoId를 읽지 못했습니다")
                } else {
                    handler.postDelayed({ photoStep() }, 700)
                }
                return@readHref
            }
            photoNoId = 0
            if (id !in set) {
                set.add(id)
                photoNew++
                photoSame = 0
                photoLastId = id
                clog("PHOTO step=$photoSteps 신규 photoId=$id (누적 ${set.size})")
                refresh("포토 순회 중… 신규 $photoNew (누적 ${set.size})")
                triggerNext(0)
                handler.postDelayed({ photoStep() }, 1000)
            } else if (id == photoLastId) {
                // 같은 사진 유지: 홀수 회는 느린 이동일 수 있으니 기다리기만 하고, 짝수 회에 다른 방법으로 시도
                photoSame++
                clog("PHOTO step=$photoSteps 같은 photoId 유지 id=$id (연속 $photoSame)")
                if (photoSame >= 6) {
                    finishPhoto("더 이상 다음 사진이 없거나 넘기기에 실패 (같은 photoId 반복)")
                } else {
                    if (photoSame % 2 == 0) triggerNext(photoSame / 2)
                    handler.postDelayed({ photoStep() }, 1000)
                }
            } else {
                finishPhoto("이미 수집한 photoId($id)로 돌아왔습니다 (순환 종료)")
            }
        }
    }

    private fun triggerNext(strategy: Int) {
        val js = NEXT_JS.replace("__SELS__", NEXT_SELS).replace("__S__", strategy.toString())
        web.evaluateJavascript(js) { raw ->
            if (photoSteps <= 5 || strategy > 0) {
                clog("PHOTO NEXT 전략=$strategy 결과=${decode(raw)}")
            }
        }
    }

    private fun diagNext() {
        web.evaluateJavascript(DIAG_JS.replace("__SELS__", NEXT_SELS)) { raw ->
            clog("PHOTO 다음 버튼 후보: ${decode(raw)}")
        }
    }

    private fun finishPhoto(reason: String) {
        photoRunning = false
        handler.removeCallbacksAndMessages(null)
        photoBtn.text = "포토 순회"
        val set = MacroState.collected[Cat.PHOTO]
        val sample = set?.firstOrNull()?.let { Cat.PHOTO.detailUrl + it }
        clog("PHOTO 종료: $reason 신규=$photoNew 누적=${set?.size ?: 0} 샘플URL=$sample")
        refresh("포토 순회 종료: $reason")
        toast("포토 ${set?.size ?: 0}개 — $reason")
    }

    // ================= 공통 =================

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