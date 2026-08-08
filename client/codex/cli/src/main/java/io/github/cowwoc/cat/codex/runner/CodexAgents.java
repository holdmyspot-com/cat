package io.github.cowwoc.cat.codex.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Installs CAT's self-contained Codex custom-agent definitions into an isolated Codex home.
 */
final class CodexAgents
{
	/**
	 * Copies every Codex custom-agent definition from the selected CAT artifact.
	 *
	 * @param artifact the validated CAT artifact
	 * @param home the isolated Codex home
	 * @throws NullPointerException if {@code artifact} or {@code home} are null
	 * @throws IOException if the artifact contains no regular Codex agent definitions or an agent cannot be copied
	 */
	static void install(Path artifact, Path home) throws IOException
	{
		requireThat(artifact, "artifact").isNotNull();
		requireThat(home, "home").isNotNull();
		Path sourceDirectory = artifact.resolve("agents/codex");
		if (!Files.isDirectory(sourceDirectory, NOFOLLOW_LINKS))
			throw new IOException("CAT artifact is missing Codex custom agents: " + sourceDirectory);
		List<Path> agents;
		try (Stream<Path> paths = Files.list(sourceDirectory))
		{
			agents = paths.filter(path -> path.getFileName().toString().endsWith(".toml")).sorted().toList();
		}
		if (agents.isEmpty())
			throw new IOException("CAT artifact contains no Codex custom-agent definitions: " + sourceDirectory);
		Path targetDirectory = home.resolve("agents");
		Files.createDirectories(targetDirectory);
		for (Path agent : agents)
		{
			if (!Files.isRegularFile(agent, NOFOLLOW_LINKS))
				throw new IOException("CAT custom-agent definition is not a regular file: " + agent);
			Files.copy(agent, targetDirectory.resolve("cat-" + agent.getFileName()));
		}
	}

	/**
	 * Prevents construction of utility class instances.
	 */
	private CodexAgents()
	{
	}
}
