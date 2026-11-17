
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

        private val CONFIRM_LABELS =
            listOf("전달", "확인", "전송", "보내기")

        private const val TICK_MS = 350L
        private const val NO_WINDOW_TIMEOUT = 6_000L
        private const val PICK_TIMEOUT = 20_000L
        private const val CONFIRM_WAIT = 8_000L
        private const val SETTLE_MS = 1_500L
        private const val GAP_MS = 1_200L
        private const val MAX_RETRY = 2
    }

    private enum class Phase {
        IDLE, PICK, CONFIRM, GAP
    }

    private val h = Handler(Looper.getMainLooper())

    private var phase = Phase.IDLE
    private var launchAt = 0L
    private var phaseAt = 0L
    private var firstClickAt = 0L
    private var lastClickAt = 0L
    private var kakaoChangeAt = 0L

    private var clicks = 0
    private var typed = false
    private var attempt = 0

    private var lastClickedLabel: String? = null

    private var startCalls = 0
    private var launchCalls = 0
    private var tickCount = 0
    private var kakaoEvents = 0
    private var contentEvents = 0
    private var openLogged = false
    private var lastHits = -1

    private val timeFmt =
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private var logFile: File? = null

    private fun log(msg: String) {
        val line =
            "[${MacroState.index}/${MacroState.queue.size}] $msg"

        Log.d(TAG, line)

        try {
            logFile?.appendText(
                "${timeFmt.format(Date())} $line\n"
            )
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

            if (MacroState.running && phase != Phase.IDLE) {
                h.postDelayed(this, TICK_MS)
            }
        }
    }

    override fun onServiceConnected() {
        instance = this

        logFile = getExternalFilesDir(null)?.let {
            File(it, "melon_macro_log.txt")
        }

        Log.d(
            TAG,
            "서비스 연결됨 logFile=${logFile?.absolutePath}"
        )
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    fun startNext() {
        h.removeCallbacksAndMessages(null)

        try {
            logFile?.writeText("")
        } catch (e: Exception) {
        }

        startCalls++
        launchCalls = 0
        tickCount = 0
        kakaoEvents = 0
        contentEvents = 0
        attempt = 0

        log(
            "startNext() 호출 #$startCalls " +
                "running=${MacroState.running} " +
                "room='${MacroState.roomName}'"
        )

        log(
            "대기열 크기=${MacroState.queue.size}, " +
                "시작 index=${MacroState.index}"
        )

        MacroState.queue.take(300).forEachIndexed { i, u ->
            log("QUEUE #${i + 1} $u")
        }

        launchNext()

        if (MacroState.running && phase != Phase.IDLE) {
            h.postDelayed(ticker, TICK_MS)
        }
    }

    fun stop(msg: String) {
        log(
            "STOP 원인='$msg' " +
                "phase=$phase clicks=$clicks attempt=$attempt"
        )

        MacroState.running = false
        phase = Phase.IDLE

        h.removeCallbacksAndMessages(null)

        Toast.makeText(
            this,
            msg,
            Toast.LENGTH_LONG
        ).show()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        if (event.packageName?.toString() != KAKAO) return

        kakaoEvents++

        when (event.eventType) {

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                kakaoChangeAt = SystemClock.uptimeMillis()

                log(
                    "EVENT 카톡 WINDOW_STATE_CHANGED " +
                        "class=${event.className} " +
                        "(launch 후 ${kakaoChangeAt - launchAt}ms)"
                )
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                contentEvents++
            }
        }
    }

    private fun launchNext() {
        launchCalls++

        log(
            "launchNext() 호출 #$launchCalls " +
                "running=${MacroState.running} phase=$phase"
        )

        if (!MacroState.running) {
            phase = Phase.IDLE
            return
        }

        if (MacroState.index >= MacroState.queue.size) {
            stop("모든 링크 전송 완료 (${MacroState.queue.size}개)")
            return
        }

        typed = false
        clicks = 0
        firstClickAt = 0L
        lastClickAt = 0L
        lastClickedLabel = null

        openLogged = false
        lastHits = -1

        val url = MacroState.queue[MacroState.index]

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            setPackage(KAKAO)
            putExtra(Intent.EXTRA_TEXT, url)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        launchAt = SystemClock.uptimeMillis()
        phase = Phase.PICK

        log("ACTION_SEND 실행 시도 url=$url")

        toast(
            "${MacroState.index + 1}/${MacroState.queue.size} 카톡 실행"
        )

        try {
            startActivity(intent)

            log(
                "startActivity() 예외 없이 반환"
            )

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
                "TICK phase=$phase " +
                    "sinceLaunch=${now - launchAt}ms " +
                    "kakaoWindowOpened=${kakaoChangeAt > launchAt} " +
                    "kakaoEvents=$kakaoEvents(content=$contentEvents) " +
                    "activePkg=${rootInActiveWindow?.packageName} " +
                    "clicks=$clicks " +
                    "lastClickAgo=" +
                    "${if (lastClickAt == 0L) -1 else now - lastClickAt}ms"
            )
        }

        when (phase) {

            Phase.PICK -> stepPick(now)

            Phase.CONFIRM -> stepConfirm(now)

            Phase.GAP -> {
                if (now - phaseAt >= GAP_MS) {
                    log("GAP 종료 → 다음 곡 launchNext() 호출")
                    launchNext()
                }
            }

            Phase.IDLE -> Unit
        }
    }

    // --------------------------------------------------
    // PICK: 카카오톡 채팅방 진입 감지
    // --------------------------------------------------

    private fun stepPick(now: Long) {

        val opened = kakaoChangeAt > launchAt

        if (!opened) {

            if (now - launchAt > NO_WINDOW_TIMEOUT) {
                log(
                    "PICK: ${NO_WINDOW_TIMEOUT}ms 동안 " +
                        "카톡 새 화면 이벤트 없음"
                )

                dumpScreen("no-window")

                retryOrStop(
                    "카카오톡 공유 화면이 열리지 않았습니다"
                )
            }

            return
        }

        if (!openLogged) {
            openLogged = true

            log(
                "PICK: 카톡 새 화면 감지 " +
                    "(launch 후 ${now - launchAt}ms)"
            )
        }

        val root = rootInActiveWindow ?: return

        if (root.packageName?.toString() != KAKAO) return

        // 핵심 수정:
        // 검색 결과가 아니라 실제 공유 화면에 진입했는지 확인한다.
        if (isShareReady(root)) {

            log(
                "PICK: 메시지 입력창 + 전달 버튼 감지 " +
                    "→ 채팅방 진입 확인"
            )

            toast("채팅방 진입 확인")

            phase = Phase.CONFIRM
            phaseAt = now

            return
        }

        if (now - launchAt > PICK_TIMEOUT) {

            log("PICK: 채팅방 선택 시간 초과")

            dumpScreen("pick-timeout")

            retryOrStop(
                "'${MacroState.roomName}' 채팅방을 찾지 못했습니다"
            )

            return
        }

        if (selectRoom(root)) {

            log(
                "PICK: 채팅방 선택 클릭 성공 → CONFIRM 단계"
            )

            toast("채팅방 선택됨")

            phase = Phase.CONFIRM
            phaseAt = now
        }
    }

    // --------------------------------------------------
    // 공유 화면 판별
    // --------------------------------------------------

    private fun isShareReady(
        root: AccessibilityNodeInfo
    ): Boolean {

        var hasMessageInput = false
        var hasTransferButton = false

        fun walk(node: AccessibilityNodeInfo) {

            val text = node.text?.toString()?.trim()
            val desc = node.contentDescription?.toString()?.trim()

            if (
                node.isEditable &&
                (
                    text == "메시지 입력" ||
                    desc == "메시지 입력"
                )
            ) {
                hasMessageInput = true
            }

            if (
                node.isVisibleToUser &&
                (
                    text == "전달" ||
                    desc == "전달"
                )
            ) {
                hasTransferButton = true
            }

            if (!hasMessageInput || !hasTransferButton) {

                for (i in 0 until node.childCount) {

                    val child = node.getChild(i) ?: continue

                    walk(child)

                    if (hasMessageInput && hasTransferButton) {
                        break
                    }
                }
            }
        }

        walk(root)

        return hasMessageInput && hasTransferButton
    }

    // --------------------------------------------------
    // CONFIRM: 실제 전달 버튼 클릭 및 완료 확인
    // --------------------------------------------------

    private fun stepConfirm(now: Long) {

        val root = rootInActiveWindow?.takeIf {
            it.packageName?.toString() == KAKAO
        }

        val node = root?.let {
            findConfirm(it)
        }

        val sinceClick = now - lastClickAt

        val label = node?.let {
            it.text?.toString()?.trim()
                ?: it.contentDescription?.toString()?.trim()
        }

        // 같은 버튼을 반복 클릭하지 않는다.
        // 버튼 문구가 바뀌면 다음 확인 단계로 진행할 수 있다.
        if (
            node != null &&
            clicks < 3 &&
            label != lastClickedLabel &&
            (
                clicks == 0 ||
                sinceClick >= 600L
            )
        ) {

            if (clickUp(node)) {

                clicks++

                lastClickAt = now

                if (clicks == 1) {
                    firstClickAt = now
                }

                lastClickedLabel = label

                log(
                    "CONFIRM: '$label' 버튼 클릭 성공 " +
                        "(clicks=$clicks)"
                )

                toast("'$label' 클릭")

                return

            } else {

                log(
                    "CONFIRM: '$label' 버튼 발견했지만 " +
                        "클릭 실패"
                )
            }
        }

        if (clicks == 0) {

            if (now - phaseAt > CONFIRM_WAIT) {

                log(
                    "CONFIRM: 버튼을 ${CONFIRM_WAIT}ms 동안 " +
                        "못 찾음 rootIsKakao=${root != null}"
                )

                dumpScreen("confirm-not-found")

                retryOrStop(
                    "전달 버튼을 찾지 못했습니다"
                )
            }

            return
        }

        val changed = kakaoChangeAt > firstClickAt

        // 전달 버튼이 사라지고 카카오톡 화면이 유지되는지 확인.
        if (
            node == null &&
            root != null &&
            sinceClick > SETTLE_MS &&
            (
                changed ||
                sinceClick > 4_000L
            )
        ) {

            log(
                "CONFIRM: 버튼 사라짐 확인 " +
                    "화면전환=$changed " +
                    "마지막 클릭 후 ${sinceClick}ms"
            )

            complete()

            return
        }

        if (sinceClick > CONFIRM_WAIT) {

            log(
                "CONFIRM: 클릭 후 ${sinceClick}ms 지나도 " +
                    "완료 판정 불가 " +
                    "buttonStillVisible=${node != null} " +
                    "changed=$changed"
            )

            dumpScreen("confirm-stuck")

            stop(
                "전송 완료를 확인하지 못해 중단합니다(중복 방지) " +
                    "— ${MacroState.index + 1}/${MacroState.queue.size}번째 링크"
            )
        }
    }

    // --------------------------------------------------
    // 전송 완료 및 다음 곡
    // --------------------------------------------------

    private fun complete() {

        val done = MacroState.index + 1

        MacroState.index++

        log(
            "COMPLETE: ${done}번째 링크 전송 완료 처리 " +
                "→ index=${MacroState.index}, " +
                "다음 곡으로 넘어감"
        )

        toast(
            "${MacroState.index}/${MacroState.queue.size} 전송 완료"
        )

        attempt = 0

        phase = Phase.GAP
        phaseAt = SystemClock.uptimeMillis()
    }

    private fun retryOrStop(reason: String) {

        if (clicks == 0 && attempt < MAX_RETRY) {

            attempt++

            log(
                "RETRY #$attempt 사유='$reason'"
            )

            launchNext()

        } else {

            stop(
                "$reason — " +
                    "${MacroState.index + 1}/${MacroState.queue.size}번째 링크에서 중단"
            )
        }
    }

    // --------------------------------------------------
    // 채팅방 검색 및 선택
    // --------------------------------------------------

    private fun selectRoom(
        root: AccessibilityNodeInfo
    ): Boolean {

        val hits =
            root.findAccessibilityNodeInfosByText(
                MacroState.roomName
            )

        val target = hits.firstOrNull {
            !it.isEditable
        }

        if (hits.size != lastHits) {

            lastHits = hits.size

            log(
                "PICK: '${MacroState.roomName}' " +
                    "검색 결과 hits=${hits.size} " +
                    "nonEditable=${target != null} typed=$typed"
            )
        }

        if (target != null && clickUp(target)) {

            log(
                "PICK: 검색 결과 클릭 성공"
            )

            return true
        }

        if (!typed) {

            val edit = findEditable(root)

            if (edit != null) {

                val args = Bundle().apply {

                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        MacroState.roomName
                    )
                }

                val success = edit.performAction(
                    AccessibilityNodeInfo.ACTION_SET_TEXT,
                    args
                )

                typed = success

                log(
                    "PICK: 검색창 입력 결과 success=$success"
                )
            }
        }

        return false
    }

    // --------------------------------------------------
    // 전달 / 확인 버튼 탐색
    // --------------------------------------------------

    private fun findConfirm(
        root: AccessibilityNodeInfo
    ): AccessibilityNodeInfo? {

        for (label in CONFIRM_LABELS) {

            fun walk(
                node: AccessibilityNodeInfo
            ): AccessibilityNodeInfo? {

                val text =
                    node.text?.toString()?.trim()

                val desc =
                    node.contentDescription?.toString()?.trim()

                if (
                    node.isVisibleToUser &&
                    (
                        text == label ||
                        desc == label
                    )
                ) {
                    return node
                }

                for (i in 0 until node.childCount) {

                    val child = node.getChild(i) ?: continue

                    val found = walk(child)

                    if (found != null) {
                        return found
                    }
                }

                return null
            }

            val found = walk(root)

            if (found != null) {
                return found
            }
        }

        return null
    }

    // --------------------------------------------------
    // 클릭 가능한 부모 노드까지 탐색
    // --------------------------------------------------

    private fun clickUp(
        node: AccessibilityNodeInfo
    ): Boolean {

        var current: AccessibilityNodeInfo? = node

        while (current != null) {

            if (
                current.isVisibleToUser &&
                current.isClickable
            ) {

                if (
                    current.performAction(
                        AccessibilityNodeInfo.ACTION_CLICK
                    )
                ) {
                    return true
                }
            }

            current = current.parent
        }

        return false
    }

    // --------------------------------------------------
    // 입력창 탐색
    // --------------------------------------------------

    private fun findEditable(
        node: AccessibilityNodeInfo
    ): AccessibilityNodeInfo? {

        if (node.isEditable) {
            return node
        }

        for (i in 0 until node.childCount) {

            val child = node.getChild(i) ?: continue

            findEditable(child)?.let {
                return it
            }
        }

        return null
    }

    // --------------------------------------------------
    // 화면 덤프
    // --------------------------------------------------

    private fun dumpScreen(tag: String) {

        val root = rootInActiveWindow

        if (root == null) {

            log("DUMP[$tag] rootInActiveWindow=null")

            return
        }

        log("DUMP[$tag] pkg=${root.packageName}")

        var count = 0

        fun walk(
            node: AccessibilityNodeInfo,
            depth: Int
        ) {

            if (count >= 80) return

            val text = node.text?.toString()
            val desc = node.contentDescription?.toString()

            if (
                !text.isNullOrEmpty() ||
                !desc.isNullOrEmpty()
            ) {

                count++

                log(
                    "  ${"-".repeat(depth)} " +
                        "text='${text ?: ""}' " +
                        "desc='${desc ?: ""}' " +
                        "cls=${node.className?.toString()?.substringAfterLast('.')} " +
                        "click=${node.isClickable} " +
                        "edit=${node.isEditable} " +
                        "visible=${node.isVisibleToUser}"
                )
            }

            for (i in 0 until node.childCount) {

                val child = node.getChild(i) ?: continue

                walk(child, depth + 1)
            }
        }

        walk(root, 0)
    }

    private fun toast(s: String) {

        Toast.makeText(
            this,
            s,
            Toast.LENGTH_SHORT
        ).show()
    }
}
