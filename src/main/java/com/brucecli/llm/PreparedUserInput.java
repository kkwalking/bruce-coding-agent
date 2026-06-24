package com.brucecli.llm;

import java.util.List;

/**
 * 用户原始输入在进入 Agent 前的归一化结果。
 */
public record PreparedUserInput(String text, Message message) {
    public PreparedUserInput {
        text = text == null ? "" : text;
        message = message == null ? Message.user(text) : message;
    }

    public static PreparedUserInput text(String text) {
        return new PreparedUserInput(text, Message.user(text));
    }

    public static PreparedUserInput multimodal(List<ContentPart> parts) {
        Message message = Message.user(parts);
        return new PreparedUserInput(message.content(), message);
    }

    public boolean hasContentParts() {
        return message.hasContentParts();
    }
}
