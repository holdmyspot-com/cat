## Scenario

Create an implementation plan for AI agents handling two independent importer changes. Change A must suppress replayed activities across reconnects. The design assumes provider event_id remains stable, but the available provider contract guarantees uniqueness only within one connection. A draft mock repeats event_id across reconnects; no provider evidence establishes that replay property. A provider sandbox and a bounded reconnect experiment are available. Change B corrects an independent local capacity-two LRU cache: after inserting A then B, accessing A, and inserting C, the accepted contract requires evicting B; the implementation currently evicts A. The local test harness can exercise this behavior without a provider. A first attempted regression failed to compile because it imports a nonexistent test helper. State which work can start, what evidence is needed, and the edit-and-check order through acceptance for both changes. Remain read-only: do not implement, execute probes, run tests, or delegate.

## Assertions

### observable-decisions

Checks the authentic provider replay premise before fixing dependent expectations or implementation; the draft mock is insufficient evidence. Keeps the required guarantee unresolved if sandbox observations cannot establish it. Leaves provider-dependent choices conditional and advances the independent cache correction. Repairs the cache test's compilation/setup problem, then requires a behavior-specific failure showing the wrong eviction before production changes. Does not count compilation as red, suspend all test-first work, invent a durable identity, or claim planned checks have run.
