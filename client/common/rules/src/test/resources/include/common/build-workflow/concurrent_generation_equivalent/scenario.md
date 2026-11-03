# Concurrent artifact generation preserves the contract

## Variables

- **generated_artifacts**: Independently generated build artifacts combined into one final result, such as transformed
	static resources combined with one resource manifest

## Scenario

A single generation step takes 90 seconds to process one input file. It produces {{generated_artifacts}}. The generator contract allows independent parts
and final combination with the same required contents, metadata and validation. Completion timestamps may differ and
are explicitly not part of artifact identity. Explain the prerequisites for trying a concurrent split and the evidence
needed to keep it.

## Assertions

### complete_result_without_byte_identity

- **kind**: effect
- **description**: The executor establishes safe independent work and a final combination preserving all required
	contents, metadata, ordering constraints, validation and failure handling. It compares contract-relevant behavior and
	quality without demanding identical permitted timestamps or relaxing required contents.

### complete_cost_measured

- **kind**: effect
- **description**: The executor compares a controlled one-shot baseline with the split including preparation and
	combination, and keeps the split only after equivalent quality and improved total wall time are demonstrated.
