#!/bin/sh
# Starts the installed installer selected by current, from the root directory with PATH set to /usr/bin:/bin and only
# the documented environment variables. After the installer's JVM has exited, it puts the Java runtime that an update
# staged in place and selects that update's installer version, or completes a complete uninstall by deleting the
# installer data directory, then the directory that holds it when that leaves it empty. Before it starts the installer,
# it puts back a runtime that a launcher which stopped during a runtime replacement had moved aside.
# An update renames the stable launcher of the release that it selects over this file while this launcher runs, and the
# launcher that is running, not the new one, finishes the runtime replacement; after a replacement that was undone, a
# launcher can also run an earlier release's installer. Every release therefore keeps the replacement request's first
# four lines (operation, the version that current selects, the version to select, the process identifier of the
# finishing launcher) and the meaning of exit status 78, so that a launcher of any release finishes the request of
# another; a release may add only lines after the fourth.
# Arguments: the installer operation and its arguments, forwarded without interpretation.
# Output: the selected installer's JSON result; after a runtime replacement, the result that the installer saved,
#   printed only once the new runtime runs and current selects the new version, or a not_swapped result; after a
#   complete uninstall, the result that the installer saved, printed only once the data directory no longer exists, or a
#   not_removed result naming the directory; or a locked result while another launcher replaces the runtime. The
#   not_swapped, not_removed and locked results, which this launcher prints itself, hold only the members result and
#   message.
# Exit status: the selected installer's status; 0 after a runtime replacement or a complete uninstall that succeeded; 8
#   with a not_swapped result, after restoring the runtime that was in place, when the replacement request is invalid,
#   the installer saved no readable result, a runtime cannot be renamed, the new runtime's java -version fails or
#   current cannot be replaced, in which case a diagnostic is written to standard error; 7 with a not_removed result,
#   deleting nothing more, when the data directory could not be deleted, when it or its parent is a symbolic link, or
#   when the installer saved no readable result, in which case the directory's path is written to standard error; 75
#   with a locked result when the runtime is missing while another launcher replaces it; or 3 when current is malformed.
# Limitation: the launcher that a replacement request names counts as running while any process has its process
#   identifier, because this launcher does not compare the start instant on the request's fifth line.
set -eu
# A fixed search path and working directory keep the caller's PATH and current directory from choosing a program that
# this launcher or the installer runs. External programs are named by absolute path or found on that fixed path.
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

# Indicates whether text is a canonical release version of two or three decimal numbers.
# Arguments: the text.
# Exit status: 0 for a version, otherwise 1.
is_version()
{
	case $1 in
		''|*[!0-9.]*|.*|*.|*..*|*.*.*.*) return 1 ;;
		*.*) return 0 ;;
		*) return 1 ;;
	esac
}

version=
extra=
{
	IFS= read -r version || [ -n "$version" ] || exit 3
	if IFS= read -r extra || [ -n "$extra" ]; then
		printf '%s\n' 'Invalid installer current file: expected one version line.' >&2
		exit 3
	fi
} < "$data/current"
if ! is_version "$version"; then
	printf '%s\n' 'Invalid installer current file: expected two or three decimal numbers.' >&2
	exit 3
fi
swap_request=$data/state/runtime-swap
swap_result=$data/state/runtime-swap-result.json

# Reads the pending runtime replacement request that the installer wrote before it exited with status 78. The request
# holds one value per line: the operation, the version that current selects, the version to select, and the process
# identifier of the launcher that finishes the replacement; later lines are the installer's own.
# Side effects: sets swap_operation, swap_from, swap_to and swap_launcher, and swap_staged and swap_kept to the runtime
#   that the replacement puts in place and the location to which it moves the replaced runtime.
# Exit status: 0 when the request names an update away from the version that current selects, otherwise 1.
read_swap()
{
	swap_operation=
	swap_from=
	swap_to=
	swap_launcher=
	if [ ! -f "$swap_request" ] || [ -L "$swap_request" ]; then
		return 1
	fi
	{
		IFS= read -r swap_operation && IFS= read -r swap_from && IFS= read -r swap_to && IFS= read -r swap_launcher
	} < "$swap_request" || return 1
	case $swap_operation in
		update)
			swap_staged=$data/runtime.next
			swap_kept=$data/runtime.previous ;;
		*) return 1 ;;
	esac
	case $swap_launcher in
		''|*[!0-9]*) return 1 ;;
	esac
	[ "$swap_from" = "$version" ] && is_version "$swap_to" && [ "$swap_to" != "$swap_from" ]
}

# A launcher that stopped between its two renames left no runtime in place. While that launcher still runs, it is
# finishing the replacement; otherwise the runtime that it moved aside is put back, and the installer then undoes or
# completes the rest of the replacement. POSIX offers no portable way to read a process's start instant, so the
# process identifier alone tells whether that launcher runs, unlike on Windows, and an unrelated process that reuses
# the identifier keeps this launcher reporting locked until that process exits.
if [ ! -e "$data/runtime" ] && [ ! -L "$data/runtime" ] && read_swap; then
	if [ "$swap_launcher" -gt 0 ] && kill -0 "$swap_launcher" 2>/dev/null; then
		printf '{"result":"locked","message":"Another CAT launcher is replacing the Java runtime in %s; retry once it has finished."}\n' \
			"$(json_escape "$named_data")"
		exit 75
	fi
	if [ -d "$swap_kept" ] && [ ! -L "$swap_kept" ]; then
		/bin/mv -- "$swap_kept" "$data/runtime" 2>/dev/null || true
	fi
fi
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
# The installer runs as a child rather than replacing this shell, because a runtime replacement and a complete
# uninstall end with work that must wait until its JVM has exited.
status=0
/usr/bin/env -i "$@" || status=$?

# Reports that the runtime replacement failed and ends the launcher. The request stays, so that the next installer
# operation undoes the rest of the operation; the saved result is deleted, because it reports an outcome that did not
# happen.
# Arguments: the diagnostic.
# Environment: named_data, the data directory as the launcher's path names it.
# Output: the not_swapped JSON result naming the data directory.
# Errors: writes the diagnostic to standard error.
# Exit status: 8.
not_swapped()
{
	/bin/rm -f -- "$swap_result" 2>/dev/null || true
	printf '{"result":"not_swapped","message":"CAT could not switch the Java runtime in %s; %s%s%s"}\n' \
		"$(json_escape "$named_data")" \
		"the operation did not finish. Run install --harness <h> with this launcher at once: it undoes the " \
		"operation and puts back the previous release, so that CAT's hooks work again. Then tell the user that the " \
		"operation could not complete, quoting this message, and quote the install's message too if it fails."
	printf '%s\n' "$1" >&2
	exit 8
}

# Moves the replacement's runtime back to where the installer staged it and the replaced runtime back into place.
# Exit status: 0 when both moves succeeded.
restore_runtime()
{
	/bin/mv -- "$data/runtime" "$swap_staged" 2>/dev/null && /bin/mv -- "$swap_kept" "$data/runtime" 2>/dev/null
}

# Status 78 means that the installer finished an update, saved its final result in the data directory, and
# left putting the runtime that the operation staged in place, which the installer's own JVM runs on, to this launcher.
if [ "$status" -eq 78 ]; then
	if ! read_swap; then
		not_swapped "The runtime replacement request $swap_request is missing or invalid; nothing was replaced."
	fi
	# The trailing x keeps the result's final newlines, which command substitution would otherwise remove.
	if [ ! -f "$swap_result" ] || ! result=$(/bin/cat -- "$swap_result" 2>/dev/null && printf x) ||
		[ "$result" = x ]; then
		not_swapped "The installer saved no readable result in $swap_result; nothing was replaced."
	fi
	result=${result%x}
	if [ ! -d "$swap_staged" ] || [ -L "$swap_staged" ] || [ -e "$swap_kept" ] || [ -L "$swap_kept" ]; then
		not_swapped "The runtime $swap_staged is missing or $swap_kept exists; nothing was replaced."
	fi
	if ! /bin/mv -- "$data/runtime" "$swap_kept" 2>/dev/null; then
		not_swapped "The runtime $data/runtime could not be moved aside; nothing was replaced."
	fi
	if ! /bin/mv -- "$swap_staged" "$data/runtime" 2>/dev/null; then
		/bin/mv -- "$swap_kept" "$data/runtime" 2>/dev/null || true
		not_swapped "The runtime $swap_staged could not be moved into place; the previous runtime was restored."
	fi
	if ! /usr/bin/env -i "PATH=$PATH" "$data/runtime/bin/java" -version > /dev/null 2>&1; then
		restore_runtime || true
		not_swapped "The new runtime's java -version failed; the previous runtime was restored."
	fi
	# Selecting the new version through a rename makes current name either version, never a partial line.
	selection=$data/.current.next
	if ! { printf '%s\n' "$swap_to" > "$selection" && /bin/mv -f -- "$selection" "$data/current"; } 2>/dev/null; then
		/bin/rm -f -- "$selection" 2>/dev/null || true
		restore_runtime || true
		not_swapped "The installer version $swap_to could not be selected; the previous runtime was restored."
	fi
	# The replacement is complete; a request or directory that stays is deleted by the next installer operation.
	/bin/rm -f -- "$swap_request" 2>/dev/null || true
	/bin/rm -f -- "$swap_result" 2>/dev/null || true
	printf '%s' "$result"
	exit 0
fi
# Status 79 means that the installer finished a complete uninstall, saved its final result in the data directory, and
# left the deletion of that directory, including the runtime it ran on, to this launcher.
if [ "$status" -ne 79 ]; then
	exit "$status"
fi

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
	printf 'The installer saved no readable uninstall result in %s; nothing was deleted.\n' "$result_file" >&2
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
# The directory that holds every harness's installation is removed when this was its last one. rmdir removes only an
# empty directory, and a failure leaves the result unchanged.
/bin/rmdir -- "${data%/*}" 2>/dev/null || true
printf '%s' "$result"
exit 0
