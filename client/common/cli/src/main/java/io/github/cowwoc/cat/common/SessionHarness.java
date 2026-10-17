package io.github.cowwoc.cat.common;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * The parts of a session work directory that depend on the harness whose session owns it. Each harness module supplies
 * its own value.
 *
 * @param id the harness's identifier, which the session marker records
 * @param sessionVariable the variable through which the harness gives its tool shells the session's ID
 * @param configurationVariable the variable that overrides the harness's configuration directory
 * @param defaultConfigurationDirectory the harness's configuration directory relative to the user's home directory,
 *   which applies when {@code configurationVariable} is unset
 * @param transcripts the glob patterns, relative to the configuration directory and separated by {@code /}, of the
 *   files in which the harness keeps a session's transcript; {@value #SESSION_ID} stands for the session's ID, and a
 *   {@code **} segment matches any number of directories
 */
public record SessionHarness(String id, String sessionVariable, String configurationVariable,
	Path defaultConfigurationDirectory, List<String> transcripts)
{
	/**
	 * The text that stands for the session's ID in a transcript pattern.
	 */
	public static final String SESSION_ID = "<session-id>";

	/**
	 * Creates a harness value.
	 *
	 * @param id the harness's identifier, which the session marker records
	 * @param sessionVariable the variable through which the harness gives its tool shells the session's ID
	 * @param configurationVariable the variable that overrides the harness's configuration directory
	 * @param defaultConfigurationDirectory the harness's configuration directory relative to the user's home directory,
	 *   which applies when {@code configurationVariable} is unset
	 * @param transcripts the glob patterns, relative to the configuration directory and separated by {@code /}, of the
	 *   files in which the harness keeps a session's transcript; {@value #SESSION_ID} stands for the session's ID, and a
	 *   {@code **} segment matches any number of directories
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if {@code id}, {@code sessionVariable} or {@code configurationVariable} are
	 *   blank, if {@code defaultConfigurationDirectory} is absolute, or if {@code transcripts} is empty or holds a
	 *   pattern without {@value #SESSION_ID}
	 */
	public SessionHarness
	{
		requireThat(id, "id").isNotBlank();
		requireThat(sessionVariable, "sessionVariable").isNotBlank();
		requireThat(configurationVariable, "configurationVariable").isNotBlank();
		requireThat(defaultConfigurationDirectory, "defaultConfigurationDirectory").isNotNull();
		requireThat(defaultConfigurationDirectory.isAbsolute(), "defaultConfigurationDirectory.isAbsolute()").isFalse();
		requireThat(transcripts, "transcripts").isNotEmpty();
		for (String pattern : transcripts)
			requireThat(pattern, "pattern").contains(SESSION_ID);
		transcripts = List.copyOf(transcripts);
	}

	/**
	 * Returns the harnesses whose modules the runtime holds. Each harness module supplies its value as a service.
	 *
	 * @return the harnesses, in the order that their modules are found
	 */
	public static List<SessionHarness> installed()
	{
		return ServiceLoader.load(SessionHarness.class).stream().map(ServiceLoader.Provider::get).toList();
	}

	/**
	 * Returns the harness's configuration directory: the directory that {@link #configurationVariable()} names, or
	 * else {@link #defaultConfigurationDirectory()} in the user's home directory.
	 *
	 * @param environment the process environment
	 * @param homeVariable the variable that names the user's home directory
	 * @return the absolute, normalized configuration directory
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if {@code homeVariable} is blank
	 * @throws IOException if {@link #configurationVariable()} and {@code homeVariable} are both unset or blank
	 */
	public Path configurationDirectory(Map<String, String> environment, String homeVariable) throws IOException
	{
		requireThat(environment, "environment").isNotNull();
		requireThat(homeVariable, "homeVariable").isNotBlank();
		String configured = environment.get(configurationVariable);
		Path directory;
		if (configured != null && !configured.isBlank())
			directory = Path.of(configured.strip());
		else
		{
			String home = environment.get(homeVariable);
			if (home == null || home.isBlank())
				throw new IOException("Neither " + configurationVariable + " nor " + homeVariable + " is set");
			directory = Path.of(home.strip()).resolve(defaultConfigurationDirectory);
		}
		return directory.toAbsolutePath().normalize();
	}
}
