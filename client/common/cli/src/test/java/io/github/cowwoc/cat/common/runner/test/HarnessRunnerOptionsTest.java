package io.github.cowwoc.cat.common.runner.test;

import static io.github.cowwoc.cat.common.test.SessionHarnesses.HARNESS_A;
import static io.github.cowwoc.cat.common.test.SessionHarnesses.HARNESS_B;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.ToolScope;
import io.github.cowwoc.cat.common.runner.HarnessRunnerOptions;
import io.github.cowwoc.cat.common.runner.HarnessTemporaryRoot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;

/**
 * Tests {@link HarnessRunnerOptions}.
 */
public final class HarnessRunnerOptionsTest
{
	/**
	 * The root of the file system that holds the working directory, so that fixture paths are absolute on every
	 * platform.
	 */
	private static final Path ROOT = Path.of("").toAbsolutePath().getRoot();
	/**
	 * The temporary root of a command that runs in a harness session whose temporary directory is {@code caller-temp}.
	 */
	private static final HarnessTemporaryRoot SESSION = HarnessTemporaryRoot.of(path("caller-temp"));
	private static final String SESSION_ID = "6b1f7c1e-5d0a-4c55-9a8e-2f6c0f1d2e3a";

	/**
	 * Creates the test fixture.
	 */
	public HarnessRunnerOptionsTest()
	{
	}

	/**
	 * Verifies every runner can expose one complete help text without parsing a bridge invocation.
	 */
	@Test
	public void exposesUsage()
	{
		requireThat(HarnessRunnerOptions.usage("harness-runner"), "usage").startsWith("Usage: harness-runner ").
			contains("--artifact <path>").
			contains("--max-concurrency <1..16>").
			contains("--deny-read <path>").
			contains("--fixtures <path>").
			contains("--plugin <path>");
	}

	/**
	 * Verifies bridge options select explicit isolated paths, retained evidence, and CAT installation.
	 */
	@Test
	public void parsesBridgeOptions()
	{
		HarnessRunnerOptions result = HarnessRunnerOptions.parse(new String[] {"--artifact", absolute("artifact"),
			"--project", absolute("project"), "--run-root", absolute("caller-temp/runs"), "--executable", "/bin/harness",
			"--model", "test-model", "--effort", "high", "--output", absolute("output"), "--receipt", absolute("receipt"),
			"--keep-run", "--install-cat"},
			"harness", SESSION);

		requireThat(result.artifact(), "artifact").isEqualTo(path("artifact"));
		requireThat(result.project(), "project").isEqualTo(path("project"));
		requireThat(result.runRoot(), "runRoot").isEqualTo(path("caller-temp/runs"));
		requireThat(result.executable(), "executable").isEqualTo("/bin/harness");
		requireThat(result.model(), "model").isEqualTo("test-model");
		requireThat(result.effort(), "effort").isEqualTo("high");
		requireThat(result.output(), "output").isEqualTo(path("output"));
		requireThat(result.receipt(), "receipt").isEqualTo(path("receipt"));
		requireThat(result.keepRun(), "keepRun").isTrue();
		requireThat(result.installCat(), "installCat").isTrue();
		requireThat(result.includeUserRules(), "includeUserRules").isTrue();
	}

	/**
	 * Verifies controlled evaluations can install CAT without inheriting personal guidance.
	 */
	@Test
	public void excludesUserRulesWhenRequested()
	{
		HarnessRunnerOptions result = HarnessRunnerOptions.parse(new String[] {"--artifact", absolute("artifact"),
			"--install-cat", "--exclude-user-rules"}, "harness", SESSION);

		requireThat(result.installCat(), "installCat").isTrue();
		requireThat(result.includeUserRules(), "includeUserRules").isFalse();
	}

	/**
	 * Verifies that {@code --fixtures} names the directory whose files the project receives, and that it is absent
	 * unless given.
	 */
	@Test
	public void parsesFixtures()
	{
		// Requirement: R-C21
		HarnessRunnerOptions result = HarnessRunnerOptions.parse(new String[] {"--artifact", absolute("artifact"),
			"--fixtures", absolute("runs/fixtures/trial")}, "harness", SESSION);
		HarnessRunnerOptions without = HarnessRunnerOptions.parse(new String[] {"--artifact", absolute("artifact")},
			"harness", SESSION);

		requireThat(result.fixtures(), "fixtures").isEqualTo(path("runs/fixtures/trial"));
		requireThat(without.fixtures(), "without").isNull();
	}

	/**
	 * Verifies that each {@code --plugin} option adds one trial plugin that the isolated home receives, in order, and
	 * that there is none unless given.
	 */
	@Test
	public void parsesTrialPlugins()
	{
		// Requirement: R-W12
		HarnessRunnerOptions result = HarnessRunnerOptions.parse(new String[] {"--artifact", absolute("artifact"),
			"--plugin", absolute("campaign/plugins/cat"), "--plugin", absolute("campaign/plugins/tools")}, "harness",
			SESSION);
		HarnessRunnerOptions without = HarnessRunnerOptions.parse(new String[] {"--artifact", absolute("artifact")},
			"harness", SESSION);

		requireThat(result.plugins(), "plugins").isEqualTo(List.of(path("campaign/plugins/cat"),
			path("campaign/plugins/tools")));
		requireThat(without.plugins(), "without").isEmpty();
	}

	/**
	 * Verifies that each {@code --deny-read} option adds one path that the session must not read, in order.
	 */
	@Test
	public void parsesDeniedReads()
	{
		// Requirement: R-E24
		HarnessRunnerOptions result = HarnessRunnerOptions.parse(new String[] {"--artifact", absolute("artifact"),
			"--deny-read", absolute("campaign/campaign.json"), "--deny-read", absolute("campaign/trials")}, "harness",
			SESSION);

		requireThat(result.deniedReads(),
			"deniedReads").isEqualTo(List.of(path("campaign/campaign.json"),
			path("campaign/trials")));
	}

	/**
	 * Verifies that a relative {@code --deny-read} path is rejected, because the harness could resolve it against
	 * a directory other than the one that holds the content.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class, expectedExceptionsMessageRegExp = ".*campaign.json.*")
	public void rejectsRelativeDeniedRead()
	{
		// Requirement: R-E24
		HarnessRunnerOptions.parse(new String[] {"--artifact", absolute("artifact"), "--deny-read", "campaign.json"},
			"harness", SESSION);
	}

	/**
	 * Verifies that a session denies no reads unless {@code --deny-read} names a path.
	 */
	@Test
	public void defaultsToNoDeniedReads()
	{
		// Requirement: R-E24
		HarnessRunnerOptions result = HarnessRunnerOptions.parse(new String[] {"--artifact", absolute("artifact")},
			"harness", SESSION);

		requireThat(result.deniedReads(), "deniedReads").isEmpty();
	}

	/**
	 * Verifies CAT installation is opt-in for bridge requests.
	 */
	@Test
	public void defaultsToNoCatInstallation()
	{
		HarnessRunnerOptions result = HarnessRunnerOptions.parse(new String[] {"--artifact", absolute("artifact")},
			"harness", SESSION);

		requireThat(result.installCat(), "installCat").isFalse();
	}

	/**
	 * Verifies the artifact is required.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsMissingArtifact()
	{
		HarnessRunnerOptions.parse(new String[0], "harness", SESSION);
	}

	/**
	 * Verifies a receipt has the bridge transcript and retained run that make it independently usable.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsIncompleteReceiptEvidence()
	{
		HarnessRunnerOptions.parse(new String[] {"--artifact", absolute("artifact"), "--receipt", absolute("receipt")},
			"harness", SESSION);
	}

	/**
	 * Verifies exclusion cannot silently suggest that a non-CAT session isolates injected guidance.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsUserRuleExclusionWithoutCat()
	{
		HarnessRunnerOptions.parse(new String[] {"--artifact", absolute("artifact"), "--exclude-user-rules"}, "harness",
			SESSION);
	}

	/**
	 * Verifies the shared capacity ceiling is the default.
	 */
	@Test
	public void defaultsToCapacityCeiling()
	{
		// Requirement: R-E8
		HarnessRunnerOptions result = HarnessRunnerOptions.parse(new String[] {"--artifact", absolute("artifact")},
			"harness", SESSION);

		requireThat(result.maximumConcurrency(), "maximumConcurrency").isEqualTo(16);
	}

	/**
	 * Verifies both ends of the accepted {@code --max-concurrency} range.
	 */
	@Test
	public void acceptsMaximumConcurrencyRange()
	{
		// Requirement: R-E8
		HarnessRunnerOptions lowest = HarnessRunnerOptions.parse(new String[] {"--artifact", absolute("artifact"),
			"--max-concurrency", "1"}, "harness", SESSION);
		HarnessRunnerOptions highest = HarnessRunnerOptions.parse(new String[] {"--artifact", absolute("artifact"),
			"--max-concurrency", "16"}, "harness", SESSION);

		requireThat(lowest.maximumConcurrency(), "lowest").isEqualTo(1);
		requireThat(highest.maximumConcurrency(), "highest").isEqualTo(16);
	}

	/**
	 * Verifies zero is outside the accepted {@code --max-concurrency} range.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsMaximumConcurrencyBelowRange()
	{
		// Requirement: R-E8
		HarnessRunnerOptions.parse(new String[] {"--artifact", absolute("artifact"), "--max-concurrency", "0"}, "harness",
			SESSION);
	}

	/**
	 * Verifies 17 is outside the accepted {@code --max-concurrency} range.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsMaximumConcurrencyAboveRange()
	{
		// Requirement: R-E8
		HarnessRunnerOptions.parse(new String[] {"--artifact", absolute("artifact"), "--max-concurrency", "17"}, "harness",
			SESSION);
	}

	/**
	 * Verifies that without {@code --run-root} the runs go to {@code runner-runs} in the temporary directory of the work
	 * directory of the session that the session variable names.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void defaultsRunRootToSessionTemp() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-runner-options-"));
			Files.createDirectories(project.resolve(".cat"));
			// Windows locates the harness's default configuration directory through USERPROFILE rather than HOME
			try (ToolScope scope = ToolScope.forSession(project, Map.of(HARNESS_B.sessionVariable(), SESSION_ID,
				"HOME", project.toString(), "USERPROFILE", project.toString()), List.of(HARNESS_A, HARNESS_B)))
			{
				HarnessRunnerOptions result = HarnessRunnerOptions.parse(new String[] {"--artifact",
					absolute("artifact")}, "harness", HarnessTemporaryRoot.from(scope));

				Path sessionTemp = project.toAbsolutePath().normalize().resolve(".cat/work").resolve(SESSION_ID).
					resolve("temp");
				requireThat(result.runRoot(), "runRoot").isEqualTo(sessionTemp.resolve("runner-runs"));
				requireThat(sessionTemp, "sessionTemp").isDirectory();
			}
		}
	}

	/**
	 * Verifies that a command line that names {@code --run-root} inside a session leaves the session's work directory
	 * alone.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void explicitRunRootLeavesSessionAlone() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-runner-options-"));
			Files.createDirectories(project.resolve(".cat"));
			try (ToolScope scope = ToolScope.forSession(project, Map.of(HARNESS_B.sessionVariable(), SESSION_ID,
				"HOME", project.toString()), List.of(HARNESS_A, HARNESS_B)))
			{
				HarnessRunnerOptions result = HarnessRunnerOptions.parse(new String[] {"--artifact",
					absolute("artifact"), "--run-root", absolute("runs")}, "harness", HarnessTemporaryRoot.from(scope));

				requireThat(result.runRoot(), "runRoot").isEqualTo(path("runs"));
				requireThat(Files.exists(project.resolve(".cat/work")), "workRootExists").isFalse();
			}
		}
	}

	/**
	 * Verifies that without a session variable the command line must name {@code --run-root}, that the refusal names
	 * both session variables, and that {@code --run-root} selects the runs' directory.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void requiresRunRootWithoutSession() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-runner-options-"));
			Files.createDirectories(project.resolve(".cat"));
			try (ToolScope scope = ToolScope.forSession(project, Map.of("HOME", project.toString()),
				List.of(HARNESS_A, HARNESS_B)))
			{
				HarnessTemporaryRoot temporaryRoot = HarnessTemporaryRoot.from(scope);
				try
				{
					HarnessRunnerOptions.parse(new String[] {"--artifact", absolute("artifact")}, "harness",
						temporaryRoot);
					throw new AssertionError("A command without a session or --run-root chose a run root");
				}
				catch (IllegalArgumentException e)
				{
					requireThat(e.getMessage(), "message").contains("--run-root").contains(HARNESS_A.sessionVariable()).
						contains(HARNESS_B.sessionVariable());
				}

				HarnessRunnerOptions result = HarnessRunnerOptions.parse(new String[] {"--artifact",
					absolute("artifact"), "--run-root", absolute("runs")}, "harness", temporaryRoot);

				requireThat(result.runRoot(), "runRoot").isEqualTo(path("runs"));
				requireThat(Files.exists(project.resolve(".cat/work")), "workRootExists").isFalse();
			}
		}
	}

	/**
	 * Returns an absolute path below the file system root.
	 *
	 * @param relative the path relative to the root, using {@code /} as the separator
	 * @return the path
	 */
	private static Path path(String relative)
	{
		return ROOT.resolve(relative);
	}

	/**
	 * Returns an absolute path below the file system root as a command-line argument.
	 *
	 * @param relative the path relative to the root, using {@code /} as the separator
	 * @return the argument
	 */
	private static String absolute(String relative)
	{
		return path(relative).toString();
	}
}
