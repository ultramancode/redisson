# BITFIELD batch read routing: verification kit

Not for a PR. This branch keeps the temporary tests, their saved output and a few scripts
that were used to check `fix/bitfield-batch-read-routing` against master.

The branch itself is based on `277a8bf86` plus a REDIS_READ_ATOMIC prototype, so its main
code is older than the batch branch. Use the kit against another checkout as shown below.

## Contents

| Path | What it is |
| --- | --- |
| `src/test/java/org/redisson/Tmp*Test.java` | Tests without assertions. Each writes one line per combination to `target/<TestName>.txt` |
| `*.txt` in this directory | Saved output. The suffix is the code that was run |
| `tools/run-matrix.sh` | Runs the four matrix tests against the current checkout |
| `tools/run-perf.sh` | Times 500 batches against the current checkout |
| `tools/run-mutations.sh`, `tools/mutate.py` | Change one rule at a time and check that a test fails |
| `tools/compare.py` | Compares two saved outputs row by row |

## Suffixes of the saved output

| Suffix | Code |
| --- | --- |
| `main` | `78dcf9379`, the base of PR #7430 |
| `pr-7430` | `fca4760ec`, the head of PR #7430 |
| `batch-branch` | `277a8bf86`, the batch branch before the queued rule was changed |
| `prototype` | `1aa6e953a`, the REDIS_READ_ATOMIC prototype of this branch |
| `e8e967ff3` | the batch branch after the queued rule was changed |
| `fa0e647b1` | the batch branch after the flags became an enum |

`batch-perf-main.txt` and `batch-perf-e8e967ff3.txt` were taken back to back, two runs each.
Timings depend on the load of the machine, so compare only runs taken that way.

## Running the kit

The scripts are run from the root of the checkout under test. They only write to its
`redisson/target` directory. A second worktree keeps the kit next to it.

```bash
git fetch origin
git worktree add ../redisson-kit origin/wip/bitfield-queued-read-routing
KIT=../redisson-kit/redisson/src/test/resources/tmp-bitfield-results

git switch fix/bitfield-batch-read-routing
mvn -Dmaven.test.skip=false -Djacoco.skip=true -pl redisson -Dtest=RedissonBitSetTest test

JAVAC=<JDK 25 javac> bash $KIT/tools/run-matrix.sh head
for t in matrix acl-reader fresh-read-atomic writable-replica; do
  python3 $KIT/tools/compare.py $KIT/$t-main.txt redisson/target/bitfield-kit/head/$t.txt
done

bash $KIT/tools/run-mutations.sh
JAVAC=<JDK 25 javac> bash $KIT/tools/run-perf.sh head
```

- Tests are compiled with `--release 25`, so `JAVAC` has to be JDK 25 or later. Docker is required.
- `MVN`, `PYTHON` and `OUT` can be set too. On Windows use Git Bash and `PYTHON=python`.
- `run-mutations.sh` overwrites `CommandBatchService` in `redisson/target/classes` and restores it with
  its last run, `none`. Rebuild the module if it's interrupted.
- `mutate.py` matches the code at `fa0e647b1`. Update its patterns after the code changes.

## Results at `fa0e647b1`

No row is worse than master. "Better" counts rows with a higher share of successful runs.

| Output | Rows | All runs OK on master | All runs OK at `fa0e647b1` | Better | Worse |
| --- | --- | --- | --- | --- | --- |
| `matrix` | 48 | 26 | 38 | 12 | 0 |
| `acl-reader` | 20 | 5 | 14 | 9 | 0 |
| `fresh-read-atomic` | 12 | 4 | 4 | 0 | 0 |
| `writable-replica` | 8 | 7 | 7 | 0 | 0 |

Compared with `277a8bf86` these rows are worse. They are back at the result of master, because a queued
command is sent as master sent it while it isn't known if BITFIELD_RO is available.

| Row | `277a8bf86` | `fa0e647b1` | master |
| --- | --- | --- | --- |
| Redis 5.0.14, SLAVE, REDIS_WRITE_ATOMIC, `bitField(GET)` | OK | FAIL | FAIL |
| Redis 8.10, MASTER_SLAVE, REDIS_READ_ATOMIC, `bitField(GET)` | OK | OK on the master only | OK on the master only |
| ACL user without write commands, MASTER_SLAVE, REDIS_READ_ATOMIC, `bitField(GET)` | OK | FAIL | FAIL |

## Mutations

Each one made the listed tests of `RedissonBitSetTest` fail at `fa0e647b1`.

| Mutation | Failing tests |
| --- | --- |
| `no-master-slave-rule` | `testQueuedBitFieldWithMasterSlaveReadMode` |
| `write-atomic-always-bitfield` | `testQueuedBitFieldReadsWithoutWritePermission` |
| `no-unavailable-rule` | `testBitFieldROFallbackInBatch`, `testBitFieldROFallbackInQueuedBatch` |
| `no-available-rule` | `testBatchReadsWithSlaveReadMode` (8.10), `testQueuedBitFieldReadsWithoutWritePermission` |
| `unknown-uses-bitfield-ro` | `testBitFieldROFallbackInQueuedBatch` |
| `available-rule-only-for-read-atomic` | `testQueuedBitFieldReadsWithoutWritePermission` |
| `no-check-before-batch` | `testBatchReadsWithSlaveReadMode` (5.0.14 and 8.10), `testBitFieldROFallbackInBatch`, `testBitFieldROFallbackInQueuedBatch` |
| `batch-ignores-unavailable` | `testBatchReadsWithSlaveReadMode` (5.0.14), `testBitFieldROFallbackInBatch`, `testBitFieldROFallbackInQueuedBatch` |

## RedissonBatchTest

`testPerformance` has to finish 10,000 batches in 40 seconds. On a slow or busy machine it times out, keeps
printing exceptions and leaves no free connections for the tests after it. The output file grew to 8 GB.
Run the class without it.

```bash
mvn -Dmaven.test.skip=false -Djacoco.skip=true -pl redisson \
  -Dtest='RedissonBatchTest, !RedissonBatchTest#testPerformance*' test
```
