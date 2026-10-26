package com.sathwikhbhat.bitplane.parallel.gpu;

import static com.sathwikhbhat.bitplane.parallel.FrameAssertions.assertIdentical;
import static com.sathwikhbhat.bitplane.parallel.FrameAssertions.randomBytes;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sathwikhbhat.bitplane.constants.Constants;
import com.sathwikhbhat.bitplane.parallel.SequentialRasterizer;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Compares kernel output with the sequential {@code RasterCodec} path. Uses any OpenCL device (a CPU runtime such as
 * pocl counts) so the kernel is checked even without a GPU; skipped only when OpenCL is not installed at all.
 */
class OpenClRasterizerTest {

    private static final int MAX_PAYLOAD = Constants.FRAME_BYTE_CAPACITY - Integer.BYTES;
    private static OpenClRasterizer rasterizer;

    @BeforeAll
    static void createRasterizer() {
        Optional<OpenClRasterizer> created = OpenClRasterizer.create(true, 2);
        assumeTrue(created.isPresent(), "No OpenCL device available");
        rasterizer = created.get();
        System.out.println("OpenCL test device: " + rasterizer.deviceDescription());
    }

    @AfterAll
    static void closeRasterizer() {
        if (rasterizer != null) {
            rasterizer.close();
        }
    }

    @ParameterizedTest
    @ValueSource(
            ints = {1, 3, 4, 5, MAX_PAYLOAD - 1, MAX_PAYLOAD, MAX_PAYLOAD + 1, 2 * MAX_PAYLOAD, 5 * MAX_PAYLOAD + 999})
    void rasterize_isByteIdenticalToSequential(int length) {
        byte[] data = randomBytes(length, 42L + length);

        assertIdentical(rasterizer.rasterize(data), new SequentialRasterizer().rasterize(data));
    }

    @Test
    void rasterize_isRepeatable() {
        byte[] data = randomBytes(3 * MAX_PAYLOAD + 17, 99);
        var expected = new SequentialRasterizer().rasterize(data);

        assertIdentical(rasterizer.rasterize(data), expected);
        assertIdentical(rasterizer.rasterize(data), expected);
    }
}
