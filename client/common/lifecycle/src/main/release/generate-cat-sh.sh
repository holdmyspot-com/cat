#!/usr/bin/env sh
# Writes the entry script of one CAT release by filling the release's tag and SHA-256 digests into the cat.sh template.
#
#   generate-cat-sh.sh TEMPLATE TAG SHA256SUMS OUTPUT
#
# TAG is a release tag such as v1.0 or v1.0.1. SHA256SUMS holds one line per release asset, a lowercase SHA-256 digest
# in hexadecimal, two spaces and the asset's name, as sha256sum writes it. The script refuses a malformed tag or line,
# and a template that does not hold each placeholder exactly once, without writing OUTPUT.
set -eu

# Prints this script's usage and exits with the command-line usage status.
#
# Errors:
#   Writes the usage to standard error.
#
# Exit status:
#   Exits with status 2.
cat_print_usage()
{
	echo "Usage: $0 TEMPLATE TAG SHA256SUMS OUTPUT" >&2
	exit 2
}

# Counts the lines of a file that contain a fixed string.
#
# Arguments:
#   $1 - The string.
#   $2 - The file.
#
# Output:
#   Writes the number of lines to standard output.
cat_count_lines_containing()
{
	awk -v cat_text="$1" 'index($0, cat_text) { ++cat_count } END { print cat_count + 0 }' "$2"
}

[ "$#" -eq 4 ] || cat_print_usage
cat_template="$1"
cat_tag="$2"
cat_sums="$3"
cat_output="$4"

# grep matches each line on its own, so a tag that spans lines is refused before grep sees it
case "${cat_tag}" in
	*"
"*) cat_tag_valid=false ;;
	*)
		cat_tag_valid=false
		if printf '%s\n' "${cat_tag}" | grep -Eqx 'v[0-9]+\.[0-9]+(\.[0-9]+)?'; then
			cat_tag_valid=true
		fi
		;;
esac
[ "${cat_tag_valid}" = true ] || {
	echo "The release tag must look like v1.0 or v1.0.1: ${cat_tag}" >&2
	exit 1
}
[ -s "${cat_sums}" ] || {
	echo "The SHA-256 list is missing or empty: ${cat_sums}" >&2
	exit 1
}
# Asset names hold only characters that need no quoting, so the list can sit inside the script's single quotes
cat_malformed="$(grep -Evxn '[0-9a-f]{64}  [A-Za-z0-9][A-Za-z0-9._-]*' "${cat_sums}" | head -n 1)" || true
[ -z "${cat_malformed}" ] || {
	echo "The SHA-256 list holds a malformed line, which must be a lowercase digest, two spaces and an asset name:" \
		"${cat_malformed}" >&2
	exit 1
}
for cat_placeholder in @CAT_RELEASE_TAG@ @CAT_SHA256SUMS@; do
	[ "$(cat_count_lines_containing "${cat_placeholder}" "${cat_template}")" -eq 1 ] || {
		echo "The template must hold ${cat_placeholder} on exactly one line: ${cat_template}" >&2
		exit 1
	}
done

cat_partial="${cat_output}.partial.$$"
trap 'rm -f -- "${cat_partial}"' EXIT
awk -v cat_tag="${cat_tag}" -v cat_sums_file="${cat_sums}" '
	BEGIN {
		cat_sums = ""
		while ((getline cat_line < cat_sums_file) > 0)
			cat_sums = cat_sums == "" ? cat_line : cat_sums "\n" cat_line
	}
	{
		cat_line = $0
		cat_at = index(cat_line, "@CAT_RELEASE_TAG@")
		if (cat_at)
			cat_line = substr(cat_line, 1, cat_at - 1) cat_tag substr(cat_line, cat_at + length("@CAT_RELEASE_TAG@"))
		cat_at = index(cat_line, "@CAT_SHA256SUMS@")
		if (cat_at)
			cat_line = substr(cat_line, 1, cat_at - 1) cat_sums substr(cat_line, cat_at + length("@CAT_SHA256SUMS@"))
		print cat_line
	}' "${cat_template}" > "${cat_partial}"
mv -- "${cat_partial}" "${cat_output}"
