<!-- version: v2 -->
<!-- path: prompts/rag/ingestion/chunk-context-user.md -->

<chunk_context>
{{chunkContext}}
</chunk_context>

Here is the chunk we want to contextualize for improving search retrieval:

<chunk>
{{chunk}}
</chunk>

Please provide a short, succinct context based on the structural metadata and chunk content.

# Constraints

- Keep the context SHORTER than the chunk itself.
- Keep the answer under {{maxContextChars}} characters.
- Answer ONLY with the succinct context text. No explanations or labels.
