"""Writes a mutated copy of CommandBatchService.java. Usage: mutate-batch.py <source> <out> <mutation>

The patterns match the batch rules after the read-only gate was added.
"""
import io
import sys

src, out, mutation = sys.argv[1], sys.argv[2], sys.argv[3]
s = io.open(src, encoding='utf-8').read()


def rep(old, new):
    global s
    assert s.count(old) == 1, (s.count(old), old[:70])
    s = s.replace(old, new)


RULE = """            boolean useBitFieldRO = state == BitFieldROState.AVAILABLE
                    || (state == BitFieldROState.UNKNOWN && entry.isReadOnlyMode());"""
LOOP = """            for (Map.Entry<NodeSource, Entry> e : entries.entrySet()) {
                for (BatchCommandData<?, ?> data : e.getValue().getCommands()) {
                    if (isBitFieldROApplicable(data.getCommand(), data.getParams())) {"""

if mutation == 'in-memory-gate-off':
    # a command sent as a write is resolved in a batch kept in memory too
    rep("""            if (readOnlyMode && isBitFieldROApplicable(command, params)) {""",
        """            if (isBitFieldROApplicable(command, params)) {""")
elif mutation == 'queued-gate-off':
    # a queued command sent as a write is resolved too
    rep("""        if (!readOnlyMode || !isBitFieldROApplicable(command, params)) {""",
        """        if (!isBitFieldROApplicable(command, params)) {""")
elif mutation == 'check-only-read-batches':
    rep(LOOP, """            for (Map.Entry<NodeSource, Entry> e : entries.entrySet()) {
                if (!e.getValue().isReadOnlyMode()) {
                    continue;
                }
                for (BatchCommandData<?, ?> data : e.getValue().getCommands()) {
                    if (isBitFieldROApplicable(data.getCommand(), data.getParams())) {""")
elif mutation == 'master-batch-always-bitfield':
    rep(RULE, """            boolean useBitFieldRO = entry.isReadOnlyMode() && state != BitFieldROState.UNAVAILABLE;""")
elif mutation == 'ignore-unavailable':
    rep(RULE, """            boolean useBitFieldRO = state == BitFieldROState.AVAILABLE || entry.isReadOnlyMode();""")
elif mutation == 'no-check':
    rep("""    private CompletionStage<Void> resolveBitFieldCommands(Map<NodeSource, Entry> entries) {
        if (getServiceManager().getBitFieldROState() == BitFieldROState.UNKNOWN) {""",
        """    private CompletionStage<Void> resolveBitFieldCommands(Map<NodeSource, Entry> entries) {
        if (false && getServiceManager().getBitFieldROState() == BitFieldROState.UNKNOWN) {""")
elif mutation == 'unknown-uses-bitfield-ro':
    rep(RULE, """            boolean useBitFieldRO = state != BitFieldROState.UNAVAILABLE;""")
elif mutation != 'none':
    raise SystemExit('unknown mutation ' + mutation)

io.open(out, 'w', encoding='utf-8').write(s)
