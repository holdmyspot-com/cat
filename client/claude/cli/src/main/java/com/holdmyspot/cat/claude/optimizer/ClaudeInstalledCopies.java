package com.holdmyspot.cat.claude.optimizer;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.InstalledCopies;

import java.nio.file.Path;

import tools.jackson.databind.json.JsonMapper;

/**
 * Finds the installed CAT plugin of Claude Code.
 */
public final class ClaudeInstalledCopies
{
	/**
	 * The name under which Claude Code records CAT's plugin.
	 */
	private static final String PLUGIN = "cat@cat";

	/**
	 * Prevents instantiation.
	 */
	private ClaudeInstalledCopies()
	{
	}

	/**
	 * Returns what finds the installed CAT plugin through Claude Code's install records.
	 *
	 * @param configuration Claude Code's configuration directory
	 * @return what finds the installed copies, which are the copies that the records name
	 * @throws NullPointerException if {@code configuration} is null
	 */
	public static InstalledCopies in(Path configuration)
	{
		requireThat(configuration, "configuration").isNotNull();
		return () -> InstalledPlugins.of(configuration, PLUGIN, JsonMapper.builder().build());
	}
}
