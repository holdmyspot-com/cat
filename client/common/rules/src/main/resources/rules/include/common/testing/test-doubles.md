# Test Doubles

## Design Goals

- A test that uses a test double fails when the production behavior it claims to verify is wrong, because each
  assertion's expected outcome comes from a decision or transformation of the production code rather than from data or
  effects that the double or the test scripted.
- A double behaves as recorded or documented evidence shows the real dependency behaves, and a test that uses it is not
  presented as evidence of how that real dependency behaves.

## Guidance

A test double, such as a fake, stub, mock, in-memory collaborator, or scripted fixture process, controls what the code
under test receives. Use it to supply that input, then assert what the production code decides or produces from it.

Before accepting an assertion, trace its expected value, and the event that brings about the asserted outcome, to their
source. When the expected value is data that the double supplied and the production code only passes along, or the
outcome happens because the test told the double to cause it, the assertion verifies the double or the test's own
script. Check by substitution: if replacing the production operation under test with a pass-through, a no-op, or a
fixed default would still pass, the test cannot detect the failure it claims to prevent. Assert a production decision
instead, such as a classification, transformation, ordering, selection, recorded state, or reported failure, or remove
the test when another test already makes that assertion.

Passing a value along unchanged is a valid claim only when it is the claimed behavior. Then name that claim and use an
input that a faulty implementation would alter, drop, or reorder. Likewise, assert what the production code sent to a
double only when the claim concerns that request; reading back the double's scripted response proves nothing about
production.

A double's scripted behavior is an assumption about the real dependency. Base it on the real dependency's recorded
output or documented behavior, name that source in the double's documentation, and script only the reactions the source
shows. When a claim depends on how the real dependency reacts, the double cannot establish it: verify that reaction
against the real dependency in an integration or contract test, and limit the double-based test's claim to the
production behavior that follows from the reaction.

A test whose claim is the double's own behavior is a test of the test-support code. Name it as such and do not count it
as coverage of production behavior.

For example, a test scripted a fake harness process to print a `stopped` event after an interrupt, told the fake to exit,
and asserted that the turn's output contained `stopped`. The production code only relayed that line, and the turn ended
because the test ended the fake, so any implementation that relays output would pass. Whether the real harness exits
after an interrupt was an unverified assumption in the fake.
