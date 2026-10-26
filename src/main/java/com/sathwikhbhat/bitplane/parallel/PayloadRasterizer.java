package com.sathwikhbhat.bitplane.parallel;

import java.awt.image.BufferedImage;
import java.util.List;

/**
 * Turns the raw bytes of a file into payload frame images (frame 1..F on disk). Image {@code i} holds the 4-byte
 * big-endian frame index, then chunk {@code i} of the file, then zero padding, in row-major 1920x1080 gray pixels.
 */
public interface PayloadRasterizer {

    List<BufferedImage> rasterize(byte[] fileBytes);
}
