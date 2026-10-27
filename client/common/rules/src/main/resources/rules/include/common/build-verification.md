# Build Verification

## Design Goals

- Ensure a claim that a project build passes is supported by the complete build scope rather than a faster diagnostic
	subset, with a clean full run of the revision being claimed.

## Guidance

Before claiming that a project's build passes, identify its complete documented build command and run that command
without module, package, test, or other scope selectors unless the documented command itself requires them. A
selected-module build is diagnostic evidence only: it can localize a failure or validate a changed component, but cannot
establish that the whole build passes. Record diagnostic command scope separately and run the complete build before
passing the build gate. Run it from a clean start on the same revision being claimed; an incremental run is diagnostic
evidence only, even when it completes the entire workflow. A clean run must rebuild the workflow's outputs rather than
reuse outputs or cached build results from earlier attempts.
