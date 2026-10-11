# Commit Messages

## Design Goals

- Make every created or revised Git commit message identify its observable change without requiring readers to inspect
  the diff.
- Keep message wording and type classification consistent across CAT Git workflows without prescribing an unnecessary
  grammatical style.

## Guidance

When creating or revising a Git commit message, use the format `type: summary`. Choose exactly one lower-case type:

- `feature`: introduces an observable capability.
- `bugfix`: corrects incorrect observable behavior.
- `test`: changes tests only.
- `refactor`: changes internal structure without changing observable behavior.
- `performance`: improves measurable performance.
- `docs`: changes reader-facing documentation only.
- `style`: changes formatting only.
- `config`: changes configuration, tooling, dependencies, or agent-facing documentation.
- `planning`: changes planning-system artifacts only.

Do not use abbreviated or unsupported types such as `feat`, `fix`, `chore`, `build`, `ci`, or `perf`.

Write a concise summary that stands alone and names the high-level feature, behavior change, bug fix, or material
correction. Use the grammatical form that states the change most clearly; do not require imperative wording when a
declarative outcome is clearer.

Add a body only when it is needed to state the reason for the change, or to explain material context, limitations, or
consequences, that the subject cannot state concisely. Do not use filenames, paths, or implementation steps as the explanation of what the commit delivers.
