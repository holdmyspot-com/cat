# Jlink Launcher Arguments

## Design Goals

- Keep every argument that reaches a CAT launcher on Windows the text its caller intended.

## Guidance

A batch file expands `%NAME%` in its own arguments when `NAME` is a defined variable, and an unquoted `&` in an
argument ends the command, so a caller must not pass untrusted text to a batch launcher as an argument; pass it on
standard input or in a file instead.
