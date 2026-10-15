package io.github.cowwoc.cat.distribution.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_16LE;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.Platform;
import io.github.cowwoc.cat.common.PortableFiles;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests that each hook of the Codex plugin starts its launcher when Codex runs the hook's command. Codex writes the
 * plugin's root directory into the command in place of {@code ${PLUGIN_ROOT}}, then runs {@code commandWindows} in
 * PowerShell on Windows, and {@code command} in {@code sh} elsewhere.
 */
public final class CodexPluginHooksTest
{
	/**
	 * The name of the launcher that each hook event starts.
	 */
	private static final Map<String, String> LAUNCHERS = Map.of("SessionStart", "session-start",
		"PostToolUse", "post-tool-use", "SubagentStart", "subagent-start");

	/** Creates a test instance. */
	public CodexPluginHooksTest()
	{
	}

	/**
	 * Verifies that each hook's command for this platform starts the launcher of its event, from a plugin root
	 * directory whose name holds a space and a single quote, as a user's name can.
	 *
	 * @throws IOException          if the hooks cannot be read or run
	 * @throws InterruptedException if the thread is interrupted while a hook runs
	 */
	@Test
	public void hooksStartTheirLaunchers() throws IOException, InterruptedException
	{
		JsonNode hooks = readHooks();
		Platform platform = Platform.current();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path pluginRoot = cleanup.add(Files.createTempDirectory("cat plugin's hooks-"));
			Path launchers = Files.createDirectories(pluginRoot.resolve("hooks"));
			int commands = 0;
			for (Entry<String, JsonNode> event : hooks.get("hooks").properties())
			{
				String launcher = LAUNCHERS.get(event.getKey());
				requireThat(launcher, event.getKey()).isNotNull();
				writeLauncher(launchers, launcher, platform);
				for (JsonNode group : event.getValue())
				{
					for (JsonNode hook : group.get("hooks"))
					{
						String output = run(hook, pluginRoot, platform);
						requireThat(output.strip(), event.getKey()).isEqualTo("started " + launcher);
						++commands;
					}
				}
			}
			requireThat(commands, "commands").isEqualTo(LAUNCHERS.size());
		}
	}

	/**
	 * Returns the Codex plugin's hook declarations.
	 *
	 * @return the parsed {@code hooks/hooks.json}
	 * @throws IOException if the file cannot be read
	 */
	private static JsonNode readHooks() throws IOException
	{
		ClassLoader loader = CodexPluginHooksTest.class.getClassLoader();
		URL manifest = loader.getResource(".codex-plugin/plugin.json");
		requireThat(manifest, "manifest").isNotNull();
		String root = manifest.toString().substring(0,
			manifest.toString().length() - ".codex-plugin/plugin.json".length());
		// Other plugins on the class path have hooks too; the Codex plugin's file shares the manifest's root
		for (URL hooks : Collections.list(loader.getResources("hooks/hooks.json")))
		{
			if (!hooks.toString().equals(root + "hooks/hooks.json"))
				continue;
			try (InputStream in = hooks.openStream())
			{
				return JsonMapper.builder().build().readTree(in);
			}
		}
		throw new AssertionError("The Codex plugin has no hooks/hooks.json beside " + manifest);
	}

	/**
	 * Writes a launcher that prints that it started, in the form that the platform runs.
	 *
	 * @param directory the directory of the launchers
	 * @param name      the launcher's name without its extension
	 * @param platform  the platform
	 * @throws IOException if the launcher cannot be written
	 */
	private static void writeLauncher(Path directory, String name, Platform platform) throws IOException
	{
		if (platform.isWindows())
		{
			Files.writeString(directory.resolve(name + ".cmd"), "@echo off\r\necho started " + name + "\r\n");
			return;
		}
		Path launcher = Files.writeString(directory.resolve(name + ".sh"), "#!/bin/sh\necho started " + name + "\n");
		if (PortableFiles.hasModes(launcher))
			Files.setPosixFilePermissions(launcher, PosixFilePermissions.fromString("rwxr-xr-x"));
	}

	/**
	 * Runs a hook's command for a platform the way that Codex runs it.
	 *
	 * @param hook       the hook's declaration
	 * @param pluginRoot the plugin's root directory
	 * @param platform   the platform
	 * @return the command's standard output
	 * @throws IOException          if the command cannot be run or fails
	 * @throws InterruptedException if the thread is interrupted while the command runs
	 */
	private static String run(JsonNode hook, Path pluginRoot, Platform platform)
		throws IOException, InterruptedException
	{
		List<String> arguments;
		if (platform.isWindows())
		{
			String command = hook.get("commandWindows").asString().replace("${PLUGIN_ROOT}", pluginRoot.toString());
			// An encoded command reaches PowerShell unchanged, whatever quotes it holds
			arguments = List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand",
				Base64.getEncoder().encodeToString(command.getBytes(UTF_16LE)));
		}
		else
		{
			String command = hook.get("command").asString().replace("${PLUGIN_ROOT}", pluginRoot.toString());
			arguments = List.of("sh", "-c", command);
		}
		try (Process process = new ProcessBuilder(arguments).redirectErrorStream(true).start())
		{
			process.getOutputStream().close();
			String output = new String(process.getInputStream().readAllBytes(), UTF_8);
			int status = process.waitFor();
			requireThat(status, "status").withContext(output, "output").isEqualTo(0);
			return output;
		}
	}
}
