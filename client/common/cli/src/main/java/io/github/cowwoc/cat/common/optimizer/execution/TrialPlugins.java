package io.github.cowwoc.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.PluginUnderTest;
import io.github.cowwoc.cat.common.optimizer.port.TrialPlugin;
import io.github.cowwoc.cat.common.optimizer.port.WorkspacePort;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Finds the plugin under test that the trials of a staged source install.
 * <p>
 * <b>Thread Safety:</b> This class is stateless and thread-safe.
 */
final class TrialPlugins
{
	/**
	 * Prevents construction.
	 */
	private TrialPlugins()
	{
	}

	/**
	 * Returns the plugin under test as the trials of a staged source install it.
	 *
	 * @param plugin the contract's plugin under test, or empty if its target prompt files are not in a plugin; a
	 *   built plugin's base digest must be set
	 * @param workspace the workspace that builds the staged source's copy of the plugin
	 * @param source the staged source
	 * @return the copy of the plugin that belongs to {@code source}, or empty if {@code plugin} is empty
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if a built plugin's base digest is empty
	 * @throws IOException if the copy cannot be built, if the staged source has no plugin root, or if the files of a
	 *   built plugin's base no longer have the identity of its base digest
	 */
	static Optional<TrialPlugin> of(Optional<PluginUnderTest> plugin, WorkspacePort workspace, Path source)
		throws IOException
	{
		requireThat(plugin, "plugin").isNotNull();
		requireThat(workspace, "workspace").isNotNull();
		requireThat(source, "source").isNotNull();
		if (plugin.isEmpty())
			return Optional.empty();
		return Optional.of(new TrialPlugin(workspace.pluginCopy(plugin.get(), source), plugin.get().dependsOnCat()));
	}
}
