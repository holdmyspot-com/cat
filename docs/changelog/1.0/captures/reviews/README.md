# Specialist-review captures

This directory retains the controlled inputs and outputs for the “More thorough reviews” section. Each response comes
from an isolated packaged runner. Each Base/CAT comparison uses the exact same concern-specific task generated from
`review-task-template.txt`. The task does not mention CAT, delegation, a reviewer, rules, or a required solution. The
CAT wrapper selects the installed medium-tier specialist and supplies its response contract at the parent-to-child
handoff boundary. CAT output is accepted only when its event stream records that selected specialist contract.

The fixtures are intentionally unsafe and incomplete. Each language-native fixture is published below
[`../../fixtures/review-languages`](../../fixtures/review-languages), so rendered review links open stable source files,
not temporary runner artifacts. They are stable review targets, not production guidance.

The previously published matrix predates the equal-prompt protocol and is diagnostic-only. Regenerate it before
publishing this section. Under `languages/`, the valid matrix contains one Base stream and one validated CAT specialist
stream for every concern, harness, and TypeScript, Python, Java, C#, C++, PHP, or Go fixture. Failed and superseded
retries remain only below the gitignored `.cat/temp` capture workspace; they are not publication candidates.

Each `codex-*` and `claude-*` event stream names its harness, Base or CAT mode, and concern. Both modes receive the
same retained task. The CAT capture tool derives the Review Contract's marked response-format block from the selected
packaged reviewer definition and provides it only at the parent-to-child handoff. The page mechanically compares the
complete raw Base and CAT responses; the streams retain the launch protocol and reviewer-invocation evidence. Regenerate
`rendered-captures.js` with `node generate-rendered-captures.mjs`; the generator preserves terminal response text and
refuses a CAT response without the required grammar, fields, or complete Markdown file links. The static HTML then works
when opened directly, without a network request.

Run `capture-language-matrix.sh` to capture the complete language matrix beneath the gitignored
`.cat/temp/changelog-review-captures` workspace. Pass a different workspace outside this published directory only when
needed. It gives every harness, language, and review concern a fresh project, home, and retained manifest while one
persistent parent supervises a bounded pool of twenty child processes by default. Set `CAPTURE_PARALLELISM` or pass its
second argument to select a different positive bound; do not background the script from a shell that will terminate
before it waits for the parent.
