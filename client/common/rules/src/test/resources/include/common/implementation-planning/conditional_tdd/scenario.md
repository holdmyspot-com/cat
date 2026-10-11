# Conditional tdd

## Variables

- **first_key**: The first key inserted into the cache, a single capital letter
- **second_key**: The second key inserted, a single capital letter different from the first
- **third_key**: The key inserted last, a single capital letter different from the other two

## Scenario

Create an implementation plan for AI agents handling two independent importer changes. Change A must suppress replayed activities across reconnects. The design assumes provider event_id remains stable, but the available provider contract guarantees uniqueness only within one connection. A draft mock repeats event_id across reconnects; no provider evidence establishes that replay property. A provider sandbox and a bounded reconnect experiment are available. Change B corrects an independent local capacity-two LRU cache: after inserting {{first_key}} then {{second_key}}, accessing {{first_key}}, and inserting {{third_key}}, the accepted contract requires evicting {{second_key}}; the implementation currently evicts {{first_key}}. The local test harness can exercise this behavior without a provider. A first attempted regression failed to compile because it imports a nonexistent test helper. State which work can start, what evidence is needed, and the edit-and-check order through acceptance for both changes. Remain read-only: do not implement, execute probes, run tests, or delegate.

## Assertions

### observable_decisions

- **kind**: effect
- **description**: Checks the authentic provider replay premise before fixing dependent expectations or implementation;
  the draft mock is insufficient evidence. Keeps the required guarantee unresolved if sandbox observations cannot
  establish it. Leaves provider-dependent choices conditional and advances the independent cache correction. Repairs the
  cache test's compilation/setup problem, then requires a behavior-specific failure showing the wrong eviction before
  production changes. Does not count compilation as red, suspend all test-first work, invent a durable identity, or
  claim planned checks have run.

### planning_guidance_before_decisions

- **kind**: effect
- **description**: The retained events establish a successful read of `rules/include/common/implementation-planning.md`
  before the first project inspection or decision about behavior coverage, assignments, dependencies, or checks. A
  failed lookup, substituted skill, or later read does not satisfy that boundary. Assess the plan's decisions separately
  from guidance selection.

### test_first_and_project_verification

- **kind**: effect
- **description**: Before fixing production-edit order, the executor successfully reads `rules/include/common/tdd.md`
  and schedules complete-project verification after focused and integration checks and before implementation handoff. It
  does not claim planned checks ran, count a compilation failure as behavioral red evidence, or invent a project
  command. Guidance selection and the scheduled acceptance checks are assessed separately.

### unresolved_provider_guidance

- **kind**: effect
- **description**: The executor successfully reads `rules/include/common/monotonic-progress.md` while the provider
  replay premise remains unresolved.

### evidence_and_scope

- **kind**: effect
- **description**: Reject unsupported numerical durations, universal speed claims, or substituted scope. Accept
  alternative decompositions whose stated dependencies and checks satisfy the request; do not grade by literal phrases.
  Keep guidance selection or delivery unverified when the retained events do not establish it.
