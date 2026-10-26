package com.sathwikhbhat.bitplane.video;

import com.sathwikhbhat.bitplane.image.ImageIOCodec;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** PNG frame read/write on a thread pool. File names and bytes match the sequential loops in the video classes. */
public class ParallelFrameIO {

    private final int threads;
    private final ImageIOCodec imageIOCodec = new ImageIOCodec();

    public ParallelFrameIO(int threads) {
        this.threads = Math.max(1, threads);
    }

    /** Writes {@code images.get(i)} as frame number {@code firstIndex + i}. */
    public void writeAll(List<BufferedImage> images, Path frameDirectory, int firstIndex) throws IOException {
        try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            List<Future<?>> futures = new ArrayList<>(images.size());
            for (int i = 0; i < images.size(); i++) {
                int position = i;
                futures.add(executor.submit(() -> {
                    try {
                        imageIOCodec.write(
                                images.get(position),
                                frameDirectory.resolve(FrameFileName.atIndex(firstIndex + position)));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }));
            }
            awaitAll(futures);
        }
    }

    /** Reads the given PNG files, returning images in the same order as {@code paths}. */
    public List<BufferedImage> readAll(List<Path> paths) throws IOException {
        try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            List<Future<BufferedImage>> futures = new ArrayList<>(paths.size());
            for (Path path : paths) {
                futures.add(executor.submit(() -> {
                    try {
                        return imageIOCodec.read(path);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }));
            }
            List<BufferedImage> images = new ArrayList<>(paths.size());
            for (Future<BufferedImage> future : futures) {
                images.add(await(future));
            }
            return images;
        }
    }

    private static void awaitAll(List<Future<?>> futures) throws IOException {
        for (Future<?> future : futures) {
            await(future);
        }
    }

    private static <T> T await(Future<T> future) throws IOException {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for frame IO", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof UncheckedIOException io) {
                throw io.getCause();
            }
            throw new IOException("Frame IO failed", e.getCause());
        }
    }
}
