package io.github.cowwoc.cat.claude.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.claude.runner.ClaudeMarketplace;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;

/**
 * Tests {@link ClaudeMarketplace}.
 */
public final class ClaudeMarketplaceTest
{
	/**
	 * Verifies the Claude-specific integration creates its marketplace metadata outside the common module.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void createsMarketplaceMetadata() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path artifact = cleanup.add(Files.createTempDirectory("cat-artifact-"));
			Files.writeString(artifact.resolve("README.md"), "artifact");
			Path home = cleanup.add(Files.createTempDirectory("cat-claude-home-"));

			Path marketplace = ClaudeMarketplace.create(artifact, home);

			requireThat(Files.readString(marketplace.resolve("plugins/cat/README.md")), "artifactContents").
				isEqualTo("artifact");
			requireThat(Files.readString(marketplace.resolve("plugins/cat/.mcp.json")), "mcpConfiguration").
				isEqualTo("""
				{
				\t"mcpServers":
				\t{
				\t}
				}
				""");
			requireThat(Files.readString(marketplace.resolve(".claude-plugin/marketplace.json")), "metadata").isEqualTo("""
			{
			\t"name": "cat",
			\t"owner":
			\t{
			\t\t"name": "cowwoc"
			\t},
			\t"plugins":
			\t[
			\t\t{
			\t\t\t"name": "cat",
			\t\t\t"source": "./plugins/cat",
			\t\t\t"description": "An agentic workflow with a focus on reliability and developer experience"
			\t\t}
			\t]
			}
			""");
		}
	}

	/**
	 * Verifies the Bubblewrap launch-configuration workaround creates only an inert configuration at the inspected
	 * ancestor path.
	 *
	 * @throws IOException if the filesystem operation fails
	 */
	@Test
	public void createsEmptyLaunchConfiguration() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("cat-claude-run-root-"));
			Path configuration = directory.resolve(".claude/launch.json");

			ClaudeMarketplace.writeEmptyLaunchConfiguration(configuration);

			requireThat(Files.readString(configuration), "launchConfiguration").isEqualTo(
				"{}\n");
		}
	}
}
