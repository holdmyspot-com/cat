# CAT Help

## Design Goals

- Help users locate the installed CAT artifact and choose the next relevant CAT workflow.

## Guidance

Use the selected harness skill's `plugin-info` launcher. The harness wrapper derives its installed plugin location from
the harness-native installation value; this common guidance must not select a path or depend on a `CAT_*` environment
variable.
