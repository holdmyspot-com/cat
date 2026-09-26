package io.github.cowwoc.cat.codex;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * Locates CAT's saved Codex data for commands run outside a native hook.
 */
final class CodexDataDirectory
{
	/**
	 * Uses an explicit Codex home, or the CLI's default directory under HOME.
	 *
	 * @param environment command process environment
	 * @return private login directory under Codex's plugin data
	 * @throws IOException if neither Codex home nor HOME is available
	 */
	static Path auth(Map<String, String> environment) throws IOException
	{
		String codexHome = environment.get("CODEX_HOME");
		Path home;
		if (codexHome != null && !codexHome.isBlank())
			home = Path.of(codexHome);
		else
		{
			String userHome = environment.get("HOME");
			if (userHome == null || userHome.isBlank())
				throw new IOException("HOME or CODEX_HOME is required to locate the saved WorkOS login");
			home = Path.of(userHome).resolve(".codex");
		}
		return home.toAbsolutePath().normalize().resolve("plugins/data/cat-cat/auth");
	}

	/**
	 * Prevents instantiation.
	 */
	private CodexDataDirectory()
	{
	}
}
