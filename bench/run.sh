#!/usr/bin/env bash
# Builds everything and runs the benchmark. Extra args are passed to BenchMain (e.g. --sizes 1,5 --runs 3).
# Output: bench/results.csv and bench/results.txt
set -euo pipefail
cd "$(dirname "$0")/.."

if command -v mpirun >/dev/null 2>&1; then mpi/build.sh; MPI_ARGS=(--mpi-classpath "$(mpi/classpath.sh)"); else mvn -B -q -DskipTests compile; MPI_ARGS=(); fi
if [ ! -f mpi/build/classpath.txt ]; then mvn -B -q dependency:build-classpath -Dmdep.outputFile=mpi/build/classpath.txt; fi
mkdir -p bench/build/classes
javac -nowarn -d bench/build/classes -cp "target/classes:$(cat mpi/build/classpath.txt)" $(find bench/src -name '*.java')
exec java -Xmx10g -cp "bench/build/classes:target/classes:$(cat mpi/build/classpath.txt)" \
  com.sathwikhbhat.bitplane.bench.BenchMain --out bench --work bench/work "${MPI_ARGS[@]}" "$@"
