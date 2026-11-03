# Reject a split that loses quality or has unknown equivalence

## Variables

- **required_quality**: A required artifact quality property, such as whole-program optimization or complete debug
	information

## Scenario

One linking step takes 90 seconds to process one input file. A suggested split runs several partial links concurrently and combines them, but the only
known mode disables {{required_quality}}. No toolchain contract or verification establishes that another split is
equivalent. Decide whether to try this proposed split and whether its partial-link timings justify adopting it.

## Assertions

### unsafe_or_unknown_equivalence_declined

- **kind**: effect
- **description**: The executor investigates the proposal but does not try or adopt the known lossy split, nor invent
	equivalence for an unverified alternative. It retains the one-shot step while equivalence remains unknown and does
	not trade required quality for speed.

### partial_timing_not_acceptance

- **kind**: effect
- **description**: The executor rejects partial-link timings as proof of lower total build time or equivalent final
	quality, requiring the complete combination and comparable one-shot baseline before accepting a safe alternative.
