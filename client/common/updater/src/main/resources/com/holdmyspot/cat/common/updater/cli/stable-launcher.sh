#!/bin/sh
# Starts the installed updater selected by current, from the root directory with PATH set to /usr/bin:/bin and only the
# documented environment variables, and completes a complete uninstall by deleting the updater data directory after
# the updater's JVM has exited.
# Arguments: the updater operation and its arguments, forwarded without interpretation.
# Output: the selected updater's JSON result; after a complete uninstall, the result that the updater saved, printed
#   only once the data directory no longer exists, or a not_removed result naming the directory.
# Exit status: the selected updater's status; 0 after a complete uninstall whose data directory was deleted; 7 with a
#   not_removed result, deleting nothing more, when that directory could not be deleted, when it or its parent is a
#   symbolic link, or when the updater saved no readable result, in which case the directory's path is written to
#   standard error; or 3 when current is malformed.
set -eu
# A fixed search path and working directory keep the caller's PATH and current directory from choosing a program that
# this launcher or the updater runs. External programs are named by absolute path or found on that fixed path.
PATH=/usr/bin:/bin
export PATH
# The data directory is found from this file's real location, so a symbolic link to the launcher elsewhere can
# neither select another installation nor make uninstall delete the directory that holds the link.
launcher=$0
case $launcher in
	*/*) ;;
	*) launcher=./$launcher ;;
esac
while [ -L "$launcher" ]; do
	target=$(readlink -- "$launcher")
	case $target in
		/*) launcher=$target ;;
		*) launcher=${launcher%/*}/$target ;;
	esac
done
data=$(CDPATH='' cd -P -- "${launcher%/*}/.." && pwd -P)
# The data directory as the launcher's path names it, which differs from data when that path passes through a link.
named_data=$(CDPATH='' cd -L -- "${launcher%/*}/.." && pwd -L)
cd /
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
set -- "PATH=$PATH" "$@"
if [ "${SYSTEMROOT+x}" = x ]; then set -- "SYSTEMROOT=$SYSTEMROOT" "$@"; fi
if [ "${USERPROFILE+x}" = x ]; then set -- "USERPROFILE=$USERPROFILE" "$@"; fi
if [ "${LOCALAPPDATA+x}" = x ]; then set -- "LOCALAPPDATA=$LOCALAPPDATA" "$@"; fi
if [ "${XDG_CONFIG_HOME+x}" = x ]; then set -- "XDG_CONFIG_HOME=$XDG_CONFIG_HOME" "$@"; fi
if [ "${XDG_DATA_HOME+x}" = x ]; then set -- "XDG_DATA_HOME=$XDG_DATA_HOME" "$@"; fi
if [ "${HOME+x}" = x ]; then set -- "HOME=$HOME" "$@"; fi
# The updater runs as a child rather than replacing this shell, because a complete uninstall ends with work that must
# wait until its JVM has exited.
status=0
/usr/bin/env -i "$@" || status=$?
# Status 79 means that the updater finished a complete uninstall, saved its final result in the data directory, and
# left the deletion of that directory, including the runtime it ran on, to this launcher.
if [ "$status" -ne 79 ]; then
	exit "$status"
fi
# Writes text with each backslash and quotation mark escaped for a JSON string.
# Arguments: the text, which contains no control characters.
# Output: the escaped text.
json_escape()
{
	rest=$1
	escaped=
	while [ -n "$rest" ]; do
		character=${rest%"${rest#?}"}
		rest=${rest#?}
		case $character in
			\\ | \") escaped=$escaped\\$character ;;
			*) escaped=$escaped$character ;;
		esac
	done
	printf '%s' "$escaped"
}

# Reports that the data directory still exists after a complete uninstall and ends the launcher.
# Environment: named_data, the data directory as the launcher's path names it.
# Output: the not_removed JSON result naming the data directory.
# Errors: writes the data directory's path to standard error.
# Exit status: 7.
not_removed()
{
	printf '{"result":"not_removed","message":"CAT could not delete %s"}\n' "$(json_escape "$named_data")"
	printf '%s\n' "$named_data" >&2
	exit 7
}
result_file=$data/state/uninstall-result.json
# The trailing x keeps the result's final newlines, which command substitution would otherwise remove.
if [ ! -f "$result_file" ] || ! result=$(/bin/cat -- "$result_file" 2>/dev/null && printf x) || [ "$result" = x ]; then
	printf 'The updater saved no readable uninstall result in %s; nothing was deleted.\n' "$result_file" >&2
	not_removed
fi
result=${result%x}
# Deleting through a link would remove a directory other than the one the installation recorded, so a data directory
# or parent that is a symbolic link is refused. rm does not follow symbolic links inside the tree it deletes.
data_parent=${named_data%/*}
if [ -L "$named_data" ] || [ -L "${data_parent:-/}" ]; then
	printf 'Refusing to delete %s through a symbolic link.\n' "$named_data" >&2
	not_removed
fi
/bin/rm -rf -- "$data" 2>/dev/null || true
if [ -e "$data" ] || [ -L "$data" ]; then
	not_removed
fi
printf '%s' "$result"
exit 0
