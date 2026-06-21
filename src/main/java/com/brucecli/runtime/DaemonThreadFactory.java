package com.brucecli.runtime;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 为学习版并发池创建守护线程，避免 CLI 退出时被工作线程挂住。
 */
public class DaemonThreadFactory implements ThreadFactory {
    private final String prefix;
    private final AtomicInteger sequence = new AtomicInteger(1);

    public DaemonThreadFactory(String prefix) {
        this.prefix = prefix == null || prefix.isBlank() ? "bruce-cli-worker" : prefix;
    }

    @Override
    public Thread newThread(Runnable runnable) {
        Thread thread = new Thread(runnable, prefix + "-" + sequence.getAndIncrement());
        thread.setDaemon(true);
        return thread;
    }
}
