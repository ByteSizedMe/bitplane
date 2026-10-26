package com.sathwikhbhat.bitplane.service;

import com.sathwikhbhat.bitplane.parallel.MpiEncodeLauncher;
import com.sathwikhbhat.bitplane.parallel.ParallelMode;
import com.sathwikhbhat.bitplane.parallel.PayloadRasterizer;
import com.sathwikhbhat.bitplane.parallel.ThreadedRasterizer;
import com.sathwikhbhat.bitplane.parallel.gpu.OpenClRasterizer;
import com.sathwikhbhat.bitplane.pipeline.DecodePipeline;
import com.sathwikhbhat.bitplane.pipeline.EncodePipeline;
import com.sathwikhbhat.bitplane.pipeline.ImageFrameSet;
import com.sathwikhbhat.bitplane.pipeline.ParallelDecodePipeline;
import com.sathwikhbhat.bitplane.pipeline.ParallelEncodePipeline;
import com.sathwikhbhat.bitplane.video.ParallelFrameIO;
import com.sathwikhbhat.bitplane.video.VideoDecoder;
import com.sathwikhbhat.bitplane.video.VideoEncoder;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class CodecService {

    private static final Logger log = LoggerFactory.getLogger(CodecService.class);

    private final ParallelMode requestedMode;
    private final ParallelMode effectiveMode;
    private final int threads;
    private final OpenClRasterizer openClRasterizer;
    private final MpiEncodeLauncher mpiLauncher;

    public CodecService(
            @Value("${bitplane.parallel.mode:sequential}") String mode,
            @Value("${bitplane.parallel.threads:0}") int threads,
            @Value("${bitplane.parallel.gpu.batch-frames:8}") int gpuBatchFrames,
            @Value("${bitplane.parallel.gpu.allow-cpu-device:false}") boolean allowCpuDevice,
            @Value("${bitplane.parallel.mpi.processes:2}") int mpiProcesses,
            @Value("${bitplane.parallel.mpi.command:mpirun}") String mpiCommand,
            @Value("${bitplane.parallel.mpi.extra-args:}") String mpiExtraArgs,
            @Value("${bitplane.parallel.mpi.classpath:}") String mpiClasspath,
            @Value("${bitplane.parallel.mpi.library-path:}") String mpiLibraryPath) {
        this.requestedMode = ParallelMode.parse(mode);
        this.threads = threads > 0 ? threads : Runtime.getRuntime().availableProcessors();

        ParallelMode effective = requestedMode;
        OpenClRasterizer gpu = null;
        MpiEncodeLauncher mpi = null;

        if (requestedMode == ParallelMode.GPU) {
            Optional<OpenClRasterizer> created = OpenClRasterizer.create(allowCpuDevice, gpuBatchFrames);
            if (created.isPresent()) {
                gpu = created.get();
            } else {
                log.warn("bitplane.parallel.mode=gpu but no OpenCL GPU found; falling back to threads");
                effective = ParallelMode.THREADS;
            }
        } else if (requestedMode == ParallelMode.MPI) {
            mpi = new MpiEncodeLauncher(mpiCommand, mpiExtraArgs, mpiProcesses, mpiClasspath, mpiLibraryPath);
            if (!mpi.isConfigured()) {
                log.warn("bitplane.parallel.mode=mpi but bitplane.parallel.mpi.classpath is not set; "
                        + "falling back to threads");
                mpi = null;
                effective = ParallelMode.THREADS;
            }
        }

        this.effectiveMode = effective;
        this.openClRasterizer = gpu;
        this.mpiLauncher = mpi;

        log.info(
                "Parallel mode: requested={}, running={}{}",
                requestedMode.name().toLowerCase(),
                effectiveMode.name().toLowerCase(),
                describeBackend());
    }

    public ParallelMode effectiveMode() {
        return effectiveMode;
    }

    public Path decode(Path videoPath, Path jobDirectory) throws IOException {
        if (effectiveMode == ParallelMode.SEQUENTIAL) {
            ImageFrameSet imageFrameSet = new VideoDecoder().decode(videoPath, jobDirectory);

            return new DecodePipeline().decode(imageFrameSet, jobDirectory);
        }

        // threads, gpu and mpi all decode on the CPU thread pool: only frame construction has a GPU/MPI form
        ImageFrameSet imageFrameSet = new VideoDecoder(new ParallelFrameIO(threads)).decode(videoPath, jobDirectory);

        return new ParallelDecodePipeline(threads).decode(imageFrameSet, jobDirectory);
    }

    public Path encode(Path inputFile, String originalFileName, Path jobDirectory) throws IOException {
        log.debug("Encoding with {} path", effectiveMode.name().toLowerCase());

        return switch (effectiveMode) {
            case SEQUENTIAL -> {
                ImageFrameSet imageFrameSet = new EncodePipeline().encode(inputFile, originalFileName);
                yield new VideoEncoder().encode(imageFrameSet, jobDirectory);
            }
            case THREADS -> encodeWith(new ThreadedRasterizer(threads), inputFile, originalFileName, jobDirectory);
            case GPU -> encodeWith(openClRasterizer, inputFile, originalFileName, jobDirectory);
            case MPI -> mpiLauncher.encode(inputFile, originalFileName, jobDirectory);
        };
    }

    private Path encodeWith(PayloadRasterizer rasterizer, Path inputFile, String name, Path jobDirectory)
            throws IOException {
        ImageFrameSet imageFrameSet = new ParallelEncodePipeline(rasterizer).encode(inputFile, name);

        return new VideoEncoder(new ParallelFrameIO(threads)).encode(imageFrameSet, jobDirectory);
    }

    private String describeBackend() {
        return switch (effectiveMode) {
            case THREADS -> " (" + threads + " threads)";
            case GPU -> " (" + openClRasterizer.deviceDescription() + ")";
            case MPI -> " (mpirun)";
            case SEQUENTIAL -> "";
        };
    }

    @PreDestroy
    public void shutdown() {
        if (openClRasterizer != null) {
            openClRasterizer.close();
        }
    }
}
