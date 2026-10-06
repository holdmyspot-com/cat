# Uninstall CAT

## Design Goals

- Remove CAT-owned user agents before the Codex plugin is removed.
- Keep CAT's data unless the user asks to delete it, so a later install can migrate it.

## Guidance

Use CAT's uninstaller. It removes only the files recorded in CAT's ownership manifest, then removes the plugin and its
marketplace registration. It keeps CAT's data directory; when the user asks to delete CAT's data as well, end the
command with `| sh -s -- --delete-data` instead of `| sh`:

```bash
curl -fsSL \
  "https://raw.githubusercontent.com/cowwoc/cat/main/client/lifecycle/src/main/resources/install/"\
  "bootstrap-uninstall-codex.sh" | sh
```
