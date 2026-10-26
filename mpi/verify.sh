#!/usr/bin/env bash
# Runs MpiEncodeMain for each process count and checks frames == sequential frames and decode == input.
# Set MPI_SKIP_BUILD=1 to reuse an existing mpi/build.
# usage: mpi/verify.sh [size-bytes] [np ...]   (default: 5000000 bytes, np 1 2 3 4)
set -euo pipefail
cd "$(dirname "$0")/.."

SIZE="${1:-5000000}"; shift || true
NPS=("${@:-1 2 3 4}")
[ $# -gt 0 ] || NPS=(1 2 3 4)

[ -n "${MPI_SKIP_BUILD:-}" ] || mpi/build.sh
CP="$(mpi/classpath.sh)"
LIBPATH="${OMPI_JAVA_LIBPATH:-/usr/lib/x86_64-linux-gnu/openmpi/lib}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

head -c "$SIZE" /dev/urandom > "$WORK/input.bin"
MPIRUN=(mpirun)
[ "$(id -u)" = 0 ] && MPIRUN+=(--allow-run-as-root)

for np in "${NPS[@]}"; do
  out="$WORK/out-np$np"
  "${MPIRUN[@]}" --oversubscribe -np "$np" java -Djava.library.path="$LIBPATH" -cp "$CP" \
    com.sathwikhbhat.bitplane.mpi.MpiEncodeMain "$WORK/input.bin" "$out" input.bin
  java -cp "$CP" com.sathwikhbhat.bitplane.mpi.MpiVerifyMain "$WORK/input.bin" "$out" input.bin
  echo "np=$np verified"
done
echo "ALL MPI CHECKS PASSED"
