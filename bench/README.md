# Benchmark harness

```bash
bench/run.sh                         # full protocol: 1/5/25/100 MiB random + 25 MiB compressible
bench/run.sh --sizes 1,5 --runs 3    # smaller run (other options: --warmups --compressible --mpi-np --out --work)
```

Writes `bench/results.csv` and `bench/results.txt` (hardware, inputs, median/min/max per measurement). The harness is
separate from the Spring app (`bench/src`). Protocol: 3 warm-up runs (discarded), then 5 timed runs, median reported,
`System.nanoTime`. After **every** run (verification is not timed) it checks that frames are pixel-identical to the
sequential encoder's frames and that the decoded file's SHA-256 equals the input's; a mismatch aborts the run.
Modes that cannot run on the machine are written as `skipped` rows with the reason, never as numbers.
