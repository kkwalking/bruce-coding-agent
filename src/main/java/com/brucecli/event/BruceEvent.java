package com.brucecli.event;

import java.time.Instant;

/**
 * bruce 运行时事件的公共契约。
 *
 * <p>每个事件都带有可读的类型、发生时间，以及可选的 runId。runId 用于把一次用户任务
 * 内的流式消息、工具调用和最终结果串起来；模式切换、session 变化等全局事件可以没有
 * runId。</p>
 */
public interface BruceEvent {
    /**
     * 事件类型的稳定字符串，适合用于日志、UI 分发或外部协议映射。
     */
    String type();

    /**
     * 本事件所属的任务运行 ID；当事件不隶属于某次用户任务时可以为 null。
     */
    String runId();

    /**
     * 事件产生的时间点。
     */
    Instant timestamp();
}
