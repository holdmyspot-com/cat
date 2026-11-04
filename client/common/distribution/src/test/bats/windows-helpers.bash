# Helpers for the Bats files that test CAT's Windows launchers under Git Bash: they run Windows PowerShell as CAT's
# callers do, build the fake java.exe that stands in for a Java runtime, and create the junctions that a Windows file
# system can hold.

# Runs a Windows PowerShell script as an encoded command, which reaches PowerShell unchanged whatever quotes it holds.
#
# Arguments:
#   $1 - The script.
#   $2... - NAME=VALUE environment assignments that the script reads as $env:NAME; a value that names a file must be
#     in its Windows form.
#
# Output:
#   Writes what the script writes; standard input is the caller's.
#
# Exit status:
#   Returns PowerShell's exit status.
powershell_command()
{
	local script="$1"
	shift
	local encoded
	encoded=$(printf '%s' "${script}" | iconv -f UTF-8 -t UTF-16LE | base64 | tr -d '\r\n')
	env "$@" powershell.exe -NoProfile -NonInteractive -EncodedCommand "${encoded}"
}

# Runs a PowerShell script file through -File, as CAT's Windows commands start the updater's launchers.
#
# Arguments:
#   $1 - The script file.
#   $2... - The script's arguments.
#
# Output:
#   Writes the script's standard output and standard error; standard input is empty.
#
# Exit status:
#   Returns the script's exit status.
powershell_file()
{
	local script
	script=$(cygpath -w "$1")
	shift
	powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "${script}" "$@" < /dev/null
}

# Compiles the fake java.exe from fake-java.cs, which describes what the fake does, with the C# compiler of the .NET
# Framework that Windows PowerShell runs on.
#
# Arguments:
#   $1 - The file to write the program to.
#
# Errors:
#   Writes the compiler's diagnostics to standard error.
#
# Exit status:
#   Returns 0 when the program was written.
build_fake_java()
{
	powershell_command \
		'Add-Type -LiteralPath $env:FAKE_JAVA_SOURCE -OutputAssembly $env:FAKE_JAVA -OutputType ConsoleApplication' \
		"FAKE_JAVA_SOURCE=$(cygpath -w "${BATS_TEST_DIRNAME}/fake-java.cs")" \
		"FAKE_JAVA=$(cygpath -w "$1")" < /dev/null
	[ -f "$1" ]
}

# Creates a directory junction, the reparse point that any Windows user can create.
#
# Arguments:
#   $1 - The junction to create.
#   $2 - The existing directory that it points to.
#
# Exit status:
#   Returns 0 when the junction was created.
create_junction()
{
	powershell_command '$null = New-Item -ItemType Junction -Path $env:JUNCTION -Target $env:JUNCTION_TARGET' \
		"JUNCTION=$(cygpath -w "$1")" "JUNCTION_TARGET=$(cygpath -w "$2")" < /dev/null
}

# Writes the Windows directory, which CAT's Windows launchers take from the operating system.
#
# Output:
#   Writes the directory's Windows form.
windows_directory()
{
	powershell_command '[Environment]::GetFolderPath([Environment+SpecialFolder]::Windows)' < /dev/null | tr -d '\r'
}
