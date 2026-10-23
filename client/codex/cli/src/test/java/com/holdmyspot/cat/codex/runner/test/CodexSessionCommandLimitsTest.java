package com.holdmyspot.cat.codex.runner.test;

import com.holdmyspot.cat.codex.runner.CodexConnectionConfiguration;
import com.holdmyspot.cat.codex.runner.CodexRunner;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.runner.HarnessDependencyCaches;
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
 * changed by the shell environment policy of its generated configuration, and the reads that the permission profile
 * of a session's thread denies.
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
	protected Limits limits(Fixture fixture) throws IOException
	{
		Path runHome = fixture.runHome();
		Path configuration = runHome.resolve("config.toml");
		Path userCodexHome = Path.of(fixture.userEnvironment().get("CODEX_HOME"));
		CodexConnectionConfiguration.copy(userCodexHome.resolve("config.toml"), configuration,
			HarnessDependencyCaches.NONE, Files.createDirectories(runHome.resolve("tmp")), 0, Platform.current());
		String profile = CodexConnectionConfiguration.addSessionProfile(configuration, List.of(),
			CodexRunner.sessionDeniedReads(fixture.manager(), fixture.run(), List.of(), List.of(), Platform.current(),
				fixture.userEnvironment()));
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
		JsonNode filesystem = root.path("permissions").path(profile).path("filesystem");
		for (String path : filesystem.propertyNames())
		{
			if (filesystem.get(path).stringValue().equals("deny"))
				deniedReads.add(Path.of(path));
		}
		return new Limits(commandEnvironment, deniedReads);
	}

	@Override
	protected List<Path> readableHarnessFiles(Fixture fixture)
	{
		// CAT's read-part command reads the session's tool output limit from the session's configuration, which holds
		// no login
		return List.of(fixture.runHome().resolve("config.toml"));
	}
}
