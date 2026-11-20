"""Compares two result tables of TmpMixedBatchAclTest row by row. Usage: compare-mixed.py <base> <new>"""
import io
import sys


def load(path):
    rows = {}
    for line in io.open(path, encoding='utf-8'):
        if ' -> ' not in line or line.startswith('as reader'):
            continue
        key, result = line.rstrip('\n').split(' -> ', 1)
        key = ' | '.join(p.strip() for p in key.split('|'))
        runs = [r.strip() for r in result.split('), ')]
        oks = ['OK' if r.startswith('OK') else 'FAIL' for r in result.replace('), ', ')\n').split('\n') for r in r.split(', ') if r]
        rows[key] = (result, oks)
    return rows


def share(oks):
    return sum(1 for o in oks if o == 'OK') / float(len(oks))


base = load(sys.argv[1])
new = load(sys.argv[2])
better, worse, same = [], [], 0
for key in base:
    b = share(base[key][1])
    n = share(new[key][1])
    if n > b:
        better.append(key)
    elif n < b:
        worse.append(key)
    else:
        same += 1

out = sys.stdout.buffer
out.write(('rows=%d same=%d better=%d worse=%d\n' % (len(base), same, len(better), len(worse))).encode('utf-8'))
for name, keys in (('WORSE', worse), ('BETTER', better)):
    for key in keys:
        out.write(('%s: %s\n    base: %s\n    new:  %s\n' % (name, key, base[key][0][:110], new[key][0][:110])).encode('utf-8'))
