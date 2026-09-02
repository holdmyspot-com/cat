## Scenario

Write product documentation for a local analysis command. Its supported privacy contract guarantees that project file
contents remain on the user's machine. The command sends only a license identifier and aggregate execution count to the
license service. State the information a security reviewer needs to approve use of the command.

## Assertions

### privacy-guarantee

The response states that project file contents remain local and identifies the information sent to the license service.

### actionable-boundary

The response treats the data-transfer boundary as a supported security guarantee rather than omitting it as an internal
implementation detail.
