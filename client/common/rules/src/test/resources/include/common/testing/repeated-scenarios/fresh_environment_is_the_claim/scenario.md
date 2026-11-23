# Keep a required clean installation check

## Variables

- **artifact_name**: The name of the verified build artifact. Vary the packaged application or utility by name.

## Scenario

The previous check verified an upgrade using `{{artifact_name}}` in a disposable installation. The next check is
whether the same build installs and starts with an empty home. The installation still has configuration and cache state from the
upgrade. Can I reuse that installation for this check? Do not run the check in this exercise.

## Assertions

### keeps_the_required_clean_start

- **kind**: guard
- **description**: The reply calls for an empty home and a fresh installation and startup, while allowing reuse of the
  verified build artifact. It does not treat the earlier upgrade result as clean-start evidence.
