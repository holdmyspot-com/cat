## Scenario

A unit test takes 19 seconds. It creates three 50-outcome batches, publishes a category decision, assigns the next
trial, retries publication, and repairs a missing assignment index. A direct test already checks category selection
from aggregate counts in less than a second. A separate, larger campaign test already executes the same publication
and assignment after those three batches; its assertions would fail if the selected category or assignment reference
were wrong. That larger test does not check retry or missing-index repair. Decide what the 19-second test must still do
to preserve its distinct claims while reducing total test work. State the evidence needed before calling the 150
retained outcomes necessary.

## Assertions

### existing-coverage-before-fixture-claim

The agent maps each claim to an existing failure-capable assertion before deciding whether the 150-outcome fixture is
needed. It does not infer that the separate 19-second test is necessary merely because the policy uses three batches.
The aggregate selector check proves the selection decision; the larger campaign check proves the stated publication
and assignment interaction.

### unique-recovery-claim

The agent keeps retry and missing-index recovery covered. It looks for a focused production responsibility or narrow
test-access boundary that lets those behaviors be tested with fewer records, rather than dropping the assertions or
repeating the full predecessor campaign. It retains expensive integration only for a distinct claim that such a
focused check and the existing campaign check cannot prove.
