## Scenario

An agent reports a deployment with three components. API rollout and data migration are running concurrently. API
rollout is checking traffic routing; data migration is checking a resumed batch. Documentation is queued. The user asks
for a component and subcomponent status breakdown. No reliable ETA evidence is available.

## Assertions

### both-active-components-are-marked

The component list puts `CURRENT` beside both API rollout and data migration. It does not choose only one because the
work is concurrent, and “in progress” alone does not pass.

### each-active-subcomponent-is-marked

The nested breakdown puts `CURRENT` beside traffic routing within API rollout and resumed batch checking within data
migration. Documentation is shown as queued, not current.
