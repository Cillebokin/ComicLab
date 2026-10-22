package com.example.comiclab.core.reader.enhance

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** 原生推理固定在持久线程；解码预取由每个session的单独线程处理 */
internal object EnhancementRuntime {
    val busy = AtomicBoolean()
    val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "page-enhancement").apply { isDaemon = true }
    }
    val io = Executors.newSingleThreadExecutor { task ->
        Thread(task, "enhancement-export").apply { isDaemon = true }
    }
    val decoder = Executors.newSingleThreadExecutor { task ->
        Thread(task, "enhancement-decode").apply { isDaemon = true }
    }
}
