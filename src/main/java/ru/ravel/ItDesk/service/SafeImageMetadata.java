package ru.ravel.ItDesk.service;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;

/**
 * Reads only image header metadata. It deliberately never decodes the raster,
 * which keeps crafted high-dimension images from allocating pixel-sized buffers.
 */
public final class SafeImageMetadata {

    private static final int MAX_DIMENSION = 50_000;
    private static final long MAX_PIXELS = 100_000_000L;

    private SafeImageMetadata() {
    }

    public static Dimensions read(File file) throws IOException {
        try (ImageInputStream imageInput = ImageIO.createImageInputStream(file)) {
            return read(imageInput);
        }
    }

    public static Dimensions read(InputStream inputStream) throws IOException {
        try (ImageInputStream imageInput = ImageIO.createImageInputStream(inputStream)) {
            return read(imageInput);
        }
    }

    private static Dimensions read(ImageInputStream imageInput) throws IOException {
        if (imageInput == null) {
            throw new IOException("Unsupported image");
        }
        Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
        if (!readers.hasNext()) {
            throw new IOException("Unsupported image");
        }

        ImageReader reader = readers.next();
        try {
            reader.setInput(imageInput, true, true);
            int width = reader.getWidth(0);
            int height = reader.getHeight(0);
            if (width <= 0 || height <= 0
                    || width > MAX_DIMENSION || height > MAX_DIMENSION
                    || (long) width * height > MAX_PIXELS) {
                throw new IOException("Unsafe image dimensions");
            }
            return new Dimensions(width, height);
        } finally {
            reader.dispose();
        }
    }

    public record Dimensions(int width, int height) {
    }
}
