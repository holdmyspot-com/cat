# Rules Own Tool Requirements

## Design Goals

- Ensure project-owned CLI tools, validators, generators, and APIs implement the rule-governed workflow contract,
  validation boundary, and failure behavior; treat an advisory or unsupported rule requirement as a rule-owned tool
  defect to repair rather than a reason to weaken the rule.

## Guidance

When guidance relies on a project-owned CLI, API, validator, generator, or other callable tool to create, preserve,
verify, or reject a required workflow state, audit and update that tool until it implements the related Design Goal. For
every tool-dependent obligation, record the goal and rule requirement, the selected command and required inputs, the
authoritative tool contract or implementation inspected, the validation boundary that reads the evidence, the success
result, the failure diagnostic, and the test or smallest negative input that proves omission is rejected. Inspect the
tool's actual contract; do not infer enforcement from a command name, a successful invocation, or a final artifact.
When a tool publishes, renders, forwards, or selects an agent or child-process response, validate the exact raw response
selected for publication at that selection boundary. Persist an acceptance record that identifies the selected response
and the checks it passed, so an interrupted supervisor, relay, or later renderer cannot substitute an unvalidated
response.

Classify each obligation as enforced, advisory, or unsupported. An enforced obligation has a reachable tool validation
boundary that rejects the missing or invalid evidence. An advisory obligation relies on reviewer judgment after the tool
runs. An unsupported obligation has no available mechanism. For advisory or unsupported obligations, either add and
verify the required tool validation and negative test, or report the external dependency that prevents a project-owned
tool from enforcing it. Do not narrow, reinterpret, or remove a rule requirement because the current project-owned tool
lacks support. Re-run this conformance audit whenever the rule, tool contract, command sequence, or validation boundary
changes.
