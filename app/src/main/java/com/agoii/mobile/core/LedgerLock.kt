package com.agoii.mobile.core

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class LedgerLock {

    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    fun <T> withLock(projectId: String, block: () -> T): T {
        val lock = locks.computeIfAbsent(projectId) { ReentrantLock() }
        return lock.withLock(block)
    }
}
