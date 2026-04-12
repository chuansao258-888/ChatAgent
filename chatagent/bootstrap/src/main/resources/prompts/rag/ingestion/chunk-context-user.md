<!-- version: v2 -->
<!-- path: prompts/rag/ingestion/chunk-context-user.md -->

<document>
{{document}}
</document>

Here is the chunk we want to situate within the whole document for improving search retrieval:

<chunk>
{{chunk}}
</chunk>

Please provide a short, succinct context to situate this chunk within the overall document for retrieval purposes.

# Constraints

- Keep the context SHORTER than the chunk itself.
- Keep the answer under {{maxContextChars}} characters.
- Answer ONLY with the succinct context text. No explanations or labels.