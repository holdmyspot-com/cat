package example.cli;

import java.nio.file.Path;

/**
 * Locates the command's configuration file.
 */
public final class ConfigLocator
{
	private ConfigLocator()
	{
	}

	/**
	 * Returns where the configuration file is, relative to the installation directory, for the startup message.
	 *
	 * @return the relative location
	 */
	public static String defaultLocation()
	{
		return Path.of("conf", "app.toml").toString();
	}
}
