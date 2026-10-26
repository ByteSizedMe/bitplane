package com.sathwikhbhat.bitplane.mpi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Runs {@code mpi/verify.sh}: MPI encode with 1..3 ranks must write the same frames as the sequential encoder and
 * decode back to the input SHA-256. Skipped unless MPI is installed and {@code mpi/build.sh} has been run once.
 */
class MpiEncodeScriptTest {

    private static final Path ROOT = Path.of("").toAbsolutePath();

    private static boolean commandWorks(String... command) {
        try {
            return new ProcessBuilder(command).redirectErrorStream(true).start().waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    @Test
    void mpiFramesEqualSequentialFramesAndDecodeReturnsInput() throws Exception {
        assumeTrue(commandWorks("mpirun", "--version"), "mpirun not installed (see mpi/README.md)");
        assumeTrue(Files.exists(ROOT.resolve("mpi/lib/mpi.jar")), "mpi/lib/mpi.jar missing: run mpi/build-mpi-jar.sh");
        assumeTrue(
                Files.exists(ROOT.resolve("mpi/build/classes"))
                        && Files.exists(ROOT.resolve("mpi/build/classpath.txt")),
                "MPI classes not built: run mpi/build.sh");
        assumeTrue(commandWorks("ffmpeg", "-version"), "ffmpeg not installed");

        ProcessBuilder builder = new ProcessBuilder("mpi/verify.sh", "2300000", "1", "2", "3")
                .directory(ROOT.toFile())
                .redirectErrorStream(true);
        builder.environment().put("MPI_SKIP_BUILD", "1");
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(process.waitFor(5, TimeUnit.MINUTES)).isTrue();
        assertThat(process.exitValue()).as(output).isZero();
        assertThat(output).contains("ALL MPI CHECKS PASSED");
    }
}
