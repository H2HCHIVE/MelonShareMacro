
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
        @Volatile
        var instance: MacroService? = null

        private const val TAG = "MelonMacro"
        private const val KAKAO = "com.kakao.talk"

        private const val TICK_MS = 350L
        private const val NO_WINDOW_TIMEOUT = 6_000L
        private const val PICK_TIMEOUT = 25_000L
        private const val CONFIRM_WAIT = 10_000L
        private const val SETTLE_MS = 1_500L
        private const val GAP_MS = 2_000L
    }

    private enum class Phase {
        IDLE, PICK, CONFIRM, GAP
    }

    private val h = Handler(Looper.getMainLooper())

    private var phase = Phase.IDLE

    private var launchAt = 0L
    private var phaseAt = 0L
    private var firstClickAt = 0L
    private var kakaoChangeAt = 0L

    private var activeIndex = -1

    private var typed = false
    private var roomSelected = false
    private var transferClicked = false

    private var openLogged = false
    private var lastHits = -1
    private var tickCount = 0

    private val launchedIndexes = mutableSetOf<Int>()

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
        } catch (_: Exception) {
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!MacroState.running || phase == Phase.IDLE) return

            try {
                step()
            } catch (e: Exception) {
                log("EXCEPTION: $e")
                stop("오류가 발생했습니다: ${e.message}")
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

        Log.d(TAG, "서비스 연결됨")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    fun startNext() {
        if (MacroState.running && phase != Phase.IDLE) {
            log("중복 startNext 호출 무시")
            return
        }

        h.removeCallbacks(ticker)

        try {
            logFile?.writeText("")
        } catch (_: Exception) {
        }

        tickCount = 0
        launchedIndexes.clear()

        log(
            "시작: queue=${MacroState.queue.size}, " +
                "index=${MacroState.index}, " +
                "room=${MacroState.roomName}"
        )

        launchNext()

        if (MacroState.running && phase != Phase.IDLE) {
            h.postDelayed(ticker, TICK_MS)
        }
    }

    fun stop(msg: String) {
        log("STOP: $msg phase=$phase")

        MacroState.running = false
        phase = Phase.IDLE

        h.removeCallbacks(ticker)

        Toast.makeText(
            this,
            msg,
            Toast.LENGTH_LONG
        ).show()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.packageName?.toString() != KAKAO) return

        if (
            event.eventType ==
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            kakaoChangeAt = SystemClock.uptimeMillis()

            log(
                "카톡 화면 변경: ${event.className}, " +
                    "launch 후 ${kakaoChangeAt - launchAt}ms"
            )
        }
    }

    // --------------------------------------------------
    // 다음 링크 실행
    // --------------------------------------------------

    private fun launchNext() {
        if (!MacroState.running) {
            phase = Phase.IDLE
            return
        }

        if (MacroState.index >= MacroState.queue.size) {
            stop("모든 링크 처리 완료 (${MacroState.queue.size}개)")
            return
        }

        activeIndex = MacroState.index

        // 같은 링크의 ACTION_SEND 중복 실행 방지
        if (!launchedIndexes.add(activeIndex)) {
            stop(
                "${activeIndex + 1}번째 링크 중복 실행 감지. " +
                    "안전을 위해 중단합니다."
            )
            return
        }

        typed = false
        roomSelected = false
        transferClicked = false
        openLogged = false
        lastHits = -1

        firstClickAt = 0L
        kakaoChangeAt = 0L

        launchAt = SystemClock.uptimeMillis()
        phaseAt = launchAt
        phase = Phase.PICK

        val url = MacroState.queue[activeIndex]

        log("ACTION_SEND 시작: ${activeIndex + 1}번째 링크")
        log("URL=$url")

        toast(
            "${activeIndex + 1}/${MacroState.queue.size} 카톡 실행"
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            setPackage(KAKAO)
            putExtra(Intent.EXTRA_TEXT, url)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            startActivity(intent)
            log("ACTION_SEND 호출 완료")
        } catch (e: Exception) {
            log("ACTION_SEND 실패: $e")
            stop("카카오톡 공유 화면을 실행하지 못했습니다.")
        }
    }

    // --------------------------------------------------
    // 단계별 처리
    // --------------------------------------------------

    private fun step() {
        val now = SystemClock.uptimeMillis()

        tickCount++

        if (tickCount % 6 == 0) {
            log(
                "TICK phase=$phase " +
                    "index=$activeIndex " +
                    "roomSelected=$roomSelected " +
                    "transferClicked=$transferClicked " +
                    "activePkg=${rootInActiveWindow?.packageName}"
            )
        }

        when (phase) {
            Phase.PICK -> stepPick(now)
            Phase.CONFIRM -> stepConfirm(now)

            Phase.GAP -> {
                if (now - phaseAt >= GAP_MS) {
                    log("GAP 종료 → 다음 링크")
                    launchNext()
                }
            }

            Phase.IDLE -> Unit
        }
    }

    // --------------------------------------------------
    // PICK: 채팅방 선택 후 실제 공유 화면 대기
    // --------------------------------------------------

    private fun stepPick(now: Long) {
        if (kakaoChangeAt <= launchAt) {
            if (now - launchAt > NO_WINDOW_TIMEOUT) {
                dumpScreen("no-window")
                stop("카카오톡 공유 화면이 열리지 않았습니다.")
            }
            return
        }

        if (!openLogged) {
            openLogged = true
            log("카카오톡 화면 진입 감지")
        }

        val root = rootInActiveWindow ?: return

        if (root.packageName?.toString() != KAKAO) return

        // 채팅방 선택 여부가 아니라 실제 공유 화면을 확인한다.
        if (isShareReady(root)) {
            log("공유 화면 확인: 메시지 입력창 + 전달 버튼")

            phase = Phase.CONFIRM
            phaseAt = now

            toast("공유 화면 확인")
            return
        }

        if (now - launchAt > PICK_TIMEOUT) {
            dumpScreen("pick-timeout")
            stop(
                "'${MacroState.roomName}' 채팅방 선택 또는 " +
                    "공유 화면 진입을 확인하지 못했습니다."
            )
            return
        }

        selectRoom(root)
    }

    private fun selectRoom(root: AccessibilityNodeInfo) {
        if (roomSelected) return

        val hits = root.findAccessibilityNodeInfosByText(
            MacroState.roomName
        )

        val target = hits.firstOrNull {
            !it.isEditable && it.isVisibleToUser
        }

        if (hits.size != lastHits) {
            lastHits = hits.size

            log(
                "채팅방 검색: hits=${hits.size}, " +
                    "target=${target != null}, typed=$typed"
            )
        }

        if (target != null) {
            if (clickUp(target)) {
                roomSelected = true

                log(
                    "채팅방 선택 클릭 성공. " +
                        "실제 공유 화면이 나타날 때까지 PICK 유지"
                )

                toast("채팅방 선택")
            } else {
                log("채팅방 선택 클릭 실패")
            }

            return
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

                typed = edit.performAction(
                    AccessibilityNodeInfo.ACTION_SET_TEXT,
                    args
                )

                log("채팅방 검색어 입력 success=$typed")
            }
        }
    }

    // --------------------------------------------------
    // 실제 공유 화면 판별
    // --------------------------------------------------

    private fun isShareReady(
        root: AccessibilityNodeInfo
    ): Boolean {
        return hasMessageInput(root) &&
            findTransfer(root) != null
    }

    private fun hasMessageInput(
        node: AccessibilityNodeInfo
    ): Boolean {
        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()

        if (
            node.isEditable &&
            (
                text == "메시지 입력" ||
                    desc == "메시지 입력"
            )
        ) {
            return true
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue

            if (hasMessageInput(child)) {
                return true
            }
        }

        return false
    }

    // --------------------------------------------------
    // CONFIRM: 전달 버튼만 한 번 클릭
    // --------------------------------------------------

    private fun stepConfirm(now: Long) {
        val root = rootInActiveWindow?.takeIf {
            it.packageName?.toString() == KAKAO
        }

        if (root == null) {
            if (now - phaseAt > CONFIRM_WAIT) {
                dumpScreen("confirm-no-root")
                stop("카카오톡 공유 화면을 확인할 수 없어 중단했습니다.")
            }
            return
        }

        val transfer = findTransfer(root)

        if (!transferClicked) {
            if (transfer == null) {
                if (now - phaseAt > CONFIRM_WAIT) {
                    dumpScreen("transfer-not-found")
                    stop("전달 버튼을 찾지 못했습니다.")
                }
                return
            }

            if (clickUp(transfer)) {
                transferClicked = true
                firstClickAt = now

                log("전달 버튼 최초 1회 클릭 성공")
                toast("전달 버튼 클릭")
            } else {
                log("전달 버튼 클릭 실패")
            }

            return
        }

        // 이미 클릭했으므로 재클릭하지 않는다.
        val sinceClick = now - firstClickAt

        val transferStillVisible = findTransfer(root) != null
        val inputStillVisible = hasMessageInput(root)
        val screenChanged = kakaoChangeAt > firstClickAt

        logCompletionState(
            sinceClick,
            transferStillVisible,
            inputStillVisible,
            screenChanged
        )

        // 버튼이 사라지고, 카카오톡 화면 전환과
        // 메시지 입력창이 함께 확인된 경우에만 완료 처리한다.
        if (
            !transferStillVisible &&
            inputStillVisible &&
            screenChanged &&
            sinceClick >= SETTLE_MS
        ) {
            complete()
            return
        }

        if (sinceClick > CONFIRM_WAIT) {
            dumpScreen("confirm-stuck")

            stop(
                "전송 완료를 확인하지 못했습니다. " +
                    "중복 전송 방지를 위해 중단합니다. " +
                    "(${activeIndex + 1}/${MacroState.queue.size})"
            )
        }
    }

    private fun logCompletionState(
        elapsed: Long,
        transferVisible: Boolean,
        inputVisible: Boolean,
        changed: Boolean
    ) {
        if (elapsed % 2_000L < TICK_MS) {
            log(
                "전송 확인: elapsed=${elapsed}ms " +
                    "transfer=$transferVisible " +
                    "input=$inputVisible " +
                    "windowChanged=$changed"
            )
        }
    }

    private fun findTransfer(
        root: AccessibilityNodeInfo
    ): AccessibilityNodeInfo? {
        fun walk(
            node: AccessibilityNodeInfo
        ): AccessibilityNodeInfo? {
            val text = node.text?.toString()?.trim()
            val desc = node.contentDescription?.toString()?.trim()

            if (
                node.isVisibleToUser &&
                (text == "전달" || desc == "전달")
            ) {
                return node
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val found = walk(child)

                if (found != null) return found
            }

            return null
        }

        return walk(root)
    }

    // --------------------------------------------------
    // 완료 처리
    // --------------------------------------------------

    private fun complete() {
        // 다른 링크가 현재 index를 변경했다면 처리하지 않는다.
        if (MacroState.index != activeIndex) {
            stop("대기열 index가 변경되어 중단했습니다.")
            return
        }

        val done = activeIndex + 1

        MacroState.index++

        log(
            "COMPLETE: ${done}번째 링크 처리 완료 " +
                "→ 다음 index=${MacroState.index}"
        )

        toast(
            "${done}/${MacroState.queue.size} 처리 완료"
        )

        phase = Phase.GAP
        phaseAt = SystemClock.uptimeMillis()
    }

    // --------------------------------------------------
    // 노드 탐색 및 클릭
    // --------------------------------------------------

    private fun findEditable(
        node: AccessibilityNodeInfo
    ): AccessibilityNodeInfo? {
        if (node.isEditable) return node

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue

            findEditable(child)?.let {
                return it
            }
        }

        return null
    }

    private fun clickUp(
        node: AccessibilityNodeInfo
    ): Boolean {
        var current: AccessibilityNodeInfo? = node

        while (current != null) {
            if (current.isClickable) {
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
    // 화면 덤프
    // --------------------------------------------------

    private fun dumpScreen(tag: String) {
        val root = rootInActiveWindow

        if (root == null) {
            log("DUMP[$tag]: root=null")
            return
        }

        log("DUMP[$tag]: pkg=${root.packageName}")

        var count = 0

        fun walk(
            node: AccessibilityNodeInfo,
            depth: Int
        ) {
            if (count >= 100) return

            val text = node.text?.toString()
            val desc = node.contentDescription?.toString()

            if (
                !text.isNullOrEmpty() ||
                !desc.isNullOrEmpty()
            ) {
                count++

                log(
                    "${"-".repeat(depth)} " +
                        "text='${text ?: ""}' " +
                        "desc='${desc ?: ""}' " +
                        "class=${node.className?.toString()?.substringAfterLast('.')} " +
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

    private fun toast(message: String) {
        Toast.makeText(
            this,
            message,
            Toast.LENGTH_SHORT
        ).show()
    }
}
