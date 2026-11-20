#!/bin/sh
# Captures selected Base and CAT review cells through the installed runner launchers.
set -eu

capture_root="${1:?absolute capture root required}"
shift
parallelism="${1:-4}"
if [ "$#" -gt 0 ]; then
	shift
fi
case "${parallelism}" in
	''|0*|*[!0-9]*) echo "Capture parallelism must be a positive integer." >&2; exit 2 ;;
esac
case "${capture_root}" in
	/*) ;;
	*) echo "Supply an absolute capture root under the current CAT session's temporary folder." >&2; exit 2 ;;
esac

review_directory="$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd -P)"
fixture="${CAT_REVIEW_FIXTURE:-${review_directory}/../../fixtures/review}"
capture_bin="${CAT_CAPTURE_BIN:?absolute bin directory of the installed caller plugin required}"
case "${capture_bin}" in
	/*) ;;
	*) echo "CAT_CAPTURE_BIN must name an absolute installed bin directory." >&2; exit 2 ;;
esac

[ -d "${fixture}" ] || {
	echo "Review fixture does not exist: ${fixture}" >&2
	exit 2
}
mkdir -p "${capture_root}"
capture_root="$(CDPATH='' cd -- "${capture_root}" && pwd -P)"
case "${capture_root}" in
	"${review_directory}"|"${review_directory}"/*)
		echo "Capture workspace lies inside the published review artifacts: ${capture_root}" >&2
		exit 2
		;;
esac
mkdir -p "${capture_root}/outputs" "${capture_root}/sessions"

# Capture one review cell and retain the exact manifest identity for publication.
#
# Arguments:
#   Harness name, base or cat mode, concern.
# Environment:
#   CAT_CODEX_MODEL and CAT_CODEX_EFFORT specify the Codex profile.
#   CAT_CLAUDE_MODEL and CAT_CLAUDE_EFFORT specify the Claude Code profile.
# Side effects:
#   Creates the cell's request and session files and native output beneath the capture workspace.
# Exit status:
#   Nonzero if the launch, review validation, or results copy fails.
capture_one()
{
	harness="$1"
	mode="$2"
	concern="$3"
	case "${harness}" in
		codex)
			model="${CAT_CODEX_MODEL:?Codex model required}"
			effort="${CAT_CODEX_EFFORT:?Codex effort required}"
			;;
		claude)
			model="${CAT_CLAUDE_MODEL:?Claude Code model required}"
			effort="${CAT_CLAUDE_EFFORT:?Claude Code effort required}"
			;;
		*) echo "Unknown review harness: ${harness}" >&2; return 2 ;;
	esac
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
		*) echo "Unknown review concern: ${concern}" >&2; return 2 ;;
	esac
	case "${mode}" in
		base|cat) ;;
		*) echo "Unknown review mode: ${mode}" >&2; return 2 ;;
	esac

	runner="${capture_bin}/${harness}-runner"
	selected="$("${capture_bin}/${harness}-artifact")"
	artifact="$(printf '%s\n' "${selected}" | sed -n 's/^artifact=//p')"
	selected_runner="$(printf '%s\n' "${selected}" | sed -n 's/^runner=//p')"
	source="$(printf '%s\n' "${selected}" | sed -n 's/^source=//p')"
	case "${artifact}" in
		/*) ;;
		*) echo "Installed selector returned no absolute ${harness} artifact directory." >&2; return 2 ;;
	esac
	case "${selected_runner}" in
		/*) ;;
		*) echo "Installed selector returned no absolute ${harness} runner." >&2; return 2 ;;
	esac
	if [ "${source}" != installed ] || [ ! -d "${artifact}" ] || [ ! -x "${selected_runner}" ] || [ ! -x "${runner}" ]; then
		echo "Installed ${harness} runner or artifact is unavailable." >&2
		return 2
	fi
	if [ "${harness}" = claude ]; then
		"${selected_runner}" --verify-artifact "${artifact}"
	fi

	output="${capture_root}/outputs/${harness}-${mode}-${concern}.events.jsonl"
	results="${capture_root}/sessions/${harness}-${mode}-${concern}"
	project="${results}/project"
	mkdir "${results}"
	mkdir "${project}"
	prompt="${results}/request.txt"
	sed "s/\\[Concern\\]/${reviewer}/g" "${review_directory}/review-task-template.txt" > "${prompt}"
	cp -R "${fixture}/." "${project}"
	if [ "${mode}" = cat ]; then
		node "${review_directory}/capture-${harness}-review.mjs" "${runner}" "${artifact}" "${project}" \
			"${prompt}" "${output}" "${results}" "${concern}-medium" "${model}" "${effort}"
	else
		node "${review_directory}/capture-base-review.mjs" "${harness}" "${runner}" "${project}" \
			"${prompt}" "${output}" "${results}" "${model}" "${effort}"
	fi
}

active=0
pids=""
failed=0

# Reap every child in the current batch and retain any failed cell for the final exit status.
wait_batch()
{
	for pid in ${pids}; do
		if ! wait "${pid}"; then
			failed=1
		fi
	done
	pids=""
	active=0
}

# Start one review in the batch; reap the batch once it reaches the requested limit.
#
# Arguments:
#   Harness name, base or cat mode, concern.
start()
{
	capture_one "$@" &
	pids="${pids} $!"
	active=$((active + 1))
	if [ "${active}" -ge "${parallelism}" ]; then
		wait_batch
	fi
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
wait_batch
exit "${failed}"
