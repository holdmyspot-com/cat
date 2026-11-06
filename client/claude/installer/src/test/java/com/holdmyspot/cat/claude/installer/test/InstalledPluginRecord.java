package com.holdmyspot.cat.claude.installer.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes the file in which Claude Code records the plugins that it installed, in the layout of Claude Code 2.1.318's
 * {@code plugins/installed_plugins.json}.
 */
final class InstalledPluginRecord
{
	/**
	 * Prevents instantiation.
	 */
	private InstalledPluginRecord()
	{
	}

	/**
	 * Records CAT as installed in the user scope, as Claude Code does after {@code plugin install cat@cat}.
	 *
	 * @param home    the configuration directory
	 * @param version the installed version
	 * @throws IOException if the record cannot be written
	 */
	static void write(Path home, String version) throws IOException
	{
		Path record = home.resolve("plugins/installed_plugins.json");
		Files.createDirectories(record.getParent());
		Files.writeString(record, """
			{
			  "version": 2,
			  "plugins": {
			    "cat@cat": [
			      {
			        "scope": "user",
			        "installPath": "%s",
			        "version": "%s"
			      }
			    ]
			  }
			}
			""".formatted(home.resolve("plugins/cache/cat/cat").resolve(version.replace('+', '-')).toString().
			replace("\\", "\\\\"), version));
	}
}
