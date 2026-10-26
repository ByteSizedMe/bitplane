#!/usr/bin/env bash
# Compiles the Bitplane classes (via Maven), then the MPI programs into mpi/build/classes.
set -euo pipefail
cd "$(dirname "$0")/.."

[ -f mpi/lib/mpi.jar ] || mpi/build-mpi-jar.sh
mvn -B -q -DskipTests compile
if [ ! -f mpi/build/classpath.txt ] || [ pom.xml -nt mpi/build/classpath.txt ]; then
  mvn -B -q dependency:build-classpath -Dmdep.outputFile=mpi/build/classpath.txt
fi
mkdir -p mpi/build/classes
javac -nowarn -d mpi/build/classes \
  -cp "target/classes:$(cat mpi/build/classpath.txt):mpi/lib/mpi.jar" \
  $(find mpi/src -name '*.java')
