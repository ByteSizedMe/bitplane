package com.sathwikhbhat.bitplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sathwikhbhat.bitplane.parallel.ParallelMode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodecServiceParallelTest {

    @TempDir
    Path tempDir;

    private static CodecService service(String mode) {
        return new CodecService(mode, 2, 4, false, 2, "mpirun", "", "", "");
    }

    private static boolean ffmpegAvailable() {
        try {
            return new ProcessBuilder("ffmpeg", "-version").start().waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    @Test
    void defaultModeIsSequential() {
        assertThat(ParallelMode.parse(null)).isEqualTo(ParallelMode.SEQUENTIAL);
        assertThat(ParallelMode.parse("")).isEqualTo(ParallelMode.SEQUENTIAL);
        assertThat(service("sequential").effectiveMode()).isEqualTo(ParallelMode.SEQUENTIAL);
    }

    @Test
    void mpiWithoutClasspathFallsBackToThreads() {
        assertThat(service("mpi").effectiveMode()).isEqualTo(ParallelMode.THREADS);
    }

    @Test
    void gpuModeRunsOnGpuOrFallsBackToThreads() {
        // On a machine with an OpenCL GPU this is GPU, otherwise threads; never sequential.
        assertThat(service("gpu").effectiveMode()).isIn(ParallelMode.GPU, ParallelMode.THREADS);
    }

    @Test
    void everyCpuModeRoundTripsAndProducesSameBytes() throws IOException {
        assumeTrue(ffmpegAvailable(), "ffmpeg not installed");
        byte[] data = new byte[2_300_000];
        new Random(11).nextBytes(data);
        Path input = tempDir.resolve("payload.bin");
        Files.write(input, data);

        for (String mode : new String[] {"sequential", "threads", "gpu"}) {
            CodecService service = service(mode);
            Path video = service.encode(input, "payload.bin", tempDir.resolve("enc-" + mode));
            Path decoded = service.decode(video, tempDir.resolve("dec-" + mode));

            assertThat(Files.readAllBytes(decoded)).as(mode).isEqualTo(data);
        }
    }
}
