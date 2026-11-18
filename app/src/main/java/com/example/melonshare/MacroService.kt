package com.example.melonshare

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MacroService : AccessibilityService() {

    companion object {
        @Volatile var instance: MacroService? = null
        private const val TAG = "MelonMacro"
        private const val KAKAO = "com.kakao.talk"
        private val CONFIRM_LABELS = listOf("확인", "전송", "보내기")
        private const val TICK_MS = 350L
        private const val NO_WINDOW_TIMEOUT = 10_000L
        private const val PICK_TIMEOUT = 25_000L
        private const val CONFIRM_WAIT = 8_000L
        private const val SETTLE_MS = 1_500L
        private const val GAP_MS = 1_200L
    }

    private enum class Phase { IDLE, PICK, DIRECT, CONFIRM, GAP }

    private val h = Handler(Looper.getMainLooper())
    private var phase = Phase.IDLE
    private var launchAt = 0L
    private var phaseAt = 0L
    private var firstClickAt = 0L
    private var lastClickAt = 0L
    private var kakaoChangeAt = 0L
    private var memoSeenAt = 0L
    private var chatRoomSeenAt = 0L
    private var lockSeenAt = 0L
    private var clicks = 0
    private var typed = false

    // 시작 시점에 복사해 두는 대기열과 서비스 자체 위치값
    private var items: List<String> = emptyList()
    private var cursor = 0
    private val done = HashSet<Int>()

    private var startCalls = 0
    private var launchCalls = 0
    private var tickCount = 0
    private var kakaoEvents = 0
    private var contentEvents = 0
    private var openLogged = false
    private var lastHits = -1
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var logFile: File? = null

    private fun log(msg: String) {
        val line = "[${cursor + 1}/${items.size}] $msg"
        Log.d(TAG, line)
        try {
            logFile?.appendText("${timeFmt.format(Date())} $line\n")
        } catch (e: Exception) {
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!MacroState.running || phase == Phase.IDLE) return
            try {
                step()
            } catch (e: Exception) {
                log("EXCEPTION in step(): $e")
                stop("오류: ${e.message}")
                return
            }
            if (MacroState.running && phase != Phase.IDLE) h.postDelayed(this, TICK_MS)
        }
    }

    override fun onServiceConnected() {
        instance = this
        logFile = getExternalFilesDir(null)?.let { File(it, "melon_macro_log.txt") }
        Log.d(TAG, "서비스 연결됨 logFile=${logFile?.absolutePath}")
    }

    override fun onDestroy() { instance = null; super.onDestroy() }
    override fun onInterrupt() {}

    /** MainActivity의 시작 버튼에서 호출 */
    fun startNext() {
        // 이미 실행 중이면 무시하고, MainActivity가 덮어쓴 값을 되돌린다
        if (phase != Phase.IDLE) {
            MacroState.queue = items
            MacroState.index = cursor
            MacroState.running = true
            log("startNext() 무시: 이미 실행 중 (phase=$phase)")
            toast("이미 실행 중입니다")
            return
        }
        h.removeCallbacksAndMessages(null)
        try { logFile?.writeText("") } catch (e: Exception) {}
        startCalls++
        launchCalls = 0
        tickCount = 0
        kakaoEvents = 0
        contentEvents = 0
        items = MacroState.queue.toList()
        cursor = MacroState.index.coerceAtLeast(0)
        done.clear()
        log("startNext() 호출 #$startCalls running=${MacroState.running} room='${MacroState.roomName}'")
        log("대기열 크기=${items.size}, 시작 위치=${cursor + 1}")
        items.take(300).forEachIndexed { i, u -> log("  QUEUE #${i + 1} $u") }
        launchNext()
        if (MacroState.running && phase != Phase.IDLE) h.postDelayed(ticker, TICK_MS)
    }

    fun stop(msg: String) {
        log("STOP 원인='$msg' phase=$phase clicks=$clicks")
        MacroState.running = false
        phase = Phase.IDLE
        h.removeCallbacksAndMessages(null)
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.packageName?.toString() != KAKAO) return
        kakaoEvents++
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val now = SystemClock.uptimeMillis()
                kakaoChangeAt = now
                val cls = event.className?.toString() ?: ""
                if (cls.endsWith("MemoChatConnectActivity")) memoSeenAt = now
                if (cls.contains("ChatRoom")) chatRoomSeenAt = now
                if (cls.contains("lockscreen") || cls.contains("PatternLock")) lockSeenAt = now
                log("EVENT 카톡 WINDOW_STATE_CHANGED class=$cls (launch 후 ${now - launchAt}ms)")
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> contentEvents++
        }
    }

    // ---------- 흐름 ----------

    private fun launchNext() {
        launchCalls++
        log("launchNext() 호출 #$launchCalls running=${MacroState.running} phase=$phase")
        if (!MacroState.running) { phase = Phase.IDLE; return }
        if (cursor >= items.size) {
            stop("모든 링크 전송 완료 (${items.size}개)")
            return
        }
        if (cursor in done) {
            stop("내부 오류: 이미 처리한 링크로 되돌아갔습니다 (${cursor + 1}번째)")
            return
        }
        typed = false; clicks = 0; firstClickAt = 0L; lastClickAt = 0L
        openLogged = false; lastHits = -1
        val url = items[cursor]
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            setPackage(KAKAO)
            putExtra(Intent.EXTRA_TEXT, url)
            // CLEAR_TASK: 이전 카톡 화면이 쌓여 있지 않도록 작업 스택을 비우고 새로 연다
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        launchAt = SystemClock.uptimeMillis()
        phase = Phase.PICK
        log("ACTION_SEND 실행 시도 url=$url")
        toast("${cursor + 1}/${items.size} 카톡 실행")
        try {
            startActivity(intent)
            log("startActivity() 예외 없이 반환 (화면이 실제로 열렸는지는 이후 EVENT 로그로 확인)")
        } catch (e: Exception) {
            log("startActivity() 예외: $e")
            stop("카카오톡을 실행하지 못했습니다")
        }
    }

    private fun step() {
        val now = SystemClock.uptimeMillis()
        tickCount++
        if (tickCount % 3 == 0) {
            log(
                "TICK phase=$phase sinceLaunch=${now - launchAt}ms " +
                    "kakaoWindowOpened=${kakaoChangeAt > launchAt} memoSeen=${memoSeenAt > launchAt} " +
                    "kakaoEvents=$kakaoEvents(content=$contentEvents) " +
                    "activePkg=${rootInActiveWindow?.packageName} " +
                    "clicks=$clicks lastClickAgo=${if (lastClickAt == 0L) -1 else now - lastClickAt}ms"
            )
        }
        when (phase) {
            Phase.PICK -> stepPick(now)
            Phase.DIRECT -> stepDirect(now)
            Phase.CONFIRM -> stepConfirm(now)
            Phase.GAP -> if (now - phaseAt >= GAP_MS) {
                log("GAP 종료 → 다음 링크 launchNext() 호출")
                launchNext()
            }
            Phase.IDLE -> Unit
        }
    }

    private fun stepPick(now: Long) {
        if (lockSeenAt > launchAt) {
            log("PICK: 카톡 잠금 화면 감지")
            failStop("카카오톡 잠금 화면이 떠 있습니다. 잠금을 해제한 뒤 다시 시작하세요")
            return
        }
        // 공유 화면 없이 나와의 채팅으로 바로 전달되는 경로
        if (memoSeenAt > launchAt) {
            log("PICK: MemoChatConnectActivity 감지 → 직접 전달 경로(DIRECT)로 판단, 채팅방 선택/버튼 클릭 없이 진행")
            phase = Phase.DIRECT
            return
        }
        val opened = kakaoChangeAt > launchAt
        if (!opened) {
            if (now - launchAt > NO_WINDOW_TIMEOUT) {
                log("PICK: ${NO_WINDOW_TIMEOUT}ms 동안 카톡 새 화면 이벤트 없음")
                dumpScreen("no-window")
                failStop("카카오톡 공유 화면이 열리지 않았습니다(앱 실행이 차단됐을 수 있음)")
            }
            return
        }
        if (!openLogged) {
            openLogged = true
            log("PICK: 카톡 새 화면 감지 (launch 후 ${now - launchAt}ms)")
        }
        if (now - launchAt > PICK_TIMEOUT) {
            log("PICK: 채팅방 선택 시간 초과")
            dumpScreen("pick-timeout")
            failStop("'${MacroState.roomName}' 채팅방을 찾지 못했습니다")
            return
        }
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != KAKAO) return
        if (selectRoom(root)) {
            log("PICK: 채팅방 선택 클릭 성공 → CONFIRM 단계")
            toast("채팅방 선택됨")
            phase = Phase.CONFIRM
            phaseAt = now
        }
    }

    /** 직접 전달 경로: 전달 화면이 끝나고 카톡이 닫히거나 채팅방이 열리면 완료 */
    private fun stepDirect(now: Long) {
        if (lockSeenAt > launchAt) {
            log("DIRECT: 카톡 잠금 화면 감지")
            failStop("카카오톡 잠금 화면이 떠 있습니다. 잠금을 해제한 뒤 다시 시작하세요")
            return
        }
        val sinceMemo = now - memoSeenAt
        val activePkg = rootInActiveWindow?.packageName?.toString()
        val leftKakao = activePkg != null && activePkg != KAKAO
        val chatRoomOpened = chatRoomSeenAt > memoSeenAt
        if (sinceMemo > SETTLE_MS && (leftKakao || chatRoomOpened)) {
            complete("직접 전달 후 " + if (leftKakao) "카톡 화면 종료 확인" else "채팅방 열림 확인")
            return
        }
        if (sinceMemo > CONFIRM_WAIT) {
            log("DIRECT: ${sinceMemo}ms 지나도 완료 근거 없음 activePkg=$activePkg chatRoomOpened=$chatRoomOpened")
            dumpScreen("direct-stuck")
            failStop("나와의 채팅 전달 완료를 확인하지 못했습니다")
        }
    }

    private fun stepConfirm(now: Long) {
        val root = rootInActiveWindow?.takeIf { it.packageName?.toString() == KAKAO }
        val node = root?.let { findConfirm(it) }
        val sinceClick = now - lastClickAt

        if (node != null && clicks < 3 && (clicks == 0 || sinceClick in 600L..2500L)) {
            val label = node.text?.toString()
            if (clickUp(node)) {
                clicks++
                lastClickAt = now
                if (clicks == 1) firstClickAt = now
                log("CONFIRM: '$label' 버튼 클릭 성공 (clicks=$clicks)")
                return
            } else {
                log("CONFIRM: '$label' 버튼 발견했지만 클릭 실패")
            }
        }

        if (clicks == 0) {
            if (now - phaseAt > CONFIRM_WAIT) {
                log("CONFIRM: 버튼을 ${CONFIRM_WAIT}ms 동안 못 찾음 rootIsKakao=${root != null}")
                dumpScreen("confirm-not-found")
                failStop("확인/전송 버튼을 찾지 못했습니다")
            }
            return
        }

        val changed = kakaoChangeAt > firstClickAt
        if (node == null && sinceClick > SETTLE_MS && (changed || sinceClick > 4_000L)) {
            complete(if (changed) "화면전환 확인" else "버튼 소멸 4초 경과(보조 판정)")
            return
        }
        if (sinceClick > CONFIRM_WAIT) {
            log("CONFIRM: 클릭 후 ${sinceClick}ms 지나도 완료 판정 불가 buttonStillVisible=${node != null} changed=$changed")
            dumpScreen("confirm-stuck")
            failStop("전송 완료를 확인하지 못했습니다")
        }
    }

    /** 링크 하나당 정확히 한 번만 실행되도록 done 집합으로 보호 */
    private fun complete(basis: String) {
        if (!done.add(cursor)) {
            log("COMPLETE 중복 호출 무시 (이미 처리된 링크)")
            return
        }
        val finished = cursor + 1
        cursor++
        MacroState.index = cursor
        log("COMPLETE: ${finished}번째 링크 완료, 근거=$basis → 다음 위치=${cursor + 1} (GAP ${GAP_MS}ms 대기)")
        toast("$finished/${items.size} 전송 완료")
        phase = Phase.GAP
        phaseAt = SystemClock.uptimeMillis()
    }

    /** 같은 링크를 다시 띄우면 중복 전송되므로 재시도하지 않고 멈춘다 */
    private fun failStop(reason: String) {
        stop("$reason — ${cursor + 1}/${items.size}번째 링크에서 중단 (중복 방지를 위해 자동 재시도 안 함)")
    }

    // ---------- 화면 조작 (공유 화면 예비 경로) ----------

    private fun selectRoom(root: AccessibilityNodeInfo): Boolean {
        val hits = root.findAccessibilityNodeInfosByText(MacroState.roomName)
        val target = hits.firstOrNull { !it.isEditable }
        if (hits.size != lastHits) {
            lastHits = hits.size
            log("PICK: '${MacroState.roomName}' 검색 결과 hits=${hits.size} nonEditable=${target != null} typed=$typed")
        }
        if (target != null && clickUp(target)) return true

        if (!typed) {
            val edit = findEditable(root)
            if (edit != null) {
                val args = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        MacroState.roomName
                    )
                }
                edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                typed = true
                log("PICK: 검색창에 '${MacroState.roomName}' 입력함")
            }
        }
        return false
    }

    private fun findConfirm(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (label in CONFIRM_LABELS) {
            val n = root.findAccessibilityNodeInfosByText(label)
                .firstOrNull { it.text?.toString() == label && it.isVisibleToUser }
            if (n != null) return n
        }
        return null
    }

    private fun clickUp(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        while (n != null) {
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            n = n.parent
        }
        return false
    }

    private fun findEditable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (n.isEditable) return n
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            findEditable(c)?.let { return it }
        }
        return null
    }

    /** 개인정보 보호: 확인/전송 문구와 채팅방 이름 외의 글자는 길이만 기록 */
    private fun mask(s: String?): String {
        if (s.isNullOrEmpty()) return ""
        val keep = s in CONFIRM_LABELS ||
            (MacroState.roomName.isNotEmpty() && s.contains(MacroState.roomName))
        return if (keep) s else "(${s.length}자)"
    }

    private fun dumpScreen(tag: String) {
        val root = rootInActiveWindow
        if (root == null) {
            log("DUMP[$tag] rootInActiveWindow=null")
            return
        }
        log("DUMP[$tag] pkg=${root.packageName}")
        var count = 0
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (count >= 60) return
            val t = n.text?.toString()
            val d = n.contentDescription?.toString()
            if (!t.isNullOrEmpty() || !d.isNullOrEmpty()) {
                count++
                log(
                    "  ${"-".repeat(depth)} text='${mask(t)}' desc='${mask(d)}' " +
                        "cls=${n.className?.toString()?.substringAfterLast('.')} " +
                        "click=${n.isClickable} edit=${n.isEditable}"
                )
            }
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                walk(c, depth + 1)
            }
        }
        walk(root, 0)
    }

    private fun toast(s: String) =
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}