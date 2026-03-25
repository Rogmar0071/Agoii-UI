package com.agoii.mobile.core

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Per-project mutual-exclusion registry.
 *
 * Guarantees that at most one thread at a time executes a critical section for a given
 * [projectId].  Uses [ReentrantLock] so that a single thread can re-enter without
 * deadlocking (relevant when the lock is held across a read + validate + write sequence).
 *
 * System Law 5 — Concurrency Safety: no two writes can interleave.
 */
class LedgerLock {

    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    /**
     * Execute [block] while holding the exclusive lock for [projectId].
     * The lock is always released when [block] completes, even on exception.
     */
    fun <T> withLock(projectId: String, block: () -> T): T {
        val lock = locks.getOrPut(projectId) { ReentrantLock() }
        return lock.withLock(block)
    }
}
