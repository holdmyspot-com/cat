import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";

const source = await readFile(new URL("./overview.html", import.meta.url), "utf8");
const firstFunction = source.indexOf("  function reviewFixturePath");
const nextFunction = source.indexOf("  function appendMarkdownInline");
assert.notEqual(firstFunction, -1, "overview must define reviewFixturePath");
assert.notEqual(nextFunction, -1, "overview must define appendMarkdownInline");

const helpers = source.slice(firstFunction, nextFunction);
const {publishedFileUrl} = Function(`${helpers}\nreturn {publishedFileUrl};`)();
const capturedUrl = "</home/gili/cat/worktrees/v1.0/docs/changelog/1.0/captures/reviews/languages/" +
  "typescript/runs/codex-cat-security/codex/run/project/deploy/release.yaml:4>";

assert.equal(publishedFileUrl(capturedUrl), "fixtures/review/deploy/release.yaml");
assert.equal(publishedFileUrl("<https://example.com/release.yaml>"), "https://example.com/release.yaml");

assert.match(source, /@media \(max-width: 640px\)[\s\S]*\.harness-picker \{[^}]*flex-direction: column;/,
  "phone layout must stack selector groups");
assert.match(source, /@media \(max-width: 640px\)[\s\S]*main \{[^}]*padding: 188px 12px 56px;/,
  "phone layout must reserve space for the fixed selector");
assert.match(source, /@media \(max-width: 640px\)[\s\S]*\.source-pane \{[^}]*max-height: 34vh;/,
  "phone layout must bound stacked source panes");
assert.match(source, /@media \(max-width: 640px\)[\s\S]*\.source-code, \.full-diff \{[^}]*tab-size: 2;/,
  "phone layout must render captured tabs as two spaces");
assert.match(source, /function terminalCapture\(capture, harness, language, prompt\)/,
  "monetary capture must render as one selected-harness terminal");
assert.match(source, /function terminalLaunch\(harness\)/,
  "monetary capture must render the selected harness launch banner");
assert.match(source, /function terminalStatus\(harness\)/,
  "the terminal must render the selected harness status bar");
assert.match(source, /terminal\.append\(launch, request, result, status\); article\.append\(frame, toolbar, terminal\)/,
  "the terminal must scroll its identity, prompt, output, and footer together below the mode controls");
assert.doesNotMatch(source, /\.terminal-capture\.claude \.terminal-status \{ position: sticky/,
  "the terminal footer must not remain visible before the user scrolls to the end of the terminal output");
assert.match(source, /\.terminal-capture \{[^}]*background: #0c0c0c/,
  "the terminal must use the native dark terminal surface");
assert.match(source, /\[\['base', 'Base'\], \['cat', 'CAT'\], \['diff', 'Diff'\]\]/,
  "monetary terminal must offer Base, CAT, and Diff output controls");
assert.match(source, /\.terminal-capture \{[^}]*border: 1px solid #437c67;[^}]*border-radius: 14px;[^}]*box-shadow: 0 12px 28px #0004;/,
  "the monetary terminal must have the same visible framed boundary as source-comparison captures");
assert.match(source, /\.terminal-frame-controls \{ display: flex; gap: 7px;/,
  "the monetary terminal frame must visibly group its window-control buttons");
assert.match(source, /\.terminal-frame-control\.close \{ background: #f57070; \} \.terminal-frame-control\.minimize \{ background: #f4c260; \} \.terminal-frame-control\.maximize \{ background: #6cdca0; \}/,
  "terminal frame controls must use the same close, minimize, and maximize colors as capture frames");
assert.match(source, /\.comparison-toolbar\.terminal-toolbar \{ justify-content: flex-start;/,
  "Base, CAT, and Diff controls must remain on the terminal's left edge");
assert.match(source, /\.comparison-toolbar\.terminal-toolbar button \{[^}]*border: 1px solid #555;[^}]*border-radius: 5px;[^}]*cursor: pointer;/,
  "terminal mode controls must have visible button affordances");
assert.match(source, /\.comparison-toolbar\.terminal-toolbar button\[aria-pressed="true"\] \{[^}]*border-color: #78a68d;[^}]*background: #294536;/,
  "the selected terminal mode must have a distinct pressed state");
assert.match(source, /cat\.changelog\.1\.0\.monetary-view/,
  "monetary terminal must persist its selected view across refreshes");
assert.match(source, /section\[data-source-example="code"\] > article\.capture \{ display: none; \}/,
  "monetary capture must hide its static fallback before the stored terminal view is rendered");
assert.match(source, /body:not\(\.source-examples-ready\) \[data-source-example\] \{ visibility: hidden; \}/,
  "source examples must stay hidden until their selected captures are ready");
assert.match(source, /selectLanguage\(document\.body\.dataset\.language, false\);[\s\S]*document\.body\.classList\.add\('source-examples-ready'\);/,
  "source examples must become visible only after their selected captures are rendered");
assert.match(source, /let selectedLanguage = document\.body\.dataset\.language \?\? 'typescript';/,
  "the first source render must use the language selected before visible panels are parsed");
assert.match(source, /if \(example === 'code'\)[\s\S]*filename: language\[example\]\.filename[\s\S]*terminalCapture\(terminalCaptureData, harness, language\.label, language\[example\]\.prompt\)/,
  "only the monetary example must use the terminal presentation");
assert.match(source, /\.terminal-result \.diff-removed[\s\S]*\.terminal-result \.diff-added/,
  "monetary diff must distinguish removed Base and added CAT lines");
assert.match(source, /const sourceLines = capture\[view\]\.split\('\\n'\);[\s\S]*appendChangedSourceLine\(rendered, \{number: String\(lineNumber\)\.padStart\(width\), change: '\+', code: line\}\);/,
  "Base and CAT terminal output must use the same numbered addition-line rendering as the diff view");
assert.doesNotMatch(source, /Wrote checkout-price-calculator with CAT/,
  "the CAT mode must not label generated source as being produced with CAT");
assert.match(source, /function terminalDiffLines\(capture, harness\)/,
  "the terminal diff must derive an AI-style file-edit display");
assert.match(source, /Write\(\$\{line\.filename\}\)/,
  "the terminal diff must identify the edited file as a write operation");
assert.match(source, /Added \$\{addedLines\}, removed \$\{removedLines\}/,
  "the terminal diff must summarize additions and removals like Claude's write output");
assert.match(source, /line\.startsWith\('diff --git '\) \|\| line\.startsWith\('index '\) \|\| line\.startsWith\('--- '\) \|\| line\.startsWith\('\+\+\+ '\)/,
  "the terminal diff must omit raw Git patch headers");
assert.match(source, /terminal-claude-launch/,
  "the Claude terminal must have its own compact launch header");
assert.match(source, /terminal-claude-mark/,
  "the Claude terminal must render the coral pixel mark from the reference");
assert.match(source, /fill="#D77757"/,
  "the Claude terminal must use the reference mark color");
assert.match(source, /\.terminal-claude-details \{ display: grid; gap: 0; color: #999; \} \.terminal-claude-details strong \{ color: #ccc; \}/,
  "the Claude identity must use the reference's muted detail and light title colors");
assert.match(source, /\.terminal-claude-directory \{ color: #b1b9f9; \}/,
  "the Claude working-directory text must use the reference blue");
assert.doesNotMatch(source, /API Usage Billing/,
  "the Claude header must omit billing-only information from the reference preview");
assert.doesNotMatch(source, /terminal-claude-tip|terminal-codex-tip/,
  "terminal launch headers must omit the non-interactive tip line");
assert.match(source, /\.terminal-capture\.claude \.terminal-prompt, \.terminal-capture\.codex \.terminal-prompt \{[^}]*color: #ccc;/,
  "the prompt text must use the reference terminal foreground color");
assert.match(source, /\.terminal-capture\.claude \.terminal-prompt, \.terminal-capture\.codex \.terminal-prompt \{ margin: 0 10px; padding: 4px 0;/,
  "the prompt bar must have a visible 10px gap at both terminal edges");
assert.match(source, /\.terminal-result \{ display: block; margin: 0; padding: 8px 10px 18px;/,
  "the generated-source result must have the same 10px horizontal inset as the prompt bar");
assert.match(source, /\.terminal-capture\.claude \.terminal-result\.diff, \.terminal-capture\.codex \.terminal-result\.diff \{ padding: 12px 10px 18px; \}/,
  "the diff pane must have the same 10px gap at both terminal edges as the prompt bar");
assert.doesNotMatch(source, /\.terminal-result::before/,
  "the write operation must be a selectable text node rather than generated CSS content");
assert.match(source, /function appendSourceOperation\(rendered, harness, filename, addedCount, removedCount\)/,
  "the source operation must render harness-specific terminal text");
assert.match(source, /action\.textContent = 'Added';[\s\S]*rendered\.append\(marker, ' ', action, ` \$\{filename\} \(`, added, removed, '\)'\);/,
  "Codex source output must use the observed Added filename (+added -removed) format");
assert.match(source, /appendSourceOperation\(operation, terminal\.classList\.contains\('codex'\) \? 'codex' : 'claude', capture\.filename,[\s\S]*sourceLines\.length, 0\);/,
  "Codex source output must derive its added count from the displayed source lines");
assert.match(source, /action\.textContent = 'Update';[\s\S]*rendered\.append\(marker, ' ', action, `\(\$\{filename\}\)`\);/,
  "Claude source output must use a bullet followed by its observed Update(filename) format");
assert.match(source, /\.terminal-write-operation \{[^}]*color: #d5d5d5;/,
  "operation text other than its action verb must retain the terminal gray foreground");
assert.match(source, /\.terminal-write-operation-action \{ color: #f8f8f2; \}/,
  "Codex Added and Claude Update action verbs must use the reference white foreground");
assert.match(source, /\.terminal-write-operation \{[^}]*user-select: text !important;/,
  "the source operation must explicitly permit selection for copy and paste");
assert.match(source, /terminal-codex-card/,
  "the Codex terminal must render the bordered identity card from the reference");
assert.match(source, /OpenAI Codex<\/strong> <em>\(v0\.147\.0\)/,
  "the Codex terminal must identify the observed version");
assert.match(source, /background: #2e2e2e/,
  "the Claude prompt bar must use the reference gray");
assert.match(source, /background: #022800/,
  "the added-line background must use the sampled reference green");
assert.match(source, /background: #3d0100/,
  "the removed-line background must use the sampled reference red");
assert.match(source, /\.terminal-result \.diff-removed \{ color: #f8f8f2; background: #3d0100; \}/,
  "removed source text and its background must match the sampled Claude reference exactly");
assert.match(source, /\.terminal-result \.diff-added \{ color: #f8f8f2; background: #022800; \}/,
  "added source text and its background must match the sampled Claude reference exactly");
assert.match(source, /\.terminal-capture\.claude \.terminal-result \.diff-removed \.diff-line-number, \.terminal-capture\.claude \.terminal-result \.diff-removed \.diff-line-prefix \{ color: #dc5a5a; \}/,
  "Claude removed-line numbers and markers must use the sampled coral foreground");
assert.match(source, /\.terminal-capture\.claude \.terminal-result \.diff-added \.diff-line-number, \.terminal-capture\.claude \.terminal-result \.diff-added \.diff-line-prefix \{ color: #50c850; \}/,
  "Claude added-line numbers and markers must use the sampled green foreground");
assert.match(source, /\.terminal-capture\.codex \.terminal-result \.diff-removed \{ color: #5e5f62; background: #4a221d; \}/,
  "Codex removed lines must use its independently sampled muted foreground and brown-red background");
assert.match(source, /\.terminal-capture\.codex \.terminal-result \.diff-added \{ color: #bcbec4; background: #213a2b; \}/,
  "Codex added lines must use its independently sampled light foreground and green background");
assert.match(source, /\.terminal-capture\.codex \.terminal-result \.diff-line-number \{ color: #666; \}/,
  "Codex changed-line numbers must use the muted gray from its reference");
assert.match(source, /\.terminal-capture\.codex \.terminal-result \.diff-removed \.diff-line-prefix \{ color: #c50f1f; \}/,
  "the Codex removed-line marker must retain its red foreground rather than inherit source text color");
assert.match(source, /\.terminal-capture\.codex \.terminal-result \.diff-added \.diff-line-prefix \{ color: #13a10e; \}/,
  "the Codex added-line marker must retain its green foreground rather than inherit source text color");
assert.match(source, /\.terminal-capture\.claude \.terminal-result \.diff-operation-marker \{ color: #50c850; \}/,
  "the Claude write-operation marker must use the same green as the reference");
assert.match(source, /\.terminal-capture\.claude \.terminal-result \.diff-count-added, \.terminal-capture\.claude \.terminal-result \.diff-count-removed \{ color: #ccc; \}/,
  "the Claude write summary must keep neutral count text");
assert.match(source, /\.terminal-capture\.codex \.terminal-result \.diff-operation-marker \{ color: #666; \}/,
  "the Codex edit-operation bullet must use its muted reference gray");
assert.match(source, /\.terminal-capture\.codex \.terminal-result \.diff-count-added \{ color: #13a10e; \}/,
  "the Codex addition count must use its sampled green");
assert.match(source, /\.terminal-capture\.codex \.terminal-result \.diff-count-removed \{ color: #c50f1f; \}/,
  "the Codex removal count must use its sampled red");
assert.match(source, /const marker = document\.createElement\('span'\); marker\.className = 'diff-operation-marker';/,
  "operation markers must be explicit elements so their foreground differs from operation text");
assert.match(source, /\.terminal-result \.diff-line-number \{[^}]*color: #999/,
  "changed-line numbers must retain the muted terminal color from the reference");
assert.match(source, /function appendChangedSourceLine\(rendered, line\)/,
  "changed lines must render their number, prefix, and source as independently colored characters");
assert.match(source, /\.terminal-codex-details \.terminal-codex-directory \{ color: #b1b9f9; \}/,
  "the Codex directory must retain its sampled reference color after card styling");
for (const color of ['#0c0c0c', '#2e2e2e', '#888', '#16c60c', '#f14c4c', '#3b78ff', '#b1b9f9', '#D77757']) {
  assert.match(source, new RegExp(color.replace('#', '\\#'), 'i'),
    `the terminal must retain sampled reference color ${color}`);
}
