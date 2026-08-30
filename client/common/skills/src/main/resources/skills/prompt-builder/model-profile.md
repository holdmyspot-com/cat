# Model Profile Selection

## Design Goals

- Change a prompt file's model or reasoning profile only through a documented control of its target harness and with
  evidence that isolates the profile from prompt and test changes.

This file governs a proposed profile change. Read [model-effort-policy.md](model-effort-policy.md) when resolving the
profile that a formal evaluation must use for the delivered route.

Inspect the target harness's current documented skill metadata before proposing a model or effort change. If no
supported control exists, retain the harness default and report that the profile cannot be selected by this prompt file.
When a
control exists, keep the task, rendered prompt, dependencies, test, and validation fixed; change one profile field,
record the two configurations, and treat the result as diagnostic until a fresh full test set confirms it.
