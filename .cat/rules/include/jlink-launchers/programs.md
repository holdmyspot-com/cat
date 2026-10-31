# Programs That Jlink Launchers Start

## Design Goals

- Let every program that a jlink launcher starts read any file name or text from its arguments, and run a command on
	its caller's behalf in the caller's own locale.

## Guidance

When the caller's `LC_CTYPE` is not UTF-8, a jlink launcher saves the caller's `LC_ALL` and `LC_CTYPE` in
`CAT_CALLER_LC_ALL` and `CAT_CALLER_LC_CTYPE`, unsets `LC_ALL` and sets `LC_CTYPE=C.UTF-8`. A program that runs a
command on the caller's behalf, such as `run-part`, restores the caller's `LC_ALL` and `LC_CTYPE` from those variables
in the command's environment and removes them.

On Windows, the `java` launcher reads its command line in the system's ANSI code page and replaces each character that
the code page lacks; every `main` that reads file names or text from its arguments passes them through
`CommandLineArguments.recover`, which reads them again from the process's UTF-16 command line.
