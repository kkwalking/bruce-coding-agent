package com.brucecli.llm;

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

    public boolean isText() {
        return "text".equals(type);
    }

    public boolean isImage() {
        return "image_url".equals(type) && imageUrl != null;
    }

    public record ImageUrl(String url) {
    }
}
