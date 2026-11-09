# aux-docs: Knowledge bases for agent tooling

This directory hosts book-derived knowledge bases that agents (e.g. the
oh-my-opencode-slim designer agent) search via the
[semble](https://github.com/MinishLab/semble) MCP server. Semble indexes
**directories of supported file types** — it cannot read an `.epub` directly.
This README documents how an epub is turned into a searchable knowledge base
and why the resulting structure looks the way it does.

Current knowledge base: **"Usability und UX für Dummies" (Elske Ludewig,
Wiley-VCH)** in `usability-ux-fuer-dummies/`.

> Copyrighted content: the extracted directories are gitignored on purpose.
> Never commit the epub, the extracted files, or the converted markdown.

## Why per-chapter Markdown files (and not the alternatives)

Three layouts were tested against semble (`content: "docs"`):

1. **Raw `.epub`** — rejected outright: semble only indexes directories
   ("Path is not a directory").
2. **Extracted XHTML + `.sembleignore`** — works (see fallback below), but is
   the inferior format:
   * Semble's chunker emits one chunk per XHTML paragraph, so snippets carry
     little surrounding context.
   * Every snippet is wrapped in markup noise (`<p class="Normal"
     id="c03-para-0070">…`), wasting tokens on every search.
3. **Single merged file** — tested and rejected: retrieval quality is
   identical, but citations become useless. A hit like `book.md:5494` forces
   the agent to map a line offset back to a chapter, while `c09.md:504` is a
   directly usable chapter/line reference. Semble's file-coherence ranking
   boost also only helps when chapters are separate files: it then points at
   the *right chapter* instead of being wasted on one monolithic file.

**Per-chapter Markdown wins**: `html2text` strips markup noise, produces
clean multi-line prose chunks, keeps tables readable, and preserves the
figure captions (»Abbildung N.M: …») that anchor each figure reference.
German prose queries retrieve accurately against it.

## Extraction & preprocessing recipe

Prerequisites: `unzip`, `html2text` (Python variant; flags used below are for
the Python CLI, not the legacy C `html2text -utf8`).

```bash
# 1. Extract the epub
mkdir -p aux-docs/<book-name>/md
cd aux-docs/<book-name>
unzip -oq "<book>.epub"

# 2. Convert every chapter XHTML to clean Markdown
for f in OPS/*.xhtml; do
  base=$(basename "$f" .xhtml)
  html2text --ignore-images --ignore-links -b 100 "$f" > "md/$base.md"
done

# 3. Extract the real figures (skip decorative icons like Tipp.png/Warnung.png)
unzip -oq "<book>.epub" "OPS/images/c*f*.png" -d /tmp/epub-imgs/
mkdir -p figures
cp /tmp/epub-imgs/OPS/images/*.png figures/
rm -rf /tmp/epub-imgs

# 4. Remove the raw extraction; only md/ and figures/ remain
rm -rf OPS META-INF mimetype
```

Notes:
* `--ignore-images` drops the 100 MB of inline PNGs from the markdown;
  informational figures are kept separately in `figures/` (see below).
* `--ignore-links` removes epub-internal anchors, which html2text would
  otherwise render as noisy bracket references.
* `-b 100` wraps lines around 100 chars — long enough for readable prose
  chunks, short enough for clean diffs.

### Figures

Most epub images are decorative margin icons (Tipp/Warnung/Techniker/Definition)
— drop them. The real figures matter: their captions survive in the markdown,
but some figures are **data-bearing diagrams** (button pixel sizes, screen
resolution tables, method comparison matrices) whose values exist only in the
pixels. For those, keep the PNGs in `figures/` plus a `figures/manifest.md`
mapping each filename to its caption, so a vision-capable agent can look the
image up instead of guessing from the caption.

Figure filenames in this epub follow the pattern `cNNfMMM.png` =
»Abbildung NN.MMM» (`c13f009.png` = Abbildung 13.9), which makes the mapping
trivial to script.

### Fallback: no html2text available

Semble ignores `.xhtml` by default; a `.sembleignore` with a `!` pattern
force-includes it:

```bash
cd aux-docs/<book-name>
unzip -oq "<book>.epub" "OPS/*.xhtml" -x "OPS/navigation.xhtml"
printf '!*.xhtml\n' > OPS/.sembleignore
```

Then index `OPS/` instead of `md/` (same `content: "docs"`). Trade-off: noisy
markup snippets and single-paragraph chunks, as described above.

## Searching the knowledge base (semble parameters)

* `repo`: the `md/` directory, relative to the repo root
  (e.g. `aux-docs/usability-ux-fuer-dummies/md`)
* `content`: `"docs"` — **mandatory**; without it the index finds nothing
* `query`: natural language; German works best for German books
* `top_k`: 4–6; `max_snippet_lines`: 6–10 to confirm relevance, then re-run
  with `max_snippet_lines: null` for the full chunk
* `find_related` accepts the same `repo`/`content` plus `file_path`/`line`
  from a previous hit to pull adjacent methods from other chapters.

Never grep/grep the markdown for content — go through semble; its index is
cached and re-validated automatically.
