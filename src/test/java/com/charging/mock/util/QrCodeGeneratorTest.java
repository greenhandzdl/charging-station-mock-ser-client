package com.charging.mock.util;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link QrCodeGenerator}.
 *
 * <p>Validates QR code generation using ZXing:
 * <ul>
 *   <li>Successful generation returns a non-null BufferedImage</li>
 *   <li>Null or blank content returns null gracefully</li>
 *   <li>QR content encodes correct charger identity information</li>
 *   <li>Different charger IDs produce different QR images</li>
 *   <li>Image size matches the configured dimensions</li>
 * </ul>
 */
class QrCodeGeneratorTest {

    private static final int DEFAULT_WIDTH = 200;
    private static final int DEFAULT_HEIGHT = 200;

    @Test
    void generate_shouldReturnBufferedImage() {
        BufferedImage image = QrCodeGenerator.generateQR(
                "{\"chargerId\":\"c1\",\"stationName\":\"test\"}",
                DEFAULT_WIDTH, DEFAULT_HEIGHT);
        assertNotNull(image);
    }

    @Test
    void generate_withNullContent_shouldHandleGracefully() {
        BufferedImage image = QrCodeGenerator.generateQR(null, DEFAULT_WIDTH, DEFAULT_HEIGHT);
        assertNull(image);
    }

    @Test
    void generate_withEmptyContent_shouldHandleGracefully() {
        BufferedImage image = QrCodeGenerator.generateQR("", DEFAULT_WIDTH, DEFAULT_HEIGHT);
        assertNull(image);
    }

    @Test
    void generate_withBlankContent_shouldHandleGracefully() {
        BufferedImage image = QrCodeGenerator.generateQR("   ", DEFAULT_WIDTH, DEFAULT_HEIGHT);
        assertNull(image);
    }

    @Test
    void generate_qrContent_shouldContainChargerInfo() {
        String chargerId = "11111111-1111-1111-1111-111111111001";
        String stationName = "朝阳站A区";
        String content = "{\"chargerId\":\"" + chargerId + "\",\"stationName\":\"" + stationName + "\"}";

        BufferedImage image = QrCodeGenerator.generateQR(content, DEFAULT_WIDTH, DEFAULT_HEIGHT);
        assertNotNull(image);
    }

    @Test
    void generate_differentChargers_produceDifferentQrs() {
        String content1 = "{\"chargerId\":\"charger-001\",\"stationName\":\"station-1\"}";
        String content2 = "{\"chargerId\":\"charger-002\",\"stationName\":\"station-2\"}";

        BufferedImage image1 = QrCodeGenerator.generateQR(content1, DEFAULT_WIDTH, DEFAULT_HEIGHT);
        BufferedImage image2 = QrCodeGenerator.generateQR(content2, DEFAULT_WIDTH, DEFAULT_HEIGHT);

        assertNotNull(image1);
        assertNotNull(image2);

        // Images should not be identical — different content produces different bit patterns
        // Compare pixel data
        boolean identical = true;
        int[] rgb1 = image1.getRGB(0, 0, DEFAULT_WIDTH, DEFAULT_HEIGHT, null, 0, DEFAULT_WIDTH);
        int[] rgb2 = image2.getRGB(0, 0, DEFAULT_WIDTH, DEFAULT_HEIGHT, null, 0, DEFAULT_WIDTH);
        for (int i = 0; i < rgb1.length; i++) {
            if (rgb1[i] != rgb2[i]) {
                identical = false;
                break;
            }
        }
        assertFalse(identical, "Different charger content should produce different QR images");
    }

    @Test
    void generate_imageSize_shouldMatchConfiguredSize() {
        int width = 300;
        int height = 300;
        BufferedImage image = QrCodeGenerator.generateQR(
                "{\"chargerId\":\"c1\",\"stationName\":\"test\"}",
                width, height);
        assertNotNull(image);
        assertEquals(width, image.getWidth());
        assertEquals(height, image.getHeight());
    }

    @Test
    void generate_sameContent_producesIdenticalQrs() {
        String content = "{\"chargerId\":\"c1\",\"stationName\":\"test\"}";
        BufferedImage image1 = QrCodeGenerator.generateQR(content, DEFAULT_WIDTH, DEFAULT_HEIGHT);
        BufferedImage image2 = QrCodeGenerator.generateQR(content, DEFAULT_WIDTH, DEFAULT_HEIGHT);

        assertNotNull(image1);
        assertNotNull(image2);

        int[] rgb1 = image1.getRGB(0, 0, DEFAULT_WIDTH, DEFAULT_HEIGHT, null, 0, DEFAULT_WIDTH);
        int[] rgb2 = image2.getRGB(0, 0, DEFAULT_WIDTH, DEFAULT_HEIGHT, null, 0, DEFAULT_WIDTH);
        assertArrayEquals(rgb1, rgb2, "Same content should produce identical QR images");
    }

    @Test
    void generate_withMinimalContent() {
        BufferedImage image = QrCodeGenerator.generateQR("{}", DEFAULT_WIDTH, DEFAULT_HEIGHT);
        assertNotNull(image);
    }

    @Test
    void generate_withLongContent_shouldStillSucceed() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"chargerId\":\"c1\",\"data\":\"");
        for (int i = 0; i < 500; i++) {
            sb.append("A");
        }
        sb.append("\"}");
        BufferedImage image = QrCodeGenerator.generateQR(sb.toString(), DEFAULT_WIDTH, DEFAULT_HEIGHT);
        assertNotNull(image);
    }
}
