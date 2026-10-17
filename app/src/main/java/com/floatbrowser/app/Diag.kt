package com.floatbrowser.app

import kotlinx.coroutines.CoroutineExceptionHandler
import java.io.File

/** Registra erros internos e traduz os motivos pelos quais o Android encerrou o app. */
object CrashLog {
    private val f get() = File(AppState.app.filesDir, "crash.txt")

    fun install() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e -> write(e); prev?.uncaughtException(t, e) }
    }

    fun write(e: Throwable) {
        runCatching { f.writeText("${System.currentTimeMillis()}\n" + e.stackTraceToString().take(3000)) }
    }

    fun read(): String = runCatching { if (f.exists()) f.readText() else "" }.getOrDefault("")

    /** Exceções em corrotinas viram registro em vez de derrubar o app. */
    val handler = CoroutineExceptionHandler { _, e -> write(e) }

    fun reasonName(r: Int) = when (r) {
        1 -> "EXIT_SELF (o próprio app saiu)"
        2 -> "SIGNALED (o sistema matou o processo)"
        3 -> "LOW_MEMORY (pouca memória)"
        4 -> "CRASH (erro do app)"
        5 -> "CRASH_NATIVE (erro nativo, ex.: navegador)"
        6 -> "ANR (app travou)"
        7 -> "INITIALIZATION_FAILURE"
        8 -> "PERMISSION_CHANGE"
        9 -> "EXCESSIVE_RESOURCE_USAGE (uso excessivo)"
        10 -> "USER_REQUESTED (você fechou)"
        11 -> "USER_STOPPED (parado nas configurações)"
        12 -> "DEPENDENCY_DIED"
        13 -> "OTHER (outro motivo do sistema)"
        14 -> "FREEZER (congelado pelo sistema)"
        15 -> "PACKAGE_STATE_CHANGE"
        16 -> "PACKAGE_UPDATED"
        else -> "DESCONHECIDO ($r)"
    }
}
