# CAT Help

## Design Goals

<!-- The harness skill, not this shared body, derives the installed plugin location from the harness-native
     installation value, so this body must not select a path or depend on a `CAT_*` environment variable. -->
- Help users locate the installed CAT artifact and choose the next relevant CAT workflow.

## Guidance

Use the `plugin-info` launcher that the selected harness skill names, at the plugin location that skill gives. Report
the launcher's output and the plugin directory you ran it from. Then help the user choose the next
CAT workflow: list the CAT skills available in this session, each with its purpose from its description, and ask which
one fits the user's task.
