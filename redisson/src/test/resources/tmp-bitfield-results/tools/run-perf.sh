#!/usr/bin/env bash
# Times 500 batches of the workload of RedissonBatchTest.testPerformance against another checkout.
# The test runs alone, because timings taken next to other test classes aren't comparable.
#
# Run from the root of the checkout under test, after its classes are built:
#   JAVAC=<JDK 25 javac> bash <kit>/tools/run-perf.sh <label>
#
# The result is appended to redisson/target/bitfield-kit/<label>/batch-perf.txt.
# Optional variables: MVN, JAVAC, OUT.
set -u
LABEL="${1:?usage: run-perf.sh <label>}"
KIT="$(cd "$(dirname "$0")" && pwd)"
TESTSRC="$KIT/../../../java/org/redisson"
MVN="${MVN:-mvn}"
JAVAC="${JAVAC:-javac}"
ROOT="$(pwd -W 2>/dev/null || pwd)"
OUT="${OUT:-$ROOT/redisson/target/bitfield-kit}"
SEP=':'
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=';' ;; esac

mkdir -p "$OUT/$LABEL"
"$MVN" -q -B -pl redisson dependency:build-classpath -Dmdep.outputFile="$OUT/cp.txt" -Dmdep.includeScope=test \
  > "$OUT/classpath.log" 2>&1 || { echo "classpath failed, see $OUT/classpath.log"; exit 1; }

"$JAVAC" --release 25 -nowarn -proc:none -encoding UTF-8 \
  -cp "redisson/target/test-classes${SEP}redisson/target/classes$SEP$(cat "$OUT/cp.txt")" \
  -d redisson/target/test-classes "$TESTSRC/TmpBatchPerfTest.java" > "$OUT/javac-perf.log" 2>&1 \
  || { echo "javac failed, see $OUT/javac-perf.log"; exit 1; }

"$MVN" -B -pl redisson surefire:test -Dmaven.test.skip=false -Djacoco.skip=true -Dtest=TmpBatchPerfTest \
  -Dtmp.out="$OUT/$LABEL/batch-perf.txt" > "$OUT/perf-$LABEL.log" 2>&1
rm -f redisson/target/test-classes/org/redisson/TmpBatchPerfTest*.class
cat "$OUT/$LABEL/batch-perf.txt"
