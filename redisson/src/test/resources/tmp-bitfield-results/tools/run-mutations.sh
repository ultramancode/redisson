#!/usr/bin/env bash
# Changes one BITFIELD rule at a time and runs the batch tests of RedissonBitSetTest.
# Every mutation has to make at least one test fail. The last run restores the real code.
#
# Run from the root of the checkout under test, after its classes are built:
#   mvn -Dmaven.test.skip=false -Djacoco.skip=true -pl redisson -Dtest=RedissonBitSetTest test
#   bash <kit>/tools/run-mutations.sh [mutation...]
#
# Optional variables: MVN, JAVAC, PYTHON, OUT.
set -u
KIT="$(cd "$(dirname "$0")" && pwd)"
MVN="${MVN:-mvn}"
JAVAC="${JAVAC:-javac}"
PYTHON="${PYTHON:-$(command -v python3 || command -v python)}"
ROOT="$(pwd -W 2>/dev/null || pwd)"
OUT="${OUT:-$ROOT/redisson/target/bitfield-kit}"
SEP=':'
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=';' ;; esac

SRC="redisson/src/main/java/org/redisson/command/CommandBatchService.java"
TESTS='RedissonBitSetTest#testBatchReadsWithSlaveReadMode+testBitFieldROFallbackInBatch+testBitFieldROFallbackInQueuedBatch+testQueuedBitFieldWithMasterSlaveReadMode+testQueuedBitFieldReadsWithoutWritePermission'

mkdir -p "$OUT"
"$MVN" -q -B -pl redisson dependency:build-classpath -Dmdep.outputFile="$OUT/cp.txt" -Dmdep.includeScope=test \
  > "$OUT/classpath.log" 2>&1 || { echo "classpath failed, see $OUT/classpath.log"; exit 1; }

if [ "$#" -eq 0 ]; then
  set -- $("$PYTHON" "$KIT/mutate.py" --list)
fi
for m in "$@"; do
  "$PYTHON" "$KIT/mutate.py" "$SRC" "$OUT/CommandBatchService.java" "$m" || exit 1
  "$JAVAC" --release 8 -g -nowarn -proc:none -encoding UTF-8 \
    -cp "redisson/target/classes$SEP$(cat "$OUT/cp.txt")" -d redisson/target/classes \
    "$OUT/CommandBatchService.java" > "$OUT/javac-$m.log" 2>&1 || { echo "$m: javac failed, see $OUT/javac-$m.log"; exit 1; }
  "$MVN" -B -pl redisson surefire:test -Dmaven.test.skip=false -Djacoco.skip=true -Dtest="$TESTS" \
    > "$OUT/mutation-$m.log" 2>&1
  echo "== $m: $(grep -E 'Tests run:.*Skipped: [0-9]+$' "$OUT/mutation-$m.log" | tail -1 | sed -E 's/^\[[A-Z]+\] +//')"
  grep -E '^\[ERROR\] +RedissonBitSetTest\.' "$OUT/mutation-$m.log" | sed -E 's/^\[ERROR\] +//; s/ [^ ]* .*//' | sort -u | sed 's/^/     /'
done
