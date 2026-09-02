#!/usr/bin/env sh

# Records one native build receipt or verifies the assembled release against every required receipt.

set -eu

usage()
{
	echo "Usage: $0 PERSONAL_TREE COMMERCIAL_TREE VERSION" >&2
	echo "   or: $0 --record PERSONAL_TREE COMMERCIAL_TREE VERSION RECEIPT" >&2
	echo "   or: $0 --record-wsl PERSONAL_TREE COMMERCIAL_TREE VERSION RECEIPT" >&2
	exit 2
}

detect_platform()
{
	case "$(uname -s)" in
		Linux) operating_system=linux ;;
		Darwin) operating_system=macos ;;
		*) echo "Unsupported platform: $(uname -s)" >&2; exit 77 ;;
	esac
	case "$(uname -m)" in
		x86_64|amd64) architecture=x86_64 ;;
		arm64|aarch64) architecture=arm64 ;;
		*) echo "Unsupported architecture: $(uname -m)" >&2; exit 77 ;;
	esac
	platform="${operating_system}-${architecture}"
}

validate_version()
{
	printf '%s\n' "$1" | grep -Eq '^[0-9]+(\.[0-9]+){1,2}$' || {
		echo "Release version must be an immutable numeric tag: $1" >&2
		exit 2
	}
}

verify_target()
{
	tree=$1
	edition=$2
	harness=$3
	platform_id=$4
	artifact="$tree/$harness/$platform_id"
	runner="$artifact/client/bin/${harness}-runner"
	[ -d "$artifact" ] || { echo "Missing $edition $harness artifact: $artifact" >&2; exit 1; }
	[ -x "$runner" ] || { echo "Missing $edition $harness runner: $runner" >&2; exit 1; }
	"$runner" --verify-artifact "$artifact" >/dev/null
}

verify_current_platform()
{
	personal_tree=$1
	commercial_tree=$2
	platform_id=$3
	for harness in codex claude; do
		verify_target "$personal_tree" personal "$harness" "$platform_id"
		verify_target "$commercial_tree" commercial "$harness" "$platform_id"
	done
}

write_receipt()
{
	personal_tree=$1
	commercial_tree=$2
	version=$3
	receipt=$4
	execution_environment=$5
	validate_version "$version"
	detect_platform
	verify_current_platform "$personal_tree" "$commercial_tree" "$platform"
	[ ! -e "$receipt" ] || { echo "Platform receipt already exists: $receipt" >&2; exit 1; }
	mkdir -p "$(dirname "$receipt")"
	created_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
	{
		printf '{\n'
		printf '  "schema_version": "cat-platform-receipt-v1",\n'
		printf '  "version": "%s",\n' "$version"
		printf '  "platform": "%s",\n' "$platform"
		printf '  "execution_environment": "%s",\n' "$execution_environment"
		printf '  "created_at": "%s"\n' "$created_at"
		printf '}\n'
	} > "$receipt"
	echo "Recorded platform receipt: platform=$platform version=$version receipt=$receipt"
}

verify_wsl()
{
	[ -r /proc/sys/kernel/osrelease ] || {
		echo "WSL evidence requires /proc/sys/kernel/osrelease" >&2
		exit 77
	}
	grep -Eqi 'microsoft|wsl' /proc/sys/kernel/osrelease || {
		echo "This Linux environment is not WSL" >&2
		exit 77
	}
}

verify_receipt()
{
	receipt=$1
	version=$2
	platform_id=$3
	expected_environment=$4
	if [ ! -f "$receipt" ] || [ -L "$receipt" ]; then
		echo "Platform receipt is missing or unsafe: $receipt" >&2
		exit 77
	fi
	[ "$(wc -c < "$receipt" | tr -d '[:space:]')" -le 4096 ] || {
		echo "Platform receipt is too large: $receipt" >&2
		exit 1
	}
	grep -Fqx '  "schema_version": "cat-platform-receipt-v1",' "$receipt" || {
		echo "Platform receipt has an unsupported schema: $receipt" >&2
		exit 1
	}
	grep -Fqx "  \"version\": \"$version\"," "$receipt" || {
		echo "Platform receipt version does not match $version: $receipt" >&2
		exit 1
	}
	grep -Fqx "  \"platform\": \"$platform_id\"," "$receipt" || {
		echo "Platform receipt does not describe $platform_id: $receipt" >&2
		exit 1
	}
	grep -Fqx "  \"execution_environment\": \"$expected_environment\"," "$receipt" || {
		echo "Platform receipt does not describe $expected_environment execution: $receipt" >&2
		exit 1
	}
	grep -Eq '^  "created_at": "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z"$' "$receipt" || {
		echo "Platform receipt has no valid creation time: $receipt" >&2
		exit 1
	}
	[ "$(wc -l < "$receipt" | tr -d '[:space:]')" = 7 ] || {
		echo "Platform receipt has unexpected fields: $receipt" >&2
		exit 1
	}
}

if [ "$#" -eq 5 ] && [ "$1" = --record ]; then
	if [ ! -d "$2" ] || [ ! -d "$3" ]; then
		echo "Native release tree is missing" >&2
		exit 1
	fi
	[ -n "$4" ] || usage
	write_receipt "$2" "$3" "$4" "$5" native
	exit 0
fi
if [ "$#" -eq 5 ] && [ "$1" = --record-wsl ]; then
	if [ ! -d "$2" ] || [ ! -d "$3" ]; then
		echo "Native release tree is missing" >&2
		exit 1
	fi
	[ -n "$4" ] || usage
	verify_wsl
	write_receipt "$2" "$3" "$4" "$5" wsl
	exit 0
fi
[ "$#" -eq 3 ] || usage
personal_tree=$1
commercial_tree=$2
version=$3
[ -n "$version" ] || usage
validate_version "$version"
if [ ! -d "$personal_tree" ] || [ ! -d "$commercial_tree" ]; then
	echo "Staged release tree is missing" >&2
	exit 1
fi
detect_platform
verify_current_platform "$personal_tree" "$commercial_tree" "$platform"
receipt_directory="${CAT_PLATFORM_RECEIPT_DIRECTORY:-}"
[ -n "$receipt_directory" ] || {
	echo "Platform-matrix receipts are unavailable; set CAT_PLATFORM_RECEIPT_DIRECTORY" >&2
	exit 77
}
for required_platform in linux-x86_64 linux-arm64 macos-x86_64 macos-arm64; do
	verify_receipt "$receipt_directory/$required_platform.json" "$version" "$required_platform" native
done
verify_receipt "$receipt_directory/wsl-linux-x86_64.json" "$version" "linux-x86_64" wsl
echo "Platform matrix passed: version=$version current_platform=$platform"
