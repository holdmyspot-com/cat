# The Java options that every launcher which starts CAT's installer passes to the installer's Java runtime, before the
# installer operation's own arguments: the plugin's POSIX installer launcher, which installer-launcher.bats runs, and
# the plugin's PowerShell installer launcher, which installer-launcher-windows.bats runs. Both files compare what their
# fake java receives with this one list, so that the installer runs alike whichever launcher the platform uses.

# Prints the Java options that an installer launcher passes, one per line.
#
# Arguments:
#   $1 - The installer runtime's logging configuration file, in the form that names it to Java.
#   $2 - The installer's application module directory, in the form that names it to Java.
#
# Output:
#   Writes the options in the order that the launchers pass them.
installer_java_options()
{
	printf '%s\n' -Xshare:auto -Xlog:all=off:stdout -Xlog:all=warning:stderr -XX:+PerfDisableSharedMem \
		-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Djdk.http.auth.tunneling.disabledSchemes= \
		--enable-native-access=com.holdmyspot.cat.common.cli "-Djava.util.logging.config.file=$1" --module-path "$2" \
		--add-modules org.slf4j.nop -m com.holdmyspot.cat.common.installer/com.holdmyspot.cat.common.installer.cli.InstallerMain
}
