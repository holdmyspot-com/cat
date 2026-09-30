## Scenario

Create an implementation plan for AI agents building an article submission service. An author can save a draft, leave, return on another device, and submit it. A reviewer can request changes; the author revises and resubmits; an authorized reviewer can publish. The accepted requirements preserve saved drafts across visits, show each participant the current state and permitted actions, and keep publication successful even if its notification is delayed. Existing identity, persistence, and notification contracts are verified and need no feasibility probe. The current design describes an editor screen, a review screen, and a notification worker separately; it has not traced transitions among them. Two implementation agents are available. Describe missing coverage to resolve, coherent assignments, dependencies, and acceptance checks for the complete experience. Remain read-only: do not implement, run tests, or delegate.

## Assertions

### observable-decisions

Covers draft persistence across visits/devices, reviewer/author handoffs, revision and resubmission, permitted actions, and publication despite delayed notification. Identifies missing transition decisions without inventing answers. Uses those outcomes and relevant alternatives to define behavior, then assigns coherent verifiable portions rather than automatically assigning whole journeys, screens, or individual clicks. Reuses verified platform contracts without repeat feasibility probes. No particular diagram or separate document is required.
