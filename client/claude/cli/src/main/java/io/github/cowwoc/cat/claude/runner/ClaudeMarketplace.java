package io.github.cowwoc.cat.claude.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.runner.LocalMarketplace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Creates Claude's isolated local CAT marketplace.
 */
public final class ClaudeMarketplace
{
	/**
	 * Prevents construction of utility class instances.
	 */
	private ClaudeMarketplace()
	{
	}

	/**
	 * Copies an artifact and replaces Claude marketplace metadata when it already exists.
	 *
	 * @param artifact the validated Claude artifact
	 * @param home the isolated Claude configuration directory
	 * @return the local marketplace root
	 * @throws NullPointerException if {@code artifact} or {@code home} are null
	 * @throws IOException if the marketplace cannot be created
	 */
	public static Path create(Path artifact, Path home) throws IOException
	{
		requireThat(artifact, "artifact").isNotNull();
		requireThat(home, "home").isNotNull();
		Path root = home.resolve("plugins/cat-marketplace");
		Path plugin = root.resolve("plugins/cat");
		LocalMarketplace.copy(artifact, plugin);
		//WORKAROUND: https://github.com/anthropics/claude-code/issues/84563
		// Bubblewrap masks .mcp.json in every added directory; the read-only plugin mount cannot create its absent target.
		// An empty configuration provides that target without granting the agent write access or configuring an MCP server.
		Files.writeString(plugin.resolve(".mcp.json"), """
			{
			\t"mcpServers":
			\t{
			\t}
			}
			""");
		Path metadata = root.resolve(".claude-plugin/marketplace.json");
		Files.createDirectories(metadata.getParent());
		Files.writeString(metadata, """
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
		return root;
	}

	/**
	 * Creates the no-server MCP configuration required by Claude's Bubblewrap setup.
	 *
	 * @param directory the directory that Claude's sandbox may inspect for an MCP configuration
	 * @throws IOException if the configuration cannot be written
	 */
	static void writeEmptyMcpConfiguration(Path directory) throws IOException
	{
		requireThat(directory, "directory").isNotNull();
		//WORKAROUND: https://github.com/anthropics/claude-code/issues/84563
		// Bubblewrap masks .mcp.json in each inspected directory; a read-only mount cannot create an absent target.
		// This no-server configuration supplies the target without granting the agent write access or adding an MCP server.
		Files.writeString(directory.resolve(".mcp.json"), """
			{
			\t"mcpServers":
			\t{
			\t}
			}
			""");
	}

	/**
	 * Creates the empty launch configuration required by Claude's Bubblewrap setup.
	 *
	 * @param configuration the launch configuration path that Claude's sandbox may inspect
	 * @throws IOException if the configuration cannot be written
	 */
	public static void writeEmptyLaunchConfiguration(Path configuration) throws IOException
	{
		requireThat(configuration, "configuration").isNotNull();
		//WORKAROUND: https://github.com/anthropics/claude-code/issues/84563
		// Bubblewrap binds an ancestor .claude/launch.json even when no launch configuration is requested. Creating an
		// inert configuration prevents Bash from failing before it starts without granting the agent additional access.
		Files.createDirectories(configuration.getParent());
		Files.writeString(configuration, "{}\n");
	}
}
