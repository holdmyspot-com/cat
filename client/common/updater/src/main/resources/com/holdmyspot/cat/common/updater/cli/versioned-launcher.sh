#!/bin/sh
# Runs this updater image with fixed JVM options; environment-provided JVM options are discarded.
# Arguments: the updater operation and its arguments.
# Output: one updater JSON result.
# Exit status: the updater operation's status.
set -eu
unset JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS CLASSPATH JLINK_VM_OPTIONS
launcher_dir=${0%/*}
image=$(CDPATH= cd -P "$launcher_dir/.." && pwd -P)
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
exec "$image/bin/java" -Xshare:auto -Xlog:all=off:stdout -Xlog:all=warning:stderr -XX:+PerfDisableSharedMem -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Djdk.http.auth.tunneling.disabledSchemes= --enable-native-access=com.holdmyspot.cat.common.cli -Djava.util.logging.config.file="$image/conf/logging.properties" -m com.holdmyspot.cat.common.updater/com.holdmyspot.cat.common.updater.cli.UpdaterMain "$@"
