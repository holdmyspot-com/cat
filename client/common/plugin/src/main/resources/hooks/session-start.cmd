@echo off
setlocal
rem Starts CAT's client\bin\session-start hook from the installed plugin that contains this launcher.
for %%I in ("%~dp0..") do set "CAT_INSTALL_DIR=%%~fI"
"%CAT_INSTALL_DIR%\client\bin\session-start.bat"
