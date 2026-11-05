package com.holdmyspot.cat.codex.policy;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.Path;
import java.util.List;

/**
 * The machine-wide files that Codex reads its configuration and requirements from.
 *
 * @param folder             the folder of the machine's configuration, requirements and managed configuration files
 * @param managedPreferences the files of the managed preferences that an administrator may push to Codex, which the
 *                           reader cannot parse
 */
public record CodexSystemFiles(Path folder, List<Path> managedPreferences)
{
	/**
	 * Creates the files.
	 *
	 * @param folder             the folder of the machine's configuration, requirements and managed configuration
	 *                           files
	 * @param managedPreferences the files of the managed preferences that an administrator may push to Codex, which
	 *                           the reader cannot parse
	 * @throws NullPointerException     if any argument is null, or {@code managedPreferences} contains null
	 * @throws IllegalArgumentException if a path is relative
	 */
	public CodexSystemFiles
	{
		requireThat(folder, "folder").isAbsolute();
		for (Path file : managedPreferences)
			requireThat(file, "file").isAbsolute();
		managedPreferences = List.copyOf(managedPreferences);
	}

	/**
	 * Returns the files that Codex reads on this machine.
	 *
	 * @param user the name of the user that runs Codex
	 * @return the files
	 * @throws NullPointerException if {@code user} is null
	 */
	public static CodexSystemFiles ofThisMachine(String user)
	{
		requireThat(user, "user").isNotNull();
		return new CodexSystemFiles(Path.of("/etc/codex"), CodexAssumptions.managedPreferences(user));
	}
}
