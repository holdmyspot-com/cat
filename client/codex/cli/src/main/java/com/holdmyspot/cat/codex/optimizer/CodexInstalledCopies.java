package com.holdmyspot.cat.codex.optimizer;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.codex.installer.CodexActivation;
import com.holdmyspot.cat.common.InstalledCopies;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Finds the installed CAT plugin of Codex.
 */
public final class CodexInstalledCopies
{
	/**
	 * The name under which Codex records CAT's plugin.
	 */
	private static final String PLUGIN = "cat@cat";

	/**
	 * Prevents instantiation.
	 */
	private CodexInstalledCopies()
	{
	}

	/**
	 * Returns what finds the CAT plugin that Codex loads. Codex keeps every cached version of the plugin but loads one
	 * of them, which the installer's own reading of Codex's cache chooses, so only that version's folder counts as
	 * installed.
	 *
	 * @param home Codex's home directory
	 * @return what finds the installed copy: the loaded version's folder, or none if Codex loads no CAT version
	 * @throws NullPointerException if {@code home} is null
	 */
	public static InstalledCopies in(Path home)
	{
		requireThat(home, "home").isNotNull();
		return () -> loaded(home);
	}

	/**
	 * Returns the cached version folder that Codex loads.
	 *
	 * @param home Codex's home directory
	 * @return the folder, or an empty list if Codex loads no version of the plugin
	 * @throws IOException if the configuration or the cache cannot be read
	 */
	private static List<Path> loaded(Path home) throws IOException
	{
		Optional<String> active = new CodexActivation().installed(home.toAbsolutePath().normalize()).version();
		if (active.isEmpty())
			return List.of();
		return InstalledPlugins.of(home, PLUGIN).stream().
			filter(copy -> copy.getFileName().toString().equals(active.get())).toList();
	}
}
