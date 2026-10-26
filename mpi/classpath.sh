#!/usr/bin/env bash
# Prints the classpath for running the MPI programs (after mpi/build.sh).
root="$(cd "$(dirname "$0")/.." && pwd)"
echo "$root/mpi/build/classes:$root/target/classes:$(cat "$root/mpi/build/classpath.txt"):$root/mpi/lib/mpi.jar"
