package com.holdmyspot.cat.codex.runner.test;

import com.holdmyspot.cat.codex.runner.CodexConnectionConfiguration;
import com.holdmyspot.cat.codex.runner.CodexRunner;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.runner.HarnessDependencyCaches;
import com.holdmyspot.cat.common.runner.SessionRequestAccess;
import com.holdmyspot.cat.common.runner.contract.test.SessionCommandLimitsContract;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.toml.TomlMapper;

/**
 * Runs {@link SessionCommandLimitsContract} against the Codex runner: the environment that it gives its app-server,
 * changed by the shell environment policy of its generated configuration, and the reads and writes that the permission
 * profile of a session's thread, with the profiles that it extends, denies or allows.
 * <p>
 * The policy is applied as Codex applies its {@code exclude} and {@code set} entries. Codex's default exclusion of
 * names that contain {@code KEY}, {@code SECRET} or {@code TOKEN} is not applied, so that the runner must name each
 * credential variable itself.
 */
public final class CodexSessionCommandLimitsTest extends SessionCommandLimitsContract
{
	/**
	 * Creates the test.
	 */
	public CodexSessionCommandLimitsTest()
	{
	}

	@Override
	protected Limits limits(Fixture fixture, int sessionRequests) throws IOException
	{
		Path runHome = fixture.runHome();
		Path configuration = runHome.resolve("config.toml");
		Path userCodexHome = Path.of(fixture.userEnvironment().get("CODEX_HOME"));
		SessionRequestAccess requestAccess = SessionRequestAccess.allowing(fixture.run(), fixture.artifact(),
			sessionRequests, Platform.current()).orElse(null);
		CodexConnectionConfiguration.copy(userCodexHome.resolve("config.toml"), configuration,
			HarnessDependencyCaches.NONE, Files.createDirectories(runHome.resolve("tmp")), 0, requestAccess,
			Platform.current());
		List<Path> readOnly = CodexRunner.sessionReadOnly(List.of(), requestAccess);
		String profile = CodexConnectionConfiguration.addSessionProfile(configuration, readOnly,
			CodexRunner.sessionDeniedReads(fixture.manager(), fixture.run(), readOnly, fixture.deniedReads(),
				Platform.current(), fixture.userEnvironment(), requestAccess));
		JsonNode root = TomlMapper.builder().build().readTree(Files.readString(configuration));

		Map<String, String> commandEnvironment = new HashMap<>(CodexRunner.processEnvironment(
			fixture.userEnvironment(), HarnessDependencyCaches.NONE, runHome, fixture.run().directory()));
		JsonNode policy = root.path("shell_environment_policy");
		for (JsonNode excluded : policy.path("exclude"))
			commandEnvironment.remove(excluded.stringValue());
		JsonNode set = policy.path("set");
		for (String name : set.propertyNames())
			commandEnvironment.put(name, set.get(name).stringValue());

		List<Path> deniedReads = new ArrayList<>();
		// The built-in workspace profile grants the thread's project as its workspace root.
		List<Path> writable = new ArrayList<>(List.of(fixture.run().directory().resolve("project")));
		List<Path> readable = new ArrayList<>();
		// A profile has the entries of the profiles that it extends, up to Codex's built-in workspace profile
		JsonNode permissions = root.path("permissions");
		String current = profile;
		while (permissions.has(current))
		{
			JsonNode filesystem = permissions.path(current).path("filesystem");
			for (String path : filesystem.propertyNames())
			{
				// Codex's own placeholders, such as its temporary directory, are not paths
				if (path.startsWith(":"))
					continue;
				switch (filesystem.get(path).stringValue())
				{
					case "deny" -> deniedReads.add(Path.of(path));
					case "write" -> writable.add(Path.of(path));
					case "read" -> readable.add(Path.of(path));
					default -> throw new AssertionError("Unexpected access to " + path + ": " + filesystem.get(path));
				}
			}
			current = permissions.path(current).path("extends").stringValue("");
		}
		return new Limits(commandEnvironment, deniedReads, writable, readable);
	}

	@Override
	protected List<Path> readableHarnessFiles(Fixture fixture)
	{
		// CAT's read-part command reads the session's tool output limit from the session's configuration, which holds
		// no login
		return List.of(fixture.runHome().resolve("config.toml"));
	}
}
