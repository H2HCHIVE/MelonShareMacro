
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

    // 현재 실행에서 방문한 사진 ID
    private val photoVisited = LinkedHashSet<String>()

    // 상세 페이지의 다음 사진 버튼
    private val NEXT_SELS = """['.btn_photo_next']"""

    private val DIAG_SELS =
        """['.btn_photo_next','button[data-action]','[title*="이전"]','[title*="다음"]']"""

    private val FIRST_PHOTO_JS = """
(function(){
  function vis(e){
    return e && (e.offsetParent !== null || e.getClientRects().length > 0);
  }

  var target = null;
  var info = '';

  var sels = [
    'a[href*="photo_detail"]',
    '[onclick*="photo_detail"]',
    'a[href*="photoId"]',
    '[data-photo-id]',
    '[data-photoid]'
  ];

  for(var i=0; i<sels.length && !target; i++){
    var els = document.querySelectorAll(sels[i]);

    for(var j=0; j<els.length; j++){
      if(vis(els[j])){
        target = els[j];
        info = sels[i];
        break;
      }
    }
  }

  var bestN = 0;
  var groups = [];

  if(!target){
    var best = null;

    document.querySelectorAll('ul,ol').forEach(function(ul){
      var lis = ul.querySelectorAll(':scope > li');
      var n = 0;

      for(var k=0; k<lis.length; k++){
        if(lis[k].querySelector('img')) n++;
      }

      if(n > 0){
        groups.push(
          n + 'x ' + String(ul.getAttribute('class') || ul.id || ul.tagName)
        );
      }

      if(n > bestN){
        bestN = n;
        best = ul;
      }
    });

    if(best && bestN >= 4){
      var lis2 = best.querySelectorAll(':scope > li');

      for(var m=0; m<lis2.length; m++){
        if(lis2[m].querySelector('img') && vis(lis2[m])){
          target =
            lis2[m].querySelector('a,button,[onclick]') ||
            lis2[m].querySelector('img');

          info = 'ul-grid(' + bestN + ')';
          break;
        }
      }
    }
  }

  if(!target){
    return 'none: groups=' + groups.slice(0,6).join(' | ');
  }

  var t = target.closest('a,button,[onclick]') || target;
  t.click();

  return 'clicked[' + info + ']: ' +
    String(t.outerHTML || '').substring(0,180) +
    ' || groups=' + groups.slice(0,6).join(' | ');
})()
"""

    // 오른쪽 화살표를 한 번만 클릭
    private val NEXT_JS = """
(function(){
  function vis(e){
    return e && (
      e.offsetParent !== null ||
      e.getClientRects().length > 0
    );
  }

  var e = document.querySelector('.btn_photo_next');

  if(!e){
    return 'no-next-button';
  }

  if(!vis(e)){
    return 'next-button-hidden';
  }

  var cls = String(e.className || '');

  if(
    e.disabled ||
    e.getAttribute('aria-disabled') === 'true' ||
    cls.indexOf('disabled') >= 0
  ){
    return 'next-button-disabled: ' +
      String(e.outerHTML || '').substring(0,200);
  }

  e.click();

  return 'clicked: ' +
    String(e.outerHTML || '').substring(0,200);
})()
"""

    private val DIAG_JS = """
(function(){
  var sels = __SELS__;
  var out = [];

  sels.forEach(function(s){
    var els = document.querySelectorAll(s);

    for(var i=0; i<els.length && out.length<16; i++){
      var e = els[i];

      out.push(
        s + ' => ' + e.tagName +
        ' class=' + String(e.getAttribute('class') || '') +
        ' title=' + String(e.getAttribute('title') || '') +
        ' action=' + String(e.getAttribute('data-action') || '') +
        ' disabled=' + String(e.disabled) +
        ' vis=' + (e.offsetParent !== null)
      );
    }
  });

  return out.length ? out.join(' | ') : 'no-candidates';
})()
"""

    private val PROBE_JS = """
(function(){
  var best = null;
  var area = 0;

  document.querySelectorAll('img').forEach(function(i){
    var a = (i.naturalWidth || 0) * (i.naturalHeight || 0);

    if(a > area && i.offsetParent !== null){
      area = a;
      best = i;
    }
  });

  var cnt = '';

  document.querySelectorAll('span,em,strong,p,div').forEach(function(e){
    if(!cnt && e.children.length === 0){
      var t = (e.textContent || '').trim();

      if(/^\d+\s*\/\s*\d+$/.test(t)){
        cnt = t;
      }
    }
  });

  return 'href=' + location.href +
    ' | mainImg=' + (best ? String(best.src).substring(0,180) : 'none') +
    ' | counter=' + cnt;
})()
"""

    private val PAGER_JS = """
(function(){
  var out = [];

  document.querySelectorAll(
    'a[href*="sendPage"],a[href*="pageObj"],.page_num a,.paginate a,.pageNum a,[class*="page"] a'
  ).forEach(function(a){
    if(out.length < 14){
      out.push(
        String(a.textContent || '').trim().substring(0,10) +
        '=>' + String(a.getAttribute('href') || '').substring(0,60)
      );
    }
  });

  return out.length ? out.join(' | ') : 'no-pager';
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

        fun btn(t: String, f: () -> Unit) =
            Button(this).apply {
                text = t
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
                setOnClickListener { f() }
            }

        fun row(vararg b: Button) =
            LinearLayout(this).apply {
                b.forEach { addView(it) }
            }

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
                override fun onPageStarted(
                    view: WebView?,
                    url: String?,
                    favicon: Bitmap?
                ) {
                    pageReady = false
                }

                override fun onPageFinished(
                    view: WebView?,
                    url: String?
                ) {
                    pageReady = true
                }
            }

            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val tabs = row(
            *Cat.values().map { c ->
                btn(c.label) { openTab(c) }
            }.toTypedArray()
        )

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
            btn("접근성 설정") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
            btn("로그 공유") {
                shareFile(
                    "melon_macro_log.txt",
                    "로그가 없습니다. 접근성 서비스를 켜고 '시작'을 한 번 실행한 뒤 다시 눌러주세요"
                )
            },
            btn("수집 로그") {
                shareFile(
                    "melon_collect_log.txt",
                    "수집 로그가 없습니다. 수집을 한 번 실행한 뒤 다시 눌러주세요"
                )
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
                MacroService.instance?.stop("중지됨")
                    ?: run { MacroState.running = false }
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

    /** 탭 전환 */
    private fun openTab(c: Cat) {
        if (photoRunning) finishPhoto("탭 전환으로 중단")

        current = c
        pageReady = false
        web.stopLoading()
        web.settings.userAgentString =
            if (c.desktop) MacroState.UA_DESKTOP else MacroState.UA_MOBILE

        web.loadUrl(c.listUrl)

        clog("TAB ${c.label} desktop=${c.desktop} url=${c.listUrl}")
        refresh("${c.label} 로딩 중…")
    }

    // ================= 목록 HTML 수집 =================

    private fun collect() {
        val cat = current
        val url = web.url ?: ""

        if (!pageReady && web.progress < 100) {
            toast("페이지 로딩 중입니다. 잠시 후 다시 누르세요")
            return
        }

        if (cat == Cat.PHOTO && !url.contains("/artist/photo.htm")) {
            toast("포토 '목록' 페이지에서 눌러주세요 (상세 페이지에서는 '포토 순회')")
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

    private fun collectAttempt(
        cat: Cat,
        attempt: Int,
        acc: LinkedHashSet<String>
    ) {
        val maxAttempts = if (cat == Cat.MV) 10 else 6
        val scroll =
            if (cat == Cat.MV) "window.scrollTo(0,document.body.scrollHeight);" else ""

        val js =
            "(function(){" + scroll +
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
                val s = r.findAll(html)
                    .map { it.groupValues[1] }
                    .toSet()

                found.addAll(s)
                s.size
            }

            val before = acc.size
            acc.addAll(found)
            val grew = acc.size > before

            if (attempt < maxAttempts && (acc.isEmpty() || grew)) {
                refresh("${cat.label} 읽는 중… ${acc.size}개 (시도 $attempt)")
                web.postDelayed(
                    { collectAttempt(cat, attempt + 1, acc) },
                    800
                )
                return@evaluateJavascript
            }

            val set = MacroState.collected.getOrPut(cat) {
                LinkedHashSet()
            }

            val setBefore = set.size
            set.addAll(acc)
            val added = set.size - setBefore

            val hints = Regex("(?:전체|총)\\s*([0-9,]{1,6})")
                .findAll(text)
                .take(5)
                .map { it.value.replace("\n", " ") }
                .toList()

            val thumbs =
                if (cat == Cat.PHOTO) {
                    " thumbs=" + Regex("class=\"thumb\"").findAll(html).count()
                } else {
                    ""
                }

            clog(
                "COLLECT ${cat.label} url=${web.url} 시도=$attempt " +
                "htmlLen=${html.length} textLen=${text.length} " +
                "화면표시개수힌트=$hints 패턴별(마지막읽기)=$perPattern " +
                "이번수집=${acc.size} 신규=$added 누적=${set.size}$thumbs"
            )

            clog("  ID 샘플: ${acc.take(5)}")

            val samples = Regex(
                "(?:onclick|href|data-[\\w-]+)=[\"']([^\"']*(?:mv|Mv|MV|video|Video|photo|Photo)[^\"']*)[\"']"
            )
                .findAll(html)
                .map { it.groupValues[1].take(150) }
                .distinct()
                .take(12)
                .toList()

            if (cat == Cat.MV || acc.isEmpty()) {
                samples.forEach { clog("  후보속성: $it") }
            }

            if (cat == Cat.PHOTO) {
                web.evaluateJavascript(PAGER_JS) { r ->
                    clog("  PAGER: ${decode(r)}")
                }
            }

            refresh("${cat.label} ${added}개 추가 (이번 ${acc.size}개 발견)")
        }
    }

    // ================= 포토 순회 =================

    private fun decode(raw: String?): String =
        try {
            JSONTokener(raw ?: "null").nextValue() as? String ?: ""
        } catch (e: Exception) {
            raw ?: ""
        }

    private fun readHref(cb: (String) -> Unit) {
        web.evaluateJavascript(
            "(function(){return location.href;})()"
        ) { raw ->
            val h = decode(raw)
            cb(if (h.isNotEmpty()) h else (web.url ?: ""))
        }
    }

    // 상세 화면에서 가장 큰 사진의 파일명으로 ID 추출
    private fun readPhotoImageId(cb: (String?) -> Unit) {
        val js = """
        (function(){
          var candidates = [];

          document.querySelectorAll('img').forEach(function(img){
            if(img.offsetParent === null) return;

            var src = img.currentSrc || img.src || '';

            if(!/\/\d{6,}_\d{8,}[^/]*\.(jpg|jpeg|png|webp)/i.test(src)){
              return;
            }

            var area =
              (img.naturalWidth || 0) *
              (img.naturalHeight || 0);

            candidates.push({
              src: src,
              area: area,
              width: img.naturalWidth || 0,
              height: img.naturalHeight || 0
            });
          });

          candidates.sort(function(a,b){
            return b.area - a.area;
          });

          return candidates.length ? candidates[0].src : '';
        })()
        """

        web.evaluateJavascript(js) { raw ->
            val src = decode(raw)

            val id = Regex(
                "/(\\d{6,})_\\d{8,}[^/]*\\.(?:jpg|jpeg|png|webp)",
                RegexOption.IGNORE_CASE
            ).find(src)?.groupValues?.get(1)

            cb(id)
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
        photoLastId = null
        photoSame = 0
        photoNoId = 0
        photoSteps = 0
        photoNew = 0
        photoVisited.clear()

        photoBtn.text = "순회 중지"

        val url = web.url ?: ""
        clog("PHOTO 순회 시작 url=$url")

        when {
            url.contains("photo_detail") -> {
                diagNext()
                handler.postDelayed({ photoStep() }, 500)
            }

            url.contains("/artist/photo.htm") -> {
                clickFirstPhoto()
            }

            else -> {
                finishPhoto("포토 목록/상세 페이지가 아닙니다 (url=$url)")
            }
        }
    }

    private fun clickFirstPhoto() {
        web.evaluateJavascript(FIRST_PHOTO_JS) { raw ->
            val r = decode(raw)
            clog("PHOTO 첫 사진 클릭 결과: $r")

            if (!photoRunning) return@evaluateJavascript

            if (r.startsWith("none")) {
                finishPhoto(
                    "목록에서 첫 사진 요소를 찾지 못했습니다. " +
                    "첫 사진을 직접 눌러 상세로 들어간 뒤 다시 눌러주세요"
                )
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
                handler.postDelayed({ photoStep() }, 500)
            } else if (tries >= 12) {
                finishPhoto("상세 페이지로 이동하지 않았습니다 (href=$href)")
            } else {
                handler.postDelayed(
                    { waitDetail(tries + 1) },
                    700
                )
            }
        }
    }

    // 현재 사진 ID를 수집하고 다음 사진으로 이동
    private fun photoStep() {
        if (!photoRunning) return

        readPhotoImageId { id ->
            if (!photoRunning) return@readPhotoImageId

            if (id == null) {
                photoNoId++

                clog(
                    "PHOTO 이미지 ID 읽기 실패 " +
                    "(연속 $photoNoId)"
                )

                if (photoNoId >= 10) {
                    finishPhoto("큰 사진의 이미지 ID를 읽지 못했습니다")
                } else {
                    handler.postDelayed({ photoStep() }, 700)
                }

                return@readPhotoImageId
            }

            photoNoId = 0

            // 이미 방문한 사진인지 확인
            if (id in photoVisited) {
                if (id == photoLastId) {
                    photoSame++
                    clog(
                        "PHOTO 같은 이미지 확인 id=$id " +
                        "연속=$photoSame"
                    )
                } else {
                    finishPhoto("이미 방문한 사진으로 돌아왔습니다: $id")
                }
                return@readPhotoImageId
            }

            photoVisited.add(id)

            val set = MacroState.collected.getOrPut(Cat.PHOTO) {
                LinkedHashSet()
            }

            val isNew = set.add(id)

            if (isNew) {
                photoNew++
            }

            photoLastId = id
            photoSame = 0
            photoSteps++

            clog(
                "PHOTO 수집 step=$photoSteps " +
                "${if (isNew) "신규" else "기존 누적 ID"} " +
                "imageId=$id 방문=${photoVisited.size} " +
                "신규=$photoNew 누적=${set.size}"
            )

            refresh(
                "포토 순회 중… 이번 실행 ${photoVisited.size}장 " +
                "(신규 $photoNew / 누적 ${set.size})"
            )

            // 현재 사진을 저장한 후 다음 사진으로 이동
            clickNextAndWait(id)
        }
    }

    // 다음 버튼을 한 번 누르고 실제 이미지 변경을 기다림
    private fun clickNextAndWait(currentId: String) {
        if (!photoRunning) return

        web.evaluateJavascript(NEXT_JS) { raw ->
            if (!photoRunning) return@evaluateJavascript

            val result = decode(raw)

            clog("PHOTO NEXT 클릭 id=$currentId 결과=$result")

            when {
                result == "no-next-button" -> {
                    finishPhoto("다음 사진 버튼을 찾지 못했습니다")
                }

                result == "next-button-hidden" -> {
                    finishPhoto("다음 사진 버튼이 화면에 표시되지 않습니다")
                }

                result.startsWith("next-button-disabled") -> {
                    finishPhoto("마지막 사진에 도달했습니다")
                }

                result.startsWith("clicked") -> {
                    waitPhotoChanged(currentId, 0)
                }

                else -> {
                    finishPhoto("다음 사진 버튼 실행 실패: $result")
                }
            }
        }
    }

    // 사진 ID가 실제로 바뀌었는지 확인
    private fun waitPhotoChanged(oldId: String, attempt: Int) {
        if (!photoRunning) return

        readPhotoImageId { newId ->
            if (!photoRunning) return@readPhotoImageId

            if (newId != null && newId != oldId) {
                clog(
                    "PHOTO 변경 확인 $oldId -> $newId " +
                    "(대기 ${attempt}회)"
                )

                handler.postDelayed(
                    { photoStep() },
                    250
                )

                return@readPhotoImageId
            }

            // 최대 12초 동안 사진 변경을 기다림
            if (attempt >= 30) {
                clog(
                    "PHOTO 변경 시간 초과 " +
                    "기존=$oldId 현재=${newId ?: "ID 없음"}"
                )

                finishPhoto(
                    "다음 사진으로 이동하지 않았습니다. " +
                    "마지막 사진이거나 다음 버튼 동작을 확인해야 합니다"
                )
                return@readPhotoImageId
            }

            if (attempt % 5 == 0) {
                clog(
                    "PHOTO 변경 대기 기존=$oldId " +
                    "현재=${newId ?: "ID 없음"} " +
                    "대기=${attempt * 400}ms"
                )
            }

            handler.postDelayed(
                { waitPhotoChanged(oldId, attempt + 1) },
                400
            )
        }
    }

    private fun diagNext() {
        web.evaluateJavascript(
            DIAG_JS.replace("__SELS__", DIAG_SELS)
        ) { raw ->
            clog("PHOTO 버튼 후보: ${decode(raw)}")
        }

        web.evaluateJavascript(PROBE_JS) { raw ->
            clog("PHOTO 초기 상태: ${decode(raw)}")
        }
    }

    private fun finishPhoto(reason: String) {
        photoRunning = false

        handler.removeCallbacksAndMessages(null)

        photoBtn.text = "포토 순회"

        val set = MacroState.collected[Cat.PHOTO]
        val sample = set?.firstOrNull()?.let {
            Cat.PHOTO.detailUrl + it
        }

        clog(
            "PHOTO 종료: $reason " +
            "신규=$photoNew " +
            "이번방문=${photoVisited.size} " +
            "누적=${set?.size ?: 0} " +
            "샘플URL=$sample"
        )

        refresh("포토 순회 종료: $reason")
        toast("포토 ${set?.size ?: 0}개 — $reason")
    }

    // ================= 공통 =================

    private fun clog(msg: String) {
        Log.d("MelonCollect", msg)

        try {
            File(
                getExternalFilesDir(null),
                "melon_collect_log.txt"
            ).appendText(
                "${logTime.format(Date())} $msg\n"
            )
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
            val uri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                f
            )

            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, name)
                clipData = ClipData.newRawUri("log", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(
                Intent.createChooser(send, "로그 공유")
            )
        } catch (e: Exception) {
            toast("로그 공유 실패: ${e.message}")
        }
    }

    private fun refresh(msg: String = "") {
        val c = Cat.values().joinToString(" · ") {
            "${it.label} ${MacroState.collected[it]?.size ?: 0}"
        }

        status.text =
            (if (msg.isNotEmpty()) "$msg\n" else "") +
            "수집: $c"
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }
}
