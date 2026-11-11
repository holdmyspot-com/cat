# Installing While Work Is Running

## Design Goals

<!-- An agent installed two CAT updates in one afternoon while probe sessions ran. The second install deleted the
     version folder that those sessions' plugin copies were bound to, so their startup hook failed and their results
     were invalid. -->
- An install, update or uninstall of CAT or of any plugin never removes or replaces an installed copy that running work
	still uses, so that no running session loses its hooks, runtime or files and no result is invalidated.
- When the user asked for the install and it has to wait for running work, the user learns what it waits for.

## Guidance

This rule applies before you install, update or uninstall CAT or any plugin, including a repair or reinstall.

First look for running work that uses the installed copy: sessions that you or a runner started, such as probe,
screen or runner sessions, subagents, and test copies of a plugin that are bound to the installed runtime or version
folder. Installs keep only the active version and the one it replaced, and delete older version folders, so work bound
to an older version is endangered by the next install.

- When there is none, install.
- When there is some, wait for it to finish before you install, or install only after you confirm that the work uses its
	own copied runtime and files that the install does not touch. Do not stop or restart that work to make the install
	possible.
- When the user asked for the install and it has to wait, tell the user what it waits for before you wait.
