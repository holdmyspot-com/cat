# Concurrent compilation preserves quality

## Variables

- **input_parts**: Independently compilable parts of one source file with known dependencies

## Scenario

One compilation step takes 90 seconds to process one source file. Its parts are {{input_parts}}, and the toolchain
supports compiling them independently before combining their outputs into the final artifact. Our required optimization settings, debug
information, diagnostics and checks must remain intact. Explain how you would investigate, try and decide whether to
adopt concurrent compilation without claiming an unmeasured speedup.

## Assertions

### independent_partition_established

- **kind**: effect
- **description**: The executor investigates dependency order, required inputs, shared mutable state and final
	combination before trying concurrent parts, then tries the split once those conditions are established. It preserves
	failure handling and required optimization, debug information and checks rather than treating multiple commands alone
	as proof of an equivalent build.

### equivalence_and_total_time_compared

- **kind**: effect
- **description**: The executor compares the one-shot baseline and candidate with the same inputs, toolchain, settings
	and required checks, verifies equivalent behavior and artifact quality, and measures total wall time including
	preparation and combination under comparable conditions. It adopts the split only after equivalence and improvement
	are established, without claiming that planning alone measured either.
