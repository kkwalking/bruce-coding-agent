package com.brucecli.tool;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolResultContentTest {
    @Test
    void parsesImageBlocksAndRemovesBase64FromText() throws Exception {
        String base64 = tinyPngBase64();
        String raw = "截图完成" + ToolResultContent.encodeImage("image/png", base64, "mcp:browser/screenshot");

        ToolResultContent content = ToolResultContent.parse(raw);

        assertEquals(1, content.imageParts().size());
        assertTrue(content.text().contains("截图完成"));
        assertTrue(content.text().contains("[已附加图片: mcp:browser/screenshot"));
        assertFalse(content.text().contains(base64));
        assertTrue(content.imageParts().get(0).imageUrl().url().startsWith("data:image/png;base64,"));
    }

    @Test
    void mapsTextWithoutTruncatingImageBlocks() throws Exception {
        String base64 = tinyPngBase64();
        String raw = "abcdefg" + ToolResultContent.encodeImage("image/png", base64, "mcp:browser/screenshot");

        String mapped = ToolResultContent.mapTextPreservingImages(raw, text -> text.substring(0, 3));
        ToolResultContent content = ToolResultContent.parse(mapped);

        assertTrue(content.text().startsWith("abc"));
        assertEquals(1, content.imageParts().size());
    }

    private static String tinyPngBase64() throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, Color.BLUE.getRGB());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return Base64.getEncoder().encodeToString(output.toByteArray());
    }
}
