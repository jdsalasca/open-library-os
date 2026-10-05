package com.openlibrary.inventory;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.EnumMap;
import java.util.Map;

/**
 * The label that goes on the spine of a copy: the EAN-13 a scanner reads, the
 * human code underneath, and a QR for phones.
 *
 * <p>Rendered server-side so one URL can be printed from any browser, a label
 * printer, or a ZPL template, and so the image never depends on the client.
 */
@Component
public class LabelRenderer {

    // 62x29 mm at 600 dpi is the classic address-label size for spine stickers.
    private static final int WIDTH = 1465;
    private static final int HEIGHT = 688;
    private static final int MARGIN = 24;

    public byte[] render(InventoryDtos.CopySummary copy) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, WIDTH, HEIGHT);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            drawBarcode(g, copy.barcode(), MARGIN, MARGIN, WIDTH - 2 * MARGIN, 430);
            drawQr(g, copy.qr(), WIDTH - MARGIN - 260, MARGIN, 260, 260);

            g.setColor(Color.BLACK);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 52));
            centred(g, copy.code(), 0, HEIGHT - 96, WIDTH);

            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 30));
            centred(g, truncate(copy.bookTitle(), 46), 0, HEIGHT - 44, WIDTH);
        } finally {
            g.dispose();
        }
        return toPng(image);
    }

    private void drawBarcode(Graphics2D g, String value, int x, int y, int width, int height) {
        BitMatrix matrix = encode(value, BarcodeFormat.EAN_13, width, height,
                Map.of(EncodeHintType.MARGIN, 0));
        g.drawImage(toImage(matrix), x, y, width, height, null);
    }

    private void drawQr(Graphics2D g, String value, int x, int y, int size, int unused) {
        BitMatrix matrix = encode(value, BarcodeFormat.QR_CODE, size, size,
                Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M));
        g.drawImage(toImage(matrix), x, y, size, size, null);
    }

    private static BitMatrix encode(String value, BarcodeFormat format, int width, int height,
                                    Map<EncodeHintType, Object> hints) {
        try {
            var all = new EnumMap<EncodeHintType, Object>(EncodeHintType.class);
            all.putAll(hints);
            var writer = new com.google.zxing.MultiFormatWriter();
            return writer.encode(value, format, width, height, all);
        } catch (WriterException e) {
            throw new IllegalStateException("no se pudo codificar " + value, e);
        }
    }

    private static BufferedImage toImage(BitMatrix matrix) {
        int width = matrix.getWidth();
        int height = matrix.getHeight();
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, matrix.get(x, y) ? Color.BLACK.getRGB() : Color.WHITE.getRGB());
            }
        }
        return image;
    }

    private static void centred(Graphics2D g, String text, int x, int y, int width) {
        FontMetrics metrics = g.getFontMetrics();
        g.drawString(text, x + (width - metrics.stringWidth(text)) / 2, y);
    }

    private static String truncate(String value, int limit) {
        if (value == null) {
            return "";
        }
        return value.length() <= limit ? value : value.substring(0, limit - 1) + "…";
    }

    private static byte[] toPng(BufferedImage image) {
        try (var out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "PNG", out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("no se pudo generar la etiqueta", e);
        }
    }
}
