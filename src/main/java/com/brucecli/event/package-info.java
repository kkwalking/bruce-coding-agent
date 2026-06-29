/**
 * bruce 运行时事件通道。
 *
 * <p>该包把 Agent、IntegratedRuntime 等核心流程中发生的任务生命周期、消息流、
 * 工具调用、会话变化和索引进度抽象成事件。TUI、session 持久化、日志或外部集成
 * 可以订阅这些事件，而不需要耦合到具体执行流程。</p>
 *
 * <p>包内职责分工：</p>
 * <ul>
 *     <li>{@link com.brucecli.event.BruceEvent}：所有事件的公共契约。</li>
 *     <li>{@link com.brucecli.event.BruceEvents}：具体事件类型的集中定义。</li>
 *     <li>{@link com.brucecli.event.BruceEventSink}：生产者依赖的事件发布端口。</li>
 *     <li>{@link com.brucecli.event.BruceEventListener}：消费者实现的事件监听端口。</li>
 *     <li>{@link com.brucecli.event.BruceEventBus}：默认的进程内同步事件总线。</li>
 * </ul>
 *
 * <p>事件只用于旁路通知，不应承载核心业务流程控制。监听器内部异常会被事件总线隔离，
 * 避免 UI、日志或持久化失败影响 Agent 主流程。</p>
 */
package com.brucecli.event;
