package com.sathwikhbhat.bitplane.parallel;

import static org.assertj.core.api.Assertions.assertThat;

import com.sathwikhbhat.bitplane.constants.Constants;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.util.List;
import java.util.Random;

public final class FrameAssertions {

    public static final int MAX_PAYLOAD = Constants.FRAME_BYTE_CAPACITY - Integer.BYTES;

    private FrameAssertions() {}

    public static byte[] randomBytes(int length, long seed) {
        byte[] data = new byte[length];
        new Random(seed).nextBytes(data);
        return data;
    }

    public static byte[] pixels(BufferedImage image) {
        return ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
    }

    public static void assertIdentical(List<BufferedImage> actual, List<BufferedImage> expected) {
        assertThat(actual).hasSameSizeAs(expected);
        for (int i = 0; i < expected.size(); i++) {
            assertThat(actual.get(i).getType()).isEqualTo(expected.get(i).getType());
            assertThat(pixels(actual.get(i))).as("frame %d pixels", i).isEqualTo(pixels(expected.get(i)));
        }
    }
}
