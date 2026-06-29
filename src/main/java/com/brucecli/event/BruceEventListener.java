package com.brucecli.event;

/**
 * 事件消费者实现的监听端口。
 *
 * <p>监听器通常用于 UI 刷新、session 记录、日志采集或测试断言。实现应尽量轻量，
 * 避免在事件回调中长时间阻塞运行时主流程。</p>
 */
@FunctionalInterface
public interface BruceEventListener {
    /**
     * 接收一个运行时事件。
     */
    void onEvent(BruceEvent event);
}
