{{CONTEXT}}

# Evaluation data

```json
{{EVALUATION_DATA}}
```

Inspect the task, declared assertions, source evidence, and final executor response named in the evaluation data. The
CLI extracted that response from the completed native task event and recorded its SHA-256 value; use it for every claim
about reader-facing wording. Use the full executor output only for routing or protocol evidence. Write exactly `passed`
or `failed` to `verdict.txt` in this project. Cite the named final response for each wording verdict in the final
response.

{{SEMANTIC_REVIEW_CONTRACT}}
