package com.holdmyspot.cat.common.runner.test;

import static com.holdmyspot.cat.common.test.SessionHarnesses.HARNESS_A;
import static com.holdmyspot.cat.common.test.SessionHarnesses.HARNESS_B;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static org.testng.Assert.expectThrows;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.ToolScope;
import com.holdmyspot.cat.common.runner.HarnessRunnerOptions;
import com.holdmyspot.cat.common.runner.HarnessTemporaryRoot;
import com.holdmyspot.cat.common.runner.InvalidArgumentsException;
import com.holdmyspot.cat.common.runner.TrialPluginInput;
import com.holdmyspot.cat.common.runner.launcher.RunnerArguments;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import com.holdmyspot.cat.common.fakes.test.GitProjects;
import com.holdmyspot.cat.common.fakes.test.Yielding;

/**
 * Tests {@link HarnessRunnerOptions}.
 */
public final class HarnessRunnerOptionsTest
{
	/**
	 * A root directory of the default file system, so that fixture paths are absolute on every platform.
	 */
	private static final Path ROOT = FileSystems.getDefault().getRootDirectories().iterator().next();
	/**
	 * The temporary root of a command that runs in a harness session whose temporary directory is {@code caller-temp}.
	 */
	private static final HarnessTemporaryRoot SESSION = HarnessTemporaryRoot.of(path("caller-temp"));
	private static final String SESSION_ID = "6b1f7c1e-5d0a-4c55-9a8e-2f6c0f1d2e3a";

	/**
	 * Returns a launch that names an artifact, a model and an effort, followed by the given options.
	 *
	 * @param options the launch's other options
	 * @return the launch's arguments
	 */
	private static String[] launch(String... options)
	{
		List<String> arguments = new ArrayList<>(List.of("--artifact", absolute("artifact"), "--model", "model-a",
			"--effort", "effort-a"));
		arguments.addAll(List.of(options));
		return arguments.toArray(String[]::new);
	}

	/**
	 * Creates the test fixture.
	 */
	public HarnessRunnerOptionsTest()
	{
	}

	/**
	 * Verifies that a launch with invalid arguments prints only the reason, as one line without a stack trace, and
	 * ends with the status of a refused launch.
	 */
	@Test
	public void invalidArgumentsEndAsRefusedLaunch() throws IOException
	{
		StringWriter errors = new StringWriter();
		int status = HarnessRunnerOptions.refusingInvalidArguments(() ->
		{
			HarnessRunnerOptions.parse(launch("--receipt", absolute("receipt")), "claude", SESSION);
			return 0;
		}, new PrintWriter(errors, true));
		requireThat(status, "status").isEqualTo(HarnessRunnerOptions.REFUSED_LAUNCH_STATUS);
		requireThat(errors.toString(), "errors").isEqualTo("--receipt requires --output" +
			System.lineSeparator());
	}

	/**
	 * Verifies that a launch outside a harness session that names no {@code --results} prints only the reason, as
	 * one line without a stack trace, and ends with the status of a refused launch.
	 */
	@Test
	public void missingResultsEndsAsRefusedLaunch() throws IOException
	{
		HarnessTemporaryRoot outsideSession = HarnessTemporaryRoot.withoutSession("CLAUDE_CODE_SESSION_ID is unset");
		StringWriter errors = new StringWriter();
		int status = HarnessRunnerOptions.refusingInvalidArguments(() ->
		{
			HarnessRunnerOptions.parse(launch(), "harness", outsideSession);
			return 0;
		}, new PrintWriter(errors, true));
		requireThat(status, "status").isEqualTo(HarnessRunnerOptions.REFUSED_LAUNCH_STATUS);
		requireThat(errors.toString(), "errors").isEqualTo("--results is required outside a harness session: " +
			"CLAUDE_CODE_SESSION_ID is unset" + System.lineSeparator());
	}

	/**
	 * Verifies that a launch that does not refuse its arguments ends with its own status.
	 */
	@Test
	public void validLaunchKeepsItsStatus() throws IOException
	{
		StringWriter errors = new StringWriter();
		int status = HarnessRunnerOptions.refusingInvalidArguments(() -> 75, new PrintWriter(errors, true));
		requireThat(status, "status").isEqualTo(75);
		requireThat(errors.toString(), "errors").isEmpty();
	}

	/**
	 * Verifies that a failure inside the running launch, including an {@link IllegalArgumentException}, is not reported
	 * as a refusal.
	 */
	@Test
	public void unexpectedFailuresPropagate() throws IOException
	{
		StringWriter errors = new StringWriter();
		PrintWriter writer = new PrintWriter(errors, true);
		try
		{
			HarnessRunnerOptions.refusingInvalidArguments(() ->
			{
				throw new IOException("disk full");
			}, writer);
			throw new AssertionError("Expected an IOException");
		}
		catch (IOException e)
		{
			requireThat(e.getMessage(), "message").isEqualTo("disk full");
		}
		try
		{
			HarnessRunnerOptions.refusingInvalidArguments(() ->
			{
				throw new IllegalArgumentException("broken");
			}, writer);
			throw new AssertionError("Expected an IllegalArgumentException");
		}
		catch (IllegalArgumentException e)
		{
			requireThat(e.getMessage(), "message").isEqualTo("broken");
		}
		requireThat(errors.toString(), "errors").isEmpty();
	}

	/**
	 * Verifies every runner can expose one complete help text without parsing a bridge invocation, and that the text
	 * offers no option that chooses the artifact or the harness program that the launcher runs.
	 */
	@Test
	public void exposesUsage()
	{
		requireThat(HarnessRunnerOptions.usage("harness-runner"), "usage").startsWith("Usage: harness-runner ").
			doesNotContain("--artifact").
			doesNotContain("--executable").
			doesNotContain("--exclude-user-rules").
			contains("--project <path>").
			contains(" (--model <name> --effort <level> | --agent <name>) ").
			contains("--max-concurrency <1..16>").
			contains("--deny-read <path>").
			contains("[--extra-permissions <file>]").
			contains("--workspace <path>").
			contains("--plugin <path>").
			contains("[--session-transcripts]").
			contains("[--session-requests <count>]").
			contains("[--request-depth <levels>]").
			contains("[--input <file> | --conversation <folder>]").
			contains("[--capacity-wait <seconds>]");
	}

	/**
	 * Verifies bridge options select explicit isolated paths, retained evidence, and CAT installation.
	 */
	@Test
	public void parsesBridgeOptions()
	{
		HarnessRunnerOptions result = HarnessRunnerOptions.parse(new String[] {"--artifact", absolute("artifact"),
			"--project", absolute("project"), "--results", absolute("caller-temp/results"), "--model",
			"test-model", "--effort", "high", "--output", absolute("output"), "--receipt", absolute("receipt"),
			"--install-cat"},
			"harness", SESSION);

		requireThat(result.artifact(), "artifact").isEqualTo(path("artifact"));
		requireThat(result.project(), "project").isEqualTo(path("project"));
		requireThat(result.resultsFolder(), "resultsFolder").isEqualTo(path("caller-temp/results"));
		requireThat(result.model(), "model").isEqualTo("test-model");
		requireThat(result.effort(), "effort").isEqualTo("high");
		requireThat(result.output(), "output").isEqualTo(path("output"));
		requireThat(result.receipt(), "receipt").isEqualTo(path("receipt"));
		requireThat(result.installCat(), "installCat").isTrue();
	}

	/**
	 * Verifies that a launch may name the identifier of the nested session, which a slot gives so that the nested session
	 * keeps its paths.
	 */
	@Test
	public void parsesSlotId()
	{
		HarnessRunnerOptions result = HarnessRunnerOptions.parse(new String[] {"--artifact", absolute("artifact"),
			"--project", absolute("project"), "--results", absolute("slot"), "--slot-id", "slot-1", "--model",
			"test-model", "--effort", "high"}, "harness", SESSION);

		requireThat(result.slotId(), "slotId").isEqualTo("slot-1");
	}

	/**
	 * Verifies a broker startup permit requires a slot and can be recovered from the runner's arguments.
	 */
	// Requirement: R-I9
	@Test
	public void parsesStartupPermit()
	{
		String generation = "6b1f7c1e-5d0a-4c55-9a8e-2f6c0f1d2e3a";
		String[] args = launch("--project", absolute("project"), "--results", absolute("slot"), "--slot-id", "slot-1",
			RunnerArguments.STARTUP_PERMIT_OPTION, generation);
		HarnessRunnerOptions.parse(args, "harness", SESSION);
		requireThat(HarnessRunnerOptions.startupPermit(args).orElseThrow(), "startupPermit").isEqualTo(generation);
		expectThrows(IllegalArgumentException.class, () -> HarnessRunnerOptions.parse(launch(
			RunnerArguments.STARTUP_PERMIT_OPTION, generation), "harness", SESSION));
	}

	/**
	 * Verifies that the runner accepts an inherited read restriction only with both a resumed session and a startup
	 * permit.
	 */
	@Test
	public void requiresManagedResumeForInheritedDenial()
	{
		String option = RunnerArguments.INHERITED_DENY_READ_OPTION;
		String[] noResume = launch("--slot-id", "slot-1", RunnerArguments.STARTUP_PERMIT_OPTION,
			"6b1f7c1e-5d0a-4c55-9a8e-2f6c0f1d2e3a", option, absolute("denied"));
		String[] noPermit = launch("--slot-id", "slot-1", RunnerArguments.RESUME_SLOT_OPTION,
			"5f0c2a8e-7b1d-4e3a-9c6f-2d8b1a4e7c90", option, absolute("denied"));

		for (String[] args : List.of(noResume, noPermit))
		{
			IllegalArgumentException refusal = expectThrows(IllegalArgumentException.class,
				() -> HarnessRunnerOptions.parse(args, "harness", SESSION));
			requireThat(refusal.getMessage(), "refusal").contains(option).contains(RunnerArguments.RESUME_SLOT_OPTION).
				contains(RunnerArguments.STARTUP_PERMIT_OPTION);
		}
	}

	/**
	 * Verifies that a launch for a session of the held-out part is accepted by the runner, which does not need the
	 * part itself.
	 */
	@Test
	public void acceptsHeldOutLaunch()
	{
		HarnessRunnerOptions result = HarnessRunnerOptions.parse(new String[] {"--artifact", absolute("artifact"),
			"--project", absolute("project"), "--results", absolute("slot"), "--held-out", "--model", "test-model",
			"--effort", "high"}, "harness", SESSION);

		requireThat(result.model(), "model").isEqualTo("test-model");
	}

	/**
	 * Verifies that a launch that names no slot ID leaves the nested session's identifier to the runner.
	 */
	@Test
	public void leavesSlotIdToRunner()
	{
		HarnessRunnerOptions result = HarnessRunnerOptions.parse(new String[] {"--artifact", absolute("artifact"),
			"--project", absolute("project"), "--results", absolute("slot"), "--model", "test-model", "--effort",
			"high"}, "harness", SESSION);

		requireThat(result.slotId(), "slotId").isNull();
	}

	/**
	 * Verifies that a slot ID that could name another directory is refused.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void refusesSlotIdThatIsNotAnIdentifier()
	{
		HarnessRunnerOptions.parse(new String[] {"--artifact", absolute("artifact"), "--project", absolute("project"),
			"--results", absolute("slot"), "--slot-id", "../escape", "--model", "test-model", "--effort", "high"},
			"harness", SESSION);
	}

	/**
	 * Returns launches that name an option that no runner accepts, wherever it appears in the launch.
	 *
	 * @return each launch
	 */
	@DataProvider
	public Object[][] unsupportedOptions()
	{
		return new Object[][]{{launch("--executable", "/bin/harness")}, {launch("--exclude-user-rules", "--install-cat")},
			{launch("--install-cat", "--exclude-user-rules")}};
	}

	/**
	 * Verifies that a launch naming an option that no runner accepts, such as {@code --executable} or
	 * {@code --exclude-user-rules}, is refused as an unsupported option, also when the option is the launch's last
	 * argument.
	 *
	 * @param launch the launch
	 */
	@Test(dataProvider = "unsupportedOptions", expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = "Unsupported option: --(executable|exclude-user-rules)")
	public void refusesUnsupportedOption(String[] launch)
	{
		HarnessRunnerOptions.parse(launch, "harness", SESSION);
	}

	/**
	 * Verifies that {@code --conversation} names the folder of a conversation, which is absent unless given, and that a
	 * launch that also names {@code --input} is refused.
	 */
	@Test
	public void parsesConversation()
	{
		HarnessRunnerOptions result = HarnessRunnerOptions.parse(launch("--conversation", absolute("talk")), "harness",
			SESSION);
		HarnessRunnerOptions without = HarnessRunnerOptions.parse(launch(), "harness", SESSION);
		IllegalArgumentException both = expectThrows(IllegalArgumentException.class, () ->
			HarnessRunnerOptions.parse(launch("--conversation", absolute("talk"), "--input", absolute("turns")),
				"harness", SESSION));

		requireThat(result.conversation(), "conversation").isEqualTo(Path.of(absolute("talk")));
		requireThat(without.conversation(), "without").isNull();
		requireThat(both.getMessage(), "both").isEqualTo("--conversation and --input cannot be used together");
	}

	/**
	 * Verifies that {@code --workspace} names the directory whose files the project receives, and that it is absent
	 * unless given.
	 */
	@Test
	public void parsesFixtures()
	{
		// Requirement: R-C21
		HarnessRunnerOptions result = HarnessRunnerOptions.parse(launch("--workspace",
			absolute("results/workspaces/trial")), "harness", SESSION);
		HarnessRunnerOptions without = HarnessRunnerOptions.parse(launch(),
			"harness", SESSION);

		requireThat(result.workspace(), "workspace").isEqualTo(path("results/workspaces/trial"));
		requireThat(without.workspace(), "without").isNull();
	}

	/**
	 * Verifies that each {@code --plugin} option adds one trial plugin that the isolated home receives, in order, and
	 * that there is none unless given.
	 */
	@Test
	public void parsesTrialPlugins()
	{
		// Requirement: R-W12
		HarnessRunnerOptions result = HarnessRunnerOptions.parse(launch("--plugin",
			absolute("campaign/plugins/cat"), "--plugin", absolute("campaign/plugins/tools")), "harness",
			SESSION);
		HarnessRunnerOptions without = HarnessRunnerOptions.parse(launch(),
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
		HarnessRunnerOptions result = HarnessRunnerOptions.parse(launch("--deny-read",
			absolute("campaign/campaign.json"), "--deny-read", absolute("campaign/trials")), "harness",
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
		HarnessRunnerOptions.parse(launch("--deny-read", "campaign.json"),
			"harness", SESSION);
	}

	/**
	 * Verifies that a session denies no reads unless {@code --deny-read} names a path.
	 */
	@Test
	public void defaultsToNoDeniedReads()
	{
		// Requirement: R-E24
		HarnessRunnerOptions result = HarnessRunnerOptions.parse(launch(),
			"harness", SESSION);

		requireThat(result.deniedReads(), "deniedReads").isEmpty();
	}

	/**
	 * Verifies CAT installation is opt-in for bridge requests.
	 */
	@Test
	public void defaultsToNoCatInstallation()
	{
		HarnessRunnerOptions result = HarnessRunnerOptions.parse(launch(),
			"harness", SESSION);

		requireThat(result.installCat(), "installCat").isFalse();
	}

	/**
	 * Verifies the artifact is required.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class, expectedExceptionsMessageRegExp = "Missing --artifact")
	public void rejectsMissingArtifact()
	{
		HarnessRunnerOptions.parse(new String[] {"--project", absolute("project"), "--results", absolute("results"),
			"--model", "model-a", "--effort", "effort-a"}, "harness", SESSION);
	}

	/**
	 * Verifies that a launch without {@code --artifact} runs the artifact that holds the launcher, and that a named
	 * artifact or the {@code serve} command keeps working.
	 */
	@Test
	public void defaultsToLauncherArtifact()
	{
		Path own = path("own-artifact");

		HarnessRunnerOptions unnamed = HarnessRunnerOptions.parse(HarnessRunnerOptions.withArtifact(
			new String[] {"--project", absolute("project"), "--model", "model-a", "--effort", "effort-a"}, own),
				"harness", SESSION);
		HarnessRunnerOptions named = HarnessRunnerOptions.parse(HarnessRunnerOptions.withArtifact(
			launch(), own), "harness", SESSION);
		HarnessRunnerOptions served = HarnessRunnerOptions.parseServe(HarnessRunnerOptions.withArtifact(
			new String[] {"serve", "--project", absolute("project"), "--model", "model-a", "--effort", "effort-a"},
			own), "harness", SESSION);

		requireThat(unnamed.artifact(), "unnamed").isEqualTo(own);
		requireThat(unnamed.project(), "project").isEqualTo(path("project"));
		requireThat(named.artifact(), "named").isEqualTo(path("artifact"));
		requireThat(served.artifact(), "served").isEqualTo(own);
	}

	/**
	 * Verifies a receipt has the bridge transcript that makes it independently usable.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsIncompleteReceiptEvidence()
	{
		HarnessRunnerOptions.parse(launch("--receipt", absolute("receipt")),
			"harness", SESSION);
	}

	/**
	 * Verifies that {@code --session-transcripts} asks for the harness's native transcripts, and that a nested session
	 * asks for none unless it is given.
	 */
	@Test
	public void parsesSessionTranscripts()
	{
		// Requirement: R-I7
		HarnessRunnerOptions requested = HarnessRunnerOptions.parse(launch("--session-transcripts"), "harness",
			SESSION);
		HarnessRunnerOptions without = HarnessRunnerOptions.parse(launch(), "harness", SESSION);

		requireThat(requested.sessionTranscripts(), "requested").isTrue();
		requireThat(without.sessionTranscripts(), "without").isFalse();
	}

	/**
	 * Verifies that {@code --keep-run} is no longer an option: every nested session's results are copied to its
	 * results folder, so a launch that names it is refused as one that names any unknown option.
	 */
	@Test
	public void rejectsKeepRun()
	{
		// Requirement: R-I13
		try
		{
			HarnessRunnerOptions.parse(launch("--keep-run"), "harness", SESSION);
			throw new AssertionError("A launch accepted --keep-run");
		}
		catch (IllegalArgumentException e)
		{
			requireThat(e.getMessage(), "message").isEqualTo("Unsupported option: --keep-run");
		}
	}

	/**
	 * Verifies that {@code --session-requests} sets how many sessions the session may request, and that a nested session
	 * allows none unless it is given.
	 */
	@Test
	public void parsesSessionRequests()
	{
		// Requirement: R-I9, R-I11
		HarnessRunnerOptions requested = HarnessRunnerOptions.parse(launch("--session-requests", "3"), "harness", SESSION);
		HarnessRunnerOptions without = HarnessRunnerOptions.parse(launch(),
			"harness", SESSION);

		requireThat(requested.sessionRequests(), "requested").isEqualTo(3);
		requireThat(without.sessionRequests(), "without").isEqualTo(0);
	}

	/**
	 * Verifies that a negative request count is refused.
	 */
	@Test
	public void rejectsNegativeSessionRequests()
	{
		// Requirement: R-I9
		try
		{
			HarnessRunnerOptions.parse(launch("--session-requests", "-1"),
				"harness", SESSION);
			throw new AssertionError("A negative --session-requests was accepted");
		}
		catch (IllegalArgumentException e)
		{
			requireThat(e.getMessage(), "message").contains("--session-requests");
		}
	}

	/**
	 * Verifies that {@code --request-depth} sets how many levels of requested sessions may request sessions in turn,
	 * and that a nested session's requested sessions may request none unless it is given.
	 */
	@Test
	public void parsesRequestDepth()
	{
		// Requirement: R-I11
		HarnessRunnerOptions nested = HarnessRunnerOptions.parse(launch("--session-requests", "2",
			"--request-depth", "1"), "harness", SESSION);
		HarnessRunnerOptions flat = HarnessRunnerOptions.parse(launch("--session-requests", "2"), "harness", SESSION);

		requireThat(nested.requestDepth(), "nested").isEqualTo(1);
		requireThat(flat.requestDepth(), "flat").isEqualTo(0);
	}

	/**
	 * Verifies that a request depth is refused unless the nested session's harness session may request sessions, since no
	 * requested session would exist to use it.
	 */
	@Test
	public void requiresSessionRequestsForRequestDepth()
	{
		// Requirement: R-I11
		try
		{
			HarnessRunnerOptions.parse(launch("--request-depth", "1"),
				"harness", SESSION);
			throw new AssertionError("A --request-depth without --session-requests was accepted");
		}
		catch (IllegalArgumentException e)
		{
			requireThat(e.getMessage(), "message").contains("--request-depth").contains("--session-requests");
		}
	}

	/**
	 * Verifies that {@code --input} names the file that holds the nested session's JSON Lines records, and that the
	 * records come from standard input unless it is given.
	 */
	@Test
	public void parsesInputFile()
	{
		// Requirement: R-I11
		HarnessRunnerOptions file = HarnessRunnerOptions.parse(launch("--input", absolute("turns.jsonl")),
			"harness", SESSION);
		HarnessRunnerOptions standard = HarnessRunnerOptions.parse(launch(),
			"harness", SESSION);

		requireThat(file.input(), "file").isEqualTo(path("turns.jsonl"));
		requireThat(standard.input(), "standard").isNull();
	}

	/**
	 * Verifies that {@code --capacity-wait} bounds the wait for a capacity slot, and that a nested session waits without
	 * a bound unless it is given.
	 */
	@Test
	public void parsesCapacityWait()
	{
		// Requirement: R-I9
		HarnessRunnerOptions bounded = HarnessRunnerOptions.parse(launch("--capacity-wait", "600"), "harness", SESSION);
		HarnessRunnerOptions unbounded = HarnessRunnerOptions.parse(launch(),
			"harness", SESSION);

		requireThat(bounded.capacityWait(), "bounded").isEqualTo(Duration.ofSeconds(600));
		requireThat(unbounded.capacityWait(), "unbounded").isNull();
	}

	/**
	 * Verifies that a capacity wait below one second is refused.
	 */
	@Test
	public void rejectsZeroCapacityWait()
	{
		// Requirement: R-I9
		try
		{
			HarnessRunnerOptions.parse(launch("--capacity-wait", "0"),
				"harness", SESSION);
			throw new AssertionError("A zero --capacity-wait was accepted");
		}
		catch (IllegalArgumentException e)
		{
			requireThat(e.getMessage(), "message").contains("--capacity-wait");
		}
	}

	/**
	 * Verifies the shared capacity ceiling is the default.
	 */
	@Test
	public void defaultsToCapacityCeiling()
	{
		// Requirement: R-E8
		HarnessRunnerOptions result = HarnessRunnerOptions.parse(launch(),
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
		HarnessRunnerOptions lowest = HarnessRunnerOptions.parse(launch("--max-concurrency", "1"), "harness", SESSION);
		HarnessRunnerOptions highest = HarnessRunnerOptions.parse(launch("--max-concurrency", "16"), "harness", SESSION);

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
		HarnessRunnerOptions.parse(launch("--max-concurrency", "0"), "harness",
			SESSION);
	}

	/**
	 * Verifies 17 is outside the accepted {@code --max-concurrency} range.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void rejectsMaximumConcurrencyAboveRange()
	{
		// Requirement: R-E8
		HarnessRunnerOptions.parse(launch("--max-concurrency", "17"), "harness",
			SESSION);
	}

	/**
	 * Verifies that without {@code --results} the nested sessions go to {@code nested-sessions} in the temporary
	 * directory of the CAT session directory of the session that the session variable names.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void defaultsResultsFolderToSessionTemp() throws IOException
	{
		// Requirement: R-W13
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-runner-options-"));
			Files.createDirectories(project.resolve(".cat"));
			Path home = cleanup.add(Files.createTempDirectory("cat-runner-home-"));
			// Windows locates the harness's default configuration directory through USERPROFILE rather than HOME
			try (ToolScope scope = ToolScope.forSession(project, Map.of(HARNESS_B.sessionVariable(), SESSION_ID,
				"HOME", home.toString(), "USERPROFILE", home.toString()), List.of(HARNESS_A, HARNESS_B),
					Yielding.waiting(), GitProjects.IGNORING))
			{
				HarnessRunnerOptions result = HarnessRunnerOptions.parse(launch(), "harness", HarnessTemporaryRoot.from(scope));

				Path sessionTemp = project.toAbsolutePath().normalize().resolve(".cat/sessions").resolve(SESSION_ID).
					resolve("temp");
				requireThat(result.resultsFolder(), "resultsFolder").isEqualTo(sessionTemp.resolve("nested-sessions"));
				requireThat(sessionTemp, "sessionTemp").isDirectory();
			}
		}
	}

	/**
	 * Verifies that a command line that names {@code --results} inside a session leaves the session's CAT session
	 * directory alone.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void explicitResultsLeavesSessionAlone() throws IOException
	{
		// Requirement: R-W13
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-runner-options-"));
			Files.createDirectories(project.resolve(".cat"));
			try (ToolScope scope = ToolScope.forSession(project, Map.of(HARNESS_B.sessionVariable(), SESSION_ID,
				"HOME", project.toString()), List.of(HARNESS_A, HARNESS_B), Yielding.waiting(), GitProjects.IGNORING))
			{
				HarnessRunnerOptions result = HarnessRunnerOptions.parse(launch("--results", absolute("results")),
					"harness", HarnessTemporaryRoot.from(scope));

				requireThat(result.resultsFolder(), "resultsFolder").isEqualTo(path("results"));
				requireThat(Files.exists(project.resolve(".cat/sessions")), "sessionsRootExists").isFalse();
			}
		}
	}

	/**
	 * Verifies that without a session variable the command line must name {@code --results}, that the refusal names
	 * both session variables, and that {@code --results} selects the nested sessions' directory.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void requiresResultsWithoutSession() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-runner-options-"));
			Files.createDirectories(project.resolve(".cat"));
			try (ToolScope scope = ToolScope.forSession(project, Map.of("HOME", project.toString()),
				List.of(HARNESS_A, HARNESS_B), Yielding.waiting(), GitProjects.IGNORING))
			{
				HarnessTemporaryRoot temporaryRoot = HarnessTemporaryRoot.from(scope);
				try
				{
					HarnessRunnerOptions.parse(launch(), "harness",
						temporaryRoot);
					throw new AssertionError("A command without a session or --results chose a results folder");
				}
				catch (IllegalArgumentException e)
				{
					requireThat(e.getMessage(), "message").contains("--results").contains(HARNESS_A.sessionVariable()).
						contains(HARNESS_B.sessionVariable());
				}

				HarnessRunnerOptions result = HarnessRunnerOptions.parse(launch("--results", absolute("results")),
					"harness", temporaryRoot);

				requireThat(result.resultsFolder(), "resultsFolder").isEqualTo(path("results"));
				requireThat(Files.exists(project.resolve(".cat/sessions")), "sessionsRootExists").isFalse();
			}
		}
	}

	/**
	 * Verifies that a request server's {@code --policy-bound} reaches the runner as the file of the session's policy
	 * bound, and that a launch without it has no bound.
	 */
	@Test
	public void parsesLauncherOptions()
	{
		HarnessRunnerOptions result = HarnessRunnerOptions.parse(launch("--policy-bound",
			absolute("requests/r1/policy-bound.json")), "harness", SESSION);
		HarnessRunnerOptions without = HarnessRunnerOptions.parse(launch(), "harness", SESSION);

		requireThat(result.policyBound(), "policyBound").isEqualTo(path("requests/r1/policy-bound.json"));
		requireThat(without.policyBound(), "withoutPolicyBound").isNull();
	}

	/**
	 * Verifies that {@code --capacity-held}, {@code --git-name} and {@code --git-email} are no longer options: no
	 * starter passes them, so a launch that names one is refused as one that names any unknown option.
	 */
	@Test
	public void rejectsRemovedLauncherOptions()
	{
		for (String[] removed : new String[][]{{"--capacity-held"}, {"--git-name", "A User"},
			{"--git-email", "user@example.test"}})
		{
			try
			{
				HarnessRunnerOptions.parse(launch(removed), "harness", SESSION);
				throw new AssertionError("A launch accepted " + removed[0]);
			}
			catch (IllegalArgumentException e)
			{
				requireThat(e.getMessage(), "message").isEqualTo("Unsupported option: " + removed[0]);
			}
		}
	}

	/**
	 * Verifies that a launch that names a file of extra permissions passes its path to the runner, and that a launch
	 * without the option requests none.
	 */
	@Test
	public void parsesExtraPermissionsFile()
	{
		HarnessRunnerOptions result = HarnessRunnerOptions.parse(launch("--extra-permissions",
			absolute("requests/r1/extra-permissions.json")), "harness", SESSION);
		HarnessRunnerOptions without = HarnessRunnerOptions.parse(launch(), "harness", SESSION);

		requireThat(result.extraPermissions(), "extraPermissions").isEqualTo(path("requests/r1/extra-permissions.json"));
		requireThat(without.extraPermissions(), "withoutExtraPermissions").isNull();
	}

	/**
	 * Verifies that a launch that names a directory for turn copies passes its path to the runner, and that a launch
	 * without the option asks for none.
	 */
	@Test
	public void parsesTurnCopyDirectory()
	{
		HarnessRunnerOptions result = HarnessRunnerOptions.parse(launch("--turn-copy", absolute("responses/r1/turn-copy")),
			"harness", SESSION);
		HarnessRunnerOptions without = HarnessRunnerOptions.parse(launch(), "harness", SESSION);

		requireThat(result.turnCopy(), "turnCopy").isEqualTo(path("responses/r1/turn-copy"));
		requireThat(without.turnCopy(), "withoutTurnCopy").isNull();
	}

	/**
	 * Verifies that a launch that installs CAT and names, as its first plugin, a plugin whose manifest names it
	 * {@code cat} installs that plugin as CAT, a candidate CAT under trial, and the other plugins beside it; and that
	 * otherwise the artifact is CAT and every plugin is a trial plugin.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void installsCandidateCat() throws IOException
	{
		Path manifest = Path.of(".harness-plugin/plugin.json");
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-options-"));
			Path candidate = plugin(root.resolve("candidate"), manifest, "cat");
			Path tools = plugin(root.resolve("tools"), manifest, "tools");
			String[] withCandidate = {"--plugin", candidate.toString(), "--plugin", tools.toString()};

			HarnessRunnerOptions trial = HarnessRunnerOptions.parse(launch(concat(withCandidate, "--install-cat")),
				"harness", SESSION);
			HarnessRunnerOptions withoutCat = HarnessRunnerOptions.parse(launch(withCandidate), "harness", SESSION);
			HarnessRunnerOptions toolsFirst = HarnessRunnerOptions.parse(launch("--install-cat", "--plugin",
				tools.toString(), "--plugin", candidate.toString()), "harness", SESSION);

			requireThat(trial.catArtifact(manifest), "trialCat").isEqualTo(candidate);
			requireThat(trial.trialPlugins(manifest), "trialPlugins").isEqualTo(List.of(tools));
			requireThat(withoutCat.catArtifact(manifest), "withoutCat").isEqualTo(path("artifact"));
			requireThat(withoutCat.trialPlugins(manifest), "withoutCatPlugins").isEqualTo(List.of(candidate, tools));
			requireThat(toolsFirst.catArtifact(manifest), "toolsFirstCat").isEqualTo(path("artifact"));
			requireThat(toolsFirst.trialPlugins(manifest), "toolsFirstPlugins").isEqualTo(List.of(tools, candidate));
			requireThat(HarnessRunnerOptions.catArtifact(launch(concat(withCandidate, "--install-cat")), manifest),
				"unparsedTrialCat").isEqualTo(candidate);
			requireThat(HarnessRunnerOptions.catArtifact(launch(withCandidate), manifest), "unparsedWithoutCat").
				isEqualTo(path("artifact"));

			HarnessRunnerOptions bound = HarnessRunnerOptions.parse(launch(concat(withCandidate, TrialPluginInput.ID_OPTION,
				"tools@original", "--plugin", absolute("legacy"), "--install-cat")), "harness", SESSION);
			requireThat(bound.trialPluginInputs(manifest), "boundInputs").isEqualTo(List.of(
				new TrialPluginInput(tools, Optional.of("tools@original")),
				new TrialPluginInput(path("legacy"), Optional.empty())));
			requireThat(trial.trialPluginInputs(manifest), "legacyInputs").
				isEqualTo(TrialPluginInput.unbound(List.of(tools)));
		}
	}

	/**
	 * A provenance option must immediately follow a plugin path and cannot name a parent directory.
	 */
	@Test
	public void refusesInvalidPluginIdentifiers()
	{
		expectThrows(InvalidArgumentsException.class, () -> HarnessRunnerOptions.parse(
			launch("--plugin", absolute("plugin"), "--install-cat", TrialPluginInput.ID_OPTION, "tools@market"),
			"harness", SESSION));
		expectThrows(InvalidArgumentsException.class, () -> HarnessRunnerOptions.parse(
			launch("--plugin", absolute("plugin"), "--deny-read", "--plugin", "--install-cat",
				TrialPluginInput.ID_OPTION, "tools@market"), "harness", SESSION));
		expectThrows(InvalidArgumentsException.class, () -> HarnessRunnerOptions.parse(
			launch("--plugin", absolute("plugin"), TrialPluginInput.ID_OPTION, "tools@.."), "harness", SESSION));
	}

	/**
	 * Creates a plugin directory whose manifest names the plugin.
	 *
	 * @param directory the new directory
	 * @param manifest  the manifest's path relative to the directory
	 * @param name      the plugin's name
	 * @return {@code directory}
	 * @throws IOException if the directory cannot be created
	 */
	private static Path plugin(Path directory, Path manifest, String name) throws IOException
	{
		Path file = directory.resolve(manifest);
		Files.createDirectories(file.getParent());
		Files.writeString(file, "{\"name\":\"" + name + "\"}");
		return directory;
	}

	/**
	 * Returns options followed by more options.
	 *
	 * @param first the first options
	 * @param more  the options that follow
	 * @return the options
	 */
	private static String[] concat(String[] first, String... more)
	{
		List<String> result = new ArrayList<>(List.of(first));
		result.addAll(List.of(more));
		return result.toArray(String[]::new);
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
