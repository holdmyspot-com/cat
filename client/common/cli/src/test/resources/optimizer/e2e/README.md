# End-to-end optimizer fixtures

Each directory is a small prompt system that the end-to-end runs of work item WI-15 in `plan.md` copy into a fresh Git
project and optimize through the installed `prompt-optimizer` skill. The fixtures are data for those runs; no Java test
reads them. Each entry names the end-to-end scenarios of `plan.md` (E1 to E8) that use it.

- `deficient/`: `AGENTS.md` asks every reply to close with the handbook motto but also forbids slogans and sign-offs.
  Before optimization, Codex closed 0 of 6 replies with the motto and Claude 4 of 6; resolving the contradiction raised
  Codex to 6 of 6. E1 expects the campaign to find that correction and certify it.
- `easy/`: one rule that a model already follows; used where a run needs cheap, passing trials (E2, E5, E8).
- `link/`: `docs/handbook.md` is an in-project symbolic link to `shared/handbook.md`. The run copies `external/` outside
  the project and links `docs/reply-rules.md` to it, so the deficient rule lives in an external target that the
  campaign may edit only after the user authorizes it (E7).
- `unsupported/`: one prompt system per case in `design.md` §6.5, each expected to end in its
  `certification unavailable` outcome or refusal (E4): `remote-instruction` (a remote response supplies governing
  instructions), `image-output` (a material requirement produces an image), `response-contingent` (an obligation needs
  a follow-up turn chosen from the reply) and `persistent-write` (a required write outside the trial copy).
