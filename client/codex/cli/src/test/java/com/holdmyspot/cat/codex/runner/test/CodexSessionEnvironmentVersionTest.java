package com.holdmyspot.cat.codex.runner.test;


import com.holdmyspot.cat.common.fakes.test.TestJvmScope;

import com.holdmyspot.cat.common.fakes.test.Yielding;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.codex.optimizer.CodexSessions;
import com.holdmyspot.cat.codex.runner.CodexConnectionConfiguration;
import com.holdmyspot.cat.codex.runner.CodexRunner;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.adapter.RunnerSessions;
import com.holdmyspot.cat.common.optimizer.port.contract.test.VersionRegistry;
import com.holdmyspot.cat.common.runner.HarnessCapacity;
import com.holdmyspot.cat.common.runner.HarnessDependencyCaches;
import com.holdmyspot.cat.common.runner.HarnessRun;
import com.holdmyspot.cat.common.runner.HarnessRunManager;

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
	 * The registry of session environment versions and the hash of the configuration, permission profile and developer
	 * instructions of each.
	 */
	private static final Path REGISTRY = Path.of("src/test/resources/optimizer/session-environment-versions.txt");

	/**
	 * Creates the test.
	 */
	public CodexSessionEnvironmentVersionTest()
	{
	}

	/**
	 * Verifies that the declared session environment version is registered with the hash of the configuration,
	 * permission profile and developer instructions that the runner gives a session for a fixed host configuration, so
	 * that changing them requires a decision about the version.
	 *
	 * @throws IOException if the configuration or the registry cannot be read or written
	 */
	@Test
	public void versionMatchesConfiguration() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-M1
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory("cat-codex-session-environment-"));
				Path source = root.resolve("host.toml");
				Path destination = root.resolve("home/config.toml");
				Path temporaryDirectory = Files.createDirectories(root.resolve("home/tmp"));
				Path npmCache = Files.createDirectories(root.resolve("npm-cache"));
				Path mavenCache = Files.createDirectories(root.resolve("maven-cache"));
				HarnessDependencyCaches caches = new HarnessDependencyCaches(List.of(npmCache, mavenCache),
					Map.of("NPM_CONFIG_CACHE", npmCache.toString(), "MAVEN_OPTS",
						"-Dmaven.repo.local=" + mavenCache.resolve("repository")));
				Path evidence = Files.createDirectories(root.resolve("evidence"));
				Path deniedFile = Files.writeString(root.resolve("campaign.json"), "{}");
				Path hostProject = Files.createDirectories(root.resolve("project"));
				Files.writeString(hostProject.resolve("AGENTS.md"), "Follow the handbook.", UTF_8);
				HarnessRunManager manager = new HarnessRunManager(hostProject.resolve("runs"), Clock.systemUTC(),
					Yielding.fileReplacer());
				HarnessRun run = manager.create("codex");
				manager.snapshotProject(run);
				Files.writeString(run.directory().resolve("home/auth.json"), "{}");
				Files.createDirectories(hostProject.resolve("runs/workspaces"));
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
				CodexConnectionConfiguration.copy(source, destination, caches, temporaryDirectory, 0,
					Platform.LINUX_X64);
				String profile = CodexConnectionConfiguration.addSessionProfile(destination, List.of(evidence),
					CodexRunner.sessionDeniedReads(manager, run, List.of(evidence), List.of(deniedFile, hostProject),
						Platform.LINUX_X64, Map.of()));
				CodexConnectionConfiguration.copyTrustedCatHooks(source, destination);

				// The instructions name paths in plain text, which each platform separates in its own way
				String instructions = CodexRunner.developerInstructions(manager.root().toRealPath(),
					run.directory().resolve("project"), run.directory().resolve("home"), "").
					replace(root.toRealPath().toString(), "<root>").replace(root.toString(), "<root>").replace('\\', '/');

				// Run directories differ per run, so the hash covers the configuration and instructions with their root
				// and the run's identifier replaced
				String configuration = (replaceRoot(Files.readString(destination, UTF_8).replace("\r\n", "\n"), root) +
					"\ninstructions = " + instructions + "\n").replace(run.id(), "<run>");
				RunnerSessions.Settings sessionSettings = new RunnerSessions.Settings(jvm, root, root, List::of, "", false,
					Map.of(), root, HarnessCapacity.unlimited(), Clock.systemUTC(), Yielding.fileReplacer());
				int version = new CodexSessions(sessionSettings).sessionEnvironmentVersion();
				VersionRegistry.requireRegistered(REGISTRY, "session environment", version,
					VersionRegistry.hash(configuration + "\nprofile = " + profile + "\n"));
			}
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
