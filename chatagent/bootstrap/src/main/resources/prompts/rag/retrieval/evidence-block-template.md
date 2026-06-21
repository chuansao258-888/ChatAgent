<!-- version: v5 -->
<!-- path: prompts/rag/retrieval/evidence-block-template.md -->

Use the following numbered evidence snippets when answering the user's question.

# Citation Rules

- If you use information from a snippet, cite it inline with [n] using the matching number below (e.g., "According to the document [1]...").
- Always cite the specific snippet number when stating facts derived from the evidence.
- Do NOT fabricate snippet numbers that do not appear below.
- First verify that a snippet directly matches the latest user's topic, entity,
  and constraints. Ignore unrelated snippets and never substitute their facts
  for the requested answer.
- If the latest user asks about a contextual scope such as "this handoff",
  "this project", "current", "our", or "the same one", treat the current
  conversation's project/object as a constraint. A snippet about another
  project/entity is evidence only that the other source exists; it does not
  prove that the other source belongs to the current handoff unless the snippet
  explicitly links them. Do not quote fields from that other source unless the
  latest user explicitly asks for that separate source's details.

# Evidence

{{evidenceSections}}
