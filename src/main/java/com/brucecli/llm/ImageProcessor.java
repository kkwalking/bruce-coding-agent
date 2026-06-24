package com.brucecli.llm;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Iterator;
import java.util.Locale;

/**
 * 把本地图片转换成可发送给多模态 Chat Completion API 的 data URL。
 */
public final class ImageProcessor {
    public static final int API_IMAGE_MAX_BASE64_SIZE = 5 * 1024 * 1024;
    public static final int MAX_DIMENSION = 2_000;

    private static final float[] JPEG_QUALITIES = {0.85f, 0.70f, 0.55f, 0.40f, 0.25f};

    private ImageProcessor() {
    }

    public static ProcessedImage process(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        byte[] bytes = Files.readAllBytes(normalized);
        return process(bytes, probeMimeType(normalized), normalized.toString());
    }

    public static ProcessedImage fromBase64(String base64, String mimeType, String source) throws IOException {
        String payload = base64 == null ? "" : base64.trim();
        String resolvedMimeType = mimeType;
        if (payload.startsWith("data:")) {
            int comma = payload.indexOf(',');
            int semicolon = payload.indexOf(';');
            if (comma > 0) {
                if ((resolvedMimeType == null || resolvedMimeType.isBlank()) && semicolon > "data:".length()) {
                    resolvedMimeType = payload.substring("data:".length(), semicolon);
                }
                payload = payload.substring(comma + 1);
            }
        }
        return process(Base64.getDecoder().decode(payload), resolvedMimeType, source);
    }

    public static ProcessedImage process(byte[] bytes, String mimeType, String source) throws IOException {
        if (bytes == null || bytes.length == 0) {
            throw new IOException("图片内容为空: " + source);
        }

        String normalizedMimeType = normalizeMimeType(mimeType);
        BufferedImage image = readImage(bytes);
        if (image == null && !normalizedMimeType.startsWith("image/")) {
            throw new IOException("文件不是可识别的图片: " + source);
        }
        boolean overSize = estimateBase64Size(bytes.length) > API_IMAGE_MAX_BASE64_SIZE;
        boolean hasAlpha = image != null && hasAlpha(image);
        boolean hasImageMimeType = normalizedMimeType.startsWith("image/");

        if (!overSize && !hasAlpha && hasImageMimeType) {
            return new ProcessedImage(
                Base64.getEncoder().encodeToString(bytes),
                normalizedMimeType,
                source,
                bytes.length,
                bytes.length,
                image == null ? 0 : image.getWidth(),
                image == null ? 0 : image.getHeight()
            );
        }
        if (image == null) {
            throw new IOException("图片过大且无法读取尺寸，暂不支持自动压缩: " + source);
        }

        BufferedImage rgbImage = hasAlpha ? flattenAlpha(image) : toRgb(image);
        if (!overSize && (hasAlpha || !hasImageMimeType)) {
            byte[] flattened = writePng(rgbImage);
            if (estimateBase64Size(flattened.length) <= API_IMAGE_MAX_BASE64_SIZE) {
                return processed(flattened, "image/png", source, bytes.length, rgbImage);
            }
        }

        ResizeSize target = fitWithin(rgbImage.getWidth(), rgbImage.getHeight(), MAX_DIMENSION, MAX_DIMENSION);
        BufferedImage resized = target.sameSize(rgbImage)
            ? rgbImage
            : resize(rgbImage, target.width(), target.height());

        byte[] png = writePng(resized);
        if (estimateBase64Size(png.length) <= API_IMAGE_MAX_BASE64_SIZE) {
            return processed(png, "image/png", source, bytes.length, resized);
        }

        for (float quality : JPEG_QUALITIES) {
            byte[] jpeg = writeJpeg(resized, quality);
            if (estimateBase64Size(jpeg.length) <= API_IMAGE_MAX_BASE64_SIZE) {
                return processed(jpeg, "image/jpeg", source, bytes.length, resized);
            }
        }

        throw new IOException("图片压缩后仍超过限制: " + source);
    }

    public static ContentPart toContentPart(ProcessedImage image) {
        return ContentPart.imageUrl(image.dataUri(), image.fallbackText());
    }

    public static long estimateBase64Size(long bytes) {
        return ((bytes + 2) / 3) * 4;
    }

    private static ProcessedImage processed(
        byte[] bytes,
        String mimeType,
        String source,
        long originalBytes,
        BufferedImage image
    ) {
        return new ProcessedImage(
            Base64.getEncoder().encodeToString(bytes),
            mimeType,
            source,
            originalBytes,
            bytes.length,
            image.getWidth(),
            image.getHeight()
        );
    }

    private static BufferedImage readImage(byte[] bytes) {
        try {
            return ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException ignored) {
            return null;
        }
    }

    private static boolean hasAlpha(BufferedImage image) {
        return image.getColorModel() != null && image.getColorModel().hasAlpha();
    }

    private static BufferedImage flattenAlpha(BufferedImage image) {
        BufferedImage flattened = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = flattened.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, flattened.getWidth(), flattened.getHeight());
            graphics.drawImage(image, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return flattened;
    }

    private static BufferedImage toRgb(BufferedImage image) {
        if (image.getType() == BufferedImage.TYPE_INT_RGB) {
            return image;
        }
        BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = rgb.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
            graphics.drawImage(image, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return rgb;
    }

    private static BufferedImage resize(BufferedImage image, int width, int height) {
        BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = resized.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.drawImage(image, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return resized;
    }

    private static byte[] writePng(BufferedImage image) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", output)) {
            throw new IOException("当前 JRE 不支持 PNG 编码");
        }
        return output.toByteArray();
    }

    private static byte[] writeJpeg(BufferedImage image, float quality) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException("当前 JRE 不支持 JPEG 编码");
        }

        ImageWriter writer = writers.next();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ImageOutputStream imageOutput = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(imageOutput);
            ImageWriteParam params = writer.getDefaultWriteParam();
            params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            params.setCompressionQuality(quality);
            writer.write(null, new IIOImage(toRgb(image), null, null), params);
        } finally {
            writer.dispose();
        }
        return output.toByteArray();
    }

    private static ResizeSize fitWithin(int width, int height, int maxWidth, int maxHeight) {
        if (width <= maxWidth && height <= maxHeight) {
            return new ResizeSize(width, height);
        }
        double scale = Math.min((double) maxWidth / width, (double) maxHeight / height);
        return new ResizeSize(
            Math.max(1, (int) Math.round(width * scale)),
            Math.max(1, (int) Math.round(height * scale))
        );
    }

    private static String probeMimeType(Path path) throws IOException {
        String probed = Files.probeContentType(path);
        if (probed != null && !probed.isBlank()) {
            return probed;
        }
        String fileName = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (fileName.endsWith(".png")) {
            return "image/png";
        }
        if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (fileName.endsWith(".webp")) {
            return "image/webp";
        }
        if (fileName.endsWith(".gif")) {
            return "image/gif";
        }
        return "application/octet-stream";
    }

    private static String normalizeMimeType(String mimeType) {
        if (mimeType == null || mimeType.isBlank()) {
            return "application/octet-stream";
        }
        String normalized = mimeType.toLowerCase(Locale.ROOT);
        if ("image/jpg".equals(normalized)) {
            return "image/jpeg";
        }
        if ("image/x-png".equals(normalized)) {
            return "image/png";
        }
        return normalized;
    }

    public record ProcessedImage(
        String base64Data,
        String mimeType,
        String source,
        long originalBytes,
        long processedBytes,
        int width,
        int height
    ) {
        public String dataUri() {
            return "data:" + mimeType + ";base64," + base64Data;
        }

        public String fallbackText() {
            String size = width > 0 && height > 0 ? ", size=" + width + "x" + height : "";
            return "[已附加图片: %s, mimeType=%s, bytes=%d%s]".formatted(
                source,
                mimeType,
                processedBytes,
                size
            );
        }
    }

    private record ResizeSize(int width, int height) {
        boolean sameSize(BufferedImage image) {
            return image.getWidth() == width && image.getHeight() == height;
        }
    }
}
