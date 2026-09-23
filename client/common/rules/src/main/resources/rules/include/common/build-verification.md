# Build Verification

## Design Goals

- Ensure a claim that a project build passes is supported by the complete build scope rather than a faster diagnostic
  subset.

## Guidance

Before claiming that a project's build passes, identify its complete documented build command and run that command
without module, package, test, or other scope selectors unless the documented command itself requires them. A
selected-module build is diagnostic evidence only: it can localize a failure or validate a changed component, but cannot
establish that the whole build passes. Record diagnostic command scope separately and run the complete build before
passing the build gate.
