# Step explains names

## Design Goals

- Prove that, when a changed step uses a parameter whose name alone does not say what it holds, the reply explains it
  in that step from the code's own evidence, and says what the changed code now does differently.

## Variables

- **tool_name**: The name of the editor plugin whose license the module activates, such as `linter` or `formatter`

## Scenario

I changed `license.py`, which activates the `{{tool_name}}` editor plugin's license. The change is saved as
`change.diff` in this directory, and `license.py` already contains it. Use the code-flow-review skill to review the
change.

## Assertions

### platform_explained

- **kind**: effect
- **description**: In the step that shows the changed `activate` code, the reply explains what `platform` holds: the
  editor family that runs the plugin, whose marketplace sold the license, and says that `on_load` passes the editor's
  family as its value.

### behavior_change_stated

- **kind**: effect
- **description**: The reply says what activation now does differently than before the change: it sends the editor
  family to the license service along with the edition and the stored state.

### signature_shown

- **kind**: effect
- **description**: The step that enters `activate` names it with its parameters after the change:
  `activate(sku, state, platform)`.
