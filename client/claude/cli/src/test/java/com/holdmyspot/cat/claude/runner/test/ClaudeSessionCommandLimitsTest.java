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
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * Runs {@link SessionCommandLimitsContract} against the Claude runner: the environment that it gives its Claude
 * process, which Claude Code passes to its commands unchanged, and the reads and writes that its generated settings
 * deny or allow the sandbox's commands. The user's harness credentials reach those commands, because the user trusts
 * the runner's process with them and so trusts its sessions; the commands still cannot read the user's login files.
 */
public final class ClaudeSessionCommandLimitsTest extends SessionCommandLimitsContract
{
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
				sessionRequests, Platform.current(), SessionRequestAccess.Shell.POSIX).orElse(null);
			Map<String, String> processEnvironment = ClaudeRunner.processEnvironment(scope, fixture.userEnvironment(),
				HarnessDependencyCaches.NONE, userSettings, runHome, requestAccess);

			Path settings = runHome.resolve(".claude/settings.json");
			ClaudeConnectionSettings.writeIsolatedSettings(scope, userSettings, settings,
				fixture.run().directory().resolve("claude"), runHome, List.of(), fixture.run().directory(),
				ClaudeRunner.sessionDeniedReads(fixture.manager(), fixture.run(), List.of(), fixture.deniedReads(),
					fixture.userEnvironment(), requestAccess), "", requestAccess,
				fixture.run().directory().resolve("project"), settingsPlatform());
			JsonNode filesystem = scope.json().readTree(Files.readString(settings)).path("sandbox").path("filesystem");
			return new Limits(processEnvironment, paths(filesystem.path("denyRead")),
				paths(filesystem.path("allowWrite")), paths(filesystem.path("allowRead")));
		}
	}

	/**
	 * Returns the platform whose Claude session settings the test describes. Claude sessions run only on Linux and
	 * macOS, because Claude Code has no command sandbox on native Windows, so on a Windows host the settings are
	 * written for Linux; the rest of the contract still follows the host's paths, environment variables and launcher
	 * names.
	 *
	 * @return the host's platform, or Linux if the host is native Windows
	 */
	private static Platform settingsPlatform()
	{
		Platform platform = Platform.current();
		if (platform.isWindows())
			return Platform.LINUX_X64;
		return platform;
	}

	@Override
	protected Path ownLogin(Fixture fixture)
	{
		// The runner copies the user's login into the configuration directory in the run's home, which
		// CLAUDE_CONFIG_DIR names for the session
		return fixture.runHome().resolve(".claude/.credentials.json");
	}

	@Override
	protected Set<String> credentialsGivenToCommands()
	{
		return Set.of("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN", "CLAUDE_CODE_OAUTH_TOKEN");
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
