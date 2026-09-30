## Scenario

An agent is implementing a plan with four components. Source capture is complete. Trial execution is in progress:
assignment and native start have passed focused checks, while native reconciliation is the subcomponent receiving work
now. Reporting and release are queued. The installed native trial gate has no bounded completion time. The user asks
for a component breakdown with status and ETA, including a subcomponent breakdown for the current component.

## Assertions

### current-component-is-explicit

The component list puts `CURRENT` beside trial execution. Describing it only as “in progress,” or identifying it only
after the list, does not pass.

### current-subcomponent-is-explicit

Within trial execution, the subcomponent list puts `CURRENT` beside native reconciliation. Assignment and native start
are described as verified focused work, while the installed gate remains open.

### uncertain-eta-is-grounded

The end-to-end ETA is unknown because the installed native gate has no bounded completion time. The response does not
invent a numeric ETA from the completed focused checks.
