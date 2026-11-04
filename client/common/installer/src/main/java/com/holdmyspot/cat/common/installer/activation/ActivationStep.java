package com.holdmyspot.cat.common.installer.activation;

/**
 * One change that activating a validated release makes to the files that a harness loads CAT from.
 * <p>
 * A {@link PluginActivation} provider plans the steps; the installer's core runs them in order. Before each step it
 * records the paths that the step will create, rename or delete in its activation journal, so that a failed or
 * interrupted activation can be undone. To undo an activation, the core undoes its completed steps in reverse order,
 * which restores the files that were active before it.
 * <p>
 * Every path of a step is absolute and normalized. Each step renames a staged copy into place, and a rename is atomic
 * only within one file system, so each staging directory must lie on the same file system as the path that it
 * replaces or creates.
 * <p>
 * <b>Thread Safety:</b> Implementations are thread-safe.
 */
public sealed interface ActivationStep permits ReplaceDirectory, AddDirectory, ReplaceOwnedFiles
{
}
