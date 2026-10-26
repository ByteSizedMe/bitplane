package com.sathwikhbhat.bitplane.video;

import com.sathwikhbhat.bitplane.image.ImageIOCodec;
import com.sathwikhbhat.bitplane.pipeline.ImageFrameSet;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VideoDecoder {

    private static final Logger log = LoggerFactory.getLogger(VideoDecoder.class);

    private final FFmpegExecutor ffmpegExecutor = new FFmpegExecutor();
    private final ImageIOCodec imageIOCodec = new ImageIOCodec();
    private final ParallelFrameIO parallelFrameIO;

    public VideoDecoder() {
        this(null);
    }

    /** @param parallelFrameIO reads PNG frames concurrently when non-null; sequential loop otherwise */
    public VideoDecoder(ParallelFrameIO parallelFrameIO) {
        this.parallelFrameIO = parallelFrameIO;
    }

    public ImageFrameSet decode(Path videoPath, Path jobDirectory) throws IOException {
        List<Path> framePaths = extractFrames(videoPath, jobDirectory);

        return readFrames(framePaths);
    }

    /** Runs only the FFmpeg step and returns the extracted PNG paths in frame order. */
    public List<Path> extractFrames(Path videoPath, Path jobDirectory) throws IOException {
        if (!Files.exists(videoPath)) {
            throw new IOException("Video file does not exist: " + videoPath);
        }

        Path extractedFrameDirectory = jobDirectory.resolve("extracted_frames");
        Files.createDirectories(extractedFrameDirectory);

        log.debug("Extracting frames from video: {}", videoPath);

        ProcessBuilder processBuilder = new ProcessBuilder(
                "ffmpeg",
                "-y",
                "-nostdin",
                "-v",
                "error",
                "-i",
                videoPath.toString(),
                "-start_number",
                "0",
                extractedFrameDirectory.resolve(FrameFileName.pattern()).toString());

        try {
            ffmpegExecutor.execute(processBuilder);
        } catch (RuntimeException e) {
            throw new IllegalStateException("The uploaded file could not be read as a video", e);
        }

        List<Path> framePaths;

        try (var paths = Files.list(extractedFrameDirectory)) {
            framePaths = paths.sorted().toList();
        }

        if (framePaths.isEmpty()) {
            throw new IllegalStateException("No frames extracted from video");
        }

        log.debug("Extracted {} image frame(s)", framePaths.size());

        return framePaths;
    }

    /** Reads extracted frames: first is the metadata frame, the rest are payload frames. */
    public ImageFrameSet readFrames(List<Path> framePaths) throws IOException {
        if (parallelFrameIO != null) {
            List<BufferedImage> images = parallelFrameIO.readAll(framePaths);
            return new ImageFrameSet(images.getFirst(), new ArrayList<>(images.subList(1, images.size())));
        }

        BufferedImage metadataImage = imageIOCodec.read(framePaths.getFirst());

        List<BufferedImage> payloadImages = new ArrayList<>();
        for (int i = 1; i < framePaths.size(); i++) {
            payloadImages.add(imageIOCodec.read(framePaths.get(i)));
        }

        return new ImageFrameSet(metadataImage, payloadImages);
    }
}
