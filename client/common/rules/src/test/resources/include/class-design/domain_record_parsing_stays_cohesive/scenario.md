# Cohesive domain parsing keeps its invariant

## Variables

- **protocol_kind**: A domain protocol, such as a length-prefixed telemetry protocol

## Scenario

A reader for {{protocol_kind}} reads a framed record, validates its version and checksum, and constructs the domain
value. Those steps jointly ensure that callers receive only valid records. A small private implementation step
delegates raw byte reads to an existing platform operation; it exposes no independent generic contract. A reviewer
proposes extracting every general-purpose statement into a utility class and moving the entire reader there because it
takes a stream as input, then replacing validation with the platform byte-reading method. Evaluate that proposal.

## Assertions

### domain_invariant_kept

- **kind**: effect
- **description**: The executor keeps the cohesive record interpretation and validation with the protocol owner,
	rather than classifying the operation as generic solely from its stream parameter or extracting each step into a
	new component.

### private_details_need_no_extra_owner

- **kind**: effect
- **description**: The executor retains ordinary private implementation steps inside the cohesive domain operation
	when they establish no independent responsibility. It does not require a utility class for each generic statement
	or rename the domain owner into a vague catch-all to accommodate unrelated contracts.

### partial_capability_not_full_replacement

- **kind**: effect
- **description**: The executor recognizes that a raw byte API does not implement framing, version validation,
	checksum validation or domain construction. It preserves those guarantees while continuing to reuse the byte-read
	primitive, without claiming that the primitive replaces the complete operation.
