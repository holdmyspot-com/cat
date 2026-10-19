package com.holdmyspot.cat.claude.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.runner.LocalMarketplace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Creates Claude's isolated local plugin marketplaces.
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
	 * Copies a plugin and replaces Claude marketplace metadata when it already exists.
	 *
	 * @param plugin the validated Claude plugin
	 * @param home the isolated Claude configuration directory
	 * @return the local marketplace root
	 * @throws NullPointerException if {@code plugin} or {@code home} are null
	 * @throws IOException if the marketplace cannot be created
	 */
	public static Path create(Path plugin, Path home) throws IOException
	{
		return create(plugin, "cat", "An agentic workflow with a focus on reliability and developer experience", home);
	}

	/**
	 * Copies a plugin into a marketplace named after it and replaces the marketplace metadata when it already exists.
	 *
	 * @param plugin the plugin directory
	 * @param name the plugin's manifest name, which also names its marketplace
	 * @param description the plugin's description in the marketplace
	 * @param home the isolated Claude configuration directory
	 * @return the local marketplace root
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if {@code name} or {@code description} are blank
	 * @throws IOException if the marketplace cannot be created
	 */
	public static Path create(Path plugin, String name, String description, Path home) throws IOException
	{
		requireThat(plugin, "plugin").isNotNull();
		requireThat(name, "name").isNotBlank();
		requireThat(description, "description").isNotBlank();
		requireThat(home, "home").isNotNull();
		Path root = home.resolve("plugins/" + name + "-marketplace");
		Path copy = root.resolve("plugins/" + name);
		LocalMarketplace.copy(plugin, copy);
		// A plugin's own MCP configuration already provides the target that Bubblewrap masks
		if (Files.notExists(copy.resolve(".mcp.json")))
			writeEmptyMcpConfiguration(copy);
		Path metadata = root.resolve(".claude-plugin/marketplace.json");
		Files.createDirectories(metadata.getParent());
		Files.writeString(metadata, """
			{
			\t"name": "%1$s",
			\t"owner":
			\t{
			\t\t"name": "cowwoc"
			\t},
			\t"plugins":
			\t[
			\t\t{
			\t\t\t"name": "%1$s",
			\t\t\t"source": "./plugins/%1$s",
			\t\t\t"description": "%2$s"
			\t\t}
			\t]
			}
			""".formatted(name, description));
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
