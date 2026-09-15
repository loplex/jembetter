#!/usr/bin/env python3
"""Resolves every commit SHA a commit message mentions.

A commit message is tracked content that makes cross-references, and nothing
resolved them. `build-tools/docs-links/check.py` does this job for Markdown,
and its README argues the general case: a pointer written as prose cannot be
checked by anything, so it rots silently.

A SHA is the half of that problem which *is* mechanically checkable, and it is
also the half that breaks for someone who clones. A message can cite a commit
that exists only in the author's object store - a pre-rebase version of one that
was rewritten, say - and it will resolve for them and for nobody else.

Prose pointers ("the previous commit") are not checkable and are out of scope.
See this directory's README for what else is deliberately not checked.

Exits 1 and prints one line per unresolvable reference.
"""

import re
import subprocess
import sys

DEFAULT_RANGE = "origin/main..HEAD"

# Seven hex digits is git's own abbreviation floor and the shortest form worth
# treating as a reference. Bounded at 40 so a longer hex run - a key, a hash in
# a quoted log line - is not silently truncated into something that resolves.
HEX = re.compile(r"\b[0-9a-f]{7,40}\b")

# A run of hex digits that happens to be all decimal is a number, not a SHA:
# these messages quote GitHub run ids ("Run 34874215120") and measurements.
DECIMAL = re.compile(r"\A[0-9]+\Z")


def git(*args):
    return subprocess.run(
        ["git", *args], capture_output=True, text=True, check=False
    ).stdout.strip()


def commits(rev_range):
    out = git("rev-list", rev_range)
    return out.split("\n") if out else []


def resolve(token):
    """(full sha or '', reachable-from-a-remote) for a candidate SHA.

    Reachability is measured against remote-tracking refs *only*, never local
    ones. That distinction is the whole point: this repository keeps a dozen
    local backup branches and tags from earlier history cleanups, and a
    rewritten commit stays reachable from those forever. It resolves on the
    author's machine and nowhere else, which is exactly the failure being
    looked for - so counting a local ref as reachable would make the check
    pass on the very case that motivated it.

    Accuracy therefore depends on the remote-tracking refs being current; the
    CI step fetches first.
    """
    full = git("rev-parse", "--verify", "--quiet", f"{token}^{{commit}}")
    if not full:
        return "", False
    # --contains=<sha>, not --contains <sha>: the value is optional, so the
    # separate form invites git to read the next argument as a ref pattern.
    refs = git("for-each-ref", f"--contains={full}", "--format=%(refname:short)",
               "refs/remotes")
    return full, bool(refs)


def main():
    rev_range = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_RANGE
    if not git("rev-parse", "--verify", "--quiet", rev_range.split("..")[0]):
        print(f"{sys.argv[0]}: cannot resolve the range {rev_range} - "
              f"is the base branch fetched?", file=sys.stderr)
        return 2

    in_range = set(commits(rev_range))
    problems = 0
    fragile = 0

    for sha in in_range:
        subject = git("log", "-1", "--format=%s", sha)
        message = git("log", "-1", "--format=%B", sha)
        seen = set()
        for token in HEX.findall(message):
            if DECIMAL.match(token) or token in seen:
                continue
            seen.add(token)
            # A commit citing itself by its own abbreviation cannot be a
            # defect - it resolves by construction.
            if sha.startswith(token):
                continue
            full, reachable = resolve(token)
            if not full:
                problems += 1
                print(f"BROKEN   {sha[:7]} ({subject}): '{token}' resolves to no commit")
            elif full in in_range:
                # Not broken: it is a sibling in the range about to be pushed,
                # so it will exist for a clone. Reported anyway, because a
                # rewrite of this branch changes that SHA and turns the
                # reference into the broken case below.
                #
                # Asked before reachability, and the order is the whole of it.
                # Once the branch has been pushed a sibling *is* contained in a
                # remote-tracking ref, so testing reachability first takes the
                # continue below and says nothing - silent on exactly the
                # branches whose references are about to be rewritten. Range
                # membership is a property of the commit; reachability is a
                # property of the remote, and the narrower question goes first.
                fragile += 1
                print(f"FRAGILE  {sha[:7]} ({subject}): '{token}' is another commit "
                      f"in this range - a rewrite of the branch will break it")
            else:
                problems += 1
                print(f"BROKEN   {sha[:7]} ({subject}): '{token}' resolves locally, but "
                      f"no remote-tracking ref contains it - it does not exist for a clone")

    if problems:
        print(f"\n{problems} broken commit reference(s) in {rev_range}"
              + (f", and {fragile} fragile." if fragile else "."))
        return 1
    if fragile:
        print(f"\nNo broken references in {rev_range}, but {fragile} would not "
              f"survive a rewrite of this branch.")
        return 0
    print(f"Every commit SHA mentioned in {rev_range} resolves for a clone.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
