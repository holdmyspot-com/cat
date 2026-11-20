#!/usr/bin/env bats
# Exercises launcher wiring, matched matrix scheduling, and publication of the observed session.

# Prepare private paths and a fixture artifact for the current test.
setup()
{
	reviews="${BATS_TEST_DIRNAME}"
	capture="${BATS_TEST_TMPDIR}/capture"
	bin="${BATS_TEST_TMPDIR}/bin"
	artifact="${BATS_TEST_TMPDIR}/artifact"
	fixture="${BATS_TEST_TMPDIR}/fixture"
	mkdir -p "${capture}" "${bin}" "${artifact}/agents/common" "${fixture}"
	cp "${reviews}/review-runner-fixture.mjs" "${bin}/runner"
	chmod +x "${bin}/runner"
	cat > "${artifact}/agents/common/review-contract.md" <<'CONTRACT'
# Review Contract

Return exactly `APPROVED`.
CONTRACT
	export CAT_CAPTURE_BIN="${bin}"
	export CAT_CODEX_MODEL=model CAT_CODEX_EFFORT=low CAT_CLAUDE_MODEL=model CAT_CLAUDE_EFFORT=low
	export CAT_REVIEW_FIXTURE="${fixture}"
}

# Supply one installed target selector and its artifact verifier without starting a native model.
#
# Arguments:
#   Harness name.
install_launcher_fixture()
{
	local harness="$1"
	ln -s "${bin}/runner" "${bin}/${harness}-runner"
	cat > "${bin}/${harness}-artifact" <<EOF
#!/bin/sh
printf '%s\n' 'artifact=${artifact}' 'runner=${bin}/verify-artifact' 'source=installed'
EOF
	cat > "${bin}/verify-artifact" <<EOF
#!/bin/sh
[ "\$1" = --verify-artifact ] && [ "\$2" = '${artifact}' ]
EOF
	chmod +x "${bin}/${harness}-artifact" "${bin}/verify-artifact"
}

# Describe the fake runner's model profile, expected source task, and terminal behavior.
#
# Arguments:
#   Harness name, optional simulated completion state.
write_fixture()
{
	local harness="$1" state="${2:-complete}"
	sed 's/\[Concern\]/Architecture/g' "${reviews}/review-task-template.txt" > "${fixture}/request.txt"
	jq -n --arg harness "${harness}" --arg state "${state}" --rawfile prompt "${fixture}/request.txt" \
		'{harness:$harness,state:$state,prompt:$prompt,model:"model",effort:"low"}' > "${fixture}/fixture.json"
}

@test "Base review supplies packed input and retains native events from the observed session" {
	install_launcher_fixture codex
	write_fixture codex

	run "${reviews}/capture-matrix.sh" "${capture}" 1 codex:base:architecture

	[ "${status}" -eq 0 ]
	jq -e '.session_id == "observed-id"' "${capture}/sessions/codex-base-architecture/capture.json"
	[ "$(cat "${capture}/outputs/codex-base-architecture.events.jsonl")" = '{ "method": "turn/completed", "params": {"threadId":"observed-id","turn":{"items":[{"type":"agentMessage","phase":"final_answer","text":"APPROVED"}]}} }' ]
}

@test "Codex adaptive review corrects one invalid reply and waits for session completion with its observed ID" {
	write_fixture codex

	run node "${reviews}/capture-codex-review.mjs" "${bin}/runner" "${artifact}" "${fixture}" \
		"${fixture}/request.txt" "${capture}/native.jsonl" "${capture}" architecture-medium model low

	[ "${status}" -eq 0 ]
	jq -e '.session_id == "observed-id"' "${capture}/capture.json"
	jq -e '.accepted and .corrections == 1' "${capture}/native.jsonl.validation.json"
}

@test "Claude adaptive review corrects one invalid reply and exports the original event bytes" {
	write_fixture claude

	run node "${reviews}/capture-claude-review.mjs" "${bin}/runner" "${artifact}" "${fixture}" \
		"${fixture}/request.txt" "${capture}/native.jsonl" "${capture}" architecture-medium model low

	[ "${status}" -eq 0 ]
	[ "$(tail -n 1 "${capture}/native.jsonl")" = '{ "type": "result", "result": "APPROVED" }' ]
}

@test "Claude adaptive review preserves the tab-indented specialist findings and accepts their exact relay" {
	write_fixture claude tab-findings

	run node "${reviews}/capture-claude-review.mjs" "${bin}/runner" "${artifact}" "${fixture}" \
		"${fixture}/request.txt" "${capture}/native.jsonl" "${capture}" architecture-medium model low

	[ "${status}" -eq 0 ]
	[ "$(tail -n 1 "${capture}/native.jsonl")" = '{"type":"result","result":"## Findings\n\n- **[CHANGE.md:1](CHANGE.md:1) Missing validation**\n\t- **Evidence:** Équipe.\n\t- **Consequence:** Bad input is accepted.\n\t- **Correction:** Validate the input."}' ]
}

@test "Adaptive review reports the launcher's refusal without accepting a decoy manifest" {
	write_fixture codex refused

	run node "${reviews}/capture-codex-review.mjs" "${bin}/runner" "${artifact}" "${fixture}" \
		"${fixture}/request.txt" "${capture}/native.jsonl" "${capture}" architecture-medium model low

	[ "${status}" -ne 0 ]
	[[ "${output}" == *"status refused"* ]]
	[ ! -e "${capture}/capture.json" ]
}

@test "Adaptive review rejects a launcher that exits before completing the first turn" {
	write_fixture claude early

	run node "${reviews}/capture-claude-review.mjs" "${bin}/runner" "${artifact}" "${fixture}" \
		"${fixture}/request.txt" "${capture}/native.jsonl" "${capture}" architecture-medium model low

	[ "${status}" -ne 0 ]
	[[ "${output}" == *"before completing turn 1"* ]]
	[ ! -e "${capture}/capture.json" ]
}

@test "Claude adaptive review rejects a valid root approval without a matching specialist reply" {
	write_fixture claude wrong-reviewer

	run node "${reviews}/capture-claude-review.mjs" "${bin}/runner" "${artifact}" "${fixture}" \
		"${fixture}/request.txt" "${capture}/native.jsonl" "${capture}" architecture-medium model low

	[ "${status}" -ne 0 ]
	[[ "${output}" == *"did not satisfy the Review Contract"* ]]
	[ ! -e "${capture}/capture.json" ]
}

@test "Claude adaptive review rejects a relayed specialist approval when the Agent call omits the contract" {
	write_fixture claude omitted-contract

	run node "${reviews}/capture-claude-review.mjs" "${bin}/runner" "${artifact}" "${fixture}" \
		"${fixture}/request.txt" "${capture}/native.jsonl" "${capture}" architecture-medium model low

	[ "${status}" -ne 0 ]
	[[ "${output}" == *"did not satisfy the Review Contract"* ]]
	[ ! -e "${capture}/capture.json" ]
}

@test "Codex adaptive review rejects a valid parent approval when the child rollout identifies another role" {
	write_fixture codex wrong-reviewer

	run node "${reviews}/capture-codex-review.mjs" "${bin}/runner" "${artifact}" "${fixture}" \
		"${fixture}/request.txt" "${capture}/native.jsonl" "${capture}" architecture-medium model low

	[ "${status}" -ne 0 ]
	[[ "${output}" == *"did not satisfy the Review Contract"* ]]
	[ ! -e "${capture}/capture.json" ]
}

@test "Codex adaptive review rejects a parent response different from the selected specialist's reply" {
	write_fixture codex changed-relay

	run node "${reviews}/capture-codex-review.mjs" "${bin}/runner" "${artifact}" "${fixture}" \
		"${fixture}/request.txt" "${capture}/native.jsonl" "${capture}" architecture-medium model low

	[ "${status}" -ne 0 ]
	[[ "${output}" == *"did not satisfy the Review Contract"* ]]
	[ ! -e "${capture}/capture.json" ]
}

@test "Base review rejects a completed decoy when the observed session manifest is absent" {
	install_launcher_fixture codex
	write_fixture codex missing

	run "${reviews}/capture-matrix.sh" "${capture}" 1 codex:base:architecture

	[ "${status}" -ne 0 ]
	[[ "${output}" == *"observed-id/session.json"* ]]
	[ ! -e "${capture}/sessions/codex-base-architecture/capture.json" ]
}

@test "Language matrix schedules one Base and one CAT capture per concern for both harnesses" {
	project="${BATS_TEST_TMPDIR}/project"
	review_scripts="${project}/docs/changelog/1.0/captures/reviews"
	mkdir -p "${review_scripts}"
	cp "${reviews}/capture-language-matrix.sh" "${review_scripts}"

	for language in typescript python java csharp cpp php go; do
		mkdir -p "${project}/docs/changelog/1.0/fixtures/review-languages/${language}"
	done

	cat > "${review_scripts}/capture-matrix.sh" <<'SH'
#!/bin/sh
mkdir -p "$1"
set -C
printf '%s\n' "$3" > "$1/$3"
SH
	chmod +x "${review_scripts}/capture-matrix.sh"

	run "${review_scripts}/capture-language-matrix.sh" "${capture}" 4

	[ "${status}" -eq 0 ]
	for language in typescript python java csharp cpp php go; do
		for harness in codex claude; do
			for concern in architecture business deployment design legal performance requirements security testing ux plan-review; do
				[ -f "${capture}/${language}/${harness}:base:${concern}" ]
				[ -f "${capture}/${language}/${harness}:cat:${concern}" ]
			done
		done
	done
	[ "$(find "${capture}" -type f | wc -l)" -eq 308 ]
}

# Populate the publisher's input with matched request and profile records and an unrelated completed session per cell.
#
# Arguments:
#   "matched" for a complete matrix or "mismatched" to vary the first CAT capture's profile.
make_publish_fixture()
{
	node --input-type=module - "${capture}" "${reviews}" "$1" <<'JS'
import {mkdir, readFile, writeFile} from "node:fs/promises";
import {join} from "node:path";
const [root, reviews, match] = process.argv.slice(2);
const request = await readFile(join(reviews, "review-task-template.txt"), "utf8");
const concerns = {
  architecture: "Architecture", business: "Business", deployment: "Deployment", design: "Design",
  legal: "Legal", performance: "Performance", requirements: "Requirements", security: "Security",
  testing: "Testing", ux: "UX", "plan-review": "Plan Review"
};
for (const language of ["typescript", "python", "java", "csharp", "cpp", "php", "go"])
{
  const outputs = join(root, language, "outputs");
  await mkdir(outputs, {recursive: true});
  for (const [concern, title] of Object.entries(concerns))
    for (const harness of ["codex", "claude"])
      for (const mode of ["base", "cat"])
      {
        const capture = `${harness}-${mode}-${concern}`;
        const results = join(root, language, "sessions", capture);
        const sessionIds = match === "matched" ? ["aaa-decoy", "observed-id"] : ["observed-id"];
        for (const id of sessionIds)
        {
          const session = join(results, harness, id);
          await mkdir(join(session, "project"), {recursive: true});
          await writeFile(join(session, "session.json"), JSON.stringify({schema_version: 6, harness, id,
            state: "completed", turn_number: 1, exit_code: 0}));
        }
        const differentModel = match === "mismatched" && language === "typescript" && capture === "codex-cat-architecture";
        await writeFile(join(results, "capture.json"), JSON.stringify({schema_version: 1, session_id: "observed-id",
          model: differentModel ? "other-model" : "model", effort: "low"}));
        await writeFile(join(results, "request.txt"), request.replaceAll("[Concern]", title));
        const events = join(outputs, `${capture}.events.jsonl`);
        const tabbedFinding = "## Findings\n\n- **[CHANGE.md:1](CHANGE.md:1) Missing validation**\n" +
          "\t- **Evidence:** Équipe.\n\t- **Consequence:** Bad input is accepted.\n\t- **Correction:** Validate the input.";
        const response = mode === "cat" ? tabbedFinding : "APPROVED";
        const nativeEvent = harness === "claude" ? {type: "result", result: response} :
          {method: "turn/completed", params: {turn: {items: [
            {type: "agentMessage", phase: "final_answer", text: response}]}}};
        await writeFile(events, `${JSON.stringify(nativeEvent)}\n`);
        if (harness === "codex" && mode === "cat")
          await writeFile(`${events}.validation.json`, '{"accepted":true,"corrections":0}\n');
      }
}
JS
}

@test "Publisher uses the observed session ID and writes a current session manifest for each matched capture" {
	make_publish_fixture matched
	published="${BATS_TEST_TMPDIR}/published"

	run node "${reviews}/publish-language-captures.mjs" "${capture}" "${published}"

	[ "${status}" -eq 0 ]
	jq -e '.id == "observed-id"' "${published}/typescript/codex-base-architecture.session.json"
	[ "$(find "${published}" -name '*.session.json' | wc -l)" -eq 308 ]
}

@test "The renderer publishes the tab-indented finding bytes from a captured specialist response" {
	make_publish_fixture matched
	published="${BATS_TEST_TMPDIR}/published"
	mkdir "${published}"
	cp "${reviews}/"{generate-rendered-captures.mjs,review-response.mjs,review-task-template.txt,semantic-correspondence.json} \
		"${published}"
	node "${reviews}/publish-language-captures.mjs" "${capture}" "${published}/languages"

	run node "${published}/generate-rendered-captures.mjs"

	[ "${status}" -eq 0 ]
	rg -Fq '\t- **Evidence:** Équipe.' "${published}/rendered-captures.js"
}

@test "Publisher rejects different model profiles for the same task before copying any captures" {
	make_publish_fixture mismatched
	published="${BATS_TEST_TMPDIR}/published"

	run node "${reviews}/publish-language-captures.mjs" "${capture}" "${published}"

	[ "${status}" -ne 0 ]
	[[ "${output}" == *"different model profiles"* ]]
	[ ! -e "${published}" ]
}
