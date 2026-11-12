"""Writes a copy of CommandBatchService.java with one BITFIELD rule changed.

Usage: mutate.py <source> <out> <mutation>

The patterns match fix/bitfield-batch-read-routing at fa0e647b1.
"""
import io
import sys

MUTATIONS = [
    'no-master-slave-rule',
    'write-atomic-always-bitfield',
    'no-unavailable-rule',
    'no-available-rule',
    'unknown-uses-bitfield-ro',
    'available-rule-only-for-read-atomic',
    'no-check-before-batch',
    'batch-ignores-unavailable',
    'none',
]

UNAVAILABLE = """        if (state == BitFieldROState.UNAVAILABLE) {
            return new RedisCommand<>(command, "BITFIELD");"""
AVAILABLE = """        if (state == BitFieldROState.AVAILABLE) {
            return new RedisCommand<>(command, "BITFIELD_RO");"""


def main():
    if len(sys.argv) == 2 and sys.argv[1] == '--list':
        print(' '.join(MUTATIONS))
        return
    src, out, mutation = sys.argv[1], sys.argv[2], sys.argv[3]
    text = io.open(src, encoding='utf-8').read()

    def rep(old, new):
        if text.count(old) != 1:
            raise SystemExit('pattern found %d times: %s' % (text.count(old), old[:70]))
        return text.replace(old, new)

    # queued modes
    if mutation == 'no-master-slave-rule':
        text = rep("if (mode == ReadMode.MASTER_SLAVE) {", "if (false && mode == ReadMode.MASTER_SLAVE) {")
    elif mutation == 'write-atomic-always-bitfield':
        text = rep("""        if (!isBitFieldROApplicable(command, params)) {
            return command;
        }
""", """        if (!isBitFieldROApplicable(command, params)) {
            return command;
        }
        if (options.getExecutionMode() == ExecutionMode.REDIS_WRITE_ATOMIC) {
            return new RedisCommand<>(command, "BITFIELD");
        }
""")
    elif mutation == 'no-unavailable-rule':
        text = rep(UNAVAILABLE, UNAVAILABLE.replace("if (state == BitFieldROState.UNAVAILABLE)", "if (false)"))
    elif mutation == 'no-available-rule':
        text = rep(AVAILABLE, AVAILABLE.replace("if (state == BitFieldROState.AVAILABLE)", "if (false)"))
    elif mutation == 'unknown-uses-bitfield-ro':
        text = rep("""            return new RedisCommand<>(command, "BITFIELD");
        }
        return command;
    }""", """            return new RedisCommand<>(command, "BITFIELD");
        }
        return new RedisCommand<>(command, "BITFIELD_RO");
    }""")
    elif mutation == 'available-rule-only-for-read-atomic':
        text = rep(AVAILABLE, AVAILABLE.replace(
            "if (state == BitFieldROState.AVAILABLE)",
            "if (state == BitFieldROState.AVAILABLE"
            " && options.getExecutionMode() == ExecutionMode.REDIS_READ_ATOMIC)"))
    # batches kept in memory
    elif mutation == 'no-check-before-batch':
        text = rep("if (getServiceManager().getBitFieldROState() == BitFieldROState.UNKNOWN) {", "if (false) {")
    elif mutation == 'batch-ignores-unavailable':
        text = rep("&& getServiceManager().getBitFieldROState() != BitFieldROState.UNAVAILABLE;",
                   "&& getServiceManager().getBitFieldROState() != null;")
    elif mutation != 'none':
        raise SystemExit('unknown mutation ' + mutation)

    io.open(out, 'w', encoding='utf-8', newline='\n').write(text)


if __name__ == '__main__':
    main()
