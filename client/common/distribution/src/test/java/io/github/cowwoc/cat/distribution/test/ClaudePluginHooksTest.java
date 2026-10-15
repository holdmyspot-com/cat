package io.github.cowwoc.cat.distribution.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.Platform;
import io.github.cowwoc.cat.common.PortableFiles;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Collections;
import java.util.Map;
import java.util.Map.Entry;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests that each hook of the Claude plugin starts its launcher when Claude Code runs the hook's command. Claude Code
 * runs the command in a POSIX shell, Git Bash on Windows, and the shell expands {@code ${CLAUDE_PLUGIN_ROOT}}, which
 * names the plugin's root directory with {@code /} separators on every platform.
 */
public final class ClaudePluginHooksTest
{
	/**
	 * The name of the launcher that each hook event starts.
	 */
	private static final Map<String, String> LAUNCHERS = Map.of("SessionStart", "session-start",
		"SubagentStart", "subagent-start", "SessionEnd", "session-end");

	/** Creates a test instance. */
	public ClaudePluginHooksTest()
	{
	}

	/**
	 * Verifies that each hook's command starts the launcher of its event, from a plugin root directory whose name
	 * holds a space and a single quote, as a user's name can.
	 *
	 * @throws IOException          if the hooks cannot be read or run
	 * @throws InterruptedException if the thread is interrupted while a hook runs
	 */
	@Test
	public void hooksStartTheirLaunchers() throws IOException, InterruptedException
	{
		JsonNode hooks = readHooks();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path pluginRoot = cleanup.add(Files.createTempDirectory("cat plugin's hooks-"));
			Path launchers = Files.createDirectories(pluginRoot.resolve("hooks"));
			int commands = 0;
			for (Entry<String, JsonNode> event : hooks.get("hooks").properties())
			{
				String launcher = LAUNCHERS.get(event.getKey());
				requireThat(launcher, event.getKey()).isNotNull();
				writeLauncher(launchers, launcher);
				for (JsonNode group : event.getValue())
				{
					for (JsonNode hook : group.get("hooks"))
					{
						String output = run(hook.get("command").asString(), pluginRoot);
						requireThat(output.strip(), event.getKey()).isEqualTo("started " + launcher);
						++commands;
					}
				}
			}
			requireThat(commands, "commands").isEqualTo(LAUNCHERS.size());
		}
	}

	/**
	 * Returns the Claude plugin's hook declarations.
	 *
	 * @return the parsed {@code hooks/hooks.json}
	 * @throws IOException if the file cannot be read
	 */
	private static JsonNode readHooks() throws IOException
	{
		ClassLoader loader = ClaudePluginHooksTest.class.getClassLoader();
		URL manifest = loader.getResource(".claude-plugin/plugin.json");
		requireThat(manifest, "manifest").isNotNull();
		String root = manifest.toString().substring(0,
			manifest.toString().length() - ".claude-plugin/plugin.json".length());
		// Other plugins on the class path have hooks too; the Claude plugin's file shares the manifest's root
		for (URL hooks : Collections.list(loader.getResources("hooks/hooks.json")))
		{
			if (!hooks.toString().equals(root + "hooks/hooks.json"))
				continue;
			try (InputStream in = hooks.openStream())
			{
				return JsonMapper.builder().build().readTree(in);
			}
		}
		throw new AssertionError("The Claude plugin has no hooks/hooks.json beside " + manifest);
	}

	/**
	 * Writes a launcher that prints that it started.
	 *
	 * @param directory the directory of the launchers
	 * @param name      the launcher's name without its extension
	 * @throws IOException if the launcher cannot be written
	 */
	private static void writeLauncher(Path directory, String name) throws IOException
	{
		Path launcher = Files.writeString(directory.resolve(name + ".sh"), "#!/bin/sh\necho started " + name + "\n");
		if (PortableFiles.hasModes(launcher))
			Files.setPosixFilePermissions(launcher, PosixFilePermissions.fromString("rwxr-xr-x"));
	}

	/**
	 * Runs a hook's command the way that Claude Code runs it. The shell reads the command from its standard input,
	 * because on Windows the command line of {@code sh -c} would lose the command's double quotes: Java passes an
	 * argument without spaces unchanged, and the shell's runtime removes the quotes while parsing its command line.
	 *
	 * @param command    the hook's command
	 * @param pluginRoot the plugin's root directory
	 * @return the command's standard output
	 * @throws IOException          if the command cannot be run or fails
	 * @throws InterruptedException if the thread is interrupted while the command runs
	 */
	private static String run(String command, Path pluginRoot) throws IOException, InterruptedException
	{
		String root = pluginRoot.toString();
		if (Platform.current().isWindows())
			root = root.replace('\\', '/');
		ProcessBuilder builder = new ProcessBuilder("sh").redirectErrorStream(true);
		builder.environment().put("CLAUDE_PLUGIN_ROOT", root);
		try (Process process = builder.start())
		{
			try (OutputStream in = process.getOutputStream())
			{
				in.write((command + "\n").getBytes(UTF_8));
			}
			String output = new String(process.getInputStream().readAllBytes(), UTF_8);
			int status = process.waitFor();
			requireThat(status, "status").withContext(output, "output").isEqualTo(0);
			return output;
		}
	}
}
