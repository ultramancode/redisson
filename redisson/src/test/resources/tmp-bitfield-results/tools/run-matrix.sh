#!/usr/bin/env bash
# Runs the four temporary matrix tests of this kit against another checkout and saves their output.
# The checkout under test isn't changed. Only its target directory is used.
#
# Run from the root of the checkout under test, after its classes are built:
#   mvn -Dmaven.test.skip=false -Djacoco.skip=true -pl redisson -Dtest=RedissonBitSetTest test
#   JAVAC=<JDK 25 javac> bash <kit>/tools/run-matrix.sh <label>
#
# Results are written to redisson/target/bitfield-kit/<label>.
# Optional variables: MVN, JAVAC, OUT.
set -u
LABEL="${1:?usage: run-matrix.sh <label>}"
KIT="$(cd "$(dirname "$0")" && pwd)"
TESTSRC="$KIT/../../../java/org/redisson"
MVN="${MVN:-mvn}"
JAVAC="${JAVAC:-javac}"
ROOT="$(pwd -W 2>/dev/null || pwd)"
OUT="${OUT:-$ROOT/redisson/target/bitfield-kit}"
SEP=':'
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=';' ;; esac
TESTS="TmpAclReaderTest TmpBatchMatrixTest TmpReadAtomicFreshTest TmpWritableReplicaTest"

mkdir -p "$OUT/$LABEL"
"$MVN" -q -B -pl redisson dependency:build-classpath -Dmdep.outputFile="$OUT/cp.txt" -Dmdep.includeScope=test \
  > "$OUT/classpath.log" 2>&1 || { echo "classpath failed, see $OUT/classpath.log"; exit 1; }

SOURCES=()
for t in $TESTS; do
  SOURCES+=("$TESTSRC/$t.java")
done
"$JAVAC" --release 25 -nowarn -proc:none -encoding UTF-8 \
  -cp "redisson/target/test-classes${SEP}redisson/target/classes$SEP$(cat "$OUT/cp.txt")" \
  -d redisson/target/test-classes "${SOURCES[@]}" > "$OUT/javac-matrix.log" 2>&1 \
  || { echo "javac failed, see $OUT/javac-matrix.log"; exit 1; }

rm -f redisson/target/Tmp*Test.txt
"$MVN" -B -pl redisson surefire:test -Dmaven.test.skip=false -Djacoco.skip=true \
  -Dtest="$(echo $TESTS | tr ' ' ',')" > "$OUT/matrix-$LABEL.log" 2>&1
grep -E 'Tests run:.*Skipped: [0-9]+$' "$OUT/matrix-$LABEL.log" | tail -1

cp redisson/target/TmpBatchMatrixTest.txt "$OUT/$LABEL/matrix.txt"
cp redisson/target/TmpAclReaderTest.txt "$OUT/$LABEL/acl-reader.txt"
cp redisson/target/TmpReadAtomicFreshTest.txt "$OUT/$LABEL/fresh-read-atomic.txt"
cp redisson/target/TmpWritableReplicaTest.txt "$OUT/$LABEL/writable-replica.txt"
for t in $TESTS; do
  rm -f redisson/target/test-classes/org/redisson/$t*.class redisson/target/$t.txt
done
echo "saved to $OUT/$LABEL"
