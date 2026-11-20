# CAT 1.0 Controlled Capture Artifacts

These files are the source evidence for [`../overview.html`](../overview.html). They contain requests, generated files,
manifests and native events. The capture host keeps the isolated homes in its private session directories.

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

To reproduce a pair, install the selected CAT build using the [developer installation instructions](../../../contributing.md).
Open a fresh Codex or Claude Code session with that installation and start its CAT request server. Use the absolute
path to the installed runner for the harness being captured. Set `model` and `effort` to a supported profile of
that harness, and use the same profile for both captures. Use an absolute fixture path and two fresh results
directories in the session's CAT temporary directory.

```sh
jq -cn --rawfile prompt code-request.txt '{type:"turn", $prompt}, {type:"complete"}' > "$records"
"$runner" --project "$fixture" --input "$records" --output "$base_transcript" \
  --model "$model" --effort "$effort" --results "$base_results"
"$runner" --project "$fixture" --input "$records" --output "$cat_transcript" \
  --model "$model" --effort "$effort" --results "$cat_results" --install-cat
```

Pass absolute paths in `records`, `base_transcript` and `cat_transcript`. Use the `session_id` record in each
transcript to locate its project and `session.json` under `<results>/<harness>/<session ID>`. Extract native events with
`jq -r 'select(.type == "harness_event") | .event'` from its transcript. Verify the Base plugin inventory is empty
before accepting the capture. Retain the requests, manifests, native events and selected generated files as capture
evidence.

The [source pair script](languages/capture-source-pairs.sh) takes an output directory followed by optional harness,
language, example and parallelism arguments. Set `CAT_CAPTURE_BIN` to the absolute `bin` directory of the plugin
installed in the calling harness and `CAT_CAPTURE_TEMP` to a prepared directory under the current session's CAT
temporary root. Set `CAT_CODEX_MODEL` and `CAT_CODEX_EFFORT` for Codex, or `CAT_CLAUDE_MODEL` and `CAT_CLAUDE_EFFORT`
for Claude Code. Set both profiles when capturing both harnesses. Newly published captures carry a `.session.json`
manifest. The historical captures in this directory retain their original metadata.
