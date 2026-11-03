@echo off
rem Starts the current updater after validating its version and discarding JVM injection settings.
setlocal EnableExtensions DisableDelayedExpansion
set "JAVA_TOOL_OPTIONS="
set "JDK_JAVA_OPTIONS="
set "_JAVA_OPTIONS="
set "CLASSPATH="
set "JLINK_VM_OPTIONS="
set "updater_data=%~dp0.."
rem Read with delayed expansion off so the installation path can contain exclamation marks.
set "updater_version="
set /p updater_version=<"%updater_data%\current"
if not defined updater_version exit /b 3
rem Delayed expansion treats the file's text as data rather than shell syntax.
setlocal EnableDelayedExpansion
set "updater_check=!updater_version!"
set "updater_check=!updater_check:0=!"
set "updater_check=!updater_check:1=!"
set "updater_check=!updater_check:2=!"
set "updater_check=!updater_check:3=!"
set "updater_check=!updater_check:4=!"
set "updater_check=!updater_check:5=!"
set "updater_check=!updater_check:6=!"
set "updater_check=!updater_check:7=!"
set "updater_check=!updater_check:8=!"
set "updater_check=!updater_check:9=!"
set "updater_check=!updater_check:.=!"
if defined updater_check exit /b 3
if "!updater_version:~0,1!"=="." exit /b 3
if "!updater_version:~-1!"=="." exit /b 3
if not "!updater_version:..=!"=="!updater_version!" exit /b 3
if "!updater_version:.=!"=="!updater_version!" exit /b 3
for /f "tokens=4 delims=." %%D in ("!updater_version!") do exit /b 3
rem Java checks the complete current file before routine dispatch, including trailing blank lines.
setlocal DisableDelayedExpansion
"%updater_data%\%updater_version%\bin\java.exe" -Xshare:auto -Xlog:all=off:stdout -Xlog:all=warning:stderr -XX:+PerfDisableSharedMem -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Djdk.http.auth.tunneling.disabledSchemes= --enable-native-access=com.holdmyspot.cat.common.cli "-Djava.util.logging.config.file=%updater_data%\%updater_version%\conf\logging.properties" -m com.holdmyspot.cat.common.updater/com.holdmyspot.cat.common.updater.cli.UpdaterMain %*
exit /b %errorlevel%
