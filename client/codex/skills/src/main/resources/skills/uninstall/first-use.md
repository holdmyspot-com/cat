# Uninstall CAT

## Design Goals

- Remove CAT-owned user agents before the Codex plugin is removed.

## Guidance

Use CAT's uninstaller. It removes only the files recorded in CAT's ownership manifest, then removes the plugin and its
marketplace registration:

```bash
CAT_BOOTSTRAP_RELEASE_TREE="$PWD/cat-1.0" \
  sh cat-1.0/codex/linux-x86_64/install/bootstrap-uninstall-codex.sh
```
