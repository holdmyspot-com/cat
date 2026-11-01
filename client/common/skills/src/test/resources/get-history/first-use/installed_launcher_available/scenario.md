# Installed history launcher available

## Variables

- **launcher**: The existing installed get-history launcher resolved by the selected harness wrapper

## Scenario

Retrieve my explicitly selected session's history. The selected harness wrapper resolved {{launcher}}, and that
installed launcher exists. The current project is unrelated to CAT. Use the installed launcher.

## Assertions

### installed_path_used

- **kind**: guard
- **description**: The reply uses the supplied installed launcher without requiring a CAT checkout, Maven build, or
  source-built jlink path.
