# Benchmark summary (shortened protocol)

Raw numbers: `bench/results.csv`, `bench/results.txt`. No value in this summary is estimated; this file contains no timings.

## Hardware / software
```
CPU model        : Intel(R) Xeon(R) Processor @ 2.30GHz
Logical CPUs     : 4 (Runtime.availableProcessors)
lscpu topology   : Thread(s) per core: 1;Core(s) per socket: 4;Socket(s): 1;Hypervisor vendor: KVM;Virtualization type: full
RAM              : 16477348 kB
GPU (nvidia-smi) : not available (nvidia-smi not found)
OpenCL devices   : Device Name cpu-skylake-avx512-Intel(R) Xeon(R) Processor @ 2.30GHz; Driver Version 5.0+debian; Device Type CPU; Device Name cpu-skylake-avx512-Intel(R) Xeon(R) Processor @ 2.30GHz; Device Name cpu-skylake-avx512-Intel(R) Xeon(R) Processor @ 2.30GHz; Device Name cpu-skylake-avx512-Intel(R) Xeon(R) Processor @ 2.30GHz
OS               : Ubuntu 24.04.5 LTS / kernel 6.18.44-fc-v153
Java             : 21.0.12.1 (OpenJDK 64-Bit Server VM, Ubuntu)
FFmpeg           : ffmpeg version 6.1.1-3ubuntu5 Copyright (c) 2000-2023 the FFmpeg developers
MPI              : mpirun (Open MPI) 4.1.6 ; Java bindings built from Open MPI source of the same version
JVM max heap     : 10240 MiB
```
GPU: **none**.

## Command line used
```
bench/run.sh --sizes 1,5,25 --compressible true --runs 3 --mpi-np 1,4 --opencl-cpu false
```
(`bench/run.sh` adds `--out bench --work bench/work --mpi-classpath <mpi/classpath.sh>`.)

**Shortened protocol: 100 MiB input skipped; 3 timed runs (not 5); MPI np = 1 and 4 only; GPU rows skipped (no GPU on this machine); OpenCL ran only on a CPU runtime (pocl), labelled NOT A GPU.**

Two further deviations to state plainly:
* `--opencl-cpu false`: pocl rows are **absent from the final results**. A first shortened run with pocl enabled died with a native
  segfault (kernel log line in `bench/crashed-run/dmesg-segfault.txt`, no JVM crash report) in a worker thread during the
  25 MiB end-to-end decode of the OpenCL-on-CPU configuration, after its 1 MiB and 5 MiB rows completed. Root cause was not
  diagnosed. Its log and partial results are kept in `bench/crashed-run/` and are not part of the results. The OpenCL kernel's
  byte-identity to `RasterCodec` is covered by `OpenClRasterizerTest` (ran on pocl, passed), which is not a GPU result either.
* The NOTES block below still mentions the pocl mode because it is printed verbatim by the harness; that mode did not run in the final results.
* The harness flag `--opencl-cpu` was added after the crash; no other benchmark code was changed between runs.
* thread counts: 1, 2, 4 (N = availableProcessors = 4 on this machine, so N is the "4" row).

## Inputs (verbatim from results.txt)
```
random-1MiB: 1048576 bytes, random bytes, java.util.Random seed 20260101, sha256=b1d8c167661f06e61e50e20e320dd223be126053be6ce012ea1ce4af69ae7417, payload frames=1 (+1 metadata), gzip ratio=1.000
random-5MiB: 5242880 bytes, random bytes, java.util.Random seed 20260101, sha256=8edb4c1b94f42c685e13959a0ba437f5d2aeda93bc053b18b703482fe20decb6, payload frames=3 (+1 metadata), gzip ratio=1.000
random-25MiB: 26214400 bytes, random bytes, java.util.Random seed 20260101, sha256=4fad0701163dfca4828e886097364365bc954c7b2ab3636e4e2cc647acae082c, payload frames=13 (+1 metadata), gzip ratio=1.000
compressible-25MiB: 26214400 bytes, repeated-word text, sha256=5d41d93ff47844208f968f12c8336b73fde13643b906d683e42891039542cbde, payload frames=13 (+1 metadata), gzip ratio=0.368
```

## Notes / caveats (verbatim from results.txt)
```
* Mode 'opencl-cpu-NOT-A-GPU' runs the OpenCL kernel on a CPU runtime (pocl). It is NOT a GPU result.
* e2e rows for sequential/threads/gpu run inside one warmed JVM; the MPI wall-clock row launches
  fresh JVMs and MPI_Init each run (the in-process row excludes JVM start). Not like-for-like.
* frame_parse_reassemble includes writing the reassembled file to disk (as DecodePipeline does).
* ffmpeg rows use the same command for every mode; they run on all cores.
```

## What was implemented / ran / skipped
| Mode | Implemented | Ran here | Notes |
| --- | --- | --- | --- |
| sequential | existing code, unchanged | yes | default |
| threads (1/2/4) | `ParallelEncodePipeline`, `ParallelDecodePipeline`, `ParallelFrameIO`, `ThreadedRasterizer` | yes | selected with `bitplane.parallel.mode=threads` |
| gpu | OpenCL via JOCL (`OpenClRasterizer`, `kernels/payload_frames.cl`), batched launches, CPU PNG encoding, auto-fallback to threads | **skipped in benchmarks** | no GPU / no `nvidia-smi`; only a CPU OpenCL runtime exists. Kernel correctness tested on pocl only. |
| mpi (np = 1, 4 benchmarked) | Open MPI Java bindings, `MpiEncodeMain` (Bcast, Scatterv, barrier, rank-0 FFmpeg) | yes, encode only | MPI decode not implemented (threads decode used); its row is `skipped`. np = 1/2/3/4 were also checked by `mpi/verify.sh`. |

Why JOCL (OpenCL) rather than JCuda/TornadoVM: it is the only route that needs no vendor SDK and can be exercised without an NVIDIA GPU
(pocl CPU runtime) - the machine has no GPU to confirm either alternative.

Environment fixes needed: `ocl-icd-opencl-dev` (JOCL loads `libOpenCL.so`, not `.so.1`), `openmpi-bin libopenmpi-dev`, `pocl-opencl-icd`, `clinfo`;
`mpi.jar` is not shipped by Ubuntu and was built from the matching Open MPI 4.1.6 source (`mpi/build-mpi-jar.sh`).
`./mvnw` does not work in this checkout (`.mvn/wrapper` is not committed); the system Maven 3.9.11 was used.

## Correctness
The harness verifies after **every** run (warm-up and timed) that frames are pixel-identical to the sequential encoder's frames and that the
decoded file's SHA-256 equals the input's, and aborts on any mismatch. The run finished with `DONE`; no mismatch or exception is in `bench/run.log`.
Therefore every run in `results.csv` passed both checks. (`grep -E 'mismatch|differs|Exception' bench/run.log` returns nothing.)

`mvn clean verify`: BUILD SUCCESS, 37 tests, 0 failures, 0 skipped (includes OpenCL kernel test on pocl and the MPI np=1/2/3 script test).
