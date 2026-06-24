package com.brucecli.llm;

import java.util.List;

/**
 * 对话历史治理工具，避免图片 base64 在多轮 ReAct 历史中长期滞留。
 */
public final class MessageHistoryPruner {
    private MessageHistoryPruner() {
    }

    public static void retainLatestImageMessage(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }

        int latestImageIndex = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if (message != null && message.hasImageContent()) {
                latestImageIndex = i;
                break;
            }
        }
        if (latestImageIndex < 0) {
            return;
        }

        for (int i = 0; i < latestImageIndex; i++) {
            Message message = messages.get(i);
            if (message != null && message.hasImageContent()) {
                messages.set(i, message.withoutImageContent());
            }
        }
    }
}
