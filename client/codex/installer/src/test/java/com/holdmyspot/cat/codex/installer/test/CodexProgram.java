package com.holdmyspot.cat.codex.installer.test;

import com.holdmyspot.cat.codex.layout.CodexInstallation;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.installation.PluginVersion;
import com.holdmyspot.cat.common.installer.activation.CommandResult;
import com.holdmyspot.cat.common.installer.activation.HarnessCli;
import com.holdmyspot.cat.common.installer.fakes.test.HarnessProgram;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stands in for Codex's {@code codex} program in one Codex home, answering the commands that the installer runs as
 * Codex 0.130 does: {@code plugin add} replaces the plugin's cache with a copy of the marketplace source, in the folder
 * of the version that the source's plugin manifest names, and {@code plugin remove} leaves the cache and the plugin's
 * data in place.
 */
final class CodexProgram implements HarnessProgram.Answers
{
	/**
	 * Reads the plugin manifest.
	 */
	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	/**
	 * The Codex home.
	 */
	private final Path home;

	/**
	 * Creates the program.
	 *
	 * @param home the Codex home
	 */
	private CodexProgram(Path home)
	{
		this.home = home;
	}

	/**
	 * Returns a runner of the program in one Codex home.
	 *
	 * @param home the Codex home
	 * @return the runner
	 */
	static HarnessCli cli(Path home)
	{
		return HarnessProgram.cli(new CodexProgram(home));
	}

	/**
	 * Returns the answer of Codex's program to {@code --version}.
	 *
	 * @return the answer
	 */
	static CommandResult versionAnswer()
	{
		return new CommandResult(0, "codex-cli 0.130.0\n");
	}

	@Override
	public CommandResult answer(List<String> arguments) throws IOException
	{
		String command = String.join(" ", arguments);
		if (command.equals("--version"))
			return versionAnswer();
		if (arguments.size() == 4 && command.startsWith("plugin marketplace add "))
			return new CommandResult(0, "Added marketplace cat\n");
		return switch (command)
		{
			case "plugin add cat@cat" ->
			{
				load();
				yield new CommandResult(0, "Added cat@cat\n");
			}
			case "plugin remove cat@cat" -> new CommandResult(0, "Removed cat@cat\n");
			case "plugin marketplace remove cat" -> new CommandResult(0, "Removed marketplace cat\n");
			default -> throw new AssertionError("Unexpected command: " + arguments);
		};
	}

	/**
	 * Replaces the plugin's cache with a copy of the marketplace source.
	 *
	 * @throws IOException if the source cannot be read or the copy cannot be written
	 */
	private void load() throws IOException
	{
		CodexInstallation installation = new CodexInstallation(home);
		Path source = installation.marketplacePlugin();
		String version = MAPPER.readTree(Files.readString(source.resolve(".codex-plugin/plugin.json"))).
			path("version").asString();
		Path cache = installation.versionFolders();
		TemporaryDirectories.deleteRecursively(cache);
		Path copy = cache.resolve(version);
		try (Stream<Path> files = Files.walk(source))
		{
			for (Path file : files.toList())
			{
				Path target = copy.resolve(source.relativize(file).toString());
				if (Files.isDirectory(file))
					Files.createDirectories(target);
				else
					Files.copy(file, target);
			}
		}
	}

	/**
	 * Writes CAT's marketplace source as install activates it from the plugin archive: the plugin manifest, stamped
	 * with the version, and the marketplace metadata.
	 *
	 * @param home    the Codex home
	 * @param version the plugin version
	 * @return the marketplace source
	 * @throws IOException if the files cannot be written
	 */
	static Path writeMarketplaceSource(Path home, PluginVersion version) throws IOException
	{
		Path plugin = new CodexInstallation(home).marketplacePlugin();
		Path manifest = plugin.resolve(".codex-plugin/plugin.json");
		Files.createDirectories(manifest.getParent());
		Files.writeString(manifest, "{\"name\":\"cat\",\"version\":\"" + version + "\"}\n");
		Path metadata = plugin.resolve("install/codex-marketplace.json");
		Files.createDirectories(metadata.getParent());
		Files.writeString(metadata, "{\"name\":\"cat\",\"plugins\":[]}\n");
		return plugin;
	}
}
