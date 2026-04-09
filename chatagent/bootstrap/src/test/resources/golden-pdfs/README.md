# Golden PDF Validation Samples

This directory holds real PDF samples used by `GoldenPdfValidationTest` to verify
end-to-end parsing quality after parameter changes or engine upgrades.

## Directory Layout

```
golden-pdfs/
  scanned/     # Scanned-image PDFs (no native text layer)
  tables/      # PDFs with structured tables
  headings/    # PDFs with clear heading hierarchy (font-size changes)
  mixed/       # PDFs mixing native text pages and image/chart pages
  expected/    # Expected output snapshots (*.segments.json)
```

## Naming Convention

Each PDF file is named `<category>-<nn>.pdf`, e.g. `table-01.pdf`, `scanned-02.pdf`.
The matching expected output is `expected/<category>-<nn>.segments.json`.

## Adding a New Sample

1. Place the PDF in the appropriate category directory.
2. Create a matching `expected/<name>.segments.json` (see schema below).
3. Run: `mvn test -pl bootstrap -am -Dsurefire.failIfNoSpecifiedTests=false -Dsurefire.excludedGroups= -Dgroups=golden -Dtest=GoldenPdfValidationTest`
4. Verify assertions pass; adjust expected values or parser parameters as needed.

## Expected Output Schema (`*.segments.json`)

```json
{
  "documentId": "table-01",
  "expectedSegmentCount": 3,
  "expectedExtractionMode": "PDF_VISUAL_ROUTED",
  "segments": [
    {
      "pageIndex": 0,
      "expectedRoute": "FAST_TRACK",
      "mustContain": ["Introduction"],
      "mustNotContain": ["[Image parsing failed]"],
      "expectedVisualType": null
    },
    {
      "pageIndex": 1,
      "expectedRoute": "VISUAL_TRACK",
      "mustContain": ["|", "---"],
      "mustNotContain": [],
      "expectedVisualType": "TABLE"
    }
  ]
}
```

### Field Descriptions

| Field | Required | Description |
|---|---|---|
| `documentId` | yes | Matches PDF filename without extension |
| `expectedSegmentCount` | no | If set, asserts exact segment count |
| `expectedExtractionMode` | no | One of `NATIVE_TEXT`, `PDF_VISUAL_ROUTED`, `OCR_REQUIRED` |
| `segments[].pageIndex` | yes | 0-based page index |
| `segments[].expectedRoute` | no | `FAST_TRACK` or `VISUAL_TRACK` |
| `segments[].mustContain` | no | Substrings that must appear in the page segment text |
| `segments[].mustNotContain` | no | Substrings that must NOT appear in the page segment text |
| `segments[].expectedVisualType` | no | `TABLE`, `CHART`, `FORMULA`, `IMAGE` (only for visual-track pages) |

## Size Constraint

Keep individual PDFs under **5 MB** (compressed subsets preferred). Total directory
size should stay under 20 MB to keep the repository lightweight.

## CI Behavior

These tests are tagged `@Tag("golden")` and are **excluded from the default surefire
run**. They only execute when explicitly requested:

```bash
mvn test -pl bootstrap -am -Dsurefire.failIfNoSpecifiedTests=false -Dsurefire.excludedGroups= -Dgroups=golden -Dtest=GoldenPdfValidationTest
```
