# Jlink Launcher Arguments

## Design Goals

- Keep every argument that reaches a CAT launcher on Windows the text its caller intended.

## Guidance

A batch file expands `%NAME%` in its own arguments when `NAME` is a defined variable, and an unquoted `&` in an
argument ends the command. Before passing text as an argument to a Windows batch launcher, establish that the batch
invocation preserves that text literally. When literal handling is unproven, pass the text on standard input or in a
file instead, whether the text is trusted or untrusted.
