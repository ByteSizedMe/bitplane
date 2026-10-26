package com.sathwikhbhat.bitplane.pipeline;

import static com.sathwikhbhat.bitplane.parallel.FrameAssertions.randomBytes;
import static org.assertj.core.api.Assertions.assertThat;

import com.sathwikhbhat.bitplane.parallel.ThreadedRasterizer;
import com.sathwikhbhat.bitplane.video.ParallelFrameIO;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ParallelPipelineTest {

    @TempDir
    Path tempDir;

    private static byte[] pixels(BufferedImage image) {
        return ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
    }

    @Test
    void encodeAndDecode_matchSequentialPipelines() throws IOException {
        byte[] data = randomBytes(5_000_000, 3);
        Path input = tempDir.resolve("in.bin");
        Files.write(input, data);

        ImageFrameSet sequential = new EncodePipeline().encode(input, "in.bin");
        ImageFrameSet parallel = new ParallelEncodePipeline(new ThreadedRasterizer(4)).encode(input, "in.bin");

        assertThat(pixels(parallel.metadataImage())).isEqualTo(pixels(sequential.metadataImage()));
        assertThat(parallel.payloadImages()).hasSameSizeAs(sequential.payloadImages());
        for (int i = 0; i < sequential.payloadImages().size(); i++) {
            assertThat(pixels(parallel.payloadImages().get(i)))
                    .isEqualTo(pixels(sequential.payloadImages().get(i)));
        }

        Path seqOut = new DecodePipeline().decode(sequential, tempDir.resolve("seq"));
        Path parOut = new ParallelDecodePipeline(4).decode(parallel, tempDir.resolve("par"));

        assertThat(Files.readAllBytes(parOut)).isEqualTo(data).isEqualTo(Files.readAllBytes(seqOut));
    }

    @Test
    void parallelFrameIo_writesSameFilesAsSequentialAndReadsBack() throws IOException {
        byte[] data = randomBytes(4_500_000, 5);
        Path input = tempDir.resolve("in.bin");
        Files.write(input, data);
        List<BufferedImage> images =
                new EncodePipeline().encode(input, "in.bin").payloadImages();

        Path dir = tempDir.resolve("frames");
        Files.createDirectories(dir);
        ParallelFrameIO io = new ParallelFrameIO(3);
        io.writeAll(images, dir, 1);

        List<Path> paths;
        try (var stream = Files.list(dir)) {
            paths = stream.sorted().toList();
        }
        assertThat(paths).hasSameSizeAs(images);
        assertThat(paths.getFirst().getFileName().toString()).isEqualTo("frame_000001.png");

        List<BufferedImage> read = io.readAll(paths);
        for (int i = 0; i < images.size(); i++) {
            assertThat(pixels(read.get(i))).isEqualTo(pixels(images.get(i)));
        }
    }
}
