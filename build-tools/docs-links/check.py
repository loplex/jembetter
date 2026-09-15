#!/usr/bin/env python3
"""Resolves every relative Markdown link in the repository, and every anchor.

Prose cross-references cannot be checked by anything, which is why this repo
writes them as links. A link is only better than prose if something notices
when its target moves, so this is that something. Run it with no arguments
from anywhere in the working tree; see this directory's README for what it
deliberately does not check.

Exits 1 and prints one line per broken link.
"""

import pathlib
import re
import subprocess
import sys

# Inline and reference-style links, plus bare <angle> autolinks. Images too:
# a missing image is a broken link with a different renderer.
LINK = re.compile(r"!?\[[^\]]*\]\(([^)\s]+)(?:\s+\"[^\"]*\")?\)")
FENCE = re.compile(r"^(\s*)(```|~~~)")
HEADING = re.compile(r"^#{1,6}\s+(.*?)\s*#*\s*$")


def strip_fences(text):
    """Blanks out fenced code, so a shell comment is not read as a heading."""
    out, fence = [], None
    for line in text.splitlines():
        match = FENCE.match(line)
        if fence is None and match:
            fence = match.group(2)
            out.append("")
            continue
        if fence is not None:
            out.append("")
            if line.strip().startswith(fence):
                fence = None
            continue
        out.append(line)
    return out


def slug(heading):
    """GitHub's anchor generator: lowercase, drop punctuation, spaces to dashes."""
    text = re.sub(r"`([^`]*)`", r"\1", heading)
    text = re.sub(r"!?\[([^\]]*)\]\([^)]*\)", r"\1", text)
    text = re.sub(r"[*_~]", "", text)
    text = text.strip().lower()
    text = re.sub(r"[^\w\s-]", "", text, flags=re.UNICODE)
    return re.sub(r"\s+", "-", text)


def anchors(path):
    """Every anchor a link can reach in `path`, duplicates numbered as GitHub does."""
    found, seen = set(), {}
    for line in strip_fences(path.read_text(encoding="utf-8")):
        match = HEADING.match(line)
        if not match:
            continue
        base = slug(match.group(1))
        count = seen.get(base, 0)
        seen[base] = count + 1
        found.add(base if count == 0 else f"{base}-{count}")
    # Explicit <a id="..."> / <a name="..."> targets count too.
    for line in strip_fences(path.read_text(encoding="utf-8")):
        found.update(re.findall(r"<a\s+(?:id|name)=\"([^\"]+)\"", line))
    return found


def markdown_files(root):
    listed = subprocess.run(["git", "-C", str(root), "ls-files", "*.md"],
                            capture_output=True, text=True, check=True)
    return [root / name for name in listed.stdout.split()]


def check(root):
    broken = []
    anchor_cache = {}
    for path in markdown_files(root):
        for line_number, line in enumerate(strip_fences(path.read_text(encoding="utf-8")), 1):
            for target in LINK.findall(line):
                if target.startswith(("http://", "https://", "mailto:", "#!")):
                    # External URLs fail for reasons unrelated to the commit;
                    # a check that goes red on someone else's outage stops
                    # being read, along with everything beside it.
                    continue
                file_part, _, fragment = target.partition("#")
                if file_part:
                    resolved = (path.parent / file_part).resolve()
                    if not resolved.exists():
                        broken.append(f"{path}:{line_number}: no such file: {target}")
                        continue
                else:
                    resolved = path.resolve()
                if not fragment:
                    continue
                if resolved.suffix != ".md":
                    # Line anchors into source files (#L42) are GitHub's, not
                    # the file's own; nothing here can confirm them.
                    continue
                if resolved not in anchor_cache:
                    anchor_cache[resolved] = anchors(resolved)
                if fragment.lower() not in anchor_cache[resolved]:
                    broken.append(f"{path}:{line_number}: no such heading: {target}")
    return broken


def main():
    root = pathlib.Path(subprocess.run(["git", "rev-parse", "--show-toplevel"],
                                       capture_output=True, text=True, check=True)
                        .stdout.strip())
    broken = check(root)
    for problem in broken:
        print(problem)
    if broken:
        print(f"\n{len(broken)} broken link(s).")
        return 1
    print("All relative Markdown links and anchors resolve.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
