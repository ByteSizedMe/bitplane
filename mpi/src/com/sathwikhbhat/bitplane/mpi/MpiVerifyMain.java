package com.sathwikhbhat.bitplane.mpi;

import com.sathwikhbhat.bitplane.image.ImageIOCodec;
import com.sathwikhbhat.bitplane.image.RasterCodec;
import com.sathwikhbhat.bitplane.pipeline.DecodePipeline;
import com.sathwikhbhat.bitplane.pipeline.EncodePipeline;
import com.sathwikhbhat.bitplane.pipeline.ImageFrameSet;
import com.sathwikhbhat.bitplane.video.VideoDecoder;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/**
 * Checks an {@link MpiEncodeMain} result (no MPI needed). Usage: {@code MpiVerifyMain <input> <outDir> [name]}.
 * Fails (exit 1) unless every PNG frame equals the sequential encoder's frame and decoding output.mp4 returns a
 * file whose SHA-256 equals the input's.
 */
public final class MpiVerifyMain {

    private MpiVerifyMain() {}

    public static void main(String[] args) throws Exception {
        Path input = Path.of(args[0]);
        Path outDir = Path.of(args[1]);
        String name = args.length > 2 ? args[2] : input.getFileName().toString();

        ImageFrameSet expected = new EncodePipeline().encode(input, name);
        RasterCodec raster = new RasterCodec();
        ImageIOCodec io = new ImageIOCodec();

        List<Path> frames;
        try (var stream = Files.list(outDir.resolve("frames"))) {
            frames = stream.sorted().toList();
        }
        int expectedCount = 1 + expected.payloadImages().size();
        check(frames.size() == expectedCount, "frame count " + frames.size() + " != " + expectedCount);

        check(
                same(raster, io.read(frames.getFirst()), expected.metadataImage()),
                "metadata frame differs from sequential");
        for (int i = 0; i < expected.payloadImages().size(); i++) {
            check(
                    same(
                            raster,
                            io.read(frames.get(i + 1)),
                            expected.payloadImages().get(i)),
                    "payload frame " + i + " differs from sequential");
        }

        Path decodedDir = outDir.resolve("verify_decoded");
        VideoDecoder decoder = new VideoDecoder();
        Path decoded =
                new DecodePipeline().decode(decoder.decode(outDir.resolve("output.mp4"), decodedDir), decodedDir);
        String want = sha256(input);
        String got = sha256(decoded);
        check(want.equals(got), "decoded SHA-256 " + got + " != input " + want);

        System.out.println("MPI_VERIFY OK frames=" + frames.size() + " sha256=" + got);
    }

    private static boolean same(RasterCodec raster, BufferedImage a, BufferedImage b) {
        return Arrays.equals(raster.deserialize(a), raster.deserialize(b));
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            System.err.println("MPI_VERIFY FAILED: " + message);
            System.exit(1);
        }
    }

    static String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}
