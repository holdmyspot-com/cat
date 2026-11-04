@echo off
setlocal
rem Starts CAT's bin\subagent-start hook from the installed plugin that contains this launcher.
for %%I in ("%~dp0..") do set "CAT_INSTALL_DIR=%%~fI"
"%CAT_INSTALL_DIR%\bin\subagent-start.cmd"
