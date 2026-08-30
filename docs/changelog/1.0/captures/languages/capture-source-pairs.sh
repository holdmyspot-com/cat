#!/usr/bin/env bash
# Captures every independent Base/CAT source response through isolated packaged runners under one persistent supervisor.
set -u

capture_root="${1:?capture root required}"
requested_harness="${2:-all}"
requested_language="${3:-all}"
requested_example="${4:-all}"
parallelism="${5:-${CAPTURE_PARALLELISM:-20}}"
capture_timeout_seconds="${CAPTURE_TIMEOUT_SECONDS:-600}"
project_root="$(CDPATH= cd -- "$(dirname -- "$0")/../../../../.." && pwd)"
runtime="${project_root}/client/distribution/target/runtime"
fixtures="${project_root}/docs/changelog/1.0/fixtures/languages"
temporary_root="${project_root}/.cat/work/temp/changelog-language-captures"
claude_temporary_root="${TMPDIR:-/tmp}/.cat/claude/changelog-language-captures"
checkout_prompt_template="$(dirname "$0")/checkout-price-calculator.prompt.txt"
documentation_prompt_template="$(dirname "$0")/documentation.prompt.txt"
money_rule_source="${project_root}/client/common/rules/src/main/resources/rules/include/common/money.md"

[[ "${parallelism}" =~ ^[1-9][0-9]*$ ]] || {
	echo "Capture parallelism must be a positive integer: ${parallelism}" >&2
	exit 2
}
[[ "${capture_timeout_seconds}" =~ ^[1-9][0-9]*$ ]] || {
	echo "CAPTURE_TIMEOUT_SECONDS must be a positive integer: ${capture_timeout_seconds}" >&2
	exit 2
}

# Verifies that both packaged harness artifacts contain the current monetary rule source.
#
# Exit status:
#   0 if each runtime contains the current rule; 1 otherwise.
verify_runtime_rules()
{
	for harness in codex claude; do
		runtime_money_rule="${runtime}/${harness}/rules/include/common/money.md"
		[ -f "${runtime_money_rule}" ] && cmp -s "${money_rule_source}" "${runtime_money_rule}" || {
			echo "${harness} runtime does not contain the current Money rule; rebuild the distribution before capturing" >&2
			return 1
		}
	done
}

# Captures one harness and mode into the caller-owned staging directory.
#
# Arguments:
#   The harness, language, example, generated filename, Base/CAT mode, and staging directory.
# Exit status:
#   0 if the harness produces a complete valid source artifact; 1 otherwise.
capture_one()
{
	harness="$1"
	language="$2"
	example="$3"
	filename="$4"
	mode="$5"
	staging_directory="$6"
	runner="${runtime}/${harness}/client/bin/${harness}-runner"
	run_temporary_root="${temporary_root}"
	if [ "${harness}" = claude ]; then
		# Claude's Bubblewrap masks ancestor Git hooks; a checkout-local run can make a missing mask target abort Bash.
		run_temporary_root="${claude_temporary_root}"
	fi
	mkdir -p "${run_temporary_root}"
	run_root="$(mktemp -d "${run_temporary_root}/${language}-${example}-${harness}-${mode}.XXXXXX")"
	project="${run_root}/project"
	mkdir -p "${project}"
	cp -R "${fixtures}/${language}/${example}/." "${project}"
	prompt="${run_root}/prompt.txt"
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
		sed "s/\\[Language\\]/${language_name}/g" "${checkout_prompt_template}" > "${prompt}"
	else
		cat "${documentation_prompt_template}" > "${prompt}"
	fi
	# The node supervisor receives the capture deadline first so it can record the runner's observed terminal status.
	if ! timeout --kill-after=15s "$((capture_timeout_seconds + 30))s" node "$(dirname "$0")/capture-source.mjs" \
		"${runner}" "${runtime}/${harness}" "${project}" "${prompt}" \
		"${run_root}/events.jsonl" "${run_root}" "${mode}"; then
		echo "Source capture failed for ${harness}/${language}/${example}/${mode}; retained run is diagnostic only" >&2
		return 1
	fi
	for attempt in {1..30}; do
		manifest="$(find "${run_root}/${harness}" -name run.json -print -quit)"
		[ -n "${manifest}" ] || {
			sleep 1
			continue
		}
		state="$(jq -r '.state' "${manifest}")"
		case "${state}" in
			completed)
				[ "$(jq -r '.exitCode' "${manifest}")" = 0 ] && [ "$(jq -r '.turnNumber' "${manifest}")" -ge 1 ] || {
					echo "Completed ${harness} run lacks a successful turn for ${language}/${example}/${mode}" >&2
					return 1
				}
				break
				;;
			failed)
				echo "${harness} source run failed for ${language}/${example}/${mode}: $(jq -r '.diagnostic' "${manifest}")" >&2
				return 1
				;;
		esac
		sleep 1
	done
	[ "${state:-missing}" = completed ] || {
		echo "${harness} source run did not reach a terminal successful state for ${language}/${example}/${mode}" >&2
		return 1
	}
	result="$(find "${run_root}/${harness}" -path "*/project/${filename}" -print -quit)"
	[ -n "${result}" ] || {
		echo "No generated ${filename} was retained for ${harness}/${language}/${example}/${mode}" >&2
		return 1
	}
	if rg -q 'TODO: implement' "${result}"; then
		echo "${harness} source run left ${filename} unimplemented for ${language}/${example}/${mode}" >&2
		return 1
	fi
	if [ "${language}" = typescript ] && [ "${example}" = code ] && [ "${mode}" = cat ]; then
		validation_events="${staging_directory}/${harness}-${mode}.money-validation.events.jsonl"
		if ! node "$(dirname "$0")/validate-money-capture.mjs" "${runner}" "${runtime}/${harness}" \
			"$(dirname "${result}")" "${prompt}" "${validation_events}" "${run_root}/money-validation"; then
			echo "${harness} source run failed TypeScript monetary model validation" >&2
			return 1
		fi
	fi
	cp "${prompt}" "${staging_directory}/${harness}-${mode}.prompt.txt"
	cp "${run_root}/events.jsonl" "${staging_directory}/${harness}-${mode}.events.jsonl"
	cp "${manifest}" "${staging_directory}/${harness}-${mode}.run.json"
	cp "${result}" "${staging_directory}/${harness}-${mode}.${filename##*.}"
}

# Captures and atomically publishes one harness's Base/CAT pair for a language example.
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
	mkdir -p "${temporary_root}"
	staging_directory="$(mktemp -d "${temporary_root}/${language}-${example}-${harness}.XXXXXX")"
	pair_record="${staging_directory}/capture-pair.json"
	pair_started_at="$(date --iso-8601=seconds)"
	pair_terminal=0

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
			'{schemaVersion: 1, state: $state, reason: $reason, exitCode: ($exit_code | if . == "" then null else tonumber end),
			  signal: ($signal | if . == "" then null else . end), harness: $harness, language: $language,
			  example: $example, processId: ($process_id | tonumber), startedAt: $started_at,
			  finishedAt: (if $state == "running" or $state == "publishing" then null else now | todateiso8601 end)}' \
			> "${pair_record}.tmp" && mv "${pair_record}.tmp" "${pair_record}"
	}

	finish_pair()
	{
		pair_terminal=1
		write_pair_record "$@"
	}

	record_pair_exit()
	{
		exit_code=$?
		[ "${pair_terminal}" -eq 1 ] ||
			finish_pair interrupted "The pair shell exited before its publication decision." "${exit_code}"
	}

	record_pair_signal()
	{
		signal="$1"
		exit_code="$2"
		finish_pair interrupted "The pair shell received SIG${signal} before its publication decision." "${exit_code}" "SIG${signal}"
		exit "${exit_code}"
	}

	# Keep the shell's terminal reason durable so later diagnosis does not infer it from an absent process.
	trap record_pair_exit EXIT
	trap 'record_pair_signal HUP 129' HUP
	trap 'record_pair_signal INT 130' INT
	trap 'record_pair_signal TERM 143' TERM

	write_pair_record running "The pair is awaiting complete Base and CAT captures."

	if ! capture_one "${harness}" "${language}" "${example}" "${filename}" base "${staging_directory}" ||
		! capture_one "${harness}" "${language}" "${example}" "${filename}" cat "${staging_directory}"; then
		finish_pair withheld "A required Base or CAT capture did not reach every publication gate." 1
		echo "Source capture pair failed for ${harness}/${language}/${example}; retained runs are diagnostic only" >&2
		return 1
	fi

	mkdir -p "${published_directory}"
	write_pair_record publishing "Both captures passed; the staged pair is being copied to the published directory."
	cp "${staging_directory}/"* "${published_directory}/"
	finish_pair published "Both captures passed and the staged pair was copied to the published directory." 0
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

verify_runtime_rules || exit 1

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
exit "${failed}"
