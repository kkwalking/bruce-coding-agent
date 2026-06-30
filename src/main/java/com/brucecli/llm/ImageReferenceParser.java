package com.brucecli.llm;

import java.io.IOException;
import java.io.OutputStream;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

/**
 * 解析 CLI 输入中的 @image: 和 @clipboard 多模态引用。
 */
public final class ImageReferenceParser {
    private static final String IMAGE_PREFIX = "@image:";
    private static final String CLIPBOARD_TOKEN = "@clipboard";
    private static final Duration CLIPBOARD_TIMEOUT = Duration.ofSeconds(8);

    private ImageReferenceParser() {
    }

    public static PreparedUserInput parse(String input, Path workspaceRoot) throws IOException {
        String value = input == null ? "" : input;
        if (!value.contains(IMAGE_PREFIX) && !value.contains(CLIPBOARD_TOKEN)) {
            return PreparedUserInput.text(value);
        }

        StringBuilder text = new StringBuilder();
        List<ContentPart> imageParts = new ArrayList<>();
        int cursor = 0;
        while (cursor < value.length()) {
            int imageIndex = value.indexOf(IMAGE_PREFIX, cursor);
            int clipboardIndex = findClipboard(value, cursor);
            int nextIndex = earliest(imageIndex, clipboardIndex);
            if (nextIndex < 0) {
                text.append(value, cursor, value.length());
                break;
            }

            text.append(value, cursor, nextIndex);
            if (nextIndex == imageIndex) {
                ParsedImageRef ref = readImageRef(value, nextIndex);
                Path imagePath = resolveImagePath(ref.reference(), workspaceRoot);
                imageParts.add(ImageProcessor.toContentPart(ImageProcessor.process(imagePath)));
                cursor = ref.end();
            } else {
                imageParts.add(ImageProcessor.toContentPart(readClipboardImage()));
                cursor = nextIndex + CLIPBOARD_TOKEN.length();
            }
        }

        if (imageParts.isEmpty()) {
            return PreparedUserInput.text(value);
        }

        List<ContentPart> parts = new ArrayList<>();
        String promptText = normalizePromptText(text.toString());
        if (!promptText.isBlank()) {
            parts.add(ContentPart.text(promptText));
        }
        parts.addAll(imageParts);
        return PreparedUserInput.multimodal(parts);
    }

    private static ParsedImageRef readImageRef(String input, int start) throws IOException {
        int refStart = start + IMAGE_PREFIX.length();
        if (refStart >= input.length()) {
            throw new IOException("@image: 后缺少图片路径");
        }

        char first = input.charAt(refStart);
        if (first == '<') {
            int end = input.indexOf('>', refStart + 1);
            if (end < 0) {
                throw new IOException("@image:<...> 缺少结束的 >");
            }
            String reference = input.substring(refStart + 1, end).trim();
            if (reference.isBlank()) {
                throw new IOException("@image:<...> 中的图片路径不能为空");
            }
            return new ParsedImageRef(reference, end + 1);
        }

        int end = refStart;
        while (end < input.length() && !Character.isWhitespace(input.charAt(end))) {
            end++;
        }
        String reference = input.substring(refStart, end).trim();
        if (reference.isBlank()) {
            throw new IOException("@image: 后缺少图片路径");
        }
        return new ParsedImageRef(reference, end);
    }

    private static Path resolveImagePath(String reference, Path workspaceRoot) throws IOException {
        Path path;
        if (reference.startsWith("file://")) {
            try {
                path = Path.of(URI.create(reference));
            } catch (IllegalArgumentException exception) {
                throw new IOException("非法 file:// 图片路径: " + reference, exception);
            }
        } else {
            path = Path.of(reference);
        }
        Path resolved = path.isAbsolute()
            ? path.normalize()
            : workspaceRoot.toAbsolutePath().normalize().resolve(path).normalize();
        if (!Files.isRegularFile(resolved)) {
            throw new IOException("图片文件不存在或不是普通文件: " + resolved);
        }
        return resolved;
    }

    private static ImageProcessor.ProcessedImage readClipboardImage() throws IOException {
        List<String> errors = new ArrayList<>();
        if (isMacOs()) {
            try {
                return readClipboardPngWithAppleScript();
            } catch (IOException exception) {
                errors.add(exception.getMessage());
            }
        }
        try {
            return readClipboardImageWithAwt();
        } catch (IOException exception) {
            errors.add(exception.getMessage());
        }
        throw new IOException("剪贴板里没有可读取的图片。" + String.join("；", errors));
    }

    private static ImageProcessor.ProcessedImage readClipboardPngWithAppleScript() throws IOException {
        Path output = Files.createTempFile("bruce-coding-agent-clipboard-", ".png");
        try {
            captureClipboardPng(output);
            return ImageProcessor.process(Files.readAllBytes(output), "image/png", "clipboard");
        } finally {
            Files.deleteIfExists(output);
        }
    }

    private static ImageProcessor.ProcessedImage readClipboardImageWithAwt() throws IOException {
        if (GraphicsEnvironment.isHeadless()) {
            throw new IOException("当前 Java 运行环境是 headless，无法读取系统剪贴板");
        }

        Transferable contents;
        try {
            contents = Toolkit.getDefaultToolkit().getSystemClipboard().getContents(null);
        } catch (IllegalStateException exception) {
            throw new IOException("系统剪贴板暂时不可用", exception);
        }
        if (contents == null) {
            throw new IOException("剪贴板为空");
        }

        try {
            if (contents.isDataFlavorSupported(DataFlavor.imageFlavor)) {
                Image image = (Image) contents.getTransferData(DataFlavor.imageFlavor);
                return ImageProcessor.process(toPngBytes(image), "image/png", "clipboard");
            }
            if (contents.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                @SuppressWarnings("unchecked")
                List<File> files = (List<File>) contents.getTransferData(DataFlavor.javaFileListFlavor);
                return processFirstImageFile(files.stream().map(File::toPath).toList());
            }
            if (contents.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                String text = (String) contents.getTransferData(DataFlavor.stringFlavor);
                Path path = clipboardTextToPath(text);
                if (path != null) {
                    return ImageProcessor.process(path);
                }
            }
        } catch (UnsupportedFlavorException exception) {
            throw new IOException("剪贴板数据格式不受支持", exception);
        }

        throw new IOException("剪贴板既不是图片数据，也不是图片文件或图片路径");
    }

    private static ImageProcessor.ProcessedImage processFirstImageFile(List<Path> paths) throws IOException {
        if (paths == null || paths.isEmpty()) {
            throw new IOException("剪贴板文件列表为空");
        }
        List<String> errors = new ArrayList<>();
        for (Path path : paths) {
            try {
                if (path != null && Files.isRegularFile(path)) {
                    return ImageProcessor.process(path);
                }
            } catch (IOException exception) {
                errors.add(path + ": " + exception.getMessage());
            }
        }
        throw new IOException("剪贴板文件列表中没有可读取的图片。" + String.join("；", errors));
    }

    private static Path clipboardTextToPath(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String value = text.strip();
        if ((value.startsWith("\"") && value.endsWith("\""))
            || (value.startsWith("'") && value.endsWith("'"))) {
            value = value.substring(1, value.length() - 1);
        }
        try {
            Path path = value.startsWith("file://")
                ? Path.of(URI.create(value))
                : Path.of(value);
            return Files.isRegularFile(path) ? path : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static byte[] toPngBytes(Image image) throws IOException {
        if (image == null) {
            throw new IOException("剪贴板图片为空");
        }
        BufferedImage buffered = toBufferedImage(image);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(buffered, "png", output)) {
            throw new IOException("当前 JRE 不支持 PNG 编码");
        }
        return output.toByteArray();
    }

    private static BufferedImage toBufferedImage(Image image) throws IOException {
        if (image instanceof BufferedImage bufferedImage) {
            return bufferedImage;
        }
        int width = image.getWidth(null);
        int height = image.getHeight(null);
        if (width <= 0 || height <= 0) {
            throw new IOException("无法读取剪贴板图片尺寸");
        }
        BufferedImage buffered = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = buffered.createGraphics();
        try {
            graphics.drawImage(image, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return buffered;
    }

    private static void captureClipboardPng(Path output) throws IOException {
        String script = """
            on run argv
                set outputPath to item 1 of argv
                try
                    set pngData to (the clipboard as \u00abclass PNGf\u00bb)
                on error errMsg
                    error "剪贴板里没有 PNG 数据"
                end try
                set fh to open for access (POSIX file outputPath as string) with write permission
                try
                    set eof of fh to 0
                    write pngData to fh
                    close access fh
                on error errMsg
                    try
                        close access fh
                    end try
                    error errMsg
                end try
            end run
            """;

        Process process = new ProcessBuilder("/usr/bin/osascript", "-", output.toString())
            .redirectErrorStream(true)
            .start();
        try (OutputStream stdin = process.getOutputStream()) {
            stdin.write(script.getBytes(StandardCharsets.UTF_8));
        }

        boolean completed;
        try {
            completed = process.waitFor(CLIPBOARD_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("读取剪贴板图片被中断", exception);
        }

        if (!completed) {
            process.destroyForcibly();
            throw new IOException("读取剪贴板图片超时");
        }
        String processOutput = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        if (process.exitValue() != 0) {
            throw new IOException(processOutput.isBlank()
                ? "剪贴板里没有可读取的 PNG 图片"
                : "剪贴板图片读取失败: " + processOutput);
        }
    }

    private static int findClipboard(String input, int fromIndex) {
        int index = input.indexOf(CLIPBOARD_TOKEN, fromIndex);
        while (index >= 0) {
            int end = index + CLIPBOARD_TOKEN.length();
            if (end >= input.length() || isTokenBoundary(input.charAt(end))) {
                return index;
            }
            index = input.indexOf(CLIPBOARD_TOKEN, end);
        }
        return -1;
    }

    private static boolean isTokenBoundary(char value) {
        return !Character.isLetterOrDigit(value) && value != '_';
    }

    private static int earliest(int left, int right) {
        if (left < 0) {
            return right;
        }
        if (right < 0) {
            return left;
        }
        return Math.min(left, right);
    }

    private static String normalizePromptText(String text) {
        return text == null ? "" : text.replaceAll("[ \\t\\x0B\\f\\r]+", " ").trim();
    }

    private static boolean isMacOs() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }

    private record ParsedImageRef(String reference, int end) {
    }
}
