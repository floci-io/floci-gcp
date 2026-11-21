#!/usr/bin/env python3
"""Deterministic group-by-profile test partitioner for CI sharding.

Usage: partition.sh <all-classes.txt> <shard-total> <shard-index>

<all-classes.txt> holds one test class per line in surefire includesFile path
format (io/floci/gcp/.../FooTest, no extension). Classes sharing a @TestProfile are
assigned to the SAME shard atomically, except known slow classes: each profile a shard
holds costs one Quarkus augmentation (~5.3s) plus one app start (~2s), so splitting a
profile group pays both once per shard it touches. Profile-less classes fill the remainder.
A class counts as one, but for the few in SLOW_CLASSES, whose run time would
otherwise be invisible to a partition that balances class counts. These are
separate groups even when they share a profile: saving one augmentation must
not force minutes of Docker work onto the same shard.

Deterministic: sorted inputs, greedy least-loaded bin-packing, no RNG/timestamps.
Exit 2 on bad arguments or an unreadable input file.
"""
import json
import os
import re
import sys

# One profile ~ augmentation (~5.3s) + app start (~2s) ~ 3 average test classes.
# Measured on CI run 44705428109 (2026-11-20): 55 augmentations in 4:51 before the first test.
AUGMENTATION_WEIGHT = 3

# Classes that take far longer than the suite's average, keyed by class name so package renames
# retain their weight, as {"ClassName": seconds}. Measured on CI run 44705428109 (2026-11-20).
# The tests pass their own through PARTITION_SLOW_CLASSES, a JSON object of the same shape.
DEFAULT_SLOW_CLASSES = {
    'BigQueryDuckIntegrationTest': 148,
    'BigQueryDuckDmlIntegrationTest': 44,
    'CloudRunJobsExecutionRestIntegrationTest': 33,
    'CloudKmsRsaServiceTest': 20,
}
SLOW_CLASSES = json.loads(os.environ['PARTITION_SLOW_CLASSES']) if 'PARTITION_SLOW_CLASSES' in os.environ \
    else DEFAULT_SLOW_CLASSES
SECONDS_PER_CLASS = 2.35  # the suite's mean: 797s over 339 classes on CI run 44705428109 (2026-11-20)

def weight(cls):
    """How many average test classes a class is worth."""
    seconds = SLOW_CLASSES.get(cls.rsplit('/', 1)[-1])
    return 1 if seconds is None else round(seconds / SECONDS_PER_CLASS)

def warn_of_stale_slow_classes(classes):
    """A renamed class would quietly lose its weight, so name it where the run's annotations show it."""
    known = {c.rsplit('/', 1)[-1] for c in classes}
    for name in sorted(set(SLOW_CLASSES) - known):
        print(f'::warning title=partition.sh::stale SLOW_CLASSES key: no test class named {name}', file=sys.stderr)

def profile_groups(test_root):
    """Map class-path -> profile key from @TestProfile usages in src/test."""
    ann = re.compile(r'@TestProfile\(\s*([A-Za-z0-9_.]+?)(?:\.class)?\s*\)')
    mapping = {}
    for dirpath, _, files in sorted(os.walk(test_root)):
        for f in sorted(files):
            if not f.endswith('.java'):
                continue
            path = os.path.join(dirpath, f)
            try:
                src = open(path, encoding='utf-8', errors='replace').read()
            except OSError:
                continue
            m = ann.search(src)
            if not m:
                continue
            cls = os.path.relpath(path, test_root)[:-len('.java')]
            # Key on the referenced profile token; identical tokens merge groups,
            # which is always safe (merging groups never splits an augmentation).
            mapping[cls] = m.group(1)
    return mapping

def holder_of(token, cls):
    """The class a nested profile is declared in, when that is not the annotated test class itself."""
    if '.' not in token:
        return None
    outer = token.rsplit('.', 1)[0]
    return None if outer.rsplit('.', 1)[-1] == cls.rsplit('/', 1)[-1] else outer

def main():
    if len(sys.argv) != 4:
        sys.exit('usage: partition.sh <all-classes.txt> <shard-total> <shard-index>')
    try:
        classes = [l.strip() for l in open(sys.argv[1]) if l.strip()]
        total, index = int(sys.argv[2]), int(sys.argv[3])
    except (OSError, ValueError) as e:
        sys.exit(f'partition.sh: {e}')
    if not (1 <= index <= total):
        sys.exit(f'partition.sh: shard index {index} outside 1..{total}')

    warn_of_stale_slow_classes(classes)
    prof = profile_groups('src/test/java')
    groups = {}
    tokens = {}
    plain = []
    for c in sorted(set(classes)):
        token = prof.get(c)
        if token is None:
            plain.append(c)
            continue
        holder = holder_of(token, c)
        if holder is not None:
            # Profiles declared together in a holder class that is not the test itself belong to tests
            # that run in order in one JVM (CloudRunJobsRestartProfiles: phase 2 reads what phase 1
            # wrote and skips itself without it), so the whole set stays on one shard.
            key = ('holder:' + holder, '')
        elif weight(c) > 1:
            key = (token, c)
        else:
            key = (token, '')
        groups.setdefault(key, []).append(c)
        tokens.setdefault(key, set()).add(token)

    load = [0] * total
    shard_of = {}
    # Heaviest first; ties broken by name for determinism.
    for key, members in sorted(groups.items(), key=lambda kv: (-sum(map(weight, kv[1])), kv[0])):
        target = min(range(total), key=lambda s: (load[s], s))
        load[target] += AUGMENTATION_WEIGHT * len(tokens[key]) + sum(map(weight, members))
        for c in members:
            shard_of[c] = target
    for c in sorted(plain, key=lambda c: (-weight(c), c)):
        target = min(range(total), key=lambda s: (load[s], s))
        load[target] += weight(c)
        shard_of[c] = target

    out = sorted(c for c, s in shard_of.items() if s == index - 1)
    if not out:
        sys.exit(f'partition.sh: shard {index}/{total} would be empty')
    print('\n'.join(out))

if __name__ == '__main__':
    main()
