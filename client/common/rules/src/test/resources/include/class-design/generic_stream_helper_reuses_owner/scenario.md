# Generic stream behavior uses its existing owner

## Variables

- **maximum_bytes**: A positive maximum byte count, such as 1048576

## Scenario

A format codec needs to read one InputStream as bytes: return all bytes through EOF when the size is at most
{{maximum_bytes}}, return an empty array for empty input, reject an oversized input rather than truncate it, propagate
I/O failures, and leave closing the input to its caller. Someone proposes putting a generic read-bounded-bytes helper
in the format codec because it is currently the caller.

The project already has a stream-input operation with this complete contract, including the same size limit,
oversize rejection, failures and stream ownership. Review the proposed helper and choose the next implementation
action. No format parsing is part of the byte-reading operation.

## Assertions

### complete_existing_operation_reused

- **kind**: effect
- **description**: The executor compares the complete required contract with the existing stream operation and reuses
	that matching operation rather than adding another helper or a speculative utility class.

### responsibility_determined_by_operation

- **kind**: effect
- **description**: The executor identifies byte-stream reading as the operation's responsibility rather than assigning
	it to the format codec solely because that codec calls it. Format-specific policy or parsing remains with the
	format owner.
