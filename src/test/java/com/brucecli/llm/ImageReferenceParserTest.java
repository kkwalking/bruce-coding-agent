package com.brucecli.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageReferenceParserTest {
    @TempDir
    Path tempDir;

    @Test
    void parsesImageReferenceIntoMultimodalContentParts() throws Exception {
        Path image = writePng("shot.png");

        PreparedUserInput input = ImageReferenceParser.parse("帮我分析这张截图 @image:shot.png", tempDir);

        assertTrue(input.hasContentParts());
        assertEquals(2, input.message().contentParts().size());
        assertEquals("帮我分析这张截图", input.message().contentParts().get(0).text());
        ContentPart imagePart = input.message().contentParts().get(1);
        assertTrue(imagePart.isImage());
        assertTrue(imagePart.imageUrl().url().startsWith("data:image/png;base64,"));
        assertTrue(input.text().contains("[已附加图片: " + image.toAbsolutePath().normalize()));
        assertFalse(input.text().contains("base64"));
    }

    @Test
    void supportsBracketedFileUriWithSpaces() throws Exception {
        Path image = writePng("path with spaces.png");

        PreparedUserInput input = ImageReferenceParser.parse(
            "看看 @image:<" + image.toUri() + ">",
            tempDir
        );

        assertTrue(input.hasContentParts());
        assertEquals("看看", input.message().contentParts().get(0).text());
        assertTrue(input.message().contentParts().get(1).imageUrl().url().startsWith("data:image/png;base64,"));
    }

    private Path writePng(String name) throws Exception {
        Path path = tempDir.resolve(name);
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, Color.RED.getRGB());
        ImageIO.write(image, "png", path.toFile());
        return path;
    }
}
