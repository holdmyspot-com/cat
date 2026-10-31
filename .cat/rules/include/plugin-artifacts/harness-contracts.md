# Harness Contracts

## Design Goals

- Keep each plugin-facing skill description, hook response, launcher result, and command output valid for the
	documented metadata, schema, permitted values, and event semantics of its target harness.

## Guidance

When changing a plugin-facing skill description or a hook, launcher, or command's harness-visible result, identify the
target harness and its applicable documented contract. Validate the description's required metadata and routing form;
validate a response or result's required schema, permitted values, and event-specific meaning. Do not reuse a contract
from another harness or infer one from a successful local invocation.

For a harness-specific hook, launcher, or command, use only exit codes whose meaning is assigned by that harness's
reference documentation. Do not invent a conventional status such as `1` or reuse a status documented for a different
event. When the reference defines a structured response for the required outcome, return that response instead. If the
reference defines no applicable status or response, report the limitation rather than assigning an exit-code meaning.
