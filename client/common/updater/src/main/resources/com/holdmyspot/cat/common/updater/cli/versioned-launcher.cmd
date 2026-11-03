@echo off
rem Runs this image with fixed JVM options and discards environment-provided JVM options.
setlocal DisableDelayedExpansion
set "JAVA_TOOL_OPTIONS="
set "JDK_JAVA_OPTIONS="
set "_JAVA_OPTIONS="
set "CLASSPATH="
set "JLINK_VM_OPTIONS="
"%~dp0java.exe" -Xshare:auto -Xlog:all=off:stdout -Xlog:all=warning:stderr -XX:+PerfDisableSharedMem -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Djdk.http.auth.tunneling.disabledSchemes= --enable-native-access=com.holdmyspot.cat.common.cli "-Djava.util.logging.config.file=%~dp0..\conf\logging.properties" -m com.holdmyspot.cat.common.updater/com.holdmyspot.cat.common.updater.cli.UpdaterMain %*
exit /b %errorlevel%
