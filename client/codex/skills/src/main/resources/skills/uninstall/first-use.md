# Uninstall CAT

## Design Goals

- Remove CAT-owned user agents before the Codex plugin is removed.

## Guidance

Use CAT's uninstaller. It removes only the files recorded in CAT's ownership manifest, then removes the plugin and its
marketplace registration:

```bash
curl -fsSL \
  "https://raw.githubusercontent.com/cowwoc/cat/main/client/codex/lifecycle/src/main/resources/install/"\
  "bootstrap-uninstall-codex.sh" | sh
```
