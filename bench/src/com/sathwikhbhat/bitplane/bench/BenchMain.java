package com.sathwikhbhat.bitplane.bench;

import com.sathwikhbhat.bitplane.image.ImageIOCodec;
import com.sathwikhbhat.bitplane.image.RasterCodec;
import com.sathwikhbhat.bitplane.parallel.PayloadRasterizer;
import com.sathwikhbhat.bitplane.parallel.SequentialRasterizer;
import com.sathwikhbhat.bitplane.parallel.ThreadedRasterizer;
import com.sathwikhbhat.bitplane.parallel.gpu.OpenClRasterizer;
import com.sathwikhbhat.bitplane.pipeline.DecodePipeline;
import com.sathwikhbhat.bitplane.pipeline.EncodePipeline;
import com.sathwikhbhat.bitplane.pipeline.ImageFrameSet;
import com.sathwikhbhat.bitplane.pipeline.ParallelDecodePipeline;
import com.sathwikhbhat.bitplane.service.CodecService;
import com.sathwikhbhat.bitplane.video.ParallelFrameIO;
import com.sathwikhbhat.bitplane.video.VideoDecoder;
import com.sathwikhbhat.bitplane.video.VideoEncoder;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

/**
 * Benchmark harness (not part of the Spring app). Every number written is a System.nanoTime measurement taken in
 * this run; anything that cannot run on this machine is recorded as a "skipped" row with the reason.
 */
public final class BenchMain {

    interface Step {
        void run() throws Exception;
    }

    record Row(
            String input,
            long sizeBytes,
            String category,
            String mode,
            String config,
            String metric,
            String status,
            int warmups,
            int runs,
            double[] seconds,
            String note) {

        double median() {
            double[] sorted = seconds.clone();
            Arrays.sort(sorted);
            int n = sorted.length;
            return n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0;
        }
    }

    /** false = never run the pocl (OpenCL on CPU) diagnostic configuration. */
    private static boolean openClCpuEnabled = true;

    private static final long SEED = 20260101L;
    private static final RasterCodec RASTER = new RasterCodec();
    private static final ImageIOCodec IMAGE_IO = new ImageIOCodec();

    private final int warmups;
    private final int runs;
    private final Path outDir;
    private final Path workDir;
    private final String mpiClasspath;
    private final String mpiLibraryPath;
    private final List<Integer> mpiProcessCounts;
    private final int cores = Runtime.getRuntime().availableProcessors();
    private final List<Row> rows = new ArrayList<>();
    private final List<String> inputNotes = new ArrayList<>();
    private String hardware = "";

    private BenchMain(
            int warmups,
            int runs,
            Path outDir,
            Path workDir,
            String mpiClasspath,
            String mpiLibraryPath,
            List<Integer> mpiProcessCounts) {
        this.warmups = warmups;
        this.runs = runs;
        this.outDir = outDir;
        this.workDir = workDir;
        this.mpiClasspath = mpiClasspath;
        this.mpiLibraryPath = mpiLibraryPath;
        this.mpiProcessCounts = mpiProcessCounts;
    }

    public static void main(String[] args) throws Exception {
        String sizes = "1,5,25,100";
        boolean compressible = true;
        int warmups = 3;
        int runs = 5;
        Path out = Path.of("bench");
        Path work = Path.of("bench/work");
        String mpiClasspath = "";
        String mpiLibraryPath = "/usr/lib/x86_64-linux-gnu/openmpi/lib";
        String nps = "1,2,4";
        for (int i = 0; i < args.length; i += 2) {
            switch (args[i]) {
                case "--sizes" -> sizes = args[i + 1];
                case "--compressible" -> compressible = Boolean.parseBoolean(args[i + 1]);
                case "--warmups" -> warmups = Integer.parseInt(args[i + 1]);
                case "--runs" -> runs = Integer.parseInt(args[i + 1]);
                case "--out" -> out = Path.of(args[i + 1]);
                case "--work" -> work = Path.of(args[i + 1]);
                case "--mpi-classpath" -> mpiClasspath = args[i + 1];
                case "--mpi-library-path" -> mpiLibraryPath = args[i + 1];
                case "--mpi-np" -> nps = args[i + 1];
                case "--opencl-cpu" -> openClCpuEnabled = Boolean.parseBoolean(args[i + 1]);
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        List<Integer> npList = Stream.of(nps.split(","))
                .filter(s -> !s.isBlank())
                .map(Integer::parseInt)
                .toList();
        BenchMain bench = new BenchMain(warmups, runs, out, work, mpiClasspath, mpiLibraryPath, npList);
        bench.hardware = Hardware.describe();
        System.out.println(bench.hardware);

        for (String size : sizes.split(",")) {
            long bytes = Long.parseLong(size.trim()) * 1024 * 1024;
            byte[] data = new byte[(int) bytes];
            new Random(SEED).nextBytes(data);
            bench.runInput("random-" + size.trim() + "MiB", data, "random bytes, java.util.Random seed " + SEED);
        }
        if (compressible) {
            bench.runInput("compressible-25MiB", compressibleData(25 * 1024 * 1024), "repeated-word text");
        }
        bench.writeResults();
        System.out.println("DONE");
    }

    // ---------------------------------------------------------------------------------------------------------

    private void runInput(String label, byte[] data, String description) throws Exception {
        System.out.println("=== input " + label + " ===");
        Path dir = workDir.resolve(label);
        deleteRecursively(dir);
        Files.createDirectories(dir);
        Path inputFile = dir.resolve("input.bin");
        Files.write(inputFile, data);
        String sha = sha256(data);

        // Sequential reference: the existing EncodePipeline. Everything else is compared against these frames.
        ImageFrameSet baseline = new EncodePipeline().encode(inputFile, "input.bin");
        int payloadFrames = baseline.payloadImages().size();
        inputNotes.add(String.format(
                "%s: %d bytes, %s, sha256=%s, payload frames=%d (+1 metadata), gzip ratio=%.3f",
                label, data.length, description, sha, payloadFrames, gzipRatio(data)));

        List<Config> configs = new ArrayList<>();
        configs.add(new Config("sequential", "1", 1, null));
        for (int t : List.of(1, 2, 4)) {
            configs.add(new Config("threads", Integer.toString(t), t, new ThreadedRasterizer(t)));
        }
        if (cores != 1 && cores != 2 && cores != 4) {
            configs.add(
                    new Config("threads", cores + " (N=availableProcessors)", cores, new ThreadedRasterizer(cores)));
        }

        Optional<OpenClRasterizer> gpu = OpenClRasterizer.create(false, 8);
        boolean haveGpu = gpu.isPresent();
        Optional<OpenClRasterizer> cpuCl =
                haveGpu || !openClCpuEnabled ? Optional.empty() : OpenClRasterizer.create(true, 8);
        if (haveGpu) {
            configs.add(new Config("gpu", "OpenCL GPU", cores, gpu.get()));
        } else {
            addSkipped(
                    label,
                    data.length,
                    "stage",
                    "gpu",
                    "-",
                    "frame_construction",
                    "no OpenCL GPU device on this machine (no nvidia-smi / only CPU OpenCL runtime)");
            addSkipped(
                    label,
                    data.length,
                    "e2e",
                    "gpu",
                    "-",
                    "encode",
                    "no OpenCL GPU device on this machine; gpu mode would fall back to threads");
        }
        if (cpuCl.isPresent()) {
            configs.add(new Config("opencl-cpu-NOT-A-GPU", "pocl", cores, cpuCl.get()));
            inputNotes.add("opencl-cpu-NOT-A-GPU device: " + cpuCl.get().deviceDescription());
        }

        // Frames on disk for the read / ffmpeg stages (written once, sequentially, from the baseline).
        Path refFrames = dir.resolve("ref_frames");
        writeSequential(baseline, refFrames);

        for (Config cfg : configs) {
            System.out.println("  stages: " + cfg.mode + " " + cfg.config);
            runStages(label, data.length, inputFile, data, sha, baseline, refFrames, dir, cfg);
        }

        // FFmpeg encode and decode (identical command for every mode).
        Path mp4 = dir.resolve("ref.mp4");
        measureRow(
                label,
                data.length,
                "ffmpeg",
                "ffmpeg",
                "-",
                "encode",
                () -> Files.deleteIfExists(mp4),
                () -> new VideoEncoder().encodeFrameDirectory(refFrames, mp4),
                () -> verifyVideoDecodes(mp4, dir.resolve("ffv"), sha),
                "libx264rgb crf 0 preset veryslow, frames already on disk");
        Path ffDecodeDir = dir.resolve("ffdec");
        measureRow(
                label,
                data.length,
                "ffmpeg",
                "ffmpeg",
                "-",
                "decode",
                () -> deleteRecursively(ffDecodeDir),
                () -> new VideoDecoder().extractFrames(mp4, ffDecodeDir),
                () -> verifyFrameDir(ffDecodeDir.resolve("extracted_frames"), baseline),
                "frame extraction to PNG only");

        // End-to-end encode/decode per mode.
        for (Config cfg : configs) {
            System.out.println("  e2e: " + cfg.mode + " " + cfg.config);
            runEndToEnd(label, data.length, inputFile, sha, baseline, mp4, dir, cfg);
        }
        runMpi(label, data.length, inputFile, sha, baseline, dir);

        gpu.ifPresent(OpenClRasterizer::close);
        cpuCl.ifPresent(OpenClRasterizer::close);
        deleteRecursively(dir);
        writeResults();
    }

    private record Config(String mode, String config, int threads, PayloadRasterizer rasterizer) {}

    // ---------------------------------------------------------------------------------------------------------

    private void runStages(
            String label,
            long size,
            Path inputFile,
            byte[] data,
            String sha,
            ImageFrameSet baseline,
            Path refFrames,
            Path dir,
            Config cfg)
            throws Exception {
        boolean seq = cfg.mode.equals("sequential");
        PayloadRasterizer rasterizer = seq ? new SequentialRasterizer() : cfg.rasterizer;
        // PNG IO and parsing always use the CPU pool sized by the thread count (cores for gpu configs).
        ParallelFrameIO io = new ParallelFrameIO(cfg.threads);
        List<Object> holder = new ArrayList<>();

        measureRow(
                label,
                size,
                "stage",
                cfg.mode,
                cfg.config,
                "frame_construction",
                () -> holder.clear(),
                () -> holder.add(rasterizer.rasterize(data)),
                () -> {
                    @SuppressWarnings("unchecked")
                    List<BufferedImage> images = (List<BufferedImage>) holder.getFirst();
                    assertSame(images, baseline.payloadImages(), "frame construction");
                },
                null);

        Path writeDir = dir.resolve("write_" + cfg.mode);
        List<BufferedImage> allFrames = new ArrayList<>();
        allFrames.add(baseline.metadataImage());
        allFrames.addAll(baseline.payloadImages());
        measureRow(
                label,
                size,
                "stage",
                cfg.mode,
                cfg.config,
                "png_write",
                () -> {
                    deleteRecursively(writeDir);
                    Files.createDirectories(writeDir);
                },
                () -> {
                    if (seq) {
                        for (int i = 0; i < allFrames.size(); i++) {
                            IMAGE_IO.write(allFrames.get(i), writeDir.resolve(String.format("frame_%06d.png", i)));
                        }
                    } else {
                        io.writeAll(allFrames, writeDir, 0);
                    }
                },
                () -> verifyFrameDir(writeDir, baseline),
                "metadata + payload frames");
        deleteRecursively(writeDir);

        List<Path> refPaths;
        try (Stream<Path> s = Files.list(refFrames)) {
            refPaths = s.sorted().toList();
        }
        holder.clear();
        measureRow(
                label,
                size,
                "stage",
                cfg.mode,
                cfg.config,
                "png_read",
                () -> holder.clear(),
                () -> holder.add(seq ? readSequential(refPaths) : io.readAll(refPaths)),
                () -> {
                    @SuppressWarnings("unchecked")
                    List<BufferedImage> images = (List<BufferedImage>) holder.getFirst();
                    assertSame(images.subList(1, images.size()), baseline.payloadImages(), "png read");
                    assertSame(List.of(images.getFirst()), List.of(baseline.metadataImage()), "png read metadata");
                },
                "metadata + payload frames");

        Path parseDir = dir.resolve("parse_" + cfg.mode);
        measureRow(
                label,
                size,
                "stage",
                cfg.mode,
                cfg.config,
                "frame_parse_reassemble",
                () -> deleteRecursively(parseDir),
                () -> {
                    if (seq) {
                        new DecodePipeline().decode(baseline, parseDir);
                    } else {
                        new ParallelDecodePipeline(cfg.threads).decode(baseline, parseDir);
                    }
                },
                () -> {
                    String got = sha256(Files.readAllBytes(parseDir.resolve("input.bin")));
                    if (!got.equals(sha)) {
                        throw new IllegalStateException("parse/reassemble sha mismatch " + got + " vs " + sha);
                    }
                },
                "includes writing the reassembled file");
        deleteRecursively(parseDir);
    }

    private void runEndToEnd(
            String label,
            long size,
            Path inputFile,
            String sha,
            ImageFrameSet baseline,
            Path refMp4,
            Path dir,
            Config cfg)
            throws Exception {
        boolean gpuMode = cfg.mode.equals("gpu") || cfg.mode.startsWith("opencl");
        String serviceMode = gpuMode ? "gpu" : cfg.mode;
        CodecService service =
                new CodecService(serviceMode, cfg.threads, 8, cfg.mode.startsWith("opencl"), 2, "mpirun", "", "", "");
        if (gpuMode && service.effectiveMode() != com.sathwikhbhat.bitplane.parallel.ParallelMode.GPU) {
            throw new IllegalStateException("gpu mode did not run on OpenCL; refusing to label results as gpu");
        }
        Path encDir = dir.resolve("e2e_enc_" + cfg.mode);
        measureRow(
                label,
                size,
                "e2e",
                cfg.mode,
                cfg.config,
                "encode",
                () -> deleteRecursively(encDir),
                () -> service.encode(inputFile, "input.bin", encDir),
                () -> {
                    verifyFrameDir(encDir.resolve("frames"), baseline);
                    verifyVideoDecodes(encDir.resolve("output.mp4"), dir.resolve("e2e_verify"), sha);
                },
                "file -> frames -> PNG -> ffmpeg -> mp4");
        deleteRecursively(encDir);

        Path decDir = dir.resolve("e2e_dec_" + cfg.mode);
        measureRow(
                label,
                size,
                "e2e",
                cfg.mode,
                cfg.config,
                "decode",
                () -> deleteRecursively(decDir),
                () -> service.decode(refMp4, decDir),
                () -> {
                    String got = sha256(Files.readAllBytes(decDir.resolve("input.bin")));
                    if (!got.equals(sha)) {
                        throw new IllegalStateException("decoded sha mismatch " + got + " vs " + sha);
                    }
                },
                "mp4 -> ffmpeg -> PNG read -> parse -> file");
        deleteRecursively(decDir);
        service.shutdown();
    }

    private void runMpi(String label, long size, Path inputFile, String sha, ImageFrameSet baseline, Path dir)
            throws Exception {
        if (mpiClasspath.isBlank()) {
            addSkipped(label, size, "e2e", "mpi", "-", "encode", "no --mpi-classpath given (MPI not set up)");
            return;
        }
        for (int np : mpiProcessCounts) {
            System.out.println("  e2e: mpi np=" + np);
            Path outDir = dir.resolve("e2e_mpi_" + np);
            double[][] timing = new double[1][];
            List<double[]> inProcess = new ArrayList<>();
            List<double[]> phases = new ArrayList<>();
            int[] counter = {0};
            Row row = measureRow(
                    label,
                    size,
                    "e2e",
                    "mpi",
                    "np=" + np,
                    "encode_wall_incl_jvm_start",
                    () -> deleteRecursively(outDir),
                    () -> timing[0] = launchMpi(np, inputFile, outDir),
                    () -> {
                        verifyFrameDir(outDir.resolve("frames"), baseline);
                        verifyVideoDecodes(outDir.resolve("output.mp4"), dir.resolve("mpi_verify"), sha);
                        counter[0]++;
                        if (counter[0] > warmups) {
                            inProcess.add(new double[] {timing[0][1]});
                            phases.add(timing[0]);
                        }
                    },
                    "mpirun -np " + np + " java ... MpiEncodeMain (fresh JVMs + MPI_Init each run)");
            double[] inside = inProcess.stream().mapToDouble(a -> a[0]).toArray();
            rows.add(new Row(
                    label,
                    size,
                    "e2e",
                    "mpi",
                    "np=" + np,
                    "encode_in_process_excl_jvm_start",
                    "ok",
                    warmups,
                    runs,
                    inside,
                    "MPI_TIMING total_in_process_s reported by rank 0"));
            addPhase(label, size, np, "mpi_read_s", phases, 2);
            addPhase(label, size, np, "mpi_scatter_s", phases, 3);
            addPhase(label, size, np, "mpi_build_max_over_ranks_s", phases, 4);
            addPhase(label, size, np, "mpi_png_write_max_over_ranks_s", phases, 5);
            addPhase(label, size, np, "mpi_ffmpeg_s", phases, 6);
            deleteRecursively(outDir);
        }
        addSkipped(
                label,
                size,
                "e2e",
                "mpi",
                "-",
                "decode",
                "decode is not distributed over MPI (spec defines MPI for encode); mpi decode = threads decode");
    }

    private void addPhase(String label, long size, int np, String metric, List<double[]> phases, int index) {
        double[] v = phases.stream().mapToDouble(a -> a[index]).toArray();
        rows.add(new Row(
                label,
                size,
                "e2e-phase",
                "mpi",
                "np=" + np,
                metric,
                "ok",
                warmups,
                runs,
                v,
                "rank 0 / max over ranks, from MPI_TIMING"));
    }

    /** @return {wall seconds incl. JVM start, in-process total, read, scatter, build_max, write_max, ffmpeg} */
    private double[] launchMpi(int np, Path inputFile, Path outDir) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("mpirun"));
        if ("root".equals(System.getProperty("user.name"))) {
            cmd.add("--allow-run-as-root");
        }
        cmd.addAll(List.of(
                "--oversubscribe",
                "-np",
                Integer.toString(np),
                "java",
                "-Xmx2g",
                "-Djava.library.path=" + mpiLibraryPath,
                "-cp",
                mpiClasspath,
                "com.sathwikhbhat.bitplane.mpi.MpiEncodeMain",
                inputFile.toAbsolutePath().toString(),
                outDir.toAbsolutePath().toString(),
                "input.bin"));
        Files.createDirectories(outDir);
        File stdout = outDir.resolve("mpi.stdout").toFile();
        File stderr = outDir.resolve("mpi.stderr").toFile();
        long t0 = System.nanoTime();
        Process p = new ProcessBuilder(cmd)
                .redirectOutput(stdout)
                .redirectError(stderr)
                .start();
        if (!p.waitFor(30, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            throw new IllegalStateException("mpirun timed out");
        }
        double wall = (System.nanoTime() - t0) / 1e9;
        if (p.exitValue() != 0) {
            throw new IllegalStateException("mpirun failed: " + Files.readString(outDir.resolve("mpi.stderr")));
        }
        String line = Files.readAllLines(stdout.toPath()).stream()
                .filter(l -> l.startsWith("MPI_TIMING"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no MPI_TIMING line"));
        return new double[] {
            wall,
            field(line, "total_in_process_s"),
            field(line, "read_s"),
            field(line, "scatter_s"),
            field(line, "build_max_s"),
            field(line, "write_max_s"),
            field(line, "ffmpeg_s")
        };
    }

    private static double field(String line, String key) {
        for (String token : line.split(" ")) {
            if (token.startsWith(key + "=")) {
                return Double.parseDouble(token.substring(key.length() + 1));
            }
        }
        throw new IllegalStateException(key + " not in " + line);
    }

    // ---------------------------------------------------------------------------------------------------------

    /** warm-up runs, then timed runs; {@code prepare} and {@code verify} are never timed. */
    private Row measureRow(
            String input,
            long size,
            String category,
            String mode,
            String config,
            String metric,
            Step prepare,
            Step timed,
            Step verify,
            String note)
            throws Exception {
        double[] samples = new double[runs];
        for (int i = 0; i < warmups + runs; i++) {
            prepare.run();
            System.gc();
            long t0 = System.nanoTime();
            timed.run();
            long t1 = System.nanoTime();
            verify.run();
            if (i >= warmups) {
                samples[i - warmups] = (t1 - t0) / 1e9;
            }
        }
        Row row = new Row(input, size, category, mode, config, metric, "ok", warmups, runs, samples, note);
        rows.add(row);
        System.out.printf("    %-24s %-8s %-10s median %.4f s%n", metric, mode, config, row.median());
        return row;
    }

    private void addSkipped(
            String input, long size, String category, String mode, String config, String metric, String reason) {
        rows.add(new Row(input, size, category, mode, config, metric, "skipped", 0, 0, new double[0], reason));
    }

    // ---------------------------------------------------------------------------------------------------------

    private static void assertSame(List<BufferedImage> got, List<BufferedImage> expected, String what) {
        if (got.size() != expected.size()) {
            throw new IllegalStateException(what + ": frame count " + got.size() + " != " + expected.size());
        }
        for (int i = 0; i < got.size(); i++) {
            if (!Arrays.equals(pixels(got.get(i)), pixels(expected.get(i)))) {
                throw new IllegalStateException(what + ": frame " + i + " differs from sequential");
            }
        }
    }

    private static byte[] pixels(BufferedImage image) {
        if (image.getType() == BufferedImage.TYPE_BYTE_GRAY
                && image.getRaster().getDataBuffer() instanceof DataBufferByte b) {
            return b.getData();
        }
        return RASTER.deserialize(image);
    }

    private static void verifyFrameDir(Path frameDir, ImageFrameSet baseline) throws IOException {
        List<Path> paths;
        try (Stream<Path> s = Files.list(frameDir)) {
            paths = s.filter(p -> p.getFileName().toString().endsWith(".png"))
                    .sorted()
                    .toList();
        }
        List<BufferedImage> images = readSequential(paths);
        assertSame(List.of(images.getFirst()), List.of(baseline.metadataImage()), "metadata frame in " + frameDir);
        assertSame(images.subList(1, images.size()), baseline.payloadImages(), "payload frames in " + frameDir);
    }

    private static void verifyVideoDecodes(Path mp4, Path scratch, String sha) throws Exception {
        deleteRecursively(scratch);
        Path out = new DecodePipeline().decode(new VideoDecoder().decode(mp4, scratch), scratch);
        String got = sha256(Files.readAllBytes(out));
        if (!got.equals(sha)) {
            throw new IllegalStateException("decoded video sha " + got + " != input " + sha);
        }
        deleteRecursively(scratch);
    }

    private static List<BufferedImage> readSequential(List<Path> paths) throws IOException {
        List<BufferedImage> images = new ArrayList<>(paths.size());
        for (Path p : paths) {
            images.add(IMAGE_IO.read(p));
        }
        return images;
    }

    private static void writeSequential(ImageFrameSet set, Path dir) throws IOException {
        Files.createDirectories(dir);
        IMAGE_IO.write(set.metadataImage(), dir.resolve("frame_000000.png"));
        for (int i = 0; i < set.payloadImages().size(); i++) {
            IMAGE_IO.write(set.payloadImages().get(i), dir.resolve(String.format("frame_%06d.png", i + 1)));
        }
    }

    static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static double gzipRatio(byte[] data) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(data);
        }
        return (double) bos.size() / data.length;
    }

    private static byte[] compressibleData(int size) {
        Random random = new Random(SEED);
        String[] vocabulary = new String[2000];
        for (int i = 0; i < vocabulary.length; i++) {
            StringBuilder w = new StringBuilder();
            int len = 3 + random.nextInt(8);
            for (int c = 0; c < len; c++) {
                w.append((char) ('a' + random.nextInt(26)));
            }
            vocabulary[i] = w.toString();
        }
        byte[] out = new byte[size];
        int pos = 0;
        while (pos < size) {
            byte[] word = (vocabulary[random.nextInt(vocabulary.length)] + (random.nextInt(12) == 0 ? ".\n" : " "))
                    .getBytes(StandardCharsets.US_ASCII);
            int n = Math.min(word.length, size - pos);
            System.arraycopy(word, 0, out, pos, n);
            pos += n;
        }
        return out;
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------

    private void writeResults() throws IOException {
        Files.createDirectories(outDir);
        StringBuilder csv = new StringBuilder(
                "input,size_bytes,category,mode,config,metric,status,warmups,runs,median_s,min_s,max_s,all_runs_s,note\n");
        for (Row r : rows) {
            if (r.status.equals("ok")) {
                double[] s = r.seconds.clone();
                Arrays.sort(s);
                StringBuilder all = new StringBuilder();
                for (double v : r.seconds) {
                    all.append(all.isEmpty() ? "" : ";").append(String.format("%.6f", v));
                }
                csv.append(String.format(
                        "%s,%d,%s,%s,%s,%s,ok,%d,%d,%.6f,%.6f,%.6f,%s,%s%n",
                        r.input,
                        r.sizeBytes,
                        r.category,
                        r.mode,
                        quote(r.config),
                        r.metric,
                        r.warmups,
                        r.runs,
                        r.median(),
                        s[0],
                        s[s.length - 1],
                        all,
                        quote(r.note == null ? "" : r.note)));
            } else {
                csv.append(String.format(
                        "%s,%d,%s,%s,%s,%s,skipped,,,,,,,%s%n",
                        r.input, r.sizeBytes, r.category, r.mode, quote(r.config), r.metric, quote(r.note)));
            }
        }
        Files.writeString(outDir.resolve("results.csv"), csv);

        StringBuilder txt = new StringBuilder();
        txt.append("BITPLANE BENCHMARK - RAW RESULTS\n");
        txt.append(
                "Every value below is a System.nanoTime measurement from this run, or a recorded 'skipped' reason.\n");
        txt.append("Protocol: ")
                .append(warmups)
                .append(" warm-up runs (discarded) then ")
                .append(runs)
                .append(" timed runs; median reported. Correctness is verified after EVERY run (warm-up and timed,\n");
        txt.append(
                "verification itself is not timed): frames pixel-identical to the sequential encoder's frames, and\n");
        txt.append("decoded file SHA-256 equals the input's. A mismatch aborts the benchmark.\n\n");
        txt.append("== HARDWARE / SOFTWARE ==\n").append(hardware).append("\n\n");
        txt.append("== INPUTS ==\n");
        inputNotes.forEach(n -> txt.append(n).append('\n'));
        txt.append("\n== NOTES ==\n");
        txt.append(
                "* Mode 'opencl-cpu-NOT-A-GPU' runs the OpenCL kernel on a CPU runtime (pocl). It is NOT a GPU result.\n");
        txt.append(
                "* e2e rows for sequential/threads/gpu run inside one warmed JVM; the MPI wall-clock row launches\n");
        txt.append("  fresh JVMs and MPI_Init each run (the in-process row excludes JVM start). Not like-for-like.\n");
        txt.append(
                "* frame_parse_reassemble includes writing the reassembled file to disk (as DecodePipeline does).\n");
        txt.append("* ffmpeg rows use the same command for every mode; they run on all cores.\n\n");
        txt.append("== RESULTS (median seconds; min/max in CSV) ==\n");
        txt.append(String.format(
                "%-20s %-10s %-26s %-34s %-34s %-9s %s%n",
                "input", "category", "mode", "config", "metric", "median_s", "min..max / note"));
        for (Row r : rows) {
            if (r.status.equals("ok")) {
                double[] s = r.seconds.clone();
                Arrays.sort(s);
                txt.append(String.format(
                        "%-20s %-10s %-26s %-34s %-34s %-9.4f %.4f..%.4f%s%n",
                        r.input,
                        r.category,
                        r.mode,
                        r.config,
                        r.metric,
                        r.median(),
                        s[0],
                        s[s.length - 1],
                        r.note == null ? "" : "  [" + r.note + "]"));
            } else {
                txt.append(String.format(
                        "%-20s %-10s %-26s %-34s %-34s %-9s %s%n",
                        r.input, r.category, r.mode, r.config, r.metric, "SKIPPED", r.note));
            }
        }
        Files.writeString(outDir.resolve("results.txt"), txt);
    }

    private static String quote(String s) {
        return '"' + s.replace("\"", "\"\"") + '"';
    }
}
