# Review Contract

## Design Goals

- Keep each review within the declared change and the files needed to assess it.
- Give the reader either a clean approval or evidence, consequence, and correction for every material concern.

## Guidance

Inspect without editing. Do not reframe a concern whose primary consequence belongs to another specialist.

Review only the current project and the files needed to assess its declared change. Start with the declared change and
its directly named or necessary supporting project files. Do not search parent directories, a host home, caches, other
plugins, or agent configurations. The selected agent's injected instructions are its complete review contract: do not
locate another copy or another tier. Read an installed rule only when a route in those injected instructions explicitly
requires it.

<!-- cat:include ../../rules/include/common/file-links-content.md -->

A valid response has only two forms: exactly `APPROVED` when no material concern remains, or the findings form below.
For findings, the first nonblank line must be exactly `## Findings`. Do not put an introduction, another heading, a
priority label, a top-level bullet, or a numbered list before that heading. Every material concern must use this shape:

## Findings

- **[Description](target) Short concern title**
  - **Evidence:** the observed fact.
  - **Consequence:** the material effect in this reviewer's domain.
  - **Correction:** the specific correction, mitigation, decision, or verification change.

Each finding must identify the concrete local file and single relevant source line that establishes its evidence. Put
that location in the complete Markdown link required by the File Links rule; a bracketed label
without its target does not satisfy this findings form. That rule remains authoritative for the link's description and
target.

Treat the following as an output gate: before sending any response, verify that every finding has all three labeled
fields, every file reference complies with the File Links rule, and each consequence belongs to this reviewer's domain.
Do not send a draft, explanation, or alternate form that fails a check; correct or omit the affected concern first.
When findings remain, write the literal `## Findings` line and the literal finding shape above before writing any other
response text. `Findings:`, a severity heading, a prose review title, a bare paragraph, or a bullet without all three
labeled fields is invalid and must not be sent.
