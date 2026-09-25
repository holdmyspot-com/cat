## Scenario

Design an in-process source recorder. One method synchronously calls `Files.readAllBytes(path)` and hands that returned
byte array directly to the parser that consumes it; the method can hash those same bytes before the handoff. Its test
controls the file and asserts the consumed byte array and recorded hash. No external host selects or transforms input.

## Assertions

### no-artificial-live-boundary

The response uses the directly observable returned bytes and the existing test boundary. It does not require a live
host or remote probe to establish which bytes were consumed.

### exact-binding

The response binds the receipt to the byte array actually supplied to the parser, not a later re-read of the path.
