#!/bin/sh
# Captures the controlled Base and CAT review matrix through retained isolated runner processes.
set -eu

capture_root="${1:?capture root required}"
shift
parallelism="${1:-4}"
if [ "$#" -gt 0 ]; then
  shift
fi
project_root="$(CDPATH= cd -- "$(dirname -- "$0")/../../../../.." && pwd)"
review_directory="${project_root}/docs/changelog/1.0/captures/reviews"
fixture="${CAT_REVIEW_FIXTURE:-${project_root}/docs/changelog/1.0/fixtures/review}"
runtime="${project_root}/client/distribution/target/runtime"

[ -d "${fixture}" ] || {
	echo "Review fixture does not exist: ${fixture}" >&2
	exit 1
}

mkdir -p "${capture_root}/outputs" "${capture_root}/sessions"

capture_one()
{
	harness="$1"
	mode="$2"
	concern="$3"
	# Each plugin holds its runner in bin/, where the other harness's plugin runs it
	runner="${runtime}/codex/bin/codex-runner"
	if [ "${harness}" = claude ]; then
		runner="${runtime}/claude/bin/claude-runner"
	fi
	output="${capture_root}/outputs/${harness}-${mode}-${concern}.events.jsonl"
	results="${capture_root}/sessions/${harness}-${mode}-${concern}"
	project="${results}/project"
	mkdir -p "${project}"
	prompt="${results}/request.txt"
    case "${concern}" in
      architecture) reviewer="Architecture" ;;
      business) reviewer="Business" ;;
      deployment) reviewer="Deployment" ;;
      design) reviewer="Design" ;;
      legal) reviewer="Legal" ;;
      performance) reviewer="Performance" ;;
      requirements) reviewer="Requirements" ;;
      security) reviewer="Security" ;;
      testing) reviewer="Testing" ;;
      ux) reviewer="UX" ;;
      plan-review) reviewer="Plan Review" ;;
      *) echo "Unknown review concern: ${concern}" >&2; exit 1 ;;
    esac
    sed "s/\\[Concern\\]/${reviewer}/g" "${review_directory}/review-task-template.txt" > "${prompt}"
	cp -R "${fixture}/." "${project}"
	if [ "${mode}" = "cat" ]; then
		if [ "${harness}" = "codex" ]; then
			node "${review_directory}/capture-codex-review.mjs" "${runner}" "${runtime}/${harness}" "${project}" \
				"${prompt}" "${output}" "${results}" "${concern}-medium"
		else
			node "${review_directory}/capture-claude-review.mjs" "${runner}" "${runtime}/${harness}" "${project}" \
				"${prompt}" "${output}" "${results}" "${concern}-medium"
		fi
	else
		{ printf '{"type":"turn","prompt":'; jq -Rs . < "${prompt}"; printf '}\n{"type":"complete"}\n'; } |
			"${runner}" --artifact "${runtime}/${harness}" --project "${project}" --output "${output}" \
				--results "${results}"
	fi
	if [ "${harness}" = "codex" ] && [ "${mode}" = "cat" ]; then
		validation_output="${output}.validation.json"
		[ -s "${validation_output}" ] && jq -e '.accepted == true' "${validation_output}" >/dev/null || {
			echo "Codex CAT capture has no accepted raw reviewer response for ${concern}" >&2
			exit 1
		}
	fi

	manifest="$(find "${results}/${harness}" -mindepth 2 -maxdepth 2 -name session.json -print -quit)"
	[ -n "${manifest}" ] || {
		echo "No ${harness} session manifest was created for ${mode}:${concern}" >&2
		exit 1
	}

	attempts=0
	while :; do
		state="$(jq -r '.state' "${manifest}")"
		case "${state}" in
			completed)
				[ -s "${output}" ] || {
					echo "Completed ${harness} session has no retained output for ${mode}:${concern}" >&2
					exit 1
				}
				if [ "${harness}" = "claude" ]; then
					terminal_response="$(jq -rs '[.[] | select(.type == "result") | .result] | last // empty' "${output}")"
				else
					terminal_response="$(jq -rs '[.[] | select(.method == "turn/completed") | .params.turn.items[]? |
						select(.type == "agentMessage" and .phase == "final_answer") | .text] | last // empty' "${output}")"
				fi
				[ -n "${terminal_response}" ] || {
					echo "Completed ${harness} session has no terminal response for ${mode}:${concern}" >&2
					exit 1
				}
				if [ "${mode}" = "cat" ]; then
					first_line="$(printf '%s\n' "${terminal_response}" | awk 'NF { print; exit }')"
					if [ "${terminal_response}" != "APPROVED" ] && [ "${first_line}" != "## Findings" ]; then
						echo "${harness} CAT response does not begin with ## Findings for ${concern}" >&2
						exit 1
					fi
					if [ "${terminal_response}" != "APPROVED" ] && ! printf '%s\n' "${terminal_response}" | awk '
						/^- \*\*/ {
							if (seen && (!link || !evidence || !consequence || !correction))
								exit 1
							seen = 1
							link = ($0 ~ /^- \*\*\[[^]]+\]\([^[:space:])]+\)/)
							evidence = consequence = correction = 0
							next
						}
						/^  - \*\*Evidence:\*\*/ { evidence = 1 }
						/^  - \*\*Consequence:\*\*/ { consequence = 1 }
						/^  - \*\*Correction:\*\*/ { correction = 1 }
						END { exit !(seen && link && evidence && consequence && correction) }
					'; then
						echo "${harness} CAT response has a finding without its required link or fields for ${concern}" >&2
						exit 1
					fi
					if printf '%s' "${terminal_response}" | rg -qP '\[[^]]+\.(?:java|md|yaml)(?::[0-9]+(?:-[0-9]+)?)?\](?!\()|\[[^]]+\.(?:java|md|yaml)(?::[0-9]+(?:-[0-9]+)?)?\]\(\s'; then
						echo "${harness} CAT response has a local-file label without a Markdown link target for ${concern}" >&2
						exit 1
					fi
				fi
				return
				;;
			failed)
				jq -r '.diagnostic' "${manifest}" >&2
				exit 1
				;;
		esac
		attempts=$((attempts + 1))
		[ "${attempts}" -lt 900 ] || {
			echo "Timed out waiting for ${harness} ${mode}:${concern}; manifest remains ${state}" >&2
			exit 1
		}
		sleep 1
	done
}

active=0
pids=""
failed=0
start()
{
  capture_one "$@" &
  pids="${pids} $!"
  active=$((active + 1))
  if [ "${active}" -ge "${parallelism}" ]; then
		wait_batch || failed=1
  fi
}

wait_batch()
{
	failed=0
	for pid in ${pids}; do
		if ! wait "${pid}"; then
			failed=1
		fi
	done
	pids=""
	active=0
	[ "${failed}" -eq 0 ]
}

if [ "$#" -gt 0 ]; then
  for capture in "$@"; do
    harness="${capture%%:*}"
    remainder="${capture#*:}"
    mode="${remainder%%:*}"
    concern="${remainder#*:}"
    start "${harness}" "${mode}" "${concern}"
  done
else
  for concern in architecture business deployment design legal performance requirements security testing ux plan-review; do
    start codex base "${concern}"
    start claude base "${concern}"
    start codex cat "${concern}"
    start claude cat "${concern}"
  done
fi
wait_batch || failed=1
exit "${failed}"
