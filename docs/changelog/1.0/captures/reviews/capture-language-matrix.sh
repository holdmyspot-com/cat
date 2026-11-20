#!/usr/bin/env bash
# Runs every independent language, harness, and review concern beneath one parent that reaps all isolated runner children.
set -u

project_root="$(CDPATH='' cd -- "$(dirname -- "$0")/../../../../.." && pwd)"
capture_root="${1:?absolute capture root required}"
parallelism="${2:-${CAPTURE_PARALLELISM:-20}}"
matrix="${project_root}/docs/changelog/1.0/captures/reviews/capture-matrix.sh"
fixtures="${project_root}/docs/changelog/1.0/fixtures/review-languages"

published_root="${project_root}/docs/changelog/1.0/captures/reviews"
case "${capture_root}" in
  "${published_root}"|"${published_root}"/*)
    echo "Capture workspace must be outside the published review artifacts: ${capture_root}" >&2
    exit 2
    ;;
  /*) ;;
  *)
    echo "Supply an absolute capture root under the current CAT session's temporary folder." >&2
    exit 2
    ;;
esac

[[ "${parallelism}" =~ ^[1-9][0-9]*$ ]] || {
	echo "Capture parallelism must be a positive integer: ${parallelism}" >&2
	exit 2
}

pids=()
failed=0

# Reap each matrix cell in the current batch and preserve any failure for the final exit status.
wait_batch()
{
	local pid
	for pid in "${pids[@]}"; do
		if ! wait "${pid}"; then
			failed=1
		fi
	done
	pids=()
}

# Start one capture for a language and reap the batch when it reaches the requested limit.
#
# Arguments:
#   Language, harness, base or cat mode, concern.
start()
{
	local language="$1" harness="$2" mode="$3" concern="$4"
	CAT_REVIEW_FIXTURE="${fixtures}/${language}" "${matrix}" "${capture_root}/${language}" 1 \
		"${harness}:${mode}:${concern}" &
	pids+=("$!")
	if [ "${#pids[@]}" -ge "${parallelism}" ]; then
		wait_batch
	fi
}

languages=(typescript python java csharp cpp php go)
for language in "${languages[@]}"; do
	[ -d "${fixtures}/${language}" ] || {
		echo "Review fixture does not exist: ${fixtures}/${language}" >&2
		exit 2
	}
done

for language in "${languages[@]}"; do
	for concern in architecture business deployment design legal performance requirements security testing ux plan-review; do
		start "${language}" codex base "${concern}"
		start "${language}" claude base "${concern}"
		start "${language}" codex cat "${concern}"
		start "${language}" claude cat "${concern}"
	done
done

wait_batch
exit "${failed}"
