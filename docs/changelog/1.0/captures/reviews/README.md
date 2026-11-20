# Specialist-review captures

This directory retains the controlled inputs and outputs for the “More thorough reviews” section. Each response comes
from an isolated packaged runner. Each Base/CAT comparison uses the exact same concern-specific task generated from
`review-task-template.txt`. The task does not mention CAT, delegation, a reviewer, rules, or a required solution. The
CAT wrapper names the installed medium-tier specialist and supplies its Review Contract for the delegated task.

The fixtures are intentionally unsafe and incomplete. Each language-native fixture is published below
[`../../fixtures/review-languages`](../../fixtures/review-languages), so rendered review links open stable source files,
not temporary runner artifacts. They are stable review targets, not production guidance.

The previously published matrix predates the equal-prompt protocol and is diagnostic-only. A complete matrix contains
one Base stream and one validated CAT specialist stream for every concern, harness, and TypeScript, Python, Java, C#,
C++, PHP, or Go fixture.

Each `codex-*` and `claude-*` event stream names its harness, Base or CAT mode, and concern. Both modes use the same
retained task and model profile. The CAT capture reads the Review Contract from the artifact selected by the installed
launcher and permits one correction turn when the first response fails validation. The native events record the
specialist's reply and the parent response, which must match. Claude Code's Agent call also exposes the Review
Contract text sent with the delegated task. Codex identifies the configured specialist role in the retained session
transcript and encrypts the delegated task text in that transcript. Each cell retains its CAT transcript and a
results copy under its harness and reported session ID. The results copy contains the nested session's project and
`session.json`.

## Capture a matrix

Use the tool shell of a top-level Codex or Claude Code session with the current CAT release installed for both
harnesses. Authenticate both harness commands. Start the calling session's installed `bin/request-server` by absolute
path in its own background command and wait until it says it serves that session. See the install instructions for
local development. [Contributing](../../../../contributing.md)

Set `CAT_CAPTURE_BIN` to the absolute `bin` directory of the calling session's installed plugin. Export a supported
model and effort for each harness using `CAT_CODEX_MODEL`, `CAT_CODEX_EFFORT`, `CAT_CLAUDE_MODEL`, and
`CAT_CLAUDE_EFFORT`. Create a fresh directory beneath the calling CAT session's temporary directory and set
`capture_root` to its absolute path. From the checkout root run:

```sh
reviews=docs/changelog/1.0/captures/reviews
"${reviews}/capture-language-matrix.sh" "${capture_root}"
```

The parent waits for every capture. It allows up to 20 simultaneous captures by default. Select a different positive
limit with the second argument or `CAPTURE_PARALLELISM`. A failed cell makes the command exit nonzero and leaves its
available evidence in the workspace for diagnosis.

## Publish and render

Set `destination` to the directory for the published language captures. The publisher checks the full matrix for
completed sessions and matching tasks and profiles before copying any files. It writes each manifest as
`<harness>-<mode>-<concern>.session.json`.

```sh
node "${reviews}/publish-language-captures.mjs" "${capture_root}" "${destination}"
```

With the published files under this directory's `languages/` folder, regenerate `rendered-captures.js` with
`node "${reviews}/generate-rendered-captures.mjs"`. The generator preserves terminal response text and rejects CAT
findings without their fields or Markdown file links. Open the static HTML directly to see the comparisons. Remove
the temporary capture workspace after publishing or diagnosing the results.
