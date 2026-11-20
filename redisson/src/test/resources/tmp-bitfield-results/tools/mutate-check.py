"""Writes a mutated copy of CommandAsyncService.java. Usage: mutate-check.py <source> <out> <mutation>

The patterns match checkBitFieldRO(), the check a batch runs before it sends BITFIELD reads.
"""
import io
import sys

src, out, mutation = sys.argv[1], sys.argv[2], sys.argv[3]
s = io.open(src, encoding='utf-8').read()

MARKS = """            if (error == null) {
                getServiceManager().markBitFieldROAvailable();
            } else if (isBitFieldROUnavailable(error)) {
                getServiceManager().markBitFieldROUnavailable();
            }
            return null;"""


def rep(old, new):
    global s
    assert s.count(old) == 1, (s.count(old), old[:70])
    s = s.replace(old, new)


if mutation == 'check-no-available':
    rep(MARKS, """            if (error != null && isBitFieldROUnavailable(error)) {
                getServiceManager().markBitFieldROUnavailable();
            }
            return null;""")
elif mutation == 'check-no-unavailable':
    rep(MARKS, """            if (error == null) {
                getServiceManager().markBitFieldROAvailable();
            }
            return null;""")
elif mutation != 'none':
    raise SystemExit('unknown mutation ' + mutation)

io.open(out, 'w', encoding='utf-8').write(s)
