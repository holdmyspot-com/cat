#!/bin/sh
# Starts the installed updater selected by current, with only the documented environment variables.
# Arguments: the updater operation and its arguments, forwarded without interpretation.
# Output: the selected updater's JSON result.
# Exit status: the selected updater's status, or 3 when current is malformed.
set -eu
launcher_dir=${0%/*}
data=$(CDPATH= cd -P "$launcher_dir/.." && pwd -P)
version=
extra=
{
	IFS= read -r version || [ -n "$version" ] || exit 3
	if IFS= read -r extra || [ -n "$extra" ]; then
		printf '%s\n' 'Invalid updater current file: expected one version line.' >&2
		exit 3
	fi
} < "$data/current"
case "$version" in
	''|*[!0-9.]*|.*|*.|*..*|*.*.*.*)
		printf '%s\n' 'Invalid updater current file: expected two or three decimal numbers.' >&2
		exit 3 ;;
	*.*) ;;
	*) exit 3 ;;
esac
set -- "$data/$version/bin/cat-updater" "$@"
if [ "${no_proxy+x}" = x ]; then set -- "no_proxy=$no_proxy" "$@"; fi
if [ "${NO_PROXY+x}" = x ]; then set -- "NO_PROXY=$NO_PROXY" "$@"; fi
if [ "${https_proxy+x}" = x ]; then set -- "https_proxy=$https_proxy" "$@"; fi
if [ "${HTTPS_PROXY+x}" = x ]; then set -- "HTTPS_PROXY=$HTTPS_PROXY" "$@"; fi
if [ "${LC_ALL+x}" = x ]; then set -- "LC_ALL=$LC_ALL" "$@"; fi
if [ "${LANG+x}" = x ]; then set -- "LANG=$LANG" "$@"; fi
if [ "${PATH+x}" = x ]; then set -- "PATH=$PATH" "$@"; fi
if [ "${SYSTEMROOT+x}" = x ]; then set -- "SYSTEMROOT=$SYSTEMROOT" "$@"; fi
if [ "${USERPROFILE+x}" = x ]; then set -- "USERPROFILE=$USERPROFILE" "$@"; fi
if [ "${LOCALAPPDATA+x}" = x ]; then set -- "LOCALAPPDATA=$LOCALAPPDATA" "$@"; fi
if [ "${XDG_CONFIG_HOME+x}" = x ]; then set -- "XDG_CONFIG_HOME=$XDG_CONFIG_HOME" "$@"; fi
if [ "${XDG_DATA_HOME+x}" = x ]; then set -- "XDG_DATA_HOME=$XDG_DATA_HOME" "$@"; fi
if [ "${HOME+x}" = x ]; then set -- "HOME=$HOME" "$@"; fi
exec /usr/bin/env -i "$@"
