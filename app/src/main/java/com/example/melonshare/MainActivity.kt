package com.example.melonshare

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import org.json.JSONArray
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

    private val ARTIST_ID = "4096106"

    // ---- 포토 순회 상태 ----
    private enum class PState { IDLE, ENTER, WATCH }

    private var photoRunning = false
    private var pState = PState.IDLE
    private var enterAt = 0L
    private var watchStartAt = 0L
    private var lastTriggerAt = 0L
    private var lastHeartbeat = 0L
    private var inFlight = false
    private var inFlightAt = 0L
    private var jsSeq = 0
    private var lastAccepted: String? = null
    private var pendingId: String? = null
    private var pendingCount = 0
    private var noIdCount = 0
    private var noNextCount = 0
    private var retryStage = 0
    private var probeLogCount = 0
    private var triggerLogCount = 0
    private var stripAccepts = 0
    private var thumbAccepts = 0
    private var mainOnlyAccepts = 0
    private var lastTotalHint = ""
    private val visited = HashSet<String>()

    private val photoTicker = object : Runnable {
        override fun run() {
            if (!photoRunning) return
            try {
                photoTick()
            } catch (e: Exception) {
                clog("PHOTO tick 예외: $e")
                finishPhoto("내부 예외: ${e.message}")
                return
            }
            if (photoRunning) handler.postDelayed(this, 400)
        }
    }

    private val FIRST_PHOTO_JS = """
(function(){
  function vis(e){return e&&(e.offsetParent!==null||e.getClientRects().length>0);}
  var target=null,info='';
  var sels=['a[href*="photo_detail"]','[onclick*="photo_detail"]','a[href*="photoId"]','[data-photo-id]','[data-photoid]'];
  for(var i=0;i<sels.length&&!target;i++){
    var els=document.querySelectorAll(sels[i]);
    for(var j=0;j<els.length;j++){if(vis(els[j])){target=els[j];info=sels[i];break;}}
  }
  var bestN=0,groups=[];
  if(!target){
    var best=null;
    document.querySelectorAll('ul,ol').forEach(function(ul){
      var lis=ul.querySelectorAll(':scope > li');var n=0;
      for(var k=0;k<lis.length;k++){if(lis[k].querySelector('img'))n++;}
      if(n>0){groups.push(n+'x '+String(ul.getAttribute('class')||ul.id||ul.tagName));}
      if(n>bestN){bestN=n;best=ul;}
    });
    if(best&&bestN>=4){
      var lis2=best.querySelectorAll(':scope > li');
      for(var m=0;m<lis2.length;m++){
        if(lis2[m].querySelector('img')&&vis(lis2[m])){
          target=lis2[m].querySelector('a,button,[onclick]')||lis2[m].querySelector('img');
          info='ul-grid('+bestN+')';break;
        }
      }
    }
  }
  if(!target){return 'none: groups='+groups.slice(0,6).join(' | ');}
  var t=target.closest('a,button,[onclick]')||target;
  t.click();
  return 'clicked['+info+']: '+String(t.outerHTML||'').substring(0,180)+' || groups='+groups.slice(0,6).join(' | ');
})()
"""

    /**
     * 현재 화면의 큰 사진 ID(이미지 파일명 앞 숫자) + 같은 페이지의 썸네일 목록 ID들
     *  - strip : goPhotoBtmDetail('아티스트ID','사진ID',..) 링크에서 가져온 ID (아티스트 ID가 같은 것만)
     *  - thumbs: strip이 없을 때 쓰는 대체값. 화면에 보이는 작은 이미지의 파일명 ID(8자리 이상)
     */
    private val PROBE_JS = """
(function(){
  var re=/(\d{5,})_\d{8,}_[A-Za-z0-9]+\.(?:jpe?g|png|gif|webp)/i;
  var ARTIST='__ARTIST__';
  var vw=window.innerWidth||document.documentElement.clientWidth;
  var vh=window.innerHeight||document.documentElement.clientHeight;
  var best=null,bestSrc='',bestArea=0,cands=[],all=[];
  var imgs=document.querySelectorAll('img');
  for(var i=0;i<imgs.length;i++){
    var im=imgs[i];
    var src=im.currentSrc||im.src||'';
    var m=re.exec(src);
    if(!m){continue;}
    var r=im.getBoundingClientRect();
    var ix=Math.max(0,Math.min(r.right,vw)-Math.max(r.left,0));
    var iy=Math.max(0,Math.min(r.bottom,vh)-Math.max(r.top,0));
    var ia=Math.round(ix*iy);
    all.push([m[1],ia]);
    if(cands.length<6){cands.push(m[1]+':'+Math.round(r.width)+'x'+Math.round(r.height)+'/vis'+ia);}
    if(ia>bestArea){bestArea=ia;best=m[1];bestSrc=src;}
  }
  var thumbs=[],tseen={};
  for(var t=0;t<all.length;t++){
    var tid=all[t][0];
    if(all[t][1]>0&&tid.length>=8&&tid!==best&&!tseen[tid]){tseen[tid]=1;thumbs.push(tid);}
  }
  var stripRe=/Photo\w*Detail\w*\(\s*['"]?(\d+)['"]?\s*,\s*['"]?(\d{5,})/;
  var strip=[],sseen={};
  var els=document.querySelectorAll('[onclick*="Photo"],[href*="Photo"]');
  for(var j=0;j<els.length;j++){
    var h=(els[j].getAttribute('href')||'')+' '+(els[j].getAttribute('onclick')||'');
    var m2=stripRe.exec(h);
    if(m2&&m2[1]===ARTIST&&!sseen[m2[2]]){sseen[m2[2]]=1;strip.push(m2[2]);}
  }
  var nb=document.querySelector('.btn_photo_next');
  var ns=!nb?'none':((nb.disabled||(nb.classList&&nb.classList.contains('disabled')))?'disabled':'ok');
  var counter='';
  var leafs=document.querySelectorAll('span,em,strong,p,div');
  for(var k=0;k<leafs.length&&!counter;k++){
    if(leafs[k].children.length===0){
      var tx=(leafs[k].textContent||'').trim();
      if(/^\d+\s*\/\s*\d+$/.test(tx)){counter=tx;}
    }
  }
  var total='';
  var body=document.body?document.body.innerText:'';
  var tm=/(?:전체|총)\s*([0-9,]+)/.exec(body);
  if(tm){total=tm[0].replace(/\s+/g,' ');}
  return JSON.stringify({id:best,src:bestSrc.substring(Math.max(0,bestSrc.length-60)),area:bestArea,cands:cands,href:location.href,next:ns,strip:strip,thumbs:thumbs,counter:counter,total:total});
})()
"""

    private val NEXT_JS = """
(function(s){
  var b=document.querySelector('.btn_photo_next');
  if(!b){return 'no-button';}
  if(b.disabled||(b.classList&&b.classList.contains('disabled'))){return 'disabled';}
  if(s===0){b.click();}
  else if(s===1){['mousedown','mouseup','click'].forEach(function(t){b.dispatchEvent(new MouseEvent(t,{bubbles:true,cancelable:true,view:window}));});}
  else{['keydown','keyup'].forEach(function(t){var ev=new KeyboardEvent(t,{key:'ArrowRight',code:'ArrowRight',keyCode:39,which:39,bubbles:true});document.dispatchEvent(ev);});}
  return 'clicked-s'+s+': '+String(b.outerHTML||'').substring(0,100);
})(__S__)
"""

    private val DIAG_JS = """
(function(){
  var o=[];
  ['.btn_photo_next','.btn_photo_pre'].forEach(function(s){
    var e=document.querySelector(s);
    o.push(s+'='+(e?((e.disabled||e.classList.contains('disabled'))?'disabled':'ok')+' vis='+(e.offsetParent!==null):'none'));
  });
  return o.join(' | ');
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

    // ================= 포토: 상세 페이지 순회 (큰 사진 ID + 같은 페이지 썸네일 목록 ID) =================

    private fun decode(raw: String?): String = try {
        JSONTokener(raw ?: "null").nextValue() as? String ?: ""
    } catch (e: Exception) {
        raw ?: ""
    }

    private fun strs(a: JSONArray?): List<String> {
        val out = ArrayList<String>()
        if (a != null) for (i in 0 until a.length()) out.add(a.optString(i))
        return out
    }

    private fun photoSet(): LinkedHashSet<String> =
        MacroState.collected.getOrPut(Cat.PHOTO) { LinkedHashSet() }

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
        val url = web.url ?: ""
        val onDetail = url.contains("photo_detail")
        val onList = url.contains("/artist/photo.htm")
        if (!onDetail && !onList) {
            toast("포토 목록/상세 페이지가 아닙니다")
            clog("PHOTO 시작 거부 url=$url")
            return
        }

        photoRunning = true
        visited.clear()
        lastAccepted = null; pendingId = null; pendingCount = 0
        noIdCount = 0; noNextCount = 0; retryStage = 0
        probeLogCount = 0; triggerLogCount = 0
        stripAccepts = 0; thumbAccepts = 0; mainOnlyAccepts = 0; lastTotalHint = ""
        inFlight = false
        val now = SystemClock.uptimeMillis()
        lastHeartbeat = 0L
        photoBtn.text = "순회 중지"
        clog("PHOTO 순회 시작 url=$url (상세=$onDetail) 기존누적=${photoSet().size}")

        if (onDetail) {
            pState = PState.WATCH
            watchStartAt = now
            diagButtons()
        } else {
            pState = PState.ENTER
            enterAt = now
            clickFirstPhoto()
        }
        handler.removeCallbacks(photoTicker)
        handler.postDelayed(photoTicker, 400)
    }

    /** 클릭 결과 로그 전용. 이후 진행은 이 콜백에 의존하지 않고 타이머가 web.url로 판단한다 */
    private fun clickFirstPhoto() {
        web.evaluateJavascript(FIRST_PHOTO_JS) { raw ->
            try {
                val r = decode(raw)
                clog("PHOTO 첫 사진 클릭 결과: $r")
                if (photoRunning && r.startsWith("none")) {
                    finishPhoto("목록에서 첫 사진 요소를 찾지 못했습니다. 첫 사진을 직접 눌러 상세로 들어간 뒤 다시 눌러주세요")
                }
            } catch (e: Exception) {
                clog("PHOTO 클릭 콜백 예외: $e")
            }
        }
    }

    private fun diagButtons() {
        web.evaluateJavascript(DIAG_JS) { raw ->
            try { clog("PHOTO 버튼 상태: ${decode(raw)}") } catch (e: Exception) { }
        }
    }

    private fun photoTick() {
        val now = SystemClock.uptimeMillis()

        // 하트비트: 어느 단계에서 멈추는지 로그로 확인 (10초마다)
        if (now - lastHeartbeat >= 10000) {
            lastHeartbeat = now
            clog(
                "PHOTO HB state=$pState inFlight=$inFlight 확인=${visited.size} 누적=${photoSet().size} " +
                    "last=$lastAccepted sinceTrigger=${if (lastTriggerAt == 0L) -1 else now - lastTriggerAt}ms"
            )
        }
        // 응답 없는 JS 호출은 유실로 보고 풀어준다
        if (inFlight && now - inFlightAt > 3000) {
            clog("PHOTO JS 콜백 유실(3초 무응답) → 재시도")
            inFlight = false
        }

        when (pState) {
            PState.ENTER -> {
                val u = web.url ?: ""
                if (u.contains("photo_detail")) {
                    clog("PHOTO 상세 진입 확인 (${now - enterAt}ms) url=$u")
                    pState = PState.WATCH
                    watchStartAt = now
                    diagButtons()
                } else if (now - enterAt > 15000) {
                    finishPhoto("상세 페이지로 이동하지 않았습니다 (url=$u)")
                }
            }
            PState.WATCH -> tickWatch(now)
            PState.IDLE -> Unit
        }
    }

    private fun tickWatch(now: Long) {
        if (lastAccepted == null && now - watchStartAt > 12000) {
            finishPhoto("상세 화면의 이미지에서 사진 ID를 읽지 못했습니다")
            return
        }
        if (lastAccepted != null) {
            val since = now - lastTriggerAt
            if (since > 12000) {
                finishPhoto("다음 사진으로 바뀌지 않았습니다 — 마지막 사진이거나 클릭 실패 (수집 ${photoSet().size}개)")
                return
            }
            if (retryStage == 0 && since > 4000) { retryStage = 1; triggerNext(1) }
            if (retryStage == 1 && since > 8000) { retryStage = 2; triggerNext(2) }
        }
        if (!inFlight) probe()
    }

    private fun probe() {
        inFlight = true
        inFlightAt = SystemClock.uptimeMillis()
        val seq = ++jsSeq
        web.evaluateJavascript(PROBE_JS.replace("__ARTIST__", ARTIST_ID)) { raw ->
            if (seq == jsSeq) inFlight = false
            try {
                onProbe(decode(raw))
            } catch (e: Exception) {
                clog("PHOTO probe 콜백 예외: $e")
            }
        }
    }

    private fun onProbe(json: String) {
        if (!photoRunning || pState != PState.WATCH) return
        val o = try {
            JSONObject(json)
        } catch (e: Exception) {
            clog("PHOTO probe JSON 파싱 실패: ${json.take(120)}")
            return
        }
        val id: String? = if (o.isNull("id")) null else o.optString("id")
        val nextState = o.optString("next")
        val strip = strs(o.optJSONArray("strip"))
        val thumbs = strs(o.optJSONArray("thumbs"))
        val counter = o.optString("counter")
        val total = o.optString("total")
        if (total.isNotEmpty()) lastTotalHint = total

        probeLogCount++
        if (probeLogCount <= 3 || (id == null && noIdCount % 10 == 0)) {
            clog(
                "PHOTO probe id=$id area=${o.optInt("area")} next=$nextState " +
                    "strip=${strip.size}${strip.take(3)} thumbs=${thumbs.size}${thumbs.take(3)} " +
                    "counter='$counter' total='$total' cands=${o.optJSONArray("cands")}"
            )
        }

        if (id == null) {
            noIdCount++
            return
        }
        noIdCount = 0

        if (id == lastAccepted) {
            // 페이지 목록이 늦게 갱신되는 경우를 대비해 새 ID가 있으면 추가
            if (strip.isNotEmpty()) {
                val set = photoSet()
                var late = 0
                for (s in strip) if (set.add(s)) late++
                if (late > 0) clog("PHOTO 늦게 갱신된 목록에서 $late 개 추가 (누적 ${set.size})")
            }
            // 아직 같은 사진: 다음 버튼이 없거나 비활성이면 마지막 사진
            pendingId = null; pendingCount = 0
            if (nextState != "ok") {
                noNextCount++
                if (noNextCount >= 4) {
                    finishPhoto("다음 버튼이 없거나 비활성입니다 — 마지막 사진 (수집 ${photoSet().size}개)")
                }
            } else {
                noNextCount = 0
            }
            return
        }
        noNextCount = 0

        // 새 ID는 연속 2번 같은 값이 읽혀야 확정 (전환 애니메이션 중 오검출 방지)
        if (id == pendingId) pendingCount++ else { pendingId = id; pendingCount = 1 }
        if (pendingCount >= 2) acceptPhoto(id, strip, thumbs)
    }

    private fun acceptPhoto(id: String, strip: List<String>, thumbs: List<String>) {
        if (id in visited) {
            finishPhoto("이미 방문한 사진 ID($id)가 다시 나타났습니다 — 순회 종료")
            return
        }
        visited.add(id)
        val set = photoSet()
        val before = set.size

        val page: List<String>
        val source: String
        when {
            strip.isNotEmpty() -> { page = strip; source = "strip"; stripAccepts++ }
            thumbs.isNotEmpty() -> { page = thumbs; source = "thumbs"; thumbAccepts++ }
            else -> { page = emptyList(); source = "main-only"; mainOnlyAccepts++ }
        }
        // 큰 사진이 목록에 없으면 먼저 넣고, 목록은 화면에 나온 순서대로 추가
        if (id !in page) set.add(id)
        for (p in page) set.add(p)

        val added = set.size - before
        lastAccepted = id
        pendingId = null; pendingCount = 0
        retryStage = 0
        lastTriggerAt = SystemClock.uptimeMillis()
        clog("PHOTO 수집 #${visited.size} main=$id 출처=$source(${page.size}) 신규=$added 누적=${set.size}")
        refresh("포토 순회 중… ${visited.size}페이지/장 확인 (누적 ${set.size})")
        triggerNext(0)
    }

    /** strategy 0=버튼 click(), 1=마우스 이벤트, 2=오른쪽 방향키 */
    private fun triggerNext(strategy: Int) {
        triggerLogCount++
        web.evaluateJavascript(NEXT_JS.replace("__S__", strategy.toString())) { raw ->
            try {
                if (triggerLogCount <= 3 || strategy > 0) {
                    clog("PHOTO NEXT 전략=$strategy 결과=${decode(raw)}")
                }
            } catch (e: Exception) {
            }
        }
    }

    private fun finishPhoto(reason: String) {
        photoRunning = false
        pState = PState.IDLE
        inFlight = false
        handler.removeCallbacksAndMessages(null)
        photoBtn.text = "포토 순회"
        val set = MacroState.collected[Cat.PHOTO]
        val sample = set?.firstOrNull()?.let { Cat.PHOTO.detailUrl + it }
        clog(
            "PHOTO 종료: $reason 확인=${visited.size} 출처별(strip/thumbs/main-only)=" +
                "$stripAccepts/$thumbAccepts/$mainOnlyAccepts 누적=${set?.size ?: 0} 화면표시총수힌트='$lastTotalHint' 샘플URL=$sample"
        )
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