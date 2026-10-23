# CAT Help

## Design Goals

- Help users locate the installed CAT artifact and choose the next relevant CAT workflow.

## Guidance

Use the selected harness skill's `plugin-info` launcher. The harness wrapper derives its installed plugin location from
the harness-native installation value; this common guidance must not select a path or depend on a `CAT_*` environment
variable. Report the launcher's output and the plugin directory you ran it from. Then help the user choose the next
CAT workflow: list the CAT skills available in this session, each with its purpose from its description, and ask which
one fits the user's task.
