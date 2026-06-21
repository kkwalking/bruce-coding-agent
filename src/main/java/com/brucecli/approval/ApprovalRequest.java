package com.brucecli.approval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 一次 HITL 审批请求，负责把工具名、参数和风险信息格式化给人看。
 */
public record ApprovalRequest(
    String toolName,
    String arguments,
    String dangerLevel,
    String riskDescription,
    String suggestion
) {
    private static final int INNER_WIDTH = 58;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static ApprovalRequest of(String toolName, String arguments, String suggestion) {
        return new ApprovalRequest(
            toolName,
            arguments,
            ApprovalPolicy.getDangerLevel(toolName),
            ApprovalPolicy.getRiskDescription(toolName),
            suggestion
        );
    }

    public String toDisplayText() {
        List<String> lines = new ArrayList<>();
        lines.add("  ⚠️  需要审批");
        lines.add("");
        lines.add("  工具: " + safe(toolName));
        lines.add("  等级: " + safe(dangerLevel));
        lines.add("  风险: " + safe(riskDescription));
        if (suggestion != null && !suggestion.isBlank()) {
            lines.add("  建议: " + suggestion.trim());
        }
        lines.add("");
        lines.add("  参数:");
        lines.addAll(formatArguments(arguments));

        StringBuilder out = new StringBuilder();
        out.append("┌").append("─".repeat(INNER_WIDTH)).append("┐").append(System.lineSeparator());
        for (String line : lines) {
            if (line.isEmpty()) {
                out.append("├").append("─".repeat(INNER_WIDTH)).append("┤").append(System.lineSeparator());
                continue;
            }
            for (String wrapped : wrap(line, INNER_WIDTH)) {
                out.append("│").append(padRight(wrapped, INNER_WIDTH)).append("│").append(System.lineSeparator());
            }
        }
        out.append("└").append("─".repeat(INNER_WIDTH)).append("┘");
        return out.toString();
    }

    private static List<String> formatArguments(String rawArguments) {
        if (rawArguments == null || rawArguments.isBlank()) {
            return List.of("    (空)");
        }

        try {
            JsonNode root = MAPPER.readTree(rawArguments);
            if (root.isObject()) {
                List<String> lines = new ArrayList<>();
                Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    lines.add("    " + entry.getKey() + ": " + summarizeJsonValue(entry.getValue()));
                }
                return lines.isEmpty() ? List.of("    {}") : lines;
            }
            return List.of("    " + summarizeJsonValue(root));
        } catch (Exception ignored) {
            return rawArguments.lines()
                .map(line -> "    " + line)
                .toList();
        }
    }

    private static String summarizeJsonValue(JsonNode value) throws Exception {
        String text = value.isTextual() ? MAPPER.writeValueAsString(value.asText()) : MAPPER.writeValueAsString(value);
        int originalLength = text.length();
        if (originalLength <= 120) {
            return text;
        }
        return text.substring(0, 117) + "... (" + originalLength + " 字符)";
    }

    private static List<String> wrap(String line, int maxWidth) {
        if (displayWidth(line) <= maxWidth) {
            return List.of(line);
        }

        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int width = 0;
        int index = 0;
        while (index < line.length()) {
            int cp = line.codePointAt(index);
            int cpWidth = codePointWidth(cp);
            if (width + cpWidth > maxWidth && current.length() > 0) {
                result.add(current.toString());
                current.setLength(0);
                width = 0;
            }
            current.appendCodePoint(cp);
            width += cpWidth;
            index += Character.charCount(cp);
        }
        if (current.length() > 0) {
            result.add(current.toString());
        }
        return result;
    }

    private static String padRight(String s, int width) {
        int padding = width - displayWidth(s);
        if (padding <= 0) {
            return s;
        }
        return s + " ".repeat(padding);
    }

    static int displayWidth(String s) {
        int width = 0;
        int index = 0;
        while (index < s.length()) {
            int cp = s.codePointAt(index);
            width += codePointWidth(cp);
            index += Character.charCount(cp);
        }
        return width;
    }

    private static int codePointWidth(int cp) {
        if (cp == 0xFE0F || cp == 0x200D) {
            return 0;
        }
        return isWideCodePoint(cp) ? 2 : 1;
    }

    private static boolean isWideCodePoint(int cp) {
        return cp >= 0x1100 && (
            cp <= 0x115F
                || cp == 0x2329
                || cp == 0x232A
                || (cp >= 0x2E80 && cp <= 0xA4CF)
                || (cp >= 0xAC00 && cp <= 0xD7A3)
                || (cp >= 0xF900 && cp <= 0xFAFF)
                || (cp >= 0xFE10 && cp <= 0xFE19)
                || (cp >= 0xFE30 && cp <= 0xFE6F)
                || (cp >= 0xFF00 && cp <= 0xFF60)
                || (cp >= 0xFFE0 && cp <= 0xFFE6)
                || (cp >= 0x1F300 && cp <= 0x1FAFF)
        );
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
