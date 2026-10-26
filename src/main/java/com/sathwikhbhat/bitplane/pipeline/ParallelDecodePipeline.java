package com.sathwikhbhat.bitplane.pipeline;

import com.sathwikhbhat.bitplane.frame.codec.MetadataFrameCodec;
import com.sathwikhbhat.bitplane.frame.codec.PayloadFrameCodec;
import com.sathwikhbhat.bitplane.frame.model.MetadataFrame;
import com.sathwikhbhat.bitplane.frame.model.PayloadFrame;
import com.sathwikhbhat.bitplane.image.RasterCodec;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** {@link DecodePipeline} with frame parsing and reassembly spread over a thread pool. Same output bytes. */
public class ParallelDecodePipeline {

    private static final Logger log = LoggerFactory.getLogger(ParallelDecodePipeline.class);

    private final int threads;
    private final RasterCodec rasterCodec = new RasterCodec();
    private final MetadataFrameCodec metadataFrameCodec = new MetadataFrameCodec();
    private final PayloadFrameCodec payloadFrameCodec = new PayloadFrameCodec();

    public ParallelDecodePipeline(int threads) {
        this.threads = Math.max(1, threads);
    }

    public Path decode(ImageFrameSet imageFrameSet, Path jobDirectory) throws IOException {
        byte[] originalFileBytes;
        MetadataFrame metadataFrame =
                metadataFrameCodec.deserialize(rasterCodec.deserialize(imageFrameSet.metadataImage()));
        originalFileBytes = reassemble(metadataFrame, imageFrameSet.payloadImages());

        Files.createDirectories(jobDirectory);
        Path outputPath =
                jobDirectory.resolve(metadataFrame.fileName().replace('/', '_').replace('\\', '_'));
        Files.write(outputPath, originalFileBytes);

        log.debug("Decoded file: {} ({} bytes)", outputPath.toAbsolutePath(), originalFileBytes.length);

        return outputPath;
    }

    /** Parses all payload frames and returns the original file bytes. Exposed so benchmarks can time it alone. */
    public byte[] reassemble(MetadataFrame metadataFrame, List<BufferedImage> payloadFrameImages) {
        validatePayloadFrameCount(metadataFrame, payloadFrameImages.size());

        try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            List<Future<PayloadFrame>> parsed = new ArrayList<>(payloadFrameImages.size());
            for (BufferedImage image : payloadFrameImages) {
                parsed.add(executor.submit(() -> payloadFrameCodec.deserialize(rasterCodec.deserialize(image))));
            }

            List<PayloadFrame> payloadFrames = new ArrayList<>(parsed.size());
            for (Future<PayloadFrame> future : parsed) {
                payloadFrames.add(await(future));
            }

            payloadFrames.sort(Comparator.comparingInt(PayloadFrame::frameIndex));
            validatePayloadFrameOrder(payloadFrames);

            int[] offsets = new int[payloadFrames.size() + 1];
            for (int i = 0; i < payloadFrames.size(); i++) {
                offsets[i + 1] = offsets[i] + payloadFrames.get(i).payload().length;
            }

            byte[] joined = new byte[offsets[payloadFrames.size()]];
            List<Future<?>> copies = new ArrayList<>(payloadFrames.size());
            for (int i = 0; i < payloadFrames.size(); i++) {
                int index = i;
                copies.add(executor.submit(() -> {
                    byte[] payload = payloadFrames.get(index).payload();
                    System.arraycopy(payload, 0, joined, offsets[index], payload.length);
                }));
            }
            for (Future<?> copy : copies) {
                await(copy);
            }

            return Arrays.copyOf(joined, (int) metadataFrame.fileSize());
        }
    }

    private static <T> T await(Future<T> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for frame worker", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Frame worker failed", e.getCause());
        }
    }

    private void validatePayloadFrameCount(MetadataFrame metadataFrame, int actualFrames) {
        int expectedFrames = metadataFrame.totalPayloadFrames();

        if (expectedFrames < 0) {
            throw new IllegalStateException("Invalid payload frame count in metadata: " + expectedFrames);
        }

        if (actualFrames != expectedFrames) {
            throw new IllegalStateException(
                    "Payload frame count mismatch. Expected " + expectedFrames + ", found " + actualFrames);
        }
    }

    private void validatePayloadFrameOrder(List<PayloadFrame> payloadFrames) {
        for (int i = 0; i < payloadFrames.size(); i++) {
            int frameIndex = payloadFrames.get(i).frameIndex();

            if (frameIndex != i) {
                throw new IllegalStateException(
                        "Invalid payload frame sequence. Expected frame " + i + ", found " + frameIndex);
            }
        }
    }
}
