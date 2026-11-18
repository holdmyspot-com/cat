package com.holdmyspot.cat.codex.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.codex.runner.CodexAgentDefinition;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.testng.annotations.Test;

/**
 * Tests the Codex agent definitions that the plugin ships.
 */
public final class CodexShippedAgentsTest
{
	private static final Path ARTIFACT = Path.of("../plugin/src/main/resources");
	/**
	 * The agent tiers, from the weakest to the strongest.
	 */
	private static final List<String> TIERS = List.of("low", "medium", "high", "xhigh");

	/**
	 * Creates the test.
	 */
	public CodexShippedAgentsTest()
	{
	}

	/**
	 * Verifies that the strongest shipped tier of every agent role selects {@code gpt-6.2-sol}.
	 *
	 * @throws IOException if an agent definition cannot be read
	 */
	@Test
	public void strongestTiersSelectLatestSol() throws IOException
	{
		Map<String, String> strongest = new TreeMap<>();
		try (Stream<Path> files = Files.list(ARTIFACT.resolve("agents/codex")))
		{
			for (Path file : files.toList())
			{
				String name = file.getFileName().toString();
				if (!name.endsWith(".toml"))
					continue;
				String agent = name.substring(0, name.length() - ".toml".length());
				int separator = agent.lastIndexOf('-');
				String role = agent.substring(0, separator);
				String tier = agent.substring(separator + 1);
				requireThat(TIERS, "TIERS").contains(tier);
				strongest.merge(role, agent, (current, candidate) ->
				{
					String currentTier = current.substring(current.lastIndexOf('-') + 1);
					if (TIERS.indexOf(tier) > TIERS.indexOf(currentTier))
						return candidate;
					return current;
				});
			}
		}
		requireThat(strongest, "strongest").isNotEmpty();

		Map<String, String> models = new TreeMap<>();
		Map<String, String> expected = new TreeMap<>();
		for (String agent : strongest.values())
		{
			models.put(agent, CodexAgentDefinition.load(ARTIFACT, agent).model());
			expected.put(agent, "gpt-6.2-sol");
		}
		requireThat(models, "models").isEqualTo(expected);
	}
}
