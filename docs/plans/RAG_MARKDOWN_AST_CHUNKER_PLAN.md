# RAG Markdown AST Chunker Upgrade Plan

## 1. Goal

Upgrade `StructureAwareMarkdownChunker` from regex-based line scanning to an AST-driven Markdown chunker using the existing `flexmark-all` dependency.

The upgraded chunker should:

- Preserve original Markdown content in `KnowledgeChunkDraft.content`.
- Add heading context to `KnowledgeChunkDraft.embeddingText` for better retrieval.
- Store structured heading metadata, not only a flattened `sectionPath`.
- Avoid splitting code fences, GFM tables, lists, blockquotes, and other atomic Markdown blocks whenever practical.
- Keep the public `StructureAwareMarkdownChunker.chunk(String markdownText)` API unchanged.

## 2. Current State

Current flow:

```text
FULL ParseSegment
  -> SegmentAwareChunkerRouter.looksLikeMarkdown(text)
  -> StructureAwareMarkdownChunker.chunk(text)
  -> KnowledgeChunkDraft(content, metadata, embeddingText)
```

Current Markdown chunker behavior:

- Uses regex and line scanning.
- Recognizes heading, code fence, atomic image/link, and paragraph.
- Stores `sectionPath` as a flattened string such as `A / B / C`.
- Uses `content` as `embeddingText`.

Current weaknesses:

- `sectionPath` loses heading levels.
- Heading titles containing ` / ` become ambiguous.
- Tables, lists, blockquotes, and HTML blocks are not represented as first-class block types.
- `embeddingText` does not include heading context unless the heading text is already inside the chunk.
- Long or nested Markdown structures are handled with coarse character ranges.

## 3. Dependency Decision

Do not add a new Markdown parser dependency.

The project already has:

```xml
<dependency>
    <groupId>com.vladsch.flexmark</groupId>
    <artifactId>flexmark-all</artifactId>
    <version>0.64.8</version>
</dependency>
```

Use flexmark AST as the parser foundation.

## 4. Target Design

### 4.1 Data Model

Introduce internal models inside `StructureAwareMarkdownChunker`:

```java
record HeadingInfo(int level, String title) {}

record MarkdownBlock(
        BlockKind kind,
        int startOffset,
        int endOffset,
        List<HeadingInfo> headingPath,
        boolean atomic
) {}
```

Suggested `BlockKind` values:

```text
HEADING
PARAGRAPH
LIST
TABLE
CODE_BLOCK
BLOCKQUOTE
HTML
THEMATIC_BREAK
ATOMIC
OTHER
```

### 4.2 Metadata Shape

Each Markdown chunk should include metadata like:

```json
{
  "chunkStrategy": "markdown_ast",
  "chunkIndex": 0,
  "contentLength": 1200,
  "blockKinds": ["heading", "paragraph", "table"],
  "sectionPath": "A / B / C",
  "sectionTitle": "C",
  "sectionLevel": 3,
  "sectionHeadings": [
    {"level": 1, "title": "A"},
    {"level": 2, "title": "B"},
    {"level": 3, "title": "C"}
  ]
}
```

Keep `sectionPath` for display compatibility, but treat `sectionHeadings` as the source of truth.

### 4.3 Content vs Embedding Text

`content` should remain the original Markdown chunk body:

```markdown
### Retry Policy

The service retries transient failures three times.
```

`embeddingText` should include heading context:

```text
Section: Operations > Reliability > Retry Policy

### Retry Policy

The service retries transient failures three times.
```

This improves retrieval without polluting the displayed source text.

## 5. Chunking Algorithm

### 5.1 Parse Markdown

Use flexmark parser to parse the full Markdown document into an AST.

Preserve source offsets where possible through flexmark node source spans.

### 5.2 Flatten AST Blocks

Walk top-level and relevant nested block nodes to create `MarkdownBlock` entries.

Rules:

- Heading nodes update the current heading path.
- Paragraphs become `PARAGRAPH`.
- Bullet/ordered lists become `LIST`.
- Fenced/indented code blocks become `CODE_BLOCK`.
- GFM tables become `TABLE`.
- Blockquotes become `BLOCKQUOTE`.
- HTML blocks become `HTML`.
- Thematic breaks become `THEMATIC_BREAK`.

### 5.3 Build Sections

Use headings to form semantic sections.

Each heading starts a new section. The heading level updates `headingPath`:

```text
# A      -> [A]
## B     -> [A, B]
### C    -> [A, B, C]
## D     -> [A, D]
```

Sections should retain structured heading metadata:

```java
List<HeadingInfo> sectionHeadings
```

### 5.4 Pack Blocks

Within each section:

- Prefer keeping a section together when it fits.
- Pack blocks until `maxChars` would be exceeded.
- If current chunk is smaller than `minChars`, allow one extra block when needed.
- Do not split atomic blocks unless they exceed an oversized threshold.

Default existing limits stay:

```text
targetChars = 1400
maxChars = 1800
minChars = 600
overlapChars = 0
```

### 5.5 Handle Oversized Blocks

If a single block is larger than `maxChars`:

- For code/table/list/blockquote blocks, keep intact up to `maxChars * 2` if possible.
- If still too large, split by paragraph/sentence/character boundary.
- Mark metadata:

```json
{
  "splitOversizedBlock": true,
  "oversizedBlockKind": "table"
}
```

### 5.6 Compact Small Chunks

Preserve the existing small chunk compaction idea.

When merging metadata:

- Merge `blockKinds` without duplicates.
- Prefer the more specific `sectionHeadings`.
- Set `mergedSmallChunk = true`.
- Recompute `chunkIndex` and `contentLength`.

## 6. Compatibility

Keep these public contracts unchanged:

- `DocumentChunker`
- `KnowledgeChunkDraft`
- `SegmentAwareChunkerRouter`
- `StructureAwareMarkdownChunker.chunk(String)`

Expected `KnowledgeChunkDraft` semantics:

```text
content
  Original Markdown chunk used for citation/display.

metadata
  JSON metadata with chunk strategy and section structure.

embeddingText
  Retrieval text with section breadcrumb plus content.
```

`SegmentAwareChunkerRouter.attachSegmentMetadata(...)` should preserve Markdown metadata and append only source segment/page metadata.

## 7. Test Plan

Add or update tests for:

1. H1/H2/H3 headings produce structured `sectionHeadings`.
2. Heading titles containing ` / ` do not break structured metadata.
3. `content` does not include synthetic `Section:` prefix.
4. `embeddingText` includes `Section: A > B > C`.
5. Fenced code blocks are not split.
6. GFM tables are recognized as `table` blocks and not split when reasonable.
7. Lists are packed as list blocks.
8. Long sections split by block boundaries.
9. Oversized paragraph fallback sets `splitOversizedBlock`.
10. Small chunk compaction preserves heading metadata.
11. Router integration still sends Markdown-looking FULL segments to the Markdown chunker.

## 8. Implementation Order

1. Add focused tests for the target behavior.
2. Replace the internal block segmentation in `StructureAwareMarkdownChunker` with flexmark AST traversal.
3. Add structured heading metadata and heading-aware `embeddingText`.
4. Preserve and adapt block packing, oversized handling, and small chunk compaction.
5. Verify `SegmentAwareChunkerRouter` metadata attachment does not overwrite Markdown metadata.
6. Run focused chunker tests and RAG parser/ingestion tests.
7. Update `docs/summary/04-rag-pipeline.md` with the new Markdown chunking behavior.

## 9. Estimated Scope

Expected production changes:

- `StructureAwareMarkdownChunker.java`: major internal rewrite.
- `SegmentAwareChunkerRouter.java`: small compatibility check or adjustment if needed.
- No schema change expected.
- No `KnowledgeChunkDraft` change expected.

Expected tests:

- New or expanded `StructureAwareMarkdownChunkerTest`.
- Possible router integration test update.

Risk level: medium.

Main risk areas:

- Flexmark source offsets can be subtle for nested nodes.
- GFM table AST handling may need extension-specific node checks.
- Existing chunk metadata assertions may need updates from `structure_aware_markdown` to `markdown_ast`.
