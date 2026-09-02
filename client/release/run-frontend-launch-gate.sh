#!/usr/bin/env sh

# Records every required frontend release check, then aggregates immutable receipts.

set -u

if [ "$#" -ne 3 ]; then
	echo "Usage: $0 VERSION STAGED_PERSONAL STAGED_COMMERCIAL" >&2
	exit 2
fi

version="$1"
staged_personal="$2"
staged_commercial="$3"
evidence_root="client/common/distribution/target/frontend-launch-gates"
distribution_classes="${PWD}/client/common/distribution/target/classes"
distribution_modules="${PWD}/client/common/distribution/target/modules"

if [ ! -f "${distribution_classes}/module-info.class" ] || [ ! -d "${distribution_modules}" ]; then
	echo "Build the current distribution before running the frontend launch gate" >&2
	exit 1
fi

launch_gate()
{
	java --module-path "${distribution_classes}:${distribution_modules}" \
		--module io.github.cowwoc.cat.distribution/io.github.cowwoc.cat.distribution.FrontendLaunchGateMain "$@"
}

root="$(launch_gate create-attempt "${evidence_root}" "${version}")"
[ -n "${root}" ] || { echo "Could not create a frontend launch-gate attempt directory" >&2; exit 1; }

record_gate()
{
	gate="$1"
	status="$2"
	command="$3"
	started="$4"
	completed="$5"
	diagnostic="$6"
	evidence="$7"
	receipt="${root}/${gate}.json"
	if [ -n "${evidence}" ]; then
		launch_gate record "${gate}" "${receipt}" "${status}" "${command}" "${started}" "${completed}" \
			"${diagnostic}" "${evidence}"
		return
	fi
	launch_gate record "${gate}" "${receipt}" "${status}" "${command}" "${started}" "${completed}" \
		"${diagnostic}"
}

run_gate()
{
	gate="$1"
	shift
	log="${root}/${gate}.log"
	command="$*"
	case "$1" in
		*/*)
			if [ ! -x "$1" ]; then
				started="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
				record_gate "${gate}" unrun - - - "Required gate fixture is unavailable: $1" ""
				return
			fi
			;;
	esac
	if [ "$1" = bats ] && [ ! -f "$2" ]; then
		started="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
		record_gate "${gate}" unrun - - - "Required Bats fixture is unavailable: $2" ""
		return
	fi
	started="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
	"$@" >"${log}" 2>&1
	exit_code="$?"
	completed="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
	provider_unavailable="${root}/work-execute-provider-unavailable.txt"
	if [ "${gate}" = work-execute-contract ] && [ -s "${provider_unavailable}" ]; then
		diagnostic="$(tr '\n' ';' < "${provider_unavailable}")"
		record_gate "${gate}" unrun - - - "${diagnostic}" "${log}"
		return
	fi
	if [ "${exit_code}" -eq 0 ]; then
		record_gate "${gate}" passed "${command}" "${started}" "${completed}" - "${log}"
		return
	fi
	if [ "${exit_code}" -eq 77 ]; then
		diagnostic="$(tail -n 1 "${log}")"
		if [ -z "${diagnostic}" ]; then
			diagnostic="Command reported unavailable (exit 77)"
		fi
		record_gate "${gate}" unrun - - - "${diagnostic}" ""
		return
	fi
	if grep -Eq 'missing .*(codex|claude)/(linux|macos)-' "${log}"; then
		diagnostic="$(tail -n 1 "${log}")"
		record_gate "${gate}" unrun - - - "${diagnostic}" ""
		return
	fi
	record_gate "${gate}" failed "${command}" "${started}" "${completed}" \
		"Command exited ${exit_code}" "${log}"
}

verification_log="${root}/maven-verify.log"
client/mvnw -q -Dmaven.build.cache.enabled=false -f client/pom.xml verify >"${verification_log}" 2>&1
verification_exit="$?"
if [ "${verification_exit}" -ne 0 ]; then
	for gate in personal-lifecycle commercial-lifecycle platform-matrix release-tag-integrity \
		commercial-lease-continuity feedback-and-analytics-privacy prompt-system-boundary \
		certification-terminal-matrix work-execute-contract license-offer-consistency; do
		started="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
		record_gate "${gate}" failed "client/mvnw -q -Dmaven.build.cache.enabled=false -f client/pom.xml verify" \
			"${started}" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "Maven verification exited ${verification_exit}" \
			"${verification_log}"
	done
else
	run_gate personal-lifecycle client/release/test-staged-release.sh "${staged_personal}" personal "${version}"
	run_gate commercial-lifecycle client/release/test-private-install.sh "${staged_commercial}" commercial "${version}"
	run_gate platform-matrix client/release/test-platform-matrix.sh "${staged_personal}" "${staged_commercial}" "${version}"
	run_gate release-tag-integrity client/release/test-release-tag.sh "${staged_personal}" "${staged_commercial}" "${version}"
	run_gate commercial-lease-continuity client/mvnw -q -Dmaven.build.cache.enabled=false -f client/pom.xml \
		-pl :client-common-cli -am '-Dtest=*CommercialEntitlement*,*EntitlementLease*,*CommercialAccountClient*' \
		-Dsurefire.failIfNoSpecifiedTests=false test
	run_gate feedback-and-analytics-privacy client/mvnw -q -Dmaven.build.cache.enabled=false -f client/pom.xml \
		-pl :client-common-cli -am '-Dtest=*Feedback*,*UsageAnalytics*' -Dsurefire.failIfNoSpecifiedTests=false test
	run_gate prompt-system-boundary client/mvnw -q -Dmaven.build.cache.enabled=false -f client/pom.xml \
		-pl :client-common-cli -am '-Dtest=*PromptSystem*,*ContractExtraction*,*PromptTest*' \
		-Dsurefire.failIfNoSpecifiedTests=false test
	run_gate certification-terminal-matrix client/release/test-terminal-certification.sh "${staged_personal}" \
		"${staged_commercial}" "${version}"
	if [ "${CAT_RUN_ACTUAL_AGENT_TESTS:-}" != 1 ]; then
		record_gate work-execute-contract unrun - - - \
			"Actual work-execute tests require CAT_RUN_ACTUAL_AGENT_TESTS=1 and both harness credentials" ""
	else
		CAT_COMMERCIAL_STAGED_TREE="${staged_commercial}" \
			CAT_WORK_EXECUTE_PROVIDER_UNAVAILABLE_FILE="${root}/work-execute-provider-unavailable.txt" \
			run_gate work-execute-contract bats client/common/agents/src/test/bats/work-execute-agent.bats
	fi
	run_gate license-offer-consistency client/release/test-license-offer-consistency.sh "${staged_personal}" \
		"${staged_commercial}" docs/PRICING.md LICENSE.md "${version}"
fi

set -- "${version}" "${root}/gate-report.json"
for gate in personal-lifecycle commercial-lifecycle platform-matrix release-tag-integrity \
	commercial-lease-continuity feedback-and-analytics-privacy prompt-system-boundary \
	certification-terminal-matrix work-execute-contract license-offer-consistency; do
	set -- "$@" "${root}/${gate}.json"
done
release_commit="$(git rev-parse HEAD)"
set -- "$@" --handoff "${staged_personal}" "${staged_commercial}" "${release_commit}" 1.0 docs/PRICING.md
launch_gate "$@"
