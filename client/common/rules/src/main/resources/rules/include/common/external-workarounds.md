# External Bug Workarounds

## Design Goals

- Use an external workaround only when authoritative evidence shows it is necessary, and keep it narrowly scoped,
  traceable, and removable when its upstream cause is fixed.

## Guidance

Before classifying behavior as an upstream defect or proposing a workaround, compare it with any known-good
implementation available in this project. Compare the working and failing artifacts, configuration, inputs, and
observable lifecycle behavior. Attribute the failure externally only after that comparison rules out a relevant project
difference.

Use a workaround only when the upstream defect and its impact are known. Mark the smallest affected code region with
`WORKAROUND: <upstream issue URL>` and explain the temporary behavior when the code alone is not clear. Remove the
workaround when the upstream fix is adopted.
