package com.js.motos.clientApp.qr.service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.imageio.ImageIO;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import org.springframework.stereotype.Service;

import com.js.motos.clientApp.common.ApiException;
import com.js.motos.clientApp.common.ErrorCode;
import com.js.motos.clientApp.qr.domain.QrCode;

/** Renders QR codes as PNGs for printing. */
@Service
public class QrImageService {

    private static final int DEFAULT_SIZE = 512;

    /**
     * High error correction (~30% recoverable). These get printed on stickers and
     * scratch cards that end up scuffed, folded and poorly lit in a workshop.
     */
    private static final ErrorCorrectionLevel CORRECTION = ErrorCorrectionLevel.H;

    public byte[] toPng(String payload, int size) {
        int pixels = Math.min(Math.max(size, 128), 2048);
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.ERROR_CORRECTION, CORRECTION);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.MARGIN, 2);

            BitMatrix matrix = new MultiFormatWriter()
                    .encode(payload, BarcodeFormat.QR_CODE, pixels, pixels, hints);
            BufferedImage image = MatrixToImageWriter.toBufferedImage(matrix);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "PNG", out);
            return out.toByteArray();

        } catch (Exception e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Could not render the QR image.");
        }
    }

    public byte[] toPng(String payload) {
        return toPng(payload, DEFAULT_SIZE);
    }

    /**
     * Bundles a whole batch as a ZIP of PNGs named after each code, so a print
     * shop can drop the folder straight into a layout template.
     */
    public byte[] toZip(List<QrCode> codes, java.util.function.Function<QrCode, String> payloadFn) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (QrCode code : codes) {
                zip.putNextEntry(new ZipEntry(code.getCode() + ".png"));
                zip.write(toPng(payloadFn.apply(code)));
                zip.closeEntry();
            }
        } catch (Exception e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Could not build the QR archive.");
        }
        return out.toByteArray();
    }
}