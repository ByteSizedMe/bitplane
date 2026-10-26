package com.sathwikhbhat.bitplane.parallel;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs {@code mpirun -np N java ... com.sathwikhbhat.bitplane.mpi.MpiEncodeMain} as a child process. The MPI
 * program lives outside the Spring app (see {@code mpi/README.md}) because it needs the MPI Java bindings.
 */
public class MpiEncodeLauncher {

    private static final Logger log = LoggerFactory.getLogger(MpiEncodeLauncher.class);
    static final String MAIN_CLASS = "com.sathwikhbhat.bitplane.mpi.MpiEncodeMain";

    private final String mpirun;
    private final String extraArgs;
    private final int processes;
    private final String classpath;
    private final String libraryPath;

    public MpiEncodeLauncher(String mpirun, String extraArgs, int processes, String classpath, String libraryPath) {
        this.mpirun = mpirun;
        this.extraArgs = extraArgs == null ? "" : extraArgs;
        this.processes = Math.max(1, processes);
        this.classpath = classpath;
        this.libraryPath = libraryPath;
    }

    public boolean isConfigured() {
        return classpath != null && !classpath.isBlank();
    }

    /** Encodes {@code inputFile} into {@code jobDirectory/output.mp4} and returns that path. */
    public Path encode(Path inputFile, String originalFileName, Path jobDirectory) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(mpirun);
        command.addAll(
                Arrays.stream(extraArgs.split("\\s+")).filter(a -> !a.isBlank()).toList());
        command.addAll(List.of("-np", Integer.toString(processes)));
        command.add("java");
        if (libraryPath != null && !libraryPath.isBlank()) {
            command.add("-Djava.library.path=" + libraryPath);
        }
        command.addAll(List.of(
                "-cp",
                classpath,
                MAIN_CLASS,
                inputFile.toAbsolutePath().toString(),
                jobDirectory.toAbsolutePath().toString(),
                originalFileName));

        log.debug("Launching MPI encode: {}", command);
        Process process = new ProcessBuilder(command).inheritIO().start();
        try {
            int exit = process.waitFor();
            if (exit != 0) {
                throw new IllegalStateException("MPI encode failed with exit code " + exit);
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("MPI encode interrupted", e);
        }
        return jobDirectory.resolve("output.mp4");
    }
}
