# commit-refs

Resolves every commit SHA written into tracked content — commit messages and
the text of tracked files alike — and fails if one of them would not resolve
for somebody who clones this repository.

    build-tools/commit-refs/check.py [<range>]      # default: origin/main..HEAD

Linux CI runs it on every push, next to the Markdown link check, which does the
same job for the other cross-reference surface.

## Why this needs checking at all

A commit message is tracked content, it makes cross-references, and until this
existed nothing resolved them. Neither did anything resolve a SHA written into
the *body* of a tracked file, which is the same reference with the same failure
mode: this checker's own README carried two of them until 2026-09-15, and they
were found by an ad-hoc grep rather than by any check. The argument is the one
[docs-links](../docs-links/README.md) already makes: a pointer written as prose
cannot be checked by anything, so it rots silently.

A SHA is the half of that problem which is mechanically checkable, and it is
also the half that breaks for other people rather than for the author. A
message can cite a commit that exists only in the author's own object store —
a pre-rebase version of one that was rewritten, kept alive by a local backup
tag — and it resolves on that machine and on no other.

That is not hypothetical: it is why this check exists. Two messages in this
repository cited a pre-rewrite version of a commit that had since been
rebased, leaving the SHA reachable from local backup tags and from nothing a
clone would get. Both were corrected by naming the commit by its subject,
which is the fix this check exists to prompt.

## Reachability means remote-tracking refs, never local ones

This repository keeps a dozen local backup branches and tags from earlier
history cleanups. A rewritten commit stays reachable from those forever, so a
check that counted any local ref would pass on the exact case it was written
for. Reachability is therefore measured against `refs/remotes` alone.

Two consequences:

- **The remote-tracking refs have to be current.** The check is only as
  accurate as the last fetch, which is why the CI job checks out with
  `fetch-depth: 0` rather than the default shallow clone.
- **A reference to a sibling commit in the same range is `FRAGILE`, not
  `BROKEN`.** It will exist for a clone once the branch is pushed, so it does
  not fail the check — but rewriting the branch changes that SHA and turns it
  into the broken case. The fix in both directions is the same: name the commit
  by its subject rather than by a SHA.

  This is asked *before* reachability, not after. A sibling on a branch that
  has already been pushed is reachable, so the other order answers that first
  and reports nothing — leaving the verdict silent on every branch where the
  reference is about to be rewritten, which is the only place it matters.

## Two surfaces, one question

Commit messages are walked over the range under test. Tracked files are walked
at `HEAD`, through `git grep -I`, which skips binary content: a reference that
is stale now is stale regardless of which commit wrote it, so there is no range
to apply. Both feed the same three verdicts.

A SHA inside a fenced code block in a tracked file is **not** exempt, unlike
the Markdown link check, which ignores fences. A link in a fence is usually an
example; a SHA in a fence is usually real output naming a real commit, and one
that stops resolving is worth the same report as any other. Invented example
SHAs resolve to no commit and are reported — which has not come up, and would
be answered by inventing one that is not 7–40 hex digits.

## What it deliberately does not check

- **Prose pointers.** "The previous commit" is not mechanically resolvable, and
  it breaks just as silently — two such pointers in this repository were each
  three commits off their target, and survived until someone read them.
  Nothing here can catch that; reading can.
- **Whether the cited commit is the right one.** A SHA that resolves to an
  unrelated commit passes.
- **Hex that is not a SHA.** A run of 7–40 hex digits that is all decimal is
  treated as a number, because these messages quote GitHub run ids and
  measurements. A 7-digit hex string that is neither a SHA nor a number would
  be reported, and has not come up.
