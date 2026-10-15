@echo off
setlocal
rem Starts CAT's client\bin\post-tool-use hook from the installed plugin that contains this launcher. Codex runs this
rem hook after every shell command. The hook changes only the result of a command that runs run-part or read-part,
rem and it receives every other event too, because a batch file cannot read the event and pass it on unchanged.
"%~dp0..\client\bin\post-tool-use.bat"
