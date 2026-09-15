# commit-refs

Refuses a commit SHA written into tracked content — commit messages and the
text of tracked files alike.

    build-tools/commit-refs/check.py [<range>]      # default: origin/main..HEAD

Linux CI runs it on every push, next to the Markdown link check, which does the
same job for the other cross-reference surface.

## The rule

**Do not write a commit SHA. Write the commit's subject.**

Subjects are unique across this repository's history — 190 commits, 190
distinct subjects when this was measured — so the subject identifies the commit
exactly as a SHA does, and it does it for a reader who has no clone.

## Why a blunt rule rather than a careful one

An earlier version sorted good SHAs from bad. It resolved each one and asked
whether any remote-tracking ref contained it: a SHA reachable from a remote was
fine, one reachable only locally was `BROKEN`, and one naming a sibling commit
in the range under test was `FRAGILE`.

That worked, and the verdict it produced was a property of the *remote* rather
than of the text. The same message passed on one machine and failed on another.
It passed before a push and failed after one. Two sibling references sat in a
branch unreported, because a sibling on a pushed branch is reachable and the
reachable case was checked first — and a history rewrite then invalidated both,
which is exactly what the check existed to prevent.

A rule that reads only the text has none of that. It needs no fetch, no
`refs/remotes`, and no argument about ordering, because there is nothing to
order. The same text gets the same verdict everywhere, forever.

It is stricter than necessary in one case: a SHA naming a commit already on
`main` is permanent and would never break. Banning it too costs nothing, since
the subject says more anyway, and it buys a rule with no exceptions to
remember.

## What counts as a SHA

A run of 7 to 40 hexadecimal digits with a word boundary at each end. Three
things are therefore immune, by construction rather than by special case:

- **`0x`-prefixed constants.** `0xdeadbeef` has no word boundary before the
  digits, because `x` is a word character.
- **Longer digests.** A 64-character sha256 has no word boundary 40 characters
  in, so no substring of it matches.
- **Decimal runs.** A hex-shaped token that is all digits is a number — these
  messages quote GitHub run ids and measurements.

Seven is git's own abbreviation floor, so anything shorter is not a reference
to begin with.

## What it deliberately does not check

- **Prose pointers.** "The previous commit" is not mechanically resolvable, and
  it breaks just as silently — two such pointers in this repository were each
  three commits off their target, and survived until someone read them.
  Nothing here can catch that; reading can.
- **Whether a cited subject is the right one.** Naming a commit by its subject
  is not verified against anything. The rule moves the reference into a form a
  human can check, which is as far as a machine gets.
- **Fenced code.** Unlike the Markdown link check, a fence is not exempt. A
  link inside a fence is usually an example; a SHA inside one is usually real
  output naming a real commit, and it goes stale the same way.

## One residual dependency

The range `origin/main..HEAD` still needs enough history to find the merge
base, which is why the CI job checks out with `fetch-depth: 0`. That affects
*which commits are read*, never the verdict on any one of them.
