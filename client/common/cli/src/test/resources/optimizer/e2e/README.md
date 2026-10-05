# End-to-end optimizer fixtures

Each directory is a small prompt system that the end-to-end acceptance runs copy into a fresh Git
project and optimize through the installed `prompt-optimizer` skill. The fixtures are data for those runs; no Java test
reads them. Each entry describes the acceptance scenarios that use it.

- `deficient/`: `AGENTS.md` asks every reply to close with the handbook motto but also forbids slogans and sign-offs.
  Before optimization, Codex closed 0 of 6 replies with the motto and Claude 4 of 6; resolving the contradiction raised
  Codex to 6 of 6. The delivery scenario expects the proposal to list the conflict with its resolutions, confirmation
  to be refused until the user chooses one, and the campaign then to raise compliance with the resolved contract,
  certify the candidate and deliver a Git diff.
- `easy/`: one rule that a model already follows; used for interruption and resumption, host tools and child subagents,
  and shared capacity across concurrent CAT processes, where a run needs cheap, passing trials.
- `link/`: `docs/handbook.md` is an in-project symbolic link to `shared/handbook.md`. The run copies `external/` outside
  the project and links `docs/reply-rules.md` to it, so the deficient rule lives in a read-only external file. The
  scenario checks the link report, that the edit the rule needs raises an authority warning that offers no way to edit
  the external file, and that delivery leaves every external file unchanged.
- `unsupported/`: one prompt system per unsupported case, each expected to end in its
  `certification unavailable` outcome or refusal: `remote-instruction` (a remote response supplies governing
  instructions), `image-output` (a material requirement produces an image), `response-contingent` (an obligation needs
  a follow-up turn chosen from the reply) and `persistent-write` (a required write outside the trial copy).
