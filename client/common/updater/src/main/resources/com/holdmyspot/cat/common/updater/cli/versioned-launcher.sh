#!/bin/sh
# Runs this updater version on its installation's runtime beside its version directory, with fixed JVM options;
# environment-provided JVM options are discarded.
# Arguments: the updater operation and its arguments.
# Output: one updater JSON result.
# Exit status: the updater operation's status, or 3 when the installation's runtime has no executable java.
set -eu
unset JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS CLASSPATH JLINK_VM_OPTIONS
# The version directory is found from this file's real location, so a symbolic link to the launcher elsewhere still
# runs the application and runtime that belong to it. The links are read with a fixed search path, so the caller's
# PATH cannot choose that program; the updater still receives the caller's PATH and working directory, because setup
# finds the harness program on that PATH and refuses data locations inside that directory.
caller_path=${PATH+"+$PATH"}
PATH=/usr/bin:/bin
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
case $caller_path in
	+*) PATH=${caller_path#+} ;;
	*) unset PATH ;;
esac
version_dir=$(CDPATH='' cd -P -- "${launcher%/*}/.." && pwd -P)
data=$(CDPATH='' cd -P -- "$version_dir/.." && pwd -P)
java=$data/runtime/bin/java
if [ ! -f "$java" ] || [ ! -x "$java" ]; then
	printf 'CAT updater runtime is missing: %s is not an executable file; rerun the CAT installation command.\n' \
		"$java" >&2
	exit 3
fi
case ${LC_ALL:-${LC_CTYPE:-${LANG:-}}} in
	*[Uu][Tt][Ff]-8* | *[Uu][Tt][Ff]8*) ;;
	*)
		CAT_CALLER_LC_ALL=${LC_ALL+"+$LC_ALL"}
		CAT_CALLER_LC_CTYPE=${LC_CTYPE+"+$LC_CTYPE"}
		export CAT_CALLER_LC_ALL="${CAT_CALLER_LC_ALL:--}"
		export CAT_CALLER_LC_CTYPE="${CAT_CALLER_LC_CTYPE:--}"
		unset LC_ALL
		export LC_CTYPE=C.UTF-8
		;;
esac
exec "$java" -Xshare:auto -Xlog:all=off:stdout -Xlog:all=warning:stderr -XX:+PerfDisableSharedMem \
	-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Djdk.http.auth.tunneling.disabledSchemes= \
	--enable-native-access=com.holdmyspot.cat.common.cli \
	-Djava.util.logging.config.file="$data/runtime/conf/logging.properties" \
	--module-path "$version_dir/app" --add-modules org.slf4j.nop \
	-m com.holdmyspot.cat.common.updater/com.holdmyspot.cat.common.updater.cli.UpdaterMain "$@"
