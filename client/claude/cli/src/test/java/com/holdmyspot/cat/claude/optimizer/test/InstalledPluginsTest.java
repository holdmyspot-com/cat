package com.holdmyspot.cat.claude.optimizer.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.claude.optimizer.InstalledPlugins;
import com.holdmyspot.cat.common.TemporaryDirectories;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests {@link InstalledPlugins} against install records written as Claude Code writes them.
 */
public final class InstalledPluginsTest
{
	/**
	 * The JSON mapper.
	 */
	private static final JsonMapper JSON = JsonMapper.builder().build();
	/**
	 * The name of the plugin that the tests look up.
	 */
	private static final String NAME = "tools@market";

	/**
	 * Creates the test.
	 */
	public InstalledPluginsTest()
	{
	}

	/**
	 * Verifies that a plugin that the records install once yields its one copy.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void findsOneCopy() throws IOException
	{
		// Requirement: R-W12
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path configuration = cleanup.add(Files.createTempDirectory("cat-claude-config-")).toRealPath();
			Path copy = pluginCopy(configuration, "1.0.0");
			writeRecords(configuration, NAME, List.of(copy));

			requireThat(InstalledPlugins.of(configuration, NAME, JSON), "copies").isEqualTo(List.of(copy));
		}
	}

	/**
	 * Verifies that a plugin that the records do not name, or that no records exist for, yields no copy.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void findsNoCopy() throws IOException
	{
		// Requirement: R-W12
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path configuration = cleanup.add(Files.createTempDirectory("cat-claude-config-")).toRealPath();
			requireThat(InstalledPlugins.of(configuration, NAME, JSON), "withoutRecords").isEmpty();

			writeRecords(configuration, "other@market", List.of(pluginCopy(configuration, "1.0.0")));
			requireThat(InstalledPlugins.of(configuration, NAME, JSON), "unnamed").isEmpty();
		}
	}

	/**
	 * Verifies that a plugin that two scopes install at different directories yields both copies.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void findsTwoCopies() throws IOException
	{
		// Requirement: R-W12
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path configuration = cleanup.add(Files.createTempDirectory("cat-claude-config-")).toRealPath();
			Path first = pluginCopy(configuration, "1.0.0");
			Path second = pluginCopy(configuration, "2.0.0");
			writeRecords(configuration, NAME, List.of(first, second));

			requireThat(InstalledPlugins.of(configuration, NAME, JSON), "copies").isEqualTo(List.of(first, second));
		}
	}

	/**
	 * Creates an installed copy of the plugin, holding its manifest, in the plugin cache.
	 *
	 * @param configuration the Claude Code configuration directory
	 * @param version the copy's version
	 * @return the copy's directory
	 * @throws IOException if the copy cannot be written
	 */
	private static Path pluginCopy(Path configuration, String version) throws IOException
	{
		Path copy = configuration.resolve("plugins/cache/market/tools").resolve(version);
		Files.createDirectories(copy.resolve(".claude-plugin"));
		Files.writeString(copy.resolve(".claude-plugin/plugin.json"), "{\"name\": \"tools\"}\n", UTF_8);
		return copy;
	}

	/**
	 * Writes install records that install a plugin once per copy, each in its own scope.
	 *
	 * @param configuration the Claude Code configuration directory
	 * @param name the recorded plugin's name
	 * @param copies the copies to record
	 * @throws IOException if the records cannot be written
	 */
	private static void writeRecords(Path configuration, String name, List<Path> copies) throws IOException
	{
		ObjectNode root = JSON.createObjectNode();
		root.put("version", 2);
		ArrayNode entries = root.putObject("plugins").putArray(name);
		List<String> scopes = List.of("user", "project");
		for (int i = 0; i < copies.size(); ++i)
		{
			ObjectNode entry = entries.addObject();
			entry.put("scope", scopes.get(i));
			entry.put("installPath", copies.get(i).toString());
		}
		Path records = configuration.resolve("plugins/installed_plugins.json");
		Files.createDirectories(records.getParent());
		Files.writeString(records, JSON.writeValueAsString(root), UTF_8);
	}
}
