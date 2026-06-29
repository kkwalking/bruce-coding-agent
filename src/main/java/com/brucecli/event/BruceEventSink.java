package com.brucecli.event;

/**
 * 事件生产者依赖的发布端口。
 *
 * <p>Agent 等底层能力只需要知道如何发事件，不需要知道事件被谁消费。这样底层能力可以
 * 在 CLI、测试或其他宿主中复用。</p>
 */
@FunctionalInterface
public interface BruceEventSink {
    /**
     * 丢弃所有事件的空实现，供不需要事件能力的调用方使用。
     */
    BruceEventSink NO_OP = event -> {
    };

    /**
     * 发布一个事件。实现可以选择同步广播、异步转发或直接忽略。
     */
    void emit(BruceEvent event);
}
