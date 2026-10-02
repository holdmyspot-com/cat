# Java Portability

## Design Goals

- Java code, including its tests, behaves the same on every operating system the project deploys to, as shown by
  passing on each of those systems.
- Operating-system behavior comes from Java platform or project APIs, not from an external program started only
  because it happens to provide that behavior on the developer's system.

## Guidance

Before relying on behavior that can differ between operating systems, identify the systems the project deploys to from
its authoritative deployment configuration, such as its release or CI platform matrix.

Do not use `ProcessBuilder`, `Runtime.exec`, or another process launcher as an escape hatch for operating-system
behavior. Use the Java API that owns the behavior: for example, `Files` instead of starting `cp`, `mkdir`, or `chmod`,
and `ProcessHandle` instead of starting `ps` or `kill -TERM`.

When no Java API provides the behavior, use a portable interface of the component that must receive it, such as a
request over that component's own input stream, before considering an operating-system mechanism. If none exists,
isolate the platform-specific call in one named method, document which systems it supports and why, and fail with a
clear diagnostic on any other system.

Start an external program only when that program is itself the required behavior, such as a version-control tool or
the harness being driven. Pass its arguments directly rather than through a shell, and use only options that the program
supports on every target system.

In a test, replace an external program with a Java fake or an injected collaborator rather than a shell script. When a
test needs an operating-system mechanism only to reach the behavior under test, inject that mechanism through the
production seam instead. For example, a test that started its fake through `env --default-signal=INT`, a GNU extension
that macOS `env` lacks, hung on macOS; sending the interrupt as a request on the harness's input stream removed the
signal from both the product and the test.

A passing run on one operating system does not establish portability. Verify on each target system, for example
through the CI platform matrix.
