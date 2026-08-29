#!/usr/bin/env bash
# Runs every independent language, harness, and review concern beneath one parent that reaps all isolated runner children.
set -u

project_root="$(CDPATH= cd -- "$(dirname -- "$0")/../../../../.." && pwd)"
capture_root="${1:-${project_root}/.cat/temp/changelog-review-captures}"
parallelism="${2:-${CAPTURE_PARALLELISM:-20}}"
matrix="${project_root}/docs/changelog/1.0/captures/reviews/capture-matrix.sh"
fixtures="${project_root}/docs/changelog/1.0/fixtures/review-languages"

published_root="${project_root}/docs/changelog/1.0/captures/reviews"
case "${capture_root}" in
  "${published_root}"|"${published_root}"/*)
    echo "Capture workspace must be outside the published review artifacts: ${capture_root}" >&2
    exit 2
    ;;
esac

[[ "${parallelism}" =~ ^[1-9][0-9]*$ ]] || {
	echo "Capture parallelism must be a positive integer: ${parallelism}" >&2
	exit 2
}

capture_one()
{
	language="$1"
	harness="$2"
	mode="$3"
	concern="$4"
	CAT_REVIEW_FIXTURE="${fixtures}/${language}" "${matrix}" "${capture_root}/${language}" 1 \
		"${harness}:${mode}:${concern}"
}

active=0
failed=0
wait_for_one()
{
	if ! wait -n; then
		failed=1
	fi
	active=$((active - 1))
}

start()
{
	capture_one "$@" &
	active=$((active + 1))
	if [ "${active}" -ge "${parallelism}" ]; then
		wait_for_one
	fi
}

for language in typescript python java csharp cpp php go; do
	[ -d "${fixtures}/${language}" ] || {
		echo "Review fixture does not exist: ${fixtures}/${language}" >&2
		exit 2
	}
	start "${language}" codex base structured
	start "${language}" claude base structured
	for concern in architecture business deployment design legal performance requirements security testing ux plan-review; do
		start "${language}" codex cat "${concern}"
		start "${language}" claude cat "${concern}"
	done
done

while [ "${active}" -gt 0 ]; do
	wait_for_one
done
exit "${failed}"
