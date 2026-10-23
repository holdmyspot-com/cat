package com.holdmyspot.cat.claude.runner.test;

import com.holdmyspot.cat.claude.runner.ClaudeConnectionSettings;
import com.holdmyspot.cat.claude.runner.ClaudeRunner;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.runner.HarnessDependencyCaches;
import com.holdmyspot.cat.common.runner.HarnessRunnerScope;
import com.holdmyspot.cat.common.runner.SessionRequestAccess;
import com.holdmyspot.cat.common.runner.contract.test.SessionCommandLimitsContract;
import com.holdmyspot.cat.common.runner.fakes.test.TestHarnessRunnerScope;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * Runs {@link SessionCommandLimitsContract} against the Claude runner: the environment that it gives its Claude
 * process, less the variables that Claude Code removes from its commands' environment, and the reads and writes
 * that its generated settings deny or allow the sandbox's commands.
 */
public final class ClaudeSessionCommandLimitsTest extends SessionCommandLimitsContract
{
	/**
	 * The variables that Claude Code 2.1.308 removes from the environment of its commands and hooks when
	 * {@code CLAUDE_CODE_SUBPROCESS_ENV_SCRUB} is set, among those that the runner gives its process: its credentials
	 * and its configuration directory. A probe of Claude Code 2.1.308 in its sandbox found that its Bash commands and
	 * plugin hooks received neither {@code ANTHROPIC_AUTH_TOKEN} nor {@code CLAUDE_CONFIG_DIR}, and that they still
	 * received {@code ANTHROPIC_BASE_URL}.
	 */
	private static final Set<String> SCRUBBED = Set.of("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN",
		"CLAUDE_CODE_OAUTH_TOKEN", "CLAUDE_CONFIG_DIR");

	/**
	 * Creates the test.
	 */
	public ClaudeSessionCommandLimitsTest()
	{
	}

	@Override
	protected Limits limits(Fixture fixture, int sessionRequests) throws IOException
	{
		try (HarnessRunnerScope scope = new TestHarnessRunnerScope())
		{
			Path runHome = fixture.runHome();
			Path userSettings = fixture.userHome().resolve(".claude/settings.json");
			SessionRequestAccess requestAccess = SessionRequestAccess.allowing(fixture.run(), fixture.artifact(),
				sessionRequests, Platform.current()).orElse(null);
			Map<String, String> processEnvironment = ClaudeRunner.processEnvironment(scope, fixture.userEnvironment(),
				HarnessDependencyCaches.NONE, userSettings, runHome, requestAccess);
			Map<String, String> commandEnvironment = new HashMap<>(processEnvironment);
			if ("1".equals(processEnvironment.get(ClaudeRunner.SUBPROCESS_ENVIRONMENT_SCRUB)))
				commandEnvironment.keySet().removeAll(SCRUBBED);

			Path settings = runHome.resolve(".claude/settings.json");
			ClaudeConnectionSettings.writeIsolatedSettings(scope, userSettings, settings,
				fixture.run().directory().resolve("claude"), runHome, List.of(), fixture.run().directory(),
				ClaudeRunner.sessionDeniedReads(fixture.manager(), fixture.run(), List.of(), List.of(),
					fixture.userEnvironment(), requestAccess), "", requestAccess, Platform.current());
			JsonNode filesystem = scope.json().readTree(Files.readString(settings)).path("sandbox").path("filesystem");
			return new Limits(commandEnvironment, paths(filesystem.path("denyRead")),
				paths(filesystem.path("allowWrite")), paths(filesystem.path("allowRead")));
		}
	}

	/**
	 * Returns the paths that a settings array lists.
	 *
	 * @param array the array, or a missing node
	 * @return the paths
	 */
	private static List<Path> paths(JsonNode array)
	{
		List<Path> result = new ArrayList<>();
		for (JsonNode path : array)
			result.add(Path.of(path.stringValue()));
		return result;
	}
}
