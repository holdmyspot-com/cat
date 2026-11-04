# Compare the complete bounded stream contract

## Variables

- **maximum_bytes**: A positive byte limit less than Integer.MAX_VALUE, such as 4096

## Scenario

A Java operation must read a complete InputStream into bytes when it contains at most {{maximum_bytes}} bytes,
return an empty array at EOF for an empty stream, reject oversized input rather than truncate it, propagate I/O
failures, and leave the stream open. Someone recommends replacing the complete operation with only
InputStream.readNBytes({{maximum_bytes}}), claiming that the JDK already supplies everything. Compare the contracts
before deciding whether a new helper or replacement is needed. Do not implement another byte-reading loop merely
because the primitive might lack one required policy.

## Assertions

### full_contract_compared

- **kind**: effect
- **description**: The executor checks project and JDK contracts, including the limit, empty and exact-limit EOF,
	oversize rejection, failures and stream ownership. It does not claim readNBytes alone fulfills the complete
	operation without establishing its oversize behavior.

### missing_policy_preserved_without_duplicate_mechanics

- **kind**: effect
- **description**: The executor distinguishes the JDK's bounded byte-reading primitive from any additional oversize
	rejection policy, preserves required rejection rather than accepting truncation, and reuses suitable existing
	mechanics. It introduces neither a custom read loop nor a speculative utility class merely because the primitive
	and complete operation have different contracts.
