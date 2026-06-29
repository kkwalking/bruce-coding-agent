package com.brucecli.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 默认的进程内同步事件总线。
 *
 * <p>事件会按订阅顺序同步投递给所有 listener。单个 listener 抛出的运行时异常会被记录
 * 并隔离，后续 listener 仍然可以继续收到事件。</p>
 *
 * <p>该实现刻意保持轻量、行为可控，并通过 {@link BruceEventSink} 和
 * {@link BruceEventListener} 保留未来替换为更成熟事件库的空间。</p>
 */
public class BruceEventBus implements BruceEventSink {
    private static final Logger logger = LoggerFactory.getLogger(BruceEventBus.class);

    private final List<BruceEventListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * 订阅事件，并返回一个取消订阅的回调。
     */
    public Runnable subscribe(BruceEventListener listener) {
        if (listener == null) {
            return () -> {
            };
        }
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /**
     * 广播事件。null 事件会被忽略。
     */
    @Override
    public void emit(BruceEvent event) {
        if (event == null) {
            return;
        }
        for (BruceEventListener listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (RuntimeException exception) {
                logger.warn("Bruce event listener failed for {}: {}", event.type(), exception.getMessage());
                logger.debug("Bruce event listener failure", exception);
            }
        }
    }
}
