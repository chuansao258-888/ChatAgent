package com.yulong.chatagent.rag.parser;

import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * Renders PDF pages to PNG images at a configured DPI.
 */
class PdfPageRenderer {

    private final float renderDpi;

    PdfPageRenderer(float renderDpi) {
        this.renderDpi = Math.max(72f, renderDpi);
    }

    RenderedPageImage renderPageAsPng(PDFRenderer pdfRenderer, int pageIndex) throws IOException {
        BufferedImage image = pdfRenderer.renderImageWithDPI(pageIndex, renderDpi, ImageType.RGB);
        try (UnsafeByteArrayOutputStream outputStream = new UnsafeByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", outputStream)) {
                throw new IOException("No ImageIO writer found for PNG");
            }
            return outputStream.toRenderedPageImage();
        } finally {
            if (image != null) {
                image.flush();
            }
        }
    }

    float renderDpi() {
        return renderDpi;
    }

    record RenderedPageImage(byte[] bytes, int length) {
        void clear() {
            if (bytes != null) {
                Arrays.fill(bytes, (byte) 0);
            }
        }
    }

    static final class UnsafeByteArrayOutputStream extends ByteArrayOutputStream {
        RenderedPageImage toRenderedPageImage() {
            return new RenderedPageImage(buf, count);
        }
    }
}
