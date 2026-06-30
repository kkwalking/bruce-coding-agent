package com.brucecli.llm;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * OpenAI-compatible 多模态 content block。
 */
public record ContentPart(
    String type,
    String text,
    ImageUrl imageUrl,
    String fallbackText
) {
    public ContentPart {
        type = type == null ? "" : type;
        fallbackText = fallbackText == null ? "" : fallbackText;
    }

    public static ContentPart text(String text) {
        String value = text == null ? "" : text;
        return new ContentPart("text", value, null, value);
    }

    public static ContentPart imageUrl(String url, String fallbackText) {
        return new ContentPart("image_url", null, new ImageUrl(url), fallbackText);
    }

    // Jackson 会把 JavaBean 风格的 isXxx() 当成布尔 JSON 属性。
    // 这里刻意避开 isText/isImage 命名，并忽略辅助方法，避免 session 再写出
    // {"text":true,"image":false}，只保留 record 定义的持久化字段。
    @JsonIgnore
    public boolean isTextPart() {
        return "text".equals(type);
    }

    @JsonIgnore
    public boolean isImagePart() {
        return "image_url".equals(type) && imageUrl != null;
    }

    public record ImageUrl(String url) {
    }
}
