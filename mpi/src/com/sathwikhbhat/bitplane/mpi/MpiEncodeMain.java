package com.sathwikhbhat.bitplane.mpi;

import com.sathwikhbhat.bitplane.constants.Constants;
import com.sathwikhbhat.bitplane.frame.codec.PayloadFrameCodec;
import com.sathwikhbhat.bitplane.frame.model.MetadataFrame;
import com.sathwikhbhat.bitplane.frame.model.PayloadFrame;
import com.sathwikhbhat.bitplane.image.ImageGenerator;
import com.sathwikhbhat.bitplane.image.RasterCodec;
import com.sathwikhbhat.bitplane.video.ParallelFrameIO;
import com.sathwikhbhat.bitplane.video.VideoEncoder;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import mpi.Intracomm;
import mpi.MPI;
import mpi.MPIException;

/**
 * Distributed encode. Usage: {@code mpirun -np N java -cp ... MpiEncodeMain <input> <outDir> [originalFileName]}.
 *
 * <p>Rank 0 reads the file, computes the payload frame count F and broadcasts it. Frames are split into contiguous
 * ranges, one per rank; the matching file bytes are distributed with MPI_Scatterv. Each rank builds its frames
 * and writes {@code outDir/frames/frame_%06d.png} (frame 0 is the metadata frame, written by rank 0). After a
 * barrier rank 0 runs the single FFmpeg call that produces {@code outDir/output.mp4}.
 *
 * <p>Rank 0 prints one {@code MPI_TIMING ...} line (seconds; build/write are the max over ranks).
 */
public final class MpiEncodeMain {

    private static final int MAX_PAYLOAD = Constants.FRAME_BYTE_CAPACITY - Integer.BYTES;

    private MpiEncodeMain() {}

    public static void main(String[] args) throws Exception {
        String[] rest = MPI.Init(args);
        Intracomm world = MPI.COMM_WORLD;
        try {
            if (rest.length < 2) {
                throw new IllegalArgumentException("usage: MpiEncodeMain <input> <outDir> [originalFileName]");
            }
            run(
                    world,
                    Path.of(rest[0]),
                    Path.of(rest[1]),
                    rest.length > 2 ? rest[2] : Path.of(rest[0]).getFileName().toString());
        } catch (Throwable t) {
            System.err.println("rank " + world.getRank() + " failed: " + t);
            t.printStackTrace();
            world.abort(1);
        } finally {
            MPI.Finalize();
        }
    }

    static void run(Intracomm world, Path input, Path outDir, String originalFileName)
            throws IOException, MPIException {
        int rank = world.getRank();
        int size = world.getSize();
        Path frameDir = outDir.resolve("frames");
        long t0 = System.nanoTime();

        ByteBuffer fileBuffer = null; // direct: the Open MPI JNI scatterv path corrupts the heap for byte[] sends
        long[] length = new long[1];
        int[] frameCount = new int[1];

        if (rank == 0) {
            long fileSize = Files.size(input);
            if (fileSize == 0) {
                throw new IllegalArgumentException("File is empty");
            }
            fileBuffer = MPI.newByteBuffer(Math.toIntExact(fileSize));
            try (FileChannel channel = FileChannel.open(input, StandardOpenOption.READ)) {
                while (fileBuffer.hasRemaining() && channel.read(fileBuffer) >= 0) {
                    // read until the buffer is full
                }
            }
            fileBuffer.flip();
            length[0] = fileSize;
            frameCount[0] = (int) ((fileSize + MAX_PAYLOAD - 1) / MAX_PAYLOAD);
            Files.createDirectories(frameDir);
            clearFrames(frameDir);
        }
        double readSeconds = seconds(t0);

        world.bcast(frameCount, 1, MPI.INT, 0);
        world.bcast(length, 1, MPI.LONG, 0);
        int totalFrames = frameCount[0];

        // Contiguous frame range [low, high) per rank, and the file bytes that back it.
        int[] counts = new int[size];
        int[] displs = new int[size];
        for (int r = 0; r < size; r++) {
            long low = (long) r * totalFrames / size;
            long high = (long) (r + 1) * totalFrames / size;
            long byteStart = Math.min(low * MAX_PAYLOAD, length[0]);
            long byteEnd = Math.min(high * MAX_PAYLOAD, length[0]);
            counts[r] = Math.toIntExact(byteEnd - byteStart);
            displs[r] = Math.toIntExact(byteStart);
        }
        int firstFrame = (int) ((long) rank * totalFrames / size);
        int lastFrame = (int) ((long) (rank + 1) * totalFrames / size);

        long t1 = System.nanoTime();
        ByteBuffer sendBuffer = rank == 0 ? fileBuffer : MPI.newByteBuffer(1);
        ByteBuffer recvBuffer = MPI.newByteBuffer(Math.max(1, counts[rank]));
        world.scatterv(sendBuffer, counts, displs, MPI.BYTE, recvBuffer, counts[rank], MPI.BYTE, 0);
        byte[] local = new byte[counts[rank]];
        recvBuffer.get(0, local);
        double scatterSeconds = seconds(t1);

        // Rank 0 has created the frame directory before the broadcast above; everyone else just waits on it.
        long t2 = System.nanoTime();
        RasterCodec rasterCodec = new RasterCodec();
        PayloadFrameCodec payloadFrameCodec = new PayloadFrameCodec();
        List<BufferedImage> images = new ArrayList<>(lastFrame - firstFrame);
        for (int frame = firstFrame; frame < lastFrame; frame++) {
            int offset = (frame - firstFrame) * MAX_PAYLOAD;
            byte[] chunk = Arrays.copyOfRange(local, offset, Math.min(offset + MAX_PAYLOAD, local.length));
            images.add(rasterCodec.serialize(payloadFrameCodec.serialize(new PayloadFrame(frame, chunk))));
        }
        double buildSeconds = seconds(t2);

        long t3 = System.nanoTime();
        new ParallelFrameIO(1).writeAll(images, frameDir, firstFrame + 1);
        if (rank == 0) {
            MetadataFrame metadata = new MetadataFrame(originalFileName, length[0], totalFrames);
            new ParallelFrameIO(1).writeAll(List.of(new ImageGenerator().metadataToImage(metadata)), frameDir, 0);
        }
        double writeSeconds = seconds(t3);

        world.barrier();

        double ffmpegSeconds = 0;
        if (rank == 0) {
            long t4 = System.nanoTime();
            new VideoEncoder().encodeFrameDirectory(frameDir, outDir.resolve("output.mp4"));
            ffmpegSeconds = seconds(t4);
        }

        double[] maxima = new double[2];
        world.reduce(new double[] {buildSeconds, writeSeconds}, maxima, 2, MPI.DOUBLE, MPI.MAX, 0);
        world.barrier();

        if (rank == 0) {
            System.out.printf(
                    "MPI_TIMING np=%d frames=%d read_s=%.6f scatter_s=%.6f build_max_s=%.6f write_max_s=%.6f ffmpeg_s=%.6f total_in_process_s=%.6f%n",
                    size, totalFrames, readSeconds, scatterSeconds, maxima[0], maxima[1], ffmpegSeconds, seconds(t0));
        }
    }

    private static void clearFrames(Path frameDir) throws IOException {
        try (DirectoryStream<Path> stale = Files.newDirectoryStream(frameDir, "frame_*.png")) {
            for (Path path : stale) {
                Files.delete(path);
            }
        }
    }

    private static double seconds(long startNanos) {
        return (System.nanoTime() - startNanos) / 1e9;
    }
}
