# CAT 1.0 Controlled Capture Artifacts

These files are the source evidence for [`../overview.html`](../overview.html). They retain no isolated home or credentials. The runner resolves
its temporary root from the calling tool scope, which defaults to the gitignored `.cat/temp` directory; complete temporary
run directories, including isolated homes, remain below it on the capture host.

Captured on 2026-08-09 UTC from worktree revision `421fba359` plus the uncommitted runner, Claude SessionStart
spill-context, monetary-routing, and safe-API rule changes represented by this capture set. Every run started from the
matching fixture and exact request in this directory. Each `run.json` records its launch command, timestamps, model
argument, lifecycle phase, and result. Each `events.jsonl` is the complete harness event stream; every
`CheckoutPriceCalculator.java` is the resulting source file.

| Artifact prefix | Harness command | Model argument | CAT state |
| --- | --- | --- | --- |
| `base-codex-*` | packaged `codex-runner` JSONL bridge | `gpt-5.6-sol` | disabled with `codex app-server --stdio --disable plugins`; no `home/plugins` files |
| `cat-codex-*` | packaged `codex-runner --install-cat` JSONL bridge | `gpt-5.6-sol` | `cat@cat` enabled in `config.toml`; packaged CAT manifest and completed SessionStart hook event present |
| `base-claude-*` | packaged `claude-runner` JSONL bridge | `sonnet` | no `home/plugins` files or `--add-dir` argument |
| `cat-claude-*` | packaged `claude-runner --install-cat` JSONL bridge | `sonnet` | packaged CAT manifest present and passed through `--add-dir` |

The harness versions are Codex CLI 0.147.0 and Claude Code 2.1.220. A model argument identifies the requested
harness model; an event stream may additionally identify the service model that fulfilled the request. Each generated
fixture passed its Maven verification after the capture.

To reproduce a pair, build the distribution and run the selected packaged runner twice against copies of the fixture:

```sh
client/mvnw -q -Dmaven.build.cache.enabled=false -f client/pom.xml verify
{ printf '{"type":"turn","prompt":'; jq -Rs . < code-request.txt; printf '}\n{"type":"complete"}\n'; } |
  client/distribution/target/runtime/codex/bin/codex-runner \
  --artifact client/distribution/target/runtime/codex --project <fixture> \
  --output <events> --model gpt-5.6-sol --keep-run
{ printf '{"type":"turn","prompt":'; jq -Rs . < code-request.txt; printf '}\n{"type":"complete"}\n'; } |
  client/distribution/target/runtime/codex/bin/codex-runner \
  --artifact client/distribution/target/runtime/codex --project <fixture> \
  --output <events> --model gpt-5.6-sol --keep-run --install-cat
```

For Claude, run the `claude` artifact's `bin/claude-runner` analogously, with the `sonnet` model. Keep each successful
run with `--keep-run`, verify the Base plugin inventory is empty before accepting the capture, and copy only the safe
files in this directory into version control.
