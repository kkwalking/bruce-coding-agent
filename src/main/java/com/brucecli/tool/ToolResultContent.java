package com.brucecli.tool;

import com.brucecli.llm.ContentPart;
import com.brucecli.llm.ImageProcessor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工具返回值的文本和图片内容拆分结果。
 */
public record ToolResultContent(String text, List<ContentPart> imageParts) {
    private static final Pattern IMAGE_BLOCK = Pattern.compile(
        "\\R?\\[bruce-image-content mimeType=([^\\]\\s]+) source=([^\\]]*)]\\R([A-Za-z0-9+/=\\r\\n]+)\\R\\[/bruce-image-content]\\R?",
        Pattern.DOTALL
    );

    public ToolResultContent {
        text = text == null ? "" : text;
        imageParts = imageParts == null ? List.of() : List.copyOf(imageParts);
    }

    public static ToolResultContent text(String text) {
        return new ToolResultContent(text, List.of());
    }

    public static String encodeImage(String mimeType, String base64Data, String source) {
        String safeMimeType = (mimeType == null || mimeType.isBlank()) ? "image/png" : mimeType.strip();
        String safeSource = source == null ? "tool" : source.replace(']', ')').strip();
        String payload = base64Data == null ? "" : base64Data.strip();
        return """

            [bruce-image-content mimeType=%s source=%s]
            %s
            [/bruce-image-content]
            """.formatted(safeMimeType, safeSource, payload);
    }

    public static ToolResultContent parse(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return text(rawText);
        }

        Matcher matcher = IMAGE_BLOCK.matcher(rawText);
        StringBuffer stripped = new StringBuffer();
        List<ContentPart> parts = new ArrayList<>();
        while (matcher.find()) {
            String mimeType = matcher.group(1);
            String source = matcher.group(2);
            String base64Data = matcher.group(3).replaceAll("\\s+", "");
            String replacement;
            try {
                ImageProcessor.ProcessedImage processed = ImageProcessor.fromBase64(base64Data, mimeType, source);
                parts.add(ImageProcessor.toContentPart(processed));
                replacement = processed.fallbackText();
            } catch (IOException exception) {
                replacement = "[图片内容处理失败: " + exception.getMessage() + "]";
            }
            matcher.appendReplacement(stripped, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(stripped);
        return new ToolResultContent(stripped.toString().strip(), parts);
    }

    public static String mapTextPreservingImages(String rawText, UnaryOperator<String> textMapper) {
        if (rawText == null || rawText.isBlank()) {
            return textMapper.apply(rawText);
        }

        Matcher matcher = IMAGE_BLOCK.matcher(rawText);
        List<String> imageBlocks = new ArrayList<>();
        StringBuffer stripped = new StringBuffer();
        while (matcher.find()) {
            imageBlocks.add(matcher.group());
            matcher.appendReplacement(stripped, "[MCP 图片内容已附加]");
        }
        matcher.appendTail(stripped);

        String mappedText = textMapper.apply(stripped.toString().strip());
        if (imageBlocks.isEmpty()) {
            return mappedText;
        }
        return mappedText + "\n" + String.join("\n", imageBlocks);
    }

    public boolean hasImageParts() {
        return !imageParts.isEmpty();
    }
}
