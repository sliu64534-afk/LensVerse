package com.example.lutcamera

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import java.util.concurrent.Executor

class LutGlThread(
    name: String = "LUT-GL"
) : AutoCloseable {

    private val thread = HandlerThread(name).apply {
        start()
    }

    val handler = Handler(thread.looper)

    @Volatile
    private var closed = false

    /**
     * 关闭后静默丢弃任务。
     *
     * 销毁阶段 Compose 副作用与 GL 线程的退出顺序不保证，
     * 这里必须容忍迟到的提交，而不是抛异常。
     */
    val executor = Executor { command ->
        if (!closed) {
            handler.post(command)
        }
    }

    fun isCurrentThread(): Boolean {
        return Looper.myLooper() == thread.looper
    }

    fun checkCurrentThread() {
        check(isCurrentThread()) {
            "OpenGL 操作必须在 GL 线程执行"
        }
    }

    override fun close() {
        closed = true
        thread.quitSafely()
    }
}
