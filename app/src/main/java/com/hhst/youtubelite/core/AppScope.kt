package com.hhst.youtubelite.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application-wide [CoroutineScope] for long-lived coroutines that should outlive
 * any single Activity/Fragment. Backed by a [SupervisorJob] so a failure in one
 * child coroutine does not cancel siblings.
 *
 * Kotlin callers: `AppScope.scope.launch { ... }`.
 * Java callers: use [launchIO] / [launchDefault] for fire-and-forget work.
 */
object AppScope {

    @JvmField
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Launches a fire-and-forget coroutine on [Dispatchers.IO] for blocking I/O
     * (network, file, SQLite). Intended for Java callers; Kotlin callers should
     * use `scope.launch(Dispatchers.IO) { ... }` directly.
     */
    @JvmStatic
    fun launchIO(block: Runnable): Job = scope.launch(Dispatchers.IO) { block.run() }

    /**
     * Launches a fire-and-forget coroutine on [Dispatchers.Default] for CPU-bound
     * work. Intended for Java callers; Kotlin callers should use
     * `scope.launch(Dispatchers.Default) { ... }` directly.
     */
    @JvmStatic
    fun launchDefault(block: Runnable): Job = scope.launch(Dispatchers.Default) { block.run() }
}
