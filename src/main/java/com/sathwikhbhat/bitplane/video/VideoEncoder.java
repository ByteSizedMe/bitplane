package com.sathwikhbhat.bitplane.video;

import com.sathwikhbhat.bitplane.image.ImageIOCodec;
import com.sathwikhbhat.bitplane.pipeline.ImageFrameSet;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VideoEncoder {

    private static final Logger log = LoggerFactory.getLogger(VideoEncoder.class);

    private final FFmpegExecutor ffmpegExecutor = new FFmpegExecutor();
    private final ImageIOCodec imageIOCodec = new ImageIOCodec();
    private final ParallelFrameIO parallelFrameIO;

    public VideoEncoder() {
        this(null);
    }

    /** @param parallelFrameIO writes PNG frames concurrently when non-null; sequential loop otherwise */
    public VideoEncoder(ParallelFrameIO parallelFrameIO) {
        this.parallelFrameIO = parallelFrameIO;
    }

    public Path encode(ImageFrameSet imageFrameSet, Path jobDirectory) throws IOException {
        BufferedImage metadataFrameImage = imageFrameSet.metadataImage();
        List<BufferedImage> payloadFrameImages = imageFrameSet.payloadImages();

        Path frameDirectory = jobDirectory.resolve("frames");
        Path outputVideo = jobDirectory.resolve("output.mp4");

        Files.createDirectories(frameDirectory);

        // Frame 0 = Metadata
        imageIOCodec.write(metadataFrameImage, frameDirectory.resolve(FrameFileName.atIndex(0)));

        // Frame 1+ = Payload Frames
        if (parallelFrameIO != null) {
            parallelFrameIO.writeAll(payloadFrameImages, frameDirectory, 1);
        } else {
            for (int i = 0; i < payloadFrameImages.size(); i++) {
                imageIOCodec.write(payloadFrameImages.get(i), frameDirectory.resolve(FrameFileName.atIndex(i + 1)));
            }
        }

        encodeFrameDirectory(frameDirectory, outputVideo);

        return outputVideo;
    }

    /** Runs only the FFmpeg step over {@code frame_%06d.png} files that already exist in {@code frameDirectory}. */
    public void encodeFrameDirectory(Path frameDirectory, Path outputVideo) throws IOException {
        ProcessBuilder processBuilder = new ProcessBuilder(
                "ffmpeg",
                "-y",
                "-nostdin",
                "-v",
                "error",
                "-framerate",
                "30",
                "-i",
                frameDirectory.resolve(FrameFileName.pattern()).toString(),
                "-c:v",
                "libx264rgb",
                "-pix_fmt",
                "rgb24",
                "-crf",
                "0",
                "-preset",
                "veryslow",
                outputVideo.toString());

        ffmpegExecutor.execute(processBuilder);

        log.debug("Encoded video: {}", outputVideo.toAbsolutePath());
    }
}
