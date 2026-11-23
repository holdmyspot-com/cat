# Test Cache Access

## Design Goals

- CAT's sandboxed tests have read and write access to the dependency caches they use, as verified from the test
  environment. Tests that intentionally exercise cache denial or absence retain that condition.

## Guidance

Give CAT's test profiles read and write access to the dependency caches their tools use, including npm and Maven caches.
Determine those paths from the test process's effective home and tool configuration. If a cache path is a symlink, grant
access to its resolved directory. Grant only the cache directories in the test profile.

Before the first test launch in a profile, and after changing the profile or cache paths, verify from that test
environment that it can read and write the caches. Keep a deliberate cache denial or missing-cache condition when the
test exercises it. This authorization applies to CAT's test profiles.
