package com.holdmyspot.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.runner.policy.SessionRefusedException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * The home folder whose harness folders hold the credential that a nested session uses, the way that the user's own
 * harness in that home would use it. A launch names it with {@value AuthenticationOptions#HOME_OPTION}; otherwise it is
 * the runner's own home folder. Each harness keeps its files in a folder of the source home that its runner names, such
 * as {@code .claude}: of a named home, always that folder; of the runner's own home, the folder that the runner's
 * variable for it names when set, as the user's own harness in the runner's environment would use it, or else that
 * folder.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 *
 * @param directory the absolute, normalized home folder
 * @param named     {@code true} if the launch named the folder, {@code false} if it is the runner's own home folder
 */
public record SourceHome(Path directory, boolean named)
{
	/**
	 * Creates a source home.
	 *
	 * @param directory the absolute home folder
	 * @param named     {@code true} if the launch named the folder, {@code false} if it is the runner's own home folder
	 * @throws NullPointerException     if {@code directory} is null
	 * @throws IllegalArgumentException if {@code directory} is not absolute
	 */
	public SourceHome
	{
		requireThat(directory, "directory").isNotNull();
		if (!directory.isAbsolute())
			throw new IllegalArgumentException("directory must be absolute: " + directory);
		directory = directory.normalize();
	}

	/**
	 * Resolves the source home of a launch.
	 *
	 * @param named       the absolute folder that the launch names, or {@code null} if it names none
	 * @param environment the runner's environment
	 * @param platform    the platform whose home variable names the runner's home folder
	 * @return the source home
	 * @throws NullPointerException     if {@code environment} or {@code platform} are null
	 * @throws IllegalArgumentException if {@code named} is not absolute
	 * @throws SessionRefusedException  if {@code named} is not an existing folder, or {@code named} is null and the
	 *                                  environment names no home folder
	 */
	public static SourceHome resolve(Path named, Map<String, String> environment, Platform platform)
		throws SessionRefusedException
	{
		requireThat(environment, "environment").isNotNull();
		requireThat(platform, "platform").isNotNull();
		if (named != null)
		{
			if (!Files.isDirectory(named))
			{
				throw new SessionRefusedException("CAT does not start the session: " +
					AuthenticationOptions.HOME_OPTION + " names " + named + ", which is not a folder. Name the home " +
					"folder whose harness folders hold the credential that the session should use.");
			}
			return new SourceHome(named, true);
		}
		Path home;
		try
		{
			home = platform.homeDirectory(environment);
		}
		catch (IOException e)
		{
			throw new SessionRefusedException("CAT does not start the session: " + e.getMessage() + ", so the " +
				"runner has no home folder whose harness folders hold the session's credential. Name one with " +
				AuthenticationOptions.HOME_OPTION + ".", e);
		}
		return new SourceHome(home.toAbsolutePath(), false);
	}

	/**
	 * Returns the folder of the source home in which one harness keeps its configuration and credentials.
	 *
	 * @param name        the folder's name in a home folder, such as {@code .claude}
	 * @param variable    the variable with which the harness moves that folder, such as {@code CLAUDE_CONFIG_DIR}
	 * @param environment the runner's environment
	 * @return {@code name} in a named home; otherwise the folder that the runner's non-blank {@code variable} names,
	 *   made absolute, or else {@code name} in the runner's home
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code name} or {@code variable} are blank
	 */
	public Path folder(String name, String variable, Map<String, String> environment)
	{
		requireThat(name, "name").isNotBlank();
		requireThat(variable, "variable").isNotBlank();
		requireThat(environment, "environment").isNotNull();
		if (!named)
		{
			String configured = environment.get(variable);
			if (configured != null && !configured.isBlank())
				return Path.of(configured).toAbsolutePath().normalize();
		}
		return directory.resolve(name);
	}
}
