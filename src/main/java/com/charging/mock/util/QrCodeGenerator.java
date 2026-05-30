package com.charging.mock.util;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.awt.image.BufferedImage;
import java.util.Hashtable;

/**
 * Utility for generating QR code images using ZXing.
 *
 * The QR code encodes charge session data (chargerId, recordId, sessionToken)
 * so that the Flutter mobile client can scan it to associate with a session.
 */
public final class QrCodeGenerator {

    private QrCodeGenerator() {
    }

    /**
     * Generate a QR code {@link BufferedImage} from the given data string.
     *
     * @param data   the text to encode (e.g. JSON with chargerId, recordId, sessionToken)
     * @param width  image width in pixels
     * @param height image height in pixels
     * @return the generated QR code image, or null if encoding fails
     */
    public static BufferedImage generateQR(String data, int width, int height) {
        if (data == null || data.isBlank()) {
            return null;
        }
        try {
            QRCodeWriter writer = new QRCodeWriter();
            Hashtable<com.google.zxing.EncodeHintType, Object> hints = new Hashtable<>();
            hints.put(com.google.zxing.EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(com.google.zxing.EncodeHintType.MARGIN, 1);
            var bitMatrix = writer.encode(data, BarcodeFormat.QR_CODE, width, height, hints);
            return MatrixToImageWriter.toBufferedImage(bitMatrix);
        } catch (WriterException e) {
            System.err.println("Failed to generate QR code: " + e.getMessage());
            return null;
        }
    }
}