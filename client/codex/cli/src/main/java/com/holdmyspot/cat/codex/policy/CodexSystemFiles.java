package com.holdmyspot.cat.codex.policy;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.Path;
import java.util.List;

/**
 * The machine-wide files that Codex reads its configuration and requirements from, and the machine-wide folder that its
 * sandbox may let commands write.
 *
 * @param folder             the folder of the machine's configuration, requirements and managed configuration files
 * @param managedPreferences the files of the managed preferences that an administrator may push to Codex, which the
 *                           reader cannot parse
 * @param slashTmp           the folder that Codex's {@code slash_tmp} special path names, or {@code null} if the
 *                           machine has none
 */
public record CodexSystemFiles(Path folder, List<Path> managedPreferences, Path slashTmp)
{
	/**
	 * Creates the files.
	 *
	 * @param folder             the folder of the machine's configuration, requirements and managed configuration
	 *                           files
	 * @param managedPreferences the files of the managed preferences that an administrator may push to Codex, which
	 *                           the reader cannot parse
	 * @param slashTmp           the folder that Codex's {@code slash_tmp} special path names, or {@code null} if the
	 *                           machine has none
	 * @throws NullPointerException     if {@code folder} or {@code managedPreferences} are null, or
	 *                                  {@code managedPreferences} contains null
	 * @throws IllegalArgumentException if a path is relative
	 */
	public CodexSystemFiles
	{
		requireThat(folder, "folder").isAbsolute();
		for (Path file : managedPreferences)
			requireThat(file, "file").isAbsolute();
		managedPreferences = List.copyOf(managedPreferences);
		if (slashTmp != null)
			requireThat(slashTmp, "slashTmp").isAbsolute();
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
		Path slashTmp = Path.of("/tmp");
		if (!slashTmp.isAbsolute())
			slashTmp = null;
		return new CodexSystemFiles(Path.of("/etc/codex"), CodexAssumptions.managedPreferences(user), slashTmp);
	}
}
