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