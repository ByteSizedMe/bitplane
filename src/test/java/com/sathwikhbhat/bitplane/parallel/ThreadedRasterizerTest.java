package com.sathwikhbhat.bitplane.parallel;

import static com.sathwikhbhat.bitplane.parallel.FrameAssertions.MAX_PAYLOAD;
import static com.sathwikhbhat.bitplane.parallel.FrameAssertions.assertIdentical;
import static com.sathwikhbhat.bitplane.parallel.FrameAssertions.randomBytes;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ThreadedRasterizerTest {

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 4, 7})
    void rasterize_matchesSequentialForAllThreadCounts(int threads) {
        byte[] data = randomBytes(MAX_PAYLOAD * 3 + 12345, 7);

        assertIdentical(new ThreadedRasterizer(threads).rasterize(data), new SequentialRasterizer().rasterize(data));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 4, MAX_PAYLOAD - 1, MAX_PAYLOAD, MAX_PAYLOAD + 1, 2 * MAX_PAYLOAD})
    void rasterize_matchesSequentialAtFrameBoundaries(int length) {
        byte[] data = randomBytes(length, length);

        assertIdentical(new ThreadedRasterizer(3).rasterize(data), new SequentialRasterizer().rasterize(data));
    }
}
