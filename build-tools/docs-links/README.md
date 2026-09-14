# Markdown link check

`check.py` resolves every relative link in every tracked `.md` file, and every
`#anchor` those links point at. No arguments, run it from anywhere in the
working tree:

```sh
python3 build-tools/docs-links/check.py
```

It prints one `path:line: reason` per broken link and exits 1, or prints that
everything resolves and exits 0. [Linux (X11) CI](../../.github/workflows/linux-ci.yml)
runs it before the test suite, where it costs about a second.

## Why this exists rather than careful reading

Nothing about a stale link looks stale. A renamed heading, a moved file and a
deleted section all render perfectly, and the first person to find out is a
reader who followed one.

This is also what makes it worth writing cross-references as links in the
first place. A pointer written as prose — "see the section above", "as the
architecture document explains" — cannot be checked by anything, so it rots
silently. A link can be checked, and this is what checks it.

## What it deliberately does not check

- **External `http(s)` URLs.** They fail for reasons unrelated to the commit,
  and a check that goes red on someone else's outage stops being read, along
  with everything beside it.
- **Line anchors into source files** (`Foo.java#L42`). Those are GitHub's
  invention, not the file's own; nothing local can confirm them.
- **Anything inside fenced code.** A `# comment` in a shell sample is not a
  heading, and a link in an example is not a claim about this repository.
- **Whether a link points at the *right* thing.** It resolves, which is all a
  machine can say. That a paragraph's pointer leads somewhere useful stays a
  matter of reading it.

## Anchors

Heading anchors follow GitHub's generator: lowercase, punctuation dropped,
spaces to dashes, and a repeated heading numbered `-1`, `-2` and so on.
Explicit `<a id="...">` and `<a name="...">` targets count as anchors too.

The corner worth knowing is punctuation with spaces around it — `Build & run`
loses the `&` and keeps both spaces, so the slug comes out with two dashes
(`build--run`). Check what this script says rather than guessing when a link
into such a heading does not resolve.
