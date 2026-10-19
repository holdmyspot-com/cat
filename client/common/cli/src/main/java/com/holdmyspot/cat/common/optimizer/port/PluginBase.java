package com.holdmyspot.cat.common.optimizer.port;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.contract.TrialPlugin;

import java.nio.file.Path;

/**
 * A trial plugin with the directory of its base copy in campaign storage.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param plugin the trial plugin
 * @param directory the absolute directory of the plugin's base copy
 */
public record PluginBase(TrialPlugin plugin, Path directory)
{
	/**
	 * Creates a plugin base.
	 *
	 * @param plugin the trial plugin
	 * @param directory the absolute directory of the plugin's base copy
	 * @throws NullPointerException if any argument is null
	 */
	public PluginBase
	{
		requireThat(plugin, "plugin").isNotNull();
		requireThat(directory, "directory").isNotNull();
	}
}
