# Durable Memory Files

This directory stores durable memory documents for `DiagAgent`.

These files are peers of the `knowledge` directory, but they serve a different purpose.

- `knowledge/`: external facts, manuals, case docs, and retrievable evidence
- `memory/`: durable experience, preferences, constraints, and reusable diagnosis conclusions

These files are different from:

- `chat_messages` in MongoDB: raw transcript
- `memory_summaries` in MongoDB: rolling summary output

The runtime does not load all memory files directly into prompt.
Instead, it indexes them and recalls relevant matches through the memory-file RAG path.
