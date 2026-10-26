# MPI encode mode

`com.sathwikhbhat.bitplane.mpi.MpiEncodeMain` is a distributed encoder:

1. rank 0 reads the file, computes the payload frame count `F` and broadcasts it (`MPI_Bcast`);
2. frames are split into contiguous ranges, one per rank, and the matching file bytes are distributed with
   `MPI_Scatterv`;
3. every rank builds its frames and writes `frames/frame_%06d.png` (rank 0 also writes the metadata frame 0);
4. barrier; rank 0 runs the single FFmpeg call that creates `output.mp4`.

It lives outside the Maven build (`mpi/src`) so `./mvnw verify` never needs MPI. Spotless still formats it.

## Install on Debian / Ubuntu (what was done on the benchmark machine)

```bash
sudo apt-get install -y openmpi-bin libopenmpi-dev      # Open MPI 4.1.6 on Ubuntu 24.04; JNI lib libmpi_java.so included
mpi/build-mpi-jar.sh                                    # builds mpi/lib/mpi.jar
```

Debian/Ubuntu compile the Java bindings' native part (`libmpi_java.so`, built `--enable-mpi-java`) but do not ship
`mpi.jar`. `mpi/build-mpi-jar.sh` downloads the matching `openmpi_<version>.orig.tar.xz` from the Ubuntu archive and
compiles `ompi/mpi/java/java/*.java` into `mpi/lib/mpi.jar`. Set `OMPI_SRC_URL` to use another mirror.

## Build and run

```bash
mpi/build.sh                                            # compiles Bitplane + the MPI programs
CP="$(mpi/classpath.sh)"
mpirun -np 4 java -Djava.library.path=/usr/lib/x86_64-linux-gnu/openmpi/lib -cp "$CP" \
  com.sathwikhbhat.bitplane.mpi.MpiEncodeMain <input> <outDir> [originalFileName]
# -> <outDir>/frames/frame_000000.png ... and <outDir>/output.mp4
```

* Running as root needs `mpirun --allow-run-as-root`; more ranks than cores needs `--oversubscribe`.
* Rank 0 prints a `MPI_TIMING ...` line with per-phase seconds.
* Use **direct** `ByteBuffer`s (`MPI.newByteBuffer`) for message buffers. With Open MPI 4.1.6 passing a heap `byte[]`
  to `scatterv` aborted the JVM with heap corruption inside `ompi_java_getReadPtrv`.

## Check correctness

```bash
mpi/verify.sh [bytes] [np ...]       # default: 5000000 bytes, np 1 2 3 4
```

For every `np` it runs `MpiEncodeMain` on random bytes, then `MpiVerifyMain` checks that every PNG frame equals the
sequential `EncodePipeline` frame and that decoding `output.mp4` returns a file with the input's SHA-256. The JUnit
test `MpiEncodeScriptTest` runs this for np = 1, 2, 3 and is skipped (with the reason) unless `mpirun`,
`mpi/lib/mpi.jar` and `mpi/build` exist.

## Use from the Spring app

```yaml
bitplane.parallel.mode: mpi
bitplane.parallel.mpi.processes: 4
bitplane.parallel.mpi.classpath: <output of mpi/classpath.sh>
bitplane.parallel.mpi.library-path: /usr/lib/x86_64-linux-gnu/openmpi/lib
bitplane.parallel.mpi.extra-args: --allow-run-as-root --oversubscribe   # only if needed
```

`CodecService` then shells out to `mpirun` for encode. Without a classpath it logs a warning and uses threads.
Decode in `mpi` mode runs on the thread pool: only encode is distributed.
