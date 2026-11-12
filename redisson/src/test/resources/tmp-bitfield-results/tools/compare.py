"""Compares two result files of the same Tmp*Test line by line.

Usage: compare.py <before> <after>

Prints the rows whose share of successful runs differs. A row holds several runs
separated by "," or "/ 2nd:". TmpBatchMatrixTest prints a single result if both of its
runs ended the same way. Rows that alternate between OK and FAIL in a different order
count as equal.
"""
import io
import re
import sys


def load(path):
    rows = {}
    for line in io.open(path, encoding='utf-8'):
        if '->' not in line or line.startswith('check'):
            continue
        key, value = line.rstrip('\n').split('->', 1)
        runs = [run.strip() for run in re.split(r',|/', value.replace('2nd:', ''))]
        # an error message may contain a comma, so only count the runs that start a result
        runs = [run for run in runs if re.match(r'(OK|FAIL|HANG|WRONG)', run)]
        rows[re.sub(r'\s+', ' ', key.strip())] = (sum(1 for run in runs if run == 'OK'), len(runs))
    return rows


def main():
    before, after = load(sys.argv[1]), load(sys.argv[2])
    worse = better = 0
    for key in before:
        if key not in after:
            continue
        # compare shares, the number of printed runs may differ
        was = before[key][0] * after[key][1]
        now = after[key][0] * before[key][1]
        if was == now:
            continue
        if now < was:
            worse += 1
        else:
            better += 1
        print('%-6s %-66s %d/%d -> %d/%d' % (('worse' if now < was else 'better', key) + before[key] + after[key]))
    all_ok = sum(1 for ok, runs in after.values() if ok == runs)
    print('rows=%d  all runs OK after=%d  better=%d  worse=%d' % (len(after), all_ok, better, worse))


if __name__ == '__main__':
    main()
