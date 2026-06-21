package com.brucecli.memory.model;

/**
 * 记忆类型，对应文章里 MemoryEntry 内部的 MemoryType。
 *
 * <p>学习项目里把 enum 单独拆出来，方便 LongTermMemory、MemoryRetriever、
 * ContextCompressor 等组件共享同一套类型定义。</p>
 */
public enum MemoryType {
    /**
     * 当前对话消息，比如用户输入、模型回复、工具调用观察。
     */
    CONVERSATION,

    /**
     * 跨会话事实，比如用户偏好、项目技术栈、代码风格约定。
     */
    FACT,

    /**
     * 被上下文压缩器压缩后的摘要。
     */
    SUMMARY,

    /**
     * 工具执行结果。工具结果通常很长，压缩和检索时需要单独识别。
     */
    TOOL_RESULT
}
