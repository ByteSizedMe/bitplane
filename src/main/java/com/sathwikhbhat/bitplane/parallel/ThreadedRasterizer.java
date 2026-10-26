package com.sathwikhbhat.bitplane.parallel;

import com.sathwikhbhat.bitplane.constants.Constants;
import com.sathwikhbhat.bitplane.frame.codec.PayloadFrameCodec;
import com.sathwikhbhat.bitplane.frame.model.PayloadFrame;
import com.sathwikhbhat.bitplane.image.RasterCodec;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Builds each frame on a pool thread; frames are independent so output order is the frame index order. */
public class ThreadedRasterizer implements PayloadRasterizer {

    private final int threads;
    private final RasterCodec rasterCodec = new RasterCodec();
    private final PayloadFrameCodec payloadFrameCodec = new PayloadFrameCodec();

    public ThreadedRasterizer(int threads) {
        this.threads = Math.max(1, threads);
    }

    @Override
    public List<BufferedImage> rasterize(byte[] fileBytes) {
        int maxPayloadSize = Constants.FRAME_BYTE_CAPACITY - Integer.BYTES;
        int totalFrames = (fileBytes.length + maxPayloadSize - 1) / maxPayloadSize;

        try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            List<Future<BufferedImage>> futures = new ArrayList<>(totalFrames);
            for (int frameIndex = 0; frameIndex < totalFrames; frameIndex++) {
                int index = frameIndex;
                futures.add(executor.submit(() -> {
                    int start = index * maxPayloadSize;
                    int end = Math.min(start + maxPayloadSize, fileBytes.length);
                    byte[] chunk = Arrays.copyOfRange(fileBytes, start, end);
                    return rasterCodec.serialize(payloadFrameCodec.serialize(new PayloadFrame(index, chunk)));
                }));
            }

            List<BufferedImage> images = new ArrayList<>(totalFrames);
            for (Future<BufferedImage> future : futures) {
                images.add(await(future));
            }
            return images;
        }
    }

    static <T> T await(Future<T> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for frame worker", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Frame worker failed", e.getCause());
        }
    }
}
