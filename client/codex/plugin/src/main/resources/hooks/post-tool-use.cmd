@echo off
setlocal
rem Starts CAT's client\bin\post-tool-use hook from the installed plugin that contains this launcher. Codex runs the
rem hook's PowerShell command after every shell command, and that command starts this launcher only for an event that
rem mentions run-part or read-part, because a batch file cannot read the event and pass it on unchanged.
"%~dp0..\client\bin\post-tool-use.bat"
