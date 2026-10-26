#!/usr/bin/env bash
# Builds mpi/lib/mpi.jar (the Open MPI Java bindings). Debian/Ubuntu compile the JNI library
# (libmpi_java.so) but do not ship mpi.jar, so it is built from the matching upstream source.
set -euo pipefail
cd "$(dirname "$0")"

OMPI_VERSION="$(ompi_info --parsable 2>/dev/null | sed -n 's/^ompi:version:full://p')"
OMPI_VERSION="${OMPI_VERSION:-4.1.6}"
SRC_URL="${OMPI_SRC_URL:-http://archive.ubuntu.com/ubuntu/pool/universe/o/openmpi/openmpi_${OMPI_VERSION}.orig.tar.xz}"

mkdir -p lib build/ompi-src build/mpi-classes
if [ ! -d "build/ompi-src/openmpi-${OMPI_VERSION}" ]; then
  curl -sSfL -o build/ompi-src.tar.xz "$SRC_URL"
  tar -xJf build/ompi-src.tar.xz -C build/ompi-src --wildcards "*/ompi/mpi/java/java/*"
fi
javac -nowarn -d build/mpi-classes "build/ompi-src/openmpi-${OMPI_VERSION}"/ompi/mpi/java/java/*.java
jar cf lib/mpi.jar -C build/mpi-classes .
echo "built $(pwd)/lib/mpi.jar (Open MPI ${OMPI_VERSION} Java bindings)"
