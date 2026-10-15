@echo off
setlocal
rem Starts CAT's client\bin\run-part from the installed plugin that contains this launcher. Agents run run-part from
rem the plugin's bin folder, and the Java runtime that runs it lives in the plugin's client folder.
"%~dp0..\client\bin\run-part.bat" %*
