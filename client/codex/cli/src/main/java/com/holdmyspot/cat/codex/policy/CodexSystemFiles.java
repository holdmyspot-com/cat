package com.holdmyspot.cat.codex.policy;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.codex.layout.CodexSystemFolder;
import java.nio.file.Path;
import java.util.List;

/**
 * The machine-wide files that Codex reads its configuration and requirements from, and the machine-wide folder that its
 * sandbox may let commands write.
 *
 * @param system             Codex's machine-wide folder, which holds the machine's configuration, requirements and
 *                           managed configuration files
 * @param managedPreferences the files of the managed preferences that an administrator may push to Codex, which the
 *                           reader cannot parse
 * @param slashTmp           the folder that Codex's {@code slash_tmp} special path names, or {@code null} if the
 *                           machine has none
 */
public record CodexSystemFiles(CodexSystemFolder system, List<Path> managedPreferences, Path slashTmp)
{
	/**
	 * Creates the files.
	 *
	 * @param system             Codex's machine-wide folder
	 * @param managedPreferences the files of the managed preferences that an administrator may push to Codex, which
	 *                           the reader cannot parse
	 * @param slashTmp           the folder that Codex's {@code slash_tmp} special path names, or {@code null} if the
	 *                           machine has none
	 * @throws NullPointerException     if {@code system} or {@code managedPreferences} are null, or
	 *                                  {@code managedPreferences} contains null
	 * @throws IllegalArgumentException if a preferences file or {@code slashTmp} is relative
	 */
	public CodexSystemFiles
	{
		requireThat(system, "system").isNotNull();
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
		return new CodexSystemFiles(CodexSystemFolder.ofThisMachine(), CodexAssumptions.managedPreferences(user),
			slashTmp);
	}
}
