#!/usr/bin/env bash
# Captures independent Base/CAT source responses through the request server of a top-level CAT harness session.
set -u

capture_root="${1:?capture root required}"
requested_harness="${2:-all}"
requested_language="${3:-all}"
requested_example="${4:-all}"
parallelism="${5:-${CAPTURE_PARALLELISM:-20}}"
capture_timeout_seconds="${CAPTURE_TIMEOUT_SECONDS:-600}"
project_root="$(CDPATH='' cd -- "$(dirname -- "$0")/../../../../.." && pwd)"
script_directory="${project_root}/docs/changelog/1.0/captures/languages"
fixtures="${project_root}/docs/changelog/1.0/fixtures/languages"
checkout_prompt_template="${script_directory}/checkout-price-calculator.prompt.txt"
documentation_prompt_template="${script_directory}/documentation.prompt.txt"
money_rule_path="rules/include/common/money.md"
money_rule_source="${project_root}/client/common/rules/src/main/resources/${money_rule_path}"
session_manifest_name="session.json"
declare -A capture_models=([codex]="${CAT_CODEX_MODEL:-}" [claude]="${CAT_CLAUDE_MODEL:-}")
declare -A capture_efforts=([codex]="${CAT_CODEX_EFFORT:-}" [claude]="${CAT_CLAUDE_EFFORT:-}")

[[ "${parallelism}" =~ ^[1-9][0-9]*$ ]] || {
	echo "Capture parallelism must be a positive integer: ${parallelism}" >&2
	exit 2
}
[[ "${capture_timeout_seconds}" =~ ^[1-9][0-9]*$ ]] || {
	echo "CAPTURE_TIMEOUT_SECONDS must be a positive integer: ${capture_timeout_seconds}" >&2
	exit 2
}
export CAPTURE_TIMEOUT_SECONDS="${capture_timeout_seconds}"
case "${requested_harness}" in
	all | codex | claude) ;;
	*) echo "Source capture harness must be all, codex, or claude: ${requested_harness}" >&2; exit 2 ;;
esac
if [[ "${CAT_SESSION_REQUESTS:-}" =~ [^[:space:]] ]]; then
	echo "Start source capture pairs in a top-level CAT harness session with its request server running." >&2
	exit 2
fi
if [[ "${CAT_CAPTURE_BIN:-}" != /* || ! -d "${CAT_CAPTURE_BIN}" ]]; then
	echo "Set CAT_CAPTURE_BIN to the absolute bin directory of the calling harness's installed CAT plugin." >&2
	exit 2
fi
if [[ "${CAT_CAPTURE_TEMP:-}" != /* || ! -d "${CAT_CAPTURE_TEMP}" ]]; then
	echo "Set CAT_CAPTURE_TEMP to an existing directory under the calling session's CAT temporary root." >&2
	exit 2
fi

# Verifies a selected harness's requested profile and installed artifact before captures start.
#
# Arguments:
#   The harness identifier, codex or claude.
# Environment:
#   CAT_CAPTURE_BIN names the calling harness's installed CAT launchers.
# Exit status:
#   0 if the selected artifact and launchers are ready; 1 otherwise.
verify_capture_artifact()
{
	local harness="$1"
	local selection artifact selected_runner source_guidance installed_guidance
	local guidance_section="/^## Guidance\$/,\$p"
	local -a selection_lines

	if [[ -z "${capture_models[${harness}]}" || -z "${capture_efforts[${harness}]}" ]]; then
		echo "Set CAT_${harness^^}_MODEL and CAT_${harness^^}_EFFORT to the profile for the selected harness." >&2
		return 1
	fi
	if [[ ! -x "${CAT_CAPTURE_BIN}/${harness}-runner" || ! -x "${CAT_CAPTURE_BIN}/${harness}-artifact" ]]; then
		echo "The calling CAT plugin has no executable ${harness} runner or artifact selector in ${CAT_CAPTURE_BIN}." >&2
		return 1
	fi
	selection="$("${CAT_CAPTURE_BIN}/${harness}-artifact")" || return 1
	mapfile -t selection_lines <<< "${selection}"
	if [[ "${#selection_lines[@]}" -ne 3 || "${selection_lines[0]}" != artifact=/* ||
		"${selection_lines[1]}" != runner=/* || "${selection_lines[2]}" != source=installed ]]; then
		echo "The ${harness} artifact selector did not return the installed artifact and runner." >&2
		return 1
	fi
	artifact="${selection_lines[0]#artifact=}"
	selected_runner="${selection_lines[1]#runner=}"
	if [[ ! -x "${selected_runner}" ]]; then
		echo "The ${harness} artifact selector returned a missing or non-executable runner: ${selected_runner}" >&2
		return 1
	fi
	# Packaging omits Design Goals and resolves this rule's relative lazy-load references from the plugin root.
	source_guidance="$(sed -n -e "s;\`\./;\`\${CAT_PLUGIN_ROOT}/${money_rule_path%/*}/;g" \
		-e "${guidance_section}" "${money_rule_source}")" || return 1
	installed_guidance="$(sed -n "${guidance_section}" "${artifact}/${money_rule_path}")" || return 1
	if [[ -z "${source_guidance}" || "${source_guidance}" != "${installed_guidance}" ]]; then
		echo "The installed ${harness} CAT artifact differs from the checkout's Money rule. Update it before capturing." >&2
		return 1
	fi
	if [[ "${harness}" = claude ]]; then
		"${selected_runner}" --verify-artifact "${artifact}" || return 1
	fi
}

# Captures one harness and mode into the caller-owned staging directory.
#
# Arguments:
#   The harness, language, example, generated filename, Base/CAT mode, and staging directory.
# Environment:
#   CAT_CAPTURE_BIN names the calling harness's installed CAT launchers.
# Exit status:
#   0 if the harness produces a complete valid source artifact; 1 otherwise.
capture_one()
{
	local harness="$1" language="$2" example="$3" filename="$4" mode="$5" staging_directory="$6"
	local runner="${CAT_CAPTURE_BIN}/${harness}-runner"
	local model="${capture_models[${harness}]}" effort="${capture_efforts[${harness}]}"
	local results project prompt language_name session_id session_copy manifest result validation_events

	results="$(mktemp -d "${temporary_root}/${language}-${example}-${harness}-${mode}.XXXXXX")" || return 1
	project="${results}/project"
	mkdir -p "${project}" || return 1
	cp -R "${fixtures}/${language}/${example}/." "${project}" || return 1
	prompt="${results}/prompt.txt"
	if [ "${example}" = "code" ]; then
		case "${language}" in
			typescript) language_name="TypeScript" ;;
			python) language_name="Python" ;;
			java) language_name="Java" ;;
			csharp) language_name="C#" ;;
			cpp) language_name="C++" ;;
			php) language_name="PHP" ;;
			go) language_name="Go" ;;
			*) echo "Unknown source-capture language: ${language}" >&2; exit 2 ;;
		esac
		sed "s/\\[Language\\]/${language_name}/g" "${checkout_prompt_template}" > "${prompt}" || return 1
	else
		cp "${documentation_prompt_template}" "${prompt}" || return 1
	fi
	if ! session_id="$(node "${script_directory}/capture-source.mjs" \
		"${runner}" "${harness}" "${project}" "${prompt}" \
		"${results}/events.jsonl" "${results}" "${mode}" "${model}" "${effort}")"; then
		echo "Source capture failed for ${harness}/${language}/${example}/${mode}; retained session is diagnostic only" >&2
		return 1
	fi

	session_copy="${results}/${harness}/${session_id}"
	manifest="${session_copy}/${session_manifest_name}"
	result="${session_copy}/project/${filename}"
	[ -f "${result}" ] || {
		echo "No generated ${filename} was retained for ${harness}/${language}/${example}/${mode}" >&2
		return 1
	}
	if rg -q 'TODO: implement' "${result}"; then
		echo "${harness} source session left ${filename} unimplemented for ${language}/${example}/${mode}" >&2
		return 1
	fi
	if [ "${language}" = typescript ] && [ "${example}" = code ] && [ "${mode}" = cat ]; then
		validation_events="${staging_directory}/${harness}-${mode}.money-validation.events.jsonl"
		if ! node "${script_directory}/validate-money-capture.mjs" "${runner}" "${harness}" \
			"${session_copy}/project" "${prompt}" "${validation_events}" "${results}/money-validation" \
			"${model}" "${effort}"; then
			echo "${harness} source session failed TypeScript monetary model validation" >&2
			return 1
		fi
	fi
	cp "${prompt}" "${staging_directory}/${harness}-${mode}.prompt.txt" || return 1
	cp "${results}/events.jsonl" "${staging_directory}/${harness}-${mode}.events.jsonl" || return 1
	cp "${manifest}" "${staging_directory}/${harness}-${mode}.${session_manifest_name}" || return 1
	cp "${result}" "${staging_directory}/${harness}-${mode}.${filename##*.}"
}

# Writes this pair's state, reason and optional exit status and signal to its staging directory.
#
# Arguments:
#   The pair state, reason, optional numeric exit status, and optional signal name.
write_pair_record()
{
	jq -n \
		--arg state "$1" \
		--arg reason "$2" \
		--arg exit_code "${3:-}" \
		--arg signal "${4:-}" \
		--arg harness "${harness}" \
		--arg language "${language}" \
		--arg example "${example}" \
		--arg started_at "${pair_started_at}" \
		--arg process_id "${BASHPID}" \
		'{schema_version: 2, state: $state, reason: $reason, exit_code: ($exit_code | if . == "" then null else tonumber end),
		  signal: ($signal | if . == "" then null else . end), harness: $harness, language: $language,
		  example: $example, process_id: ($process_id | tonumber), started_at: $started_at,
		  finished_at: (if $state == "running" or $state == "publishing" then null else now | todateiso8601 end)}' \
		> "${pair_record}.tmp" && mv "${pair_record}.tmp" "${pair_record}"
}

# Records a terminal decision using the same arguments as write_pair_record.
#
# Exit status:
#   0 after the terminal decision is recorded; 1 when the record could not be written.
finish_pair()
{
	write_pair_record "$@" || return 1
	pair_terminal=1
}

# Records the pair's observed exit status if it ended before reaching a publication decision.
record_pair_exit()
{
	exit_code=$?
	[ "${pair_terminal}" -eq 1 ] ||
		finish_pair interrupted "The pair shell exited before its publication decision." "${exit_code}"
}

# Records interruption with the received signal name and the shell's corresponding exit status.
#
# Arguments:
#   The signal name without its SIG prefix and the shell exit status.
# Exit status:
#   The supplied exit status.
record_pair_signal()
{
	signal="$1"
	exit_code="$2"
	finish_pair interrupted "The pair shell received SIG${signal} before its publication decision." "${exit_code}" "SIG${signal}"
	exit "${exit_code}"
}

# Stages and validates one harness's Base and CAT captures before publishing their pair.
#
# Arguments:
#   The harness, language, example, and generated filename.
# Exit status:
#   0 if both modes complete and publish; 1 otherwise.
capture_pair()
{
	harness="$1"
	language="$2"
	example="$3"
	filename="$4"
	published_directory="${capture_root}/${language}/${example}"
	staging_directory="$(mktemp -d "${temporary_root}/${language}-${example}-${harness}.XXXXXX")" || return 1
	pair_record="${staging_directory}/capture-pair.json"
	pair_started_at="$(date -u '+%Y-%m-%dT%H:%M:%SZ')" || return 1
	pair_terminal=0

	# Keep the shell's terminal reason durable so later diagnosis does not infer it from an absent process.
	trap record_pair_exit EXIT
	trap 'record_pair_signal HUP 129' HUP
	trap 'record_pair_signal INT 130' INT
	trap 'record_pair_signal TERM 143' TERM

	write_pair_record running "The pair is awaiting complete Base and CAT captures." || return 1

	if ! capture_one "${harness}" "${language}" "${example}" "${filename}" base "${staging_directory}" ||
		! capture_one "${harness}" "${language}" "${example}" "${filename}" cat "${staging_directory}"; then
		finish_pair withheld "A required Base or CAT capture did not reach every publication gate." 1
		echo "Source capture pair failed for ${harness}/${language}/${example}; retained sessions are diagnostic only" >&2
		return 1
	fi

	mkdir -p "${published_directory}" || return 1
	write_pair_record publishing "Both captures passed; the staged pair is being copied to the published directory." || return 1
	cp "${staging_directory}/"* "${published_directory}/" || return 1
	finish_pair published "Both captures passed and the staged pair was copied to the published directory." 0 || return 1
	cp "${pair_record}" "${published_directory}/"
}

# Schedules a requested harness-language-example pair for capture.
#
# Arguments:
#   The harness, language, example, and generated filename.
capture_selected()
{
	harness="$1"
	language="$2"
	example="$3"
	filename="$4"
	[ "${requested_harness}" = all ] || [ "${requested_harness}" = "${harness}" ] || return 0
	[ "${requested_language}" = all ] || [ "${requested_language}" = "${language}" ] || return 0
	[ "${requested_example}" = all ] || [ "${requested_example}" = "${example}" ] || return 0
	start capture_pair "${harness}" "${language}" "${example}" "${filename}"
}

active=0
failed=0
# Waits for one scheduled pair and records whether it failed.
wait_for_one()
{
	if ! wait -n; then
		failed=1
	fi
	active=$((active - 1))
}

# Starts one pair capture and limits concurrent pairs to the configured parallelism.
#
# Arguments:
#   A capture function followed by its arguments.
start()
{
	"$@" &
	active=$((active + 1))
	if [ "${active}" -ge "${parallelism}" ]; then
		wait_for_one
	fi
}

for harness in codex claude; do
	if [[ "${requested_harness}" = all || "${requested_harness}" = "${harness}" ]]; then
		verify_capture_artifact "${harness}" || exit 1
	fi
done

temporary_root="$(mktemp -d "${CAT_CAPTURE_TEMP}/language-captures.XXXXXX")" || exit 1

for example in code documentation; do
	for language_and_file in \
		"typescript:checkout-price-calculator.ts:reservation-expiry.ts" \
		"python:checkout_price_calculator.py:reservation_expiry.py" \
		"java:CheckoutPriceCalculator.java:ReservationExpiry.java" \
		"csharp:CheckoutPriceCalculator.cs:ReservationExpiry.cs" \
		"cpp:CheckoutPriceCalculator.cpp:ReservationExpiry.cpp" \
		"php:CheckoutPriceCalculator.php:ReservationExpiry.php" \
		"go:checkout_price_calculator.go:reservation_expiry.go"; do
		IFS=: read -r language code_filename documentation_filename <<< "${language_and_file}"
		if [ "${example}" = code ]; then
			filename="${code_filename}"
		else
			filename="${documentation_filename}"
		fi
		for harness in codex claude; do
			capture_selected "${harness}" "${language}" "${example}" "${filename}"
		done
	done
done

while [ "${active}" -gt 0 ]; do
	wait_for_one
done
if [ "${failed}" -ne 0 ]; then
	echo "Capture diagnostics are in ${temporary_root}. Remove this directory after inspecting the failed sessions." >&2
	exit 1
fi
rm -rf -- "${temporary_root}"
