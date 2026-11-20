#!/usr/bin/env bats
# Exercises source pair publication through the installed launcher contract without starting a model.

# Creates one test-local caller plugin, target rule and launcher stand-in.
setup() {
	project_root="$(cd -- "${BATS_TEST_DIRNAME}/../../../../.." && pwd)"
	export CAT_CAPTURE_BIN="${BATS_TEST_TMPDIR}/caller/bin"
	export CAT_CAPTURE_TEMP="${BATS_TEST_TMPDIR}/capture-temp"
	export CAT_CODEX_MODEL=provided-codex-model
	export CAT_CODEX_EFFORT=medium
	export CAT_CLAUDE_MODEL=provided-claude-model
	export CAT_CLAUDE_EFFORT=high
	export FAKE_CAPTURE_ARTIFACT="${BATS_TEST_TMPDIR}/target plugin"
	unset CAT_SESSION_REQUESTS
	captures="${BATS_TEST_TMPDIR}/published"

	mkdir -p "${CAT_CAPTURE_BIN}" "${CAT_CAPTURE_TEMP}" "${FAKE_CAPTURE_ARTIFACT}/rules/include/common"
	# Distribution omits design goals and delivers lazy-load references relative to the plugin root.
	{
		printf '# Money\n\n'
		awk '/^## Guidance$/ { visible = 1 } visible { print }' \
			"${project_root}/client/common/rules/src/main/resources/rules/include/common/money.md" |
			sed 's|`\./prefer-reuse.md`|`${CAT_PLUGIN_ROOT}/rules/include/common/prefer-reuse.md`|'
	} > "${FAKE_CAPTURE_ARTIFACT}/rules/include/common/money.md"
	cat > "${CAT_CAPTURE_BIN}/codex-artifact" <<'SH'
#!/usr/bin/env bash
name="${0##*/}"
printf 'artifact=%s\nrunner=%s\nsource=installed\n' "${FAKE_CAPTURE_ARTIFACT}" \
	"${CAT_CAPTURE_BIN}/${name%-artifact}-runner"
SH

	# The stand-in implements the one-shot input, transcript and results copy in harness-runners.md.
	cat > "${CAT_CAPTURE_BIN}/codex-runner" <<'JS'
#!/usr/bin/env node
import assert from "node:assert/strict";
import {mkdir, readFile, writeFile} from "node:fs/promises";
import {basename, join} from "node:path";
import {parseArgs} from "node:util";

const harness = basename(process.argv[1], "-runner");
if (process.argv[2] === "--verify-artifact")
{
  assert.equal(harness, "claude");
  assert.equal(process.argv[3], process.env.FAKE_CAPTURE_ARTIFACT);
  process.exit(0);
}

const {values} = parseArgs({options: {
  project: {type: "string"}, input: {type: "string"}, output: {type: "string"}, results: {type: "string"},
  model: {type: "string"}, effort: {type: "string"}, "install-cat": {type: "boolean"}
}});
assert.equal(values.model, `provided-${harness}-model`);
assert.equal(values.effort, harness === "codex" ? "medium" : "high");
const [turn, complete] = (await readFile(values.input, "utf8")).trimEnd().split("\n").map(JSON.parse);
assert.equal(turn.type, "turn");
assert.deepEqual(complete, {type: "complete"});
const review = turn.prompt.includes("Review the generated TypeScript");
if (review)
{
  assert.match(await readFile(join(values.project, "checkout-price-calculator.ts"), "utf8"), /generated cat/);
  assert.match(turn.prompt, /complete final response must be exactly APPROVED/);
  assert.match(turn.prompt, /sole caller-visible contract[\s\S]*Do not add[\s\S]*caller-visible requirements after generation/);
}
const mode = values["install-cat"] ? "cat" : "base";
const id = review ? "review" : `generated-${mode}`;
const correct = join(values.results, harness, id);
const decoy = join(values.results, harness, "aaa-decoy");

for (const folder of [decoy, correct])
{
  await mkdir(join(folder, "project"), {recursive: true});
  const sessionId = folder === correct ? id : "aaa-decoy";
  await writeFile(join(folder, "session.json"), JSON.stringify({
    id: sessionId, harness, state: "completed", turn_number: 1, exit_code: 0
  }));
  const source = folder === correct ? `// generated ${mode}\n` : "// unrelated capture\n";
  await writeFile(join(folder, "project", "checkout-price-calculator.ts"), source);
}

const assistantResponse = review && process.env.REJECT_SOURCE_CAPTURE ? "REJECTED: fake validation" : "APPROVED";
const nativeEvent = harness === "codex" ?
  '{  "method":"turn/completed", "params":{"turn":{"status":"completed"}}  }' : '{"type":"result","is_error":false}';
await writeFile(values.output, [
  {type: "session_id", session_id: id},
  {type: "harness_event", event: nativeEvent},
  {type: "turn_completed", exit_code: 0, error: "", assistant_response: assistantResponse, assistant_messages: []}
].map(JSON.stringify).join("\n") + "\n");
JS
	chmod +x "${CAT_CAPTURE_BIN}/codex-artifact" "${CAT_CAPTURE_BIN}/codex-runner"
	cp -p "${CAT_CAPTURE_BIN}/codex-artifact" "${CAT_CAPTURE_BIN}/claude-artifact"
	cp -p "${CAT_CAPTURE_BIN}/codex-runner" "${CAT_CAPTURE_BIN}/claude-runner"
}

@test "source pairs publish both matching session copies after independent CAT validation" {
	run bash "${BATS_TEST_DIRNAME}/capture-source-pairs.sh" "${captures}" all typescript code 1
	[ "${status}" -eq 0 ]

	pair="${captures}/typescript/code"
	for harness in codex claude; do
		[ "$(cat "${pair}/${harness}-base.ts")" = '// generated base' ]
		[ "$(cat "${pair}/${harness}-cat.ts")" = '// generated cat' ]
		[ "$(jq -r .id "${pair}/${harness}-base.session.json")" = 'generated-base' ]
		[ "$(jq -r .id "${pair}/${harness}-cat.session.json")" = 'generated-cat' ]
		[ -s "${pair}/${harness}-cat.money-validation.events.jsonl" ]
	done
	[ "$(jq -r .state "${pair}/capture-pair.json")" = 'published' ]
}

@test "source pairs preserve existing captures when independent CAT validation rejects the candidate" {
	pair="${captures}/typescript/code"
	mkdir -p "${pair}"
	printf 'previous evidence\n' > "${pair}/codex-base.ts"
	export REJECT_SOURCE_CAPTURE=1

	run bash "${BATS_TEST_DIRNAME}/capture-source-pairs.sh" "${captures}" codex typescript code 1
	[ "${status}" -ne 0 ]
	[[ "${output}" == *"REJECTED: fake validation"* ]]
	[ "$(cat "${pair}/codex-base.ts")" = 'previous evidence' ]
	[ ! -e "${pair}/codex-cat.session.json" ]
}

@test "source pairs reject a stale installed Money rule before publishing any capture" {
	printf '\nStale guidance.\n' >> "${FAKE_CAPTURE_ARTIFACT}/rules/include/common/money.md"

	run bash "${BATS_TEST_DIRNAME}/capture-source-pairs.sh" "${captures}" codex typescript code 1
	[ "${status}" -ne 0 ]
	[[ "${output}" == *"installed codex CAT artifact differs from the checkout's Money rule"* ]]
	[ ! -e "${captures}" ]
}

@test "the renderer uses a current session manifest alongside untouched historical capture formats" {
	prepare_renderer

	run node "${render_directory}/generate-rendered-captures.mjs"
	[ "${status}" -eq 0 ]
	cp "${render_directory}/rendered-captures.js" "${BATS_TEST_TMPDIR}/historical-render.js"

	prefix="${render_directory}/typescript/code/codex-base"
	jq '{harness, id, state, exit_code: .exitCode, turn_number: .turnNumber}' \
		"${prefix}.run.json" > "${prefix}.session.json"
	printf '{"state":"failed"}\n' > "${prefix}.run.json"

	run node "${render_directory}/generate-rendered-captures.mjs"
	[ "${status}" -eq 0 ]
	cmp -s "${render_directory}/rendered-captures.js" "${BATS_TEST_TMPDIR}/historical-render.js"
}

@test "the renderer rejects a failed current session even when old successful evidence exists" {
	prepare_renderer
	prefix="${render_directory}/typescript/code/codex-base"
	jq '{harness, id, state: "failed", exit_code: 1, turn_number: .turnNumber}' \
		"${prefix}.run.json" > "${prefix}.session.json"

	run node "${render_directory}/generate-rendered-captures.mjs"
	[ "${status}" -ne 0 ]
	[[ "${output}" == *"codex-base.session.json"* ]]
}

# Makes a test-owned rendering fixture with matching prompts so only manifest compatibility decides its outcome.
prepare_renderer() {
	render_directory="${BATS_TEST_TMPDIR}/render"
	cp -R "${BATS_TEST_DIRNAME}" "${render_directory}"
	for mode in base cat; do
		sed 's/\[Language\]/TypeScript/g' "${render_directory}/checkout-price-calculator.prompt.txt" \
			> "${render_directory}/typescript/code/codex-${mode}.prompt.txt"
	done
}
