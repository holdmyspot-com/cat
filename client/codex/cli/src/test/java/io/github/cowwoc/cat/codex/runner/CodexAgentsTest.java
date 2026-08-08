package io.github.cowwoc.cat.codex.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;

/**
 * Tests {@link CodexAgents}.
 */
public final class CodexAgentsTest
{
	/**
	 * Verifies the isolated home receives only CAT-prefixed copies of artifact-owned agent definitions.
	 */
	@Test
	public void installsArtifactOwnedAgents() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path artifact = cleanup.add(Files.createTempDirectory("cat-artifact-"));
			Path home = cleanup.add(Files.createTempDirectory("codex-home-"));
			Path agents = Files.createDirectories(artifact.resolve("agents/codex"));
			Files.writeString(agents.resolve("reviewer.toml"), "name = \"cat-reviewer\"\n");
			Files.writeString(agents.resolve("ignored.md"), "not an agent definition\n");

			CodexAgents.install(artifact, home);

			requireThat(Files.readString(home.resolve("agents/cat-reviewer.toml")), "installedAgent").
				isEqualTo("name = \"cat-reviewer\"\n");
			requireThat(Files.exists(home.resolve("agents/ignored.md")), "nonAgent").isFalse();
		}
	}
}
