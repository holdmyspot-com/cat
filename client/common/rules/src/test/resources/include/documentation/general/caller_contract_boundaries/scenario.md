# Caller contract boundaries

## Variables

- **storage**: The internal storage mechanism that the implementation currently uses, with its article, such as
  `a SQLite cache` or `an in-memory buffer`

## Scenario

Handle each of the following independent requests. Answer them in order and label each answer with its request number.

**Request 1.** Draft a product license summary and pricing introduction that state that personal use is free and commercial use
requires a paid license. The installer currently contains readable Markdown instructions and compiled Java components.
The build and publication process may change. Include the information a customer needs to understand the offer.

**Request 2.** Write API documentation for `exportReport(Path destination)`. It replaces an existing file at `destination`, returns
the resulting path, and throws `IOException` if the replacement cannot be completed. The current implementation uses
{{storage}} before writing the report, but it may use another storage mechanism later. Document what a caller needs to
use the operation correctly.

**Request 3.** Write product documentation for a local analysis command. Its supported privacy contract guarantees that project file
contents remain on the user's machine. The command sends only a license identifier and aggregate execution count to the
license service. State the information a security reviewer needs to approve use of the command.

## Assertions

### licensing_boundary

- **kind**: guard
- **description**: For request 1: The response states the personal-use and commercial-use licensing boundary clearly.

### unspecified_packaging

- **kind**: effect
- **description**: For request 1: The response does not discuss whether installed files are readable, whether source or
  build tooling is published, or whether those implementation choices are promised, guaranteed, or subject to change.

### caller_contract

- **kind**: guard
- **description**: For request 2: The response documents the destination replacement, returned path, and observable
  failure.

### replaceable_storage

- **kind**: effect
- **description**: For request 2: The response does not mention {{storage}}, another possible storage mechanism, or
  disclaim whether the storage mechanism is guaranteed.

### privacy_guarantee

- **kind**: guard
- **description**: For request 3: The response states that project file contents remain local and identifies the
  information sent to the license service.

### actionable_boundary

- **kind**: effect
- **description**: For request 3: The response treats the data-transfer boundary as a supported security guarantee
  rather than omitting it as an internal implementation detail.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the executor selects `rules/include/documentation/general.md`
  before its first governed action.
