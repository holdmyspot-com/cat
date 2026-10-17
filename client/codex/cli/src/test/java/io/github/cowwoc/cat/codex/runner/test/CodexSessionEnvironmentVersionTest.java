package io.github.cowwoc.cat.codex.runner.test;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.codex.optimizer.CodexSessions;
import io.github.cowwoc.cat.codex.runner.CodexConnectionConfiguration;
import io.github.cowwoc.cat.codex.runner.CodexRunner;
import io.github.cowwoc.cat.common.Platform;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.adapter.RunnerSessions;
import io.github.cowwoc.cat.common.optimizer.port.contract.test.VersionRegistry;
import io.github.cowwoc.cat.common.runner.HarnessCapacity;
import io.github.cowwoc.cat.common.runner.HarnessRun;
import io.github.cowwoc.cat.common.runner.HarnessRunManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.testng.annotations.Test;

/**
 * Guards the session environment version that {@link CodexSessions} declares.
 */
public final class CodexSessionEnvironmentVersionTest
{
	/**
	 * The registry of session environment versions and the hash of the configuration and permission profile of each.
	 */
	private static final Path REGISTRY = Path.of("src/test/resources/optimizer/session-environment-versions.txt");

	/**
	 * Creates the test.
	 */
	public CodexSessionEnvironmentVersionTest()
	{
	}

	/**
	 * Verifies that the declared session environment version is registered with the hash of the configuration and
	 * permission profile that the runner writes for a fixed host configuration, so that changing them requires a
	 * decision about the version.
	 *
	 * @throws IOException if the configuration or the registry cannot be read or written
	 */
	@Test
	public void versionMatchesConfiguration() throws IOException
	{
		// Requirement: R-M1
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-session-environment-"));
			Path source = root.resolve("host.toml");
			Path destination = root.resolve("home/config.toml");
			Path temporaryDirectory = Files.createDirectories(root.resolve("home/tmp"));
			Path npmCache = Files.createDirectories(root.resolve("npm-cache"));
			Path evidence = Files.createDirectories(root.resolve("evidence"));
			Path deniedFile = Files.writeString(root.resolve("campaign.json"), "{}");
			HarnessRunManager manager = new HarnessRunManager(root.resolve("runs"), Clock.systemUTC());
			HarnessRun run = manager.create("codex");
			manager.snapshotProject(run);
			Files.writeString(run.directory().resolve("home/auth.json"), "{}");
			Files.createDirectories(root.resolve("runs/fixtures"));
			Files.writeString(source, """
				openai_base_url = "http://localhost:2455/backend-api/codex"
				forced_login_method = "api"
				model = "gpt-6-sol"
				model_reasoning_effort = "medium"
				approval_policy = "never"

				[sandbox_workspace_write]
				network_access = true

				[permissions.cat]
				extends = ":workspace"
				[permissions.cat.filesystem]
				"/shared" = "read"

				[hooks.state."cat@cat:hooks/hooks.json:session_start:0:0"]
				trusted_hash = "sha256:0000000000000000000000000000000000000000000000000000000000000001"
				""", UTF_8);

			// Windows adds only the choice of sandbox backend that enforces the same profile, so the hash covers the
			// configuration that other platforms receive
			CodexConnectionConfiguration.copy(source, destination, npmCache, temporaryDirectory, 0,
				Platform.LINUX_X64);
			String profile = CodexConnectionConfiguration.addSessionProfile(destination, List.of(evidence),
				CodexRunner.sessionDeniedReads(manager, run, List.of(evidence), List.of(deniedFile),
					Platform.LINUX_X64));
			CodexConnectionConfiguration.copyTrustedCatHooks(source, destination);

			// Run directories differ per run, so the hash covers the configuration with their root and the run's
			// identifier replaced
			String configuration = replaceRoot(Files.readString(destination, UTF_8).replace("\r\n", "\n"), root).
				replace(run.id(), "<run>");
			RunnerSessions.Settings sessionSettings = new RunnerSessions.Settings(root, root, List::of, "", false,
				Map.of(), root, HarnessCapacity.unlimited(), Clock.systemUTC());
			int version = new CodexSessions(sessionSettings).sessionEnvironmentVersion();
			VersionRegistry.requireRegistered(REGISTRY, "session environment", version,
				VersionRegistry.hash(configuration + "\nprofile = " + profile + "\n"));
		}
	}

	/**
	 * Replaces each path below a root in a configuration with {@code <root>} followed by the path's names, separated
	 * by {@code /}, so that every platform hashes the same content.
	 *
	 * @param configuration the configuration
	 * @param root          the root, which the configuration names in its own form or its real form, inside TOML
	 *                      strings that escape each backslash
	 * @return the configuration with the root replaced
	 * @throws IOException if the root's real path cannot be read
	 */
	private static String replaceRoot(String configuration, Path root) throws IOException
	{
		String result = configuration;
		for (Path form : List.of(root.toRealPath(), root))
		{
			String escaped = form.toString().replace("\\", "\\\\");
			result = Pattern.compile(Pattern.quote(escaped) + "([^\"]*)").matcher(result).
				replaceAll(match -> Matcher.quoteReplacement("<root>" + match.group(1).replace("\\\\", "/")));
		}
		return result;
	}
}
