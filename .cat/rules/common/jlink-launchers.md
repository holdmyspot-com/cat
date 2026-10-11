---
paths:
  - "client/**"
---
# Jlink Launchers

## Design Goals

- Let every program that a generated jlink launcher starts name any file, whatever the caller's locale.
- Give every program the same view of the file system as its caller, so files it writes for a later call or reads on the
  caller's behalf are where the caller expects them.
- Test the launcher template that all jlink launchers share once, rather than once for each program or harness.

## Guidance

Every jlink launcher runs Java in a UTF-8 `LC_CTYPE`, because Java names files and decodes its arguments in the
character set of `LC_CTYPE`, and the C locale cannot name a file whose name is not ASCII. Generate this in the template
that `JlinkImageBuilder` writes for every launcher, not in one launcher or program. When the caller's `LC_CTYPE` is
already UTF-8, the launcher keeps the caller's locale. Otherwise it saves the caller's `LC_ALL` and `LC_CTYPE` in
`CAT_CALLER_LC_ALL` and `CAT_CALLER_LC_CTYPE`, unsets `LC_ALL` and sets `LC_CTYPE=C.UTF-8`. A program that runs a
command on the caller's behalf, such as `run-part`, restores the caller's `LC_ALL` and `LC_CTYPE` from those variables
in the command's environment and removes them.

Every jlink launcher shares the caller's `/tmp`. When Bubblewrap is available, the template mounts an empty temporary
filesystem over only the JVM's performance-data directory, `/tmp/hsperfdata_<user>`, because JVMs in separate PID
namespaces can have the same PID and would otherwise write the same file there. Do not hide more of the file system
from a launcher, give one launcher a different template, or disable the JVM's performance data instead.

One Bats test, `jlink-launchers.bats`, verifies the launcher template for all launchers against a built image. Do not
add a Bats test for a launcher or a harness merely to show that its launcher starts its program, runs from a path with
spaces, or works in another locale; extend that test instead when the template changes. Test a program's own behavior,
such as its arguments, output and exit status, with Java tests that call it directly.
