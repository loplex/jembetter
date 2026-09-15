#!/usr/bin/env python3
"""Refuses a commit SHA written into tracked content.

Commit messages and the text of tracked files both make cross-references that
outlive the machine they were written on. A SHA is the worst way to write one.
It is unreadable on its own, it says nothing about what it points at, and it
stops resolving the moment the branch it names is rewritten - which is a thing
that happens to every branch here before it lands.

An earlier version of this check tried to sort good SHAs from bad by resolving
them against remote-tracking refs. That works, and it made the verdict a
property of the remote rather than of the commit: the same message passed on
one machine and failed on another, and passed before a push and failed after
one. Two references sat unreported because of it.

So the rule is the blunt one instead, and it is a property of the text alone:
do not write a commit SHA. Write the commit's subject. Subjects are unique
across this repository's history, so nothing is lost by it, and a subject still
means something to a reader who has no clone at all.

Exits 1 and prints one line per SHA found.
"""

import re
import subprocess
import sys

DEFAULT_RANGE = "origin/main..HEAD"

# Seven hex digits is git's own abbreviation floor and the shortest form worth
# mistaking for a reference. Bounded at 40, which also makes a longer hex run
# immune: a sha256 in a quoted log line has no word boundary 40 characters in,
# so it never matches. `0x`-prefixed constants are immune for the same reason -
# the `x` is a word character, so there is no boundary before the digits.
HEX = re.compile(r"\b[0-9a-f]{7,40}\b")

# A run of hex digits that happens to be all decimal is a number, not a SHA:
# these messages quote GitHub run ids ("Run 34874215120") and measurements.
DECIMAL = re.compile(r"\A[0-9]+\Z")


def git(*args):
    return subprocess.run(
        ["git", *args], capture_output=True, text=True, check=False
    ).stdout.strip()


def references(rev_range):
    """Yield (where, token) for every SHA-shaped token in scope.

    Messages are read over the range; tracked files are read at HEAD, through
    `git grep -I` so binary content is skipped. A file needs no range - text
    that names a SHA names it now, whichever commit wrote it.
    """
    for sha in [s for s in git("rev-list", rev_range).split("\n") if s]:
        where = f"{sha[:7]} ({git('log', '-1', '--format=%s', sha)})"
        for token in HEX.findall(git("log", "-1", "--format=%B", sha)):
            yield where, token

    # HEX.pattern rather than a second copy: git grep -E reads the same word
    # boundaries Python does here, and two spellings of one rule drift apart.
    for line in [l for l in git("grep", "-InE", HEX.pattern).split("\n") if l]:
        path, lineno, text = line.split(":", 2)
        for token in HEX.findall(text):
            yield f"{path}:{lineno}", token


def main():
    rev_range = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_RANGE
    if not git("rev-parse", "--verify", "--quiet", rev_range.split("..")[0]):
        print(f"{sys.argv[0]}: cannot resolve the range {rev_range} - "
              f"is the base branch fetched?", file=sys.stderr)
        return 2

    seen = set()
    for where, token in references(rev_range):
        if DECIMAL.match(token) or (where, token) in seen:
            continue
        seen.add((where, token))
        print(f"SHA  {where}: '{token}' - name the commit by its subject")

    if seen:
        print(f"\n{len(seen)} commit SHA(s) written into tracked content in "
              f"{rev_range}. A SHA is not a reference anyone else can follow.")
        return 1
    print(f"No commit SHAs written into tracked content in {rev_range}.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
