# Installing Plugins

## Design Goals

<!-- An agent installed two CAT updates in one afternoon while probe sessions ran. The second install deleted the
     version folder that those sessions' plugin copies were bound to, so their startup hook failed and their results
     were invalid. -->
- An install, update or uninstall of CAT or of any plugin never removes or replaces an installed copy that running work
	still uses, so that no running session loses its hooks, runtime or files and no result is invalidated.
- When the user asked for the install and it has to wait for running work, the user learns what it waits for.
- A plugin installation uses a documented authorized execution route, and its reported outcome is established in the
	intended harness home rather than inferred from a sandbox-local attempt or a service's location.

## Guidance

This rule applies before you install, update or uninstall CAT or any plugin, including a repair or reinstall.

Before invoking the installer, establish its intended harness home and the execution context its documented procedure
requires. Use the supported outside execution route when the installation needs access beyond the session's sandbox.
Check the actual operation that a launcher or outside service supports: a server running outside the sandbox does not
give its sandboxed children that access, and a session-launch service need not support installation. Follow the route's
invocation requirements so the installer actually runs in the required context.

If a sandboxed attempt is refused, check the documented outside installation routes and any existing service before
concluding that this session cannot complete the update. Use an available authorized route that supports the operation;
do not weaken sandbox settings or invent an unsupported service operation. When no such route is available, report the
specific unmet prerequisite and the observed refusals. A copied artifact or an accepted service request is intermediate
evidence: verify the active installed version in the intended harness home before reporting completion.

First look for running work that uses the installed copy: sessions that you or a runner started, such as probe,
screen or runner sessions, subagents, and test copies of a plugin that are bound to the installed runtime or version
folder. Installs keep only the active version and the one it replaced, and delete older version folders, so work bound
to an older version is endangered by the next install.

- When there is none, install.
- When there is some, wait for it to finish before you install, or install only after you confirm that the work uses its
	own copied runtime and files that the install does not touch. Do not stop or restart that work to make the install
	possible.
- When the user asked for the install and it has to wait, tell the user what it waits for before you wait.
