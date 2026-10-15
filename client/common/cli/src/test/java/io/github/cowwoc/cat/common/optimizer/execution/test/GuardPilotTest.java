package io.github.cowwoc.cat.common.optimizer.execution.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.contract.DerivedSource;
import io.github.cowwoc.cat.common.optimizer.campaign.FailedAssertion;
import io.github.cowwoc.cat.common.optimizer.campaign.Verdict;
import io.github.cowwoc.cat.common.optimizer.contract.Assertion;
import io.github.cowwoc.cat.common.optimizer.contract.AssertionKind;
import io.github.cowwoc.cat.common.optimizer.contract.BuiltInToolResult;
import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.contract.DrawnTestCase;
import io.github.cowwoc.cat.common.optimizer.contract.JudgeProfile;
import io.github.cowwoc.cat.common.optimizer.contract.JudgedAssertion;
import io.github.cowwoc.cat.common.optimizer.contract.MappedAssertion;
import io.github.cowwoc.cat.common.optimizer.contract.OnToolFailure;
import io.github.cowwoc.cat.common.optimizer.contract.Part;
import io.github.cowwoc.cat.common.optimizer.contract.Dependent;
import io.github.cowwoc.cat.common.optimizer.contract.PluginUnderTest;
import io.github.cowwoc.cat.common.optimizer.contract.PromptTest;
import io.github.cowwoc.cat.common.optimizer.contract.ProposalSchema;
import io.github.cowwoc.cat.common.optimizer.contract.TestCase;
import io.github.cowwoc.cat.common.optimizer.contract.TrialProfile;
import io.github.cowwoc.cat.common.optimizer.contract.test.ContractFixtures;
import io.github.cowwoc.cat.common.optimizer.contract.test.TierFixtures;
import io.github.cowwoc.cat.common.optimizer.evidence.SessionEvidence;
import io.github.cowwoc.cat.common.optimizer.execution.Driver;
import io.github.cowwoc.cat.common.optimizer.execution.GuardPilot;
import io.github.cowwoc.cat.common.optimizer.execution.JudgeProtocol;
import io.github.cowwoc.cat.common.optimizer.execution.Judgment;
import io.github.cowwoc.cat.common.optimizer.execution.PilotResult;
import io.github.cowwoc.cat.common.optimizer.execution.PilotTrial;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry.RegularFile;
import io.github.cowwoc.cat.common.optimizer.inventory.FileInventory;
import io.github.cowwoc.cat.common.optimizer.inventory.Overlay;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;
import io.github.cowwoc.cat.common.optimizer.port.HostLogin;
import io.github.cowwoc.cat.common.optimizer.port.PluginRemovals;
import io.github.cowwoc.cat.common.optimizer.port.SessionHandle;
import io.github.cowwoc.cat.common.optimizer.port.SessionPort;
import io.github.cowwoc.cat.common.optimizer.port.SessionRequest;
import io.github.cowwoc.cat.common.optimizer.port.SessionResult;
import io.github.cowwoc.cat.common.optimizer.port.SessionRole;
import io.github.cowwoc.cat.common.optimizer.port.TrialPlugin;
import io.github.cowwoc.cat.common.optimizer.port.test.FakeWorkspacePort;
import io.github.cowwoc.cat.common.runner.HarnessCapacity.Hold;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Tests the guard pilot: the control trials that run before CAT presents a proposal.
 * <p>
 * The sessions and the judge are test doubles. A trial's reply is the text that the test's script chooses; the judge
 * double receives that reply and returns the verdict that it names: {@code pass} passes every judged assertion,
 * {@code fail:<assertion>} fails that assertion, and {@code invalid} is output that does not match the judge's result
 * schema. The tests assert what the pilot decides from those inputs: the cell, part, source and test cases of its
 * trials, its per-assertion tallies, and when it reuses, reruns or stops.
 */
public final class GuardPilotTest
{
	private static final Path PROMPT = Path.of("CLAUDE.md");
	/**
	 * The reply that fails the {@code forbidden-text} check of {@link #checkedContract()}.
	 */
	private static final String BROKEN = "BROKEN";
	private final JsonMapper json = JsonMapper.builder().build();

	/**
	 * The pilot runs 20 control trials of each prompt test in its weakest cell, of development test cases only, and
	 * counts how often the judge passed each assertion.
	 *
	 * @throws IOException if the test directory cannot be used
	 * @throws InterruptedException if the thread is interrupted
	 */
	@Test
	public void runsControlTrialsInWeakestCell() throws IOException, InterruptedException
	{
		// Requirement: R-C23
		Contract contract = TierFixtures.tieredContract();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("pilot-weakest"));
			ScriptedPort sessions = new ScriptedPort(directory, _ -> "pass");
			FakeWorkspacePort workspace = workspace(directory);
			Overlay control = control();

			Set<Path> excluded = Set.of(Path.of(".cat/tests/main"));
			Map<String, PilotResult> pilots = pilot(sessions, workspace, 4).run(contract, control, excluded, Map.of(),
				new SplittableRandom(1));

			requireThat(workspace.excluded(control.identity()), "excluded").isEqualTo(excluded);
			Path source = workspace.stage(control, excluded);
			for (SessionRequest request : sessions.requests(SessionRole.TRIAL))
			{
				requireThat(request.part(), "part").isEqualTo(Part.DEVELOPMENT);
				requireThat(request.source(), "source").isEqualTo(source);
			}
			requireThat(sessions.requests(SessionRole.TRIAL).stream().
				filter(request -> request.profile().equals(new TrialProfile("small", "high"))).count(),
				"smallTrials").isEqualTo((long) GuardPilot.TRIALS);

			PilotResult formatting = pilots.get("formatting");
			requireThat(formatting.cellId(), "formattingCell").isEqualTo(TierFixtures.SMALL_HIGH);
			requireThat(formatting.trials().size(), "formattingTrials").isEqualTo(GuardPilot.TRIALS);
			requireThat(formatting.failedRuns(), "failedRuns").isEmpty();
			requireThat(formatting.stopReason(), "stopReason").isNull();
			requireThat(formatting.passes("uses_requested_tone"), "passes").isEqualTo(GuardPilot.TRIALS);
			requireThat(formatting.decided("uses_requested_tone"), "decided").isEqualTo(GuardPilot.TRIALS);
			requireThat(pilots.get("safety").cellId(), "safetyCell").isEqualTo(TierFixtures.SAFETY);
			requireThat(pilots.get("safety").trials().size(), "safetyTrials").isEqualTo(GuardPilot.TRIALS);

			for (PromptTest test : contract.tests())
			{
				List<Map<String, String>> development = contract.split().testCases(test, Part.DEVELOPMENT).stream().
					map(PromptTest::textByVariable).toList();
				for (PilotTrial trial : pilots.get(test.id()).trials())
					requireThat(development, "development").contains(trial.values());
			}
		}
	}

	/**
	 * Each assertion's tally counts the trials in which the judge passed it; a guard that the agent fails in 5 of 20
	 * control trials is below a minimum pass rate of 95% but not below 75%.
	 *
	 * @throws IOException if the test directory cannot be used
	 * @throws InterruptedException if the thread is interrupted
	 */
	@Test
	public void talliesEachAssertion() throws IOException, InterruptedException
	{
		// Requirement: R-C23
		Contract contract = TierFixtures.tieredContract();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("pilot-tallies"));
			AtomicInteger safetyTrials = new AtomicInteger();
			ScriptedPort sessions = new ScriptedPort(directory, request ->
			{
				if (!isSafety(request) || safetyTrials.incrementAndGet() % 4 != 0)
					return "pass";
				return "fail:asks_before_deleting";
			});

			PilotResult safety = pilot(sessions, workspace(directory), 4).run(contract, control(), Set.of(), Map.of(),
				new SplittableRandom(2)).get("safety");

			requireThat(safety.decided("asks_before_deleting"), "decided").isEqualTo(GuardPilot.TRIALS);
			requireThat(safety.passes("asks_before_deleting"), "passes").isEqualTo(15);
			requireThat(safety.passes(TestCase.EXPECTED_OUTCOME), "expectedOutcomePasses").
				isEqualTo(GuardPilot.TRIALS);
			requireThat(safety.isBelow("asks_before_deleting", new BigDecimal("0.95")), "belowNinetyFive").isTrue();
			requireThat(safety.isBelow("asks_before_deleting", new BigDecimal("0.75")), "belowSeventyFive").isFalse();
		}
	}

	/**
	 * A trial that fails a built-in tool records the tools' results and runs no judge, so the assertions that the
	 * judge decides stay undecided in that trial.
	 *
	 * @throws IOException if the test directory cannot be used
	 * @throws InterruptedException if the thread is interrupted
	 */
	@Test
	public void failedCheckSkipsJudge() throws IOException, InterruptedException
	{
		// Requirement: R-C23
		Contract contract = checkedContract();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("pilot-check"));
			ScriptedPort sessions = new ScriptedPort(directory, request ->
			{
				if (isSafety(request))
					return BROKEN;
				return "pass";
			});

			PilotResult safety = pilot(sessions, workspace(directory), 4).run(contract, control(), Set.of(), Map.of(),
				new SplittableRandom(3)).get("safety");

			requireThat(sessions.requests(SessionRole.JUDGE).stream().
				filter(request -> request.turns().getFirst().equals(BROKEN)).count(), "safetyJudges").isEqualTo(0L);
			requireThat(safety.decided("reply_intact"), "checkDecided").isEqualTo(GuardPilot.TRIALS);
			requireThat(safety.passes("reply_intact"), "checkPasses").isEqualTo(0);
			requireThat(safety.decided("asks_before_deleting"), "judgedDecided").isEqualTo(0);
			requireThat(safety.decided(TestCase.EXPECTED_OUTCOME), "expectedOutcomeDecided").isEqualTo(0);
			requireThat(safety.trials().getFirst().record(), "record").contains("forbidden-text " + BROKEN);
		}
	}

	/**
	 * A trial that fails a built-in tool whose {@code on_tool_failure} is {@code judge} runs its judge, which decides
	 * that tool's assertion along with the assertions that the judge always decides.
	 *
	 * @throws IOException if the test directory cannot be used
	 * @throws InterruptedException if the thread is interrupted
	 */
	@Test
	public void failedToolLeftToJudge() throws IOException, InterruptedException
	{
		// Requirement: R-C23
		// Requirement: R-C9
		Contract tiered = TierFixtures.tieredContract();
		List<MappedAssertion> assertions = new ArrayList<>(tiered.findTest("safety").orElseThrow().assertions());
		assertions.add(new MappedAssertion(new Assertion("reply_intact", AssertionKind.GUARD,
			"forbidden-text " + BROKEN, OnToolFailure.JUDGE, "No reply says " + BROKEN + "."), "r2"));
		Contract contract = withSafety(tiered, assertions);
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("pilot-deferred"));
			ScriptedPort sessions = new ScriptedPort(directory, request ->
			{
				if (isSafety(request))
					return BROKEN;
				return "pass";
			});

			PilotResult safety = pilot(sessions, workspace(directory), 4).run(contract, control(), Set.of(), Map.of(),
				new SplittableRandom(3)).get("safety");

			requireThat(sessions.requests(SessionRole.JUDGE).stream().
				filter(request -> request.turns().getFirst().equals(BROKEN)).count(), "safetyJudges").
				isEqualTo((long) GuardPilot.TRIALS);
			requireThat(safety.decided("reply_intact"), "toolDecided").isEqualTo(GuardPilot.TRIALS);
			requireThat(safety.passes("reply_intact"), "toolPasses").isEqualTo(GuardPilot.TRIALS);
			requireThat(safety.decided("asks_before_deleting"), "judgedDecided").isEqualTo(GuardPilot.TRIALS);
		}
	}

	/**
	 * A trial whose judge never returns valid output, after {@value Driver#JUDGE_ATTEMPTS} attempts, counts as a
	 * failed run rather than a decided trial.
	 *
	 * @throws IOException if the test directory cannot be used
	 * @throws InterruptedException if the thread is interrupted
	 */
	@Test
	public void invalidJudgeOutputIsFailedRun() throws IOException, InterruptedException
	{
		// Requirement: R-C23
		Contract contract = TierFixtures.tieredContract();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("pilot-invalid"));
			ScriptedPort sessions = new ScriptedPort(directory, request ->
			{
				if (isSafety(request))
					return "invalid";
				return "pass";
			});

			PilotResult safety = pilot(sessions, workspace(directory), 4).run(contract, control(), Set.of(), Map.of(),
				new SplittableRandom(4)).get("safety");

			requireThat(safety.trials(), "trials").isEmpty();
			requireThat(safety.failedRuns(), "failedRuns").isEqualTo(Collections.nCopies(GuardPilot.TRIALS,
				"The judge failed " + Driver.JUDGE_ATTEMPTS + " times. Last failure: The judge's output does not match " +
					"its result schema"));
			requireThat(sessions.requests(SessionRole.JUDGE).stream().
				filter(request -> request.turns().getFirst().equals("invalid")).count(), "judges").
				isEqualTo((long) GuardPilot.TRIALS * Driver.JUDGE_ATTEMPTS);
		}
	}

	/**
	 * A trial whose built-in tool cannot be decided from its record counts as a failed run that says why.
	 *
	 * @throws IOException if the test directory cannot be used
	 * @throws InterruptedException if the thread is interrupted
	 */
	@Test
	public void undecidableToolRecordsReason() throws IOException, InterruptedException
	{
		// Requirement: R-C23
		Contract tiered = TierFixtures.tieredContract();
		List<MappedAssertion> assertions = new ArrayList<>(tiered.findTest("safety").orElseThrow().assertions());
		assertions.add(new MappedAssertion(new Assertion("read_notes", AssertionKind.GUARD, "read-in-full notes.md",
			"The agent reads notes.md in full."), "r2"));
		Contract contract = withSafety(tiered, assertions);
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("pilot-undecidable"));
			ScriptedPort sessions = new ScriptedPort(directory, _ -> "pass");

			PilotResult safety = pilot(sessions, workspace(directory), 4).run(contract, control(), Set.of(), Map.of(),
				new SplittableRandom(5)).get("safety");

			requireThat(safety.trials(), "trials").isEmpty();
			requireThat(safety.failedRuns(), "failedRuns").isEqualTo(Collections.nCopies(GuardPilot.TRIALS,
				"The built-in tools could not be decided: `notes.md` was not a file when the trial started"));
		}
	}

	/**
	 * A stored pilot whose test, cell and control source are unchanged is returned without starting a session.
	 *
	 * @throws IOException if the test directory cannot be used
	 * @throws InterruptedException if the thread is interrupted
	 */
	@Test
	public void reusesApplicablePilot() throws IOException, InterruptedException
	{
		// Requirement: R-C23
		Contract contract = TierFixtures.tieredContract();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("pilot-reuse"));
			Map<String, PilotResult> first = pilot(new ScriptedPort(directory, _ -> "pass"), workspace(directory), 4).
				run(contract, control(), Set.of(), Map.of(), new SplittableRandom(5));
			ScriptedPort sessions = new ScriptedPort(directory, _ -> "pass");

			Map<String, PilotResult> second = pilot(sessions, workspace(directory), 4).
				run(contract, control(), Set.of(), first, new SplittableRandom(6));

			requireThat(sessions.requests(SessionRole.TRIAL), "trials").isEmpty();
			requireThat(second, "second").isEqualTo(first);
		}
	}

	/**
	 * After a revision changes one prompt test, the pilot reruns that test only and keeps the other test's results.
	 *
	 * @throws IOException if the test directory cannot be used
	 * @throws InterruptedException if the thread is interrupted
	 */
	@Test
	public void rerunsChangedTestOnly() throws IOException, InterruptedException
	{
		// Requirement: R-C23
		Contract contract = TierFixtures.tieredContract();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("pilot-revise"));
			Map<String, PilotResult> first = pilot(new ScriptedPort(directory, _ -> "pass"), workspace(directory), 4).
				run(contract, control(), Set.of(), Map.of(), new SplittableRandom(7));
			Contract revised = withSafetyStatement(contract, "The assistant confirms before deleting {{target}}.");
			ScriptedPort sessions = new ScriptedPort(directory, _ -> "pass");

			Map<String, PilotResult> second = pilot(sessions, workspace(directory), 4).
				run(revised, control(), Set.of(), first, new SplittableRandom(8));

			List<SessionRequest> trials = sessions.requests(SessionRole.TRIAL);
			requireThat(trials.size(), "trials").isEqualTo(GuardPilot.TRIALS);
			requireThat(trials.stream().allMatch(GuardPilotTest::isSafety), "onlySafety").isTrue();
			requireThat(second.get("formatting"), "formatting").isEqualTo(first.get("formatting"));
			requireThat(second.get("safety"), "safety").isNotEqualTo(first.get("safety"));
		}
	}

	/**
	 * Each control trial installs the copy of the plugin under test that belongs to the staged control source.
	 *
	 * @throws IOException if the test directory cannot be used
	 * @throws InterruptedException if the thread is interrupted
	 */
	@Test
	public void controlTrialsInstallPlugin() throws IOException, InterruptedException
	{
		// Requirement: R-W12
		PluginUnderTest plugin = builtPlugin("a");
		Contract contract = TierFixtures.tieredContract().withPlugin(Optional.of(plugin));
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("pilot-plugin"));
			ScriptedPort sessions = new ScriptedPort(directory, _ -> "pass");
			FakeWorkspacePort workspace = workspace(directory);

			pilot(sessions, workspace, 4).run(contract, control(), Set.of(), Map.of(), new SplittableRandom(9));

			Path source = workspace.stage(control(), Set.of());
			TrialPlugin expected = new TrialPlugin(workspace.pluginCopy(plugin, source, PluginRemovals.NONE), true);
			List<SessionRequest> trials = sessions.requests(SessionRole.TRIAL);
			requireThat(trials, "trials").isNotEmpty();
			for (SessionRequest trial : trials)
				requireThat(trial.plugin(), "plugin").isEqualTo(Optional.of(expected));
		}
	}

	/**
	 * Each control trial installs the copy of the plugin under test without the dependents of the target that the
	 * contract lists, and a stored pilot no longer applies once those dependents change.
	 *
	 * @throws IOException if the test directory cannot be used
	 * @throws InterruptedException if the thread is interrupted
	 */
	@Test
	public void controlTrialsRemovePluginDependents() throws IOException, InterruptedException
	{
		// Requirement: R-W10
		PluginUnderTest plugin = builtPlugin("a");
		Contract withPlugin = TierFixtures.tieredContract().withPlugin(Optional.of(plugin));
		Contract contract = ContractFixtures.withDependents(withPlugin, List.of(new Dependent(
			Dependent.Location.PLUGIN, Path.of("bin/read-part"), new Dependent.WholeFile(), "Reads a part.")));
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("pilot-plugin"));
			ScriptedPort sessions = new ScriptedPort(directory, _ -> "pass");
			FakeWorkspacePort workspace = workspace(directory);
			GuardPilot pilot = pilot(sessions, workspace, 4);

			pilot.run(contract, control(), Set.of(), Map.of(), new SplittableRandom(9));

			Path source = workspace.stage(control(), Set.of());
			TrialPlugin expected = new TrialPlugin(workspace.pluginCopy(plugin, source,
				PluginRemovals.of(contract.pluginRemovals(), json)), true);
			List<SessionRequest> trials = sessions.requests(SessionRole.TRIAL);
			requireThat(trials, "trials").isNotEmpty();
			for (SessionRequest trial : trials)
				requireThat(trial.plugin(), "plugin").isEqualTo(Optional.of(expected));
			PromptTest test = contract.tests().getFirst();
			requireThat(pilot.definition(contract, test), "definition").
				isNotEqualTo(pilot.definition(withPlugin, test));
		}
	}

	/**
	 * A stored pilot no longer applies once the files of the plugin's base change, because its control trials
	 * installed the old files.
	 */
	@Test
	public void pluginBaseChangesDefinition()
	{
		// Requirement: R-W12
		Contract contract = TierFixtures.tieredContract();
		PromptTest test = contract.tests().getFirst();
		GuardPilot pilot = pilot(new ScriptedPort(Path.of("unused"), _ -> "pass"), workspace(Path.of("unused")), 4);

		Sha256 before = pilot.definition(contract.withPlugin(Optional.of(builtPlugin("a"))), test);
		Sha256 after = pilot.definition(contract.withPlugin(Optional.of(builtPlugin("b"))), test);

		requireThat(after, "after").isNotEqualTo(before);
	}

	/**
	 * Returns a built plugin under test that depends on CAT.
	 *
	 * @param digit the hexadecimal digit that every position of the base digest holds
	 * @return the plugin
	 */
	private static PluginUnderTest builtPlugin(String digit)
	{
		return new PluginUnderTest.Built(Path.of("/plugin-base").toAbsolutePath(), digit.repeat(64),
			List.of(new PluginUnderTest.Mapping(Path.of("rules"), Path.of("rules/codex"))), true,
			"The prompt files invoke CAT skills.");
	}

	/**
	 * A rate limit stops the pilot from starting another session, and every unfinished test records why it stopped,
	 * so that the stored result is not reused.
	 *
	 * @throws IOException if the test directory cannot be used
	 * @throws InterruptedException if the thread is interrupted
	 */
	@Test
	public void rateLimitStopsPilot() throws IOException, InterruptedException
	{
		// Requirement: R-C23
		Contract contract = TierFixtures.tieredContract();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("pilot-limit"));
			ScriptedPort sessions = new ScriptedPort(directory, _ -> "pass");
			sessions.limitTrials();
			Overlay control = control();

			Map<String, PilotResult> pilots = pilot(sessions, workspace(directory), 1).
				run(contract, control, Set.of(), Map.of(), new SplittableRandom(9));

			requireThat(sessions.requests(SessionRole.TRIAL).size(), "trials").isEqualTo(1);
			for (PromptTest test : contract.tests())
			{
				PilotResult pilot = pilots.get(test.id());
				requireThat(pilot.stopReason(), "stopReason").contains("rate limit");
				requireThat(pilot.appliesTo(pilot.definition(), pilot.cellId(), control.identity()), "applies").
					isFalse();
			}
		}
	}

	/**
	 * Returns a pilot whose judge is the double that returns the verdict named by the trial's reply.
	 *
	 * @param sessions the session port
	 * @param workspace the workspace port
	 * @param ceiling the maximum number of simultaneous sessions
	 * @return the pilot
	 */
	private GuardPilot pilot(ScriptedPort sessions, FakeWorkspacePort workspace, int ceiling)
	{
		return new GuardPilot(sessions, workspace, new ScriptedJudge(), new ProposalSchema(json), json,
			ceiling);
	}

	/**
	 * Returns an in-memory workspace.
	 *
	 * @param directory a directory that the test owns
	 * @return the workspace
	 */
	private static FakeWorkspacePort workspace(Path directory)
	{
		return new FakeWorkspacePort(directory.resolve("workspace"), fork());
	}

	/**
	 * Returns the files of the fork: one prompt file.
	 *
	 * @return the inventory
	 */
	private static FileInventory fork()
	{
		return new FileInventory(Path.of("/project").toAbsolutePath(), Map.of(PROMPT,
			new RegularFile(Sha256.of("fork".getBytes(UTF_8)), PosixFilePermissions.fromString("rw-r--r--"))));
	}

	/**
	 * Returns the control source: the fork with its prompt file emptied.
	 *
	 * @return the overlay
	 */
	private static Overlay control()
	{
		return Overlay.of(fork()).write(PROMPT, new io.github.cowwoc.cat.common.optimizer.inventory.FileContent(
			new byte[0]), PosixFilePermissions.fromString("rw-r--r--"));
	}

	/**
	 * Indicates whether a session runs or judges the {@code safety} test.
	 *
	 * @param request the trial session's request
	 * @return {@code true} if its first turn asks to delete a directory
	 */
	private static boolean isSafety(SessionRequest request)
	{
		return request.turns().getFirst().startsWith("Delete");
	}

	/**
	 * Returns the tiered contract whose {@code safety} test also checks that no reply says {@value #BROKEN}.
	 *
	 * @return the contract
	 */
	private static Contract checkedContract()
	{
		Contract contract = TierFixtures.tieredContract();
		PromptTest safety = contract.findTest("safety").orElseThrow();
		List<MappedAssertion> assertions = new ArrayList<>(safety.assertions());
		assertions.add(new MappedAssertion(new Assertion("reply_intact", AssertionKind.GUARD,
			"forbidden-text " + BROKEN, "No reply says " + BROKEN + "."), "r2"));
		return withSafety(contract, assertions);
	}

	/**
	 * Returns a contract whose {@code safety} assertion has another statement.
	 *
	 * @param contract the contract
	 * @param statement the new statement
	 * @return the contract
	 */
	private static Contract withSafetyStatement(Contract contract, String statement)
	{
		return withSafety(contract, List.of(ContractFixtures.judged("asks_before_deleting", AssertionKind.GUARD,
			statement, "r2")));
	}

	/**
	 * Returns a contract whose {@code safety} test has other assertions.
	 *
	 * @param contract the contract
	 * @param assertions every assertion of the {@code safety} test
	 * @return the contract, with the same cells
	 */
	private static Contract withSafety(Contract contract, List<MappedAssertion> assertions)
	{
		List<PromptTest> tests = new ArrayList<>();
		for (PromptTest test : contract.tests())
		{
			if (test.id().equals("safety"))
			{
				test = new PromptTest(test.id(), test.riskRank(), test.requirementIds(), test.absentTriggers(),
					test.applicability(), test.turns(), test.timeLimit(), test.expectedBehaviour(),
					test.uncoveredInteractions(), test.variables(), test.committedTestCases(), test.testCases(), assertions,
					test.regressionScenarios(), List.of());
			}
			tests.add(test);
		}
		return new Contract(contract.requirements(), tests, contract.profiles(), contract.judge(), contract.fixtures(),
			contract.limitations(), contract.minimumPassRate(), contract.salt());
	}

	/**
	 * A session port whose sessions end as soon as they start. A trial replies with the text that the test's script
	 * chooses for its request; a judge replies with the trial reply that its request carries, so that
	 * {@link ScriptedJudge} reads the verdict that the script chose.
	 * <p>
	 * <b>Thread Safety:</b> This class is thread-safe.
	 */
	private static final class ScriptedPort implements SessionPort
	{
		private final Path directory;
		private final Function<SessionRequest, String> replies;
		private final List<SessionRequest> requests = new ArrayList<>();
		private boolean limitTrials;

		/**
		 * Creates a port.
		 *
		 * @param directory a directory that the test owns, which holds each session's effects
		 * @param replies the reply of each trial session
		 */
		ScriptedPort(Path directory, Function<SessionRequest, String> replies)
		{
			this.directory = directory;
			this.replies = replies;
		}

		/**
		 * Makes the provider reject every trial session because of a rate limit.
		 */
		void limitTrials()
		{
			synchronized (requests)
			{
				limitTrials = true;
			}
		}

		/**
		 * Returns the requests of the sessions that started with a role.
		 *
		 * @param role the role
		 * @return the requests, in start order
		 */
		List<SessionRequest> requests(SessionRole role)
		{
			synchronized (requests)
			{
				return requests.stream().filter(request -> request.role() == role).toList();
			}
		}

		@Override
		public SessionHandle start(SessionRequest request)
		{
			int number;
			boolean limited;
			synchronized (requests)
			{
				requests.add(request);
				number = requests.size();
				limited = limitTrials;
			}
			SessionResult result;
			if (request.role() == SessionRole.TRIAL && limited)
				result = new SessionResult.RateLimited(new Hold(Instant.EPOCH, 0), List.of());
			else if (request.role() == SessionRole.TRIAL)
				result = completed(number, replies.apply(request));
			else
				result = completed(number, request.turns().getFirst());
			return new SessionHandle()
			{
				@Override
				public CompletionStage<SessionResult> completion()
				{
					return CompletableFuture.completedFuture(result);
				}

				@Override
				public void interrupt()
				{
				}
			};
		}

		/**
		 * Returns a completed session.
		 *
		 * @param number the session's start position, which names its evidence directory
		 * @param reply the session's only reply
		 * @return the result
		 */
		private SessionResult.Completed completed(int number, String reply)
		{
			Path evidence = directory.resolve("sessions").resolve(String.valueOf(number));
			Path effects = evidence.resolve("project");
			Path initialProject = evidence.resolve("initial-project");
			try
			{
				Files.createDirectories(effects);
				Files.createDirectories(initialProject);
			}
			catch (IOException e)
			{
				throw new UncheckedIOException(e);
			}
			return new SessionResult.Completed(evidence.resolve("transcript"), List.of(List.of(reply)), effects,
				initialProject, "model", "reasoning", null, List.of(), "none", List.of());
		}

		@Override
		public int sessionEnvironmentVersion()
		{
			return 1;
		}

		@Override
		public int judgeVersion()
		{
			return 1;
		}

		@Override
		public SessionEvidence readEvidence(Path transcript)
		{
			return new SessionEvidence(List.of());
		}

		@Override
		public boolean reportsExitStatus()
		{
			return true;
		}

		@Override
		public HostLogin hostLogin()
		{
			return HostLogin.UNKNOWN;
		}

		@Override
		public List<String> unapprovedHooks(Path plugin)
		{
			return List.of();
		}

		@Override
		public Optional<String> findHeldOutExposure(Path project)
		{
			return Optional.empty();
		}

		@Override
		public List<Path> runRoots()
		{
			return List.of();
		}

		@Override
		public List<DerivedSource> derivedSources(Path project, Optional<PluginUnderTest> plugin)
		{
			return List.of();
		}
	}

	/**
	 * A judge whose request carries the trial's reply and whose verdict is the one that the reply names.
	 * <p>
	 * <b>Thread Safety:</b> This class is stateless and thread-safe.
	 */
	private static final class ScriptedJudge implements JudgeProtocol
	{
		@Override
		public SessionRequest request(JudgeProfile profile, DrawnTestCase testCase, SessionResult.Completed evidence,
			List<JudgedAssertion> assertions, List<BuiltInToolResult> results, String evidenceSection)
		{
			return new SessionRequest(SessionRole.JUDGE, Part.HELD_OUT, evidence.effects(),
				List.of(evidence.responses().getFirst().getFirst()), List.of(), Duration.ofMinutes(1), profile.profile());
		}

		@Override
		public Optional<Judgment> judgment(SessionResult.Completed result, List<JudgedAssertion> decided)
		{
			String reply = result.responses().getFirst().getFirst();
			if (reply.equals("invalid"))
				return Optional.empty();
			if (!reply.startsWith("fail:"))
				return Optional.of(new Judgment(Verdict.PASS, "Every assertion holds.", List.of()));
			String assertionId = reply.substring("fail:".length());
			JudgedAssertion failed = decided.stream().filter(judged -> judged.assertion().id().equals(assertionId)).
				findFirst().orElseThrow();
			return Optional.of(new Judgment(Verdict.FAIL, "The assistant deleted without asking.",
				List.of(new FailedAssertion(assertionId, failed.behaviors().getFirst()))));
		}
	}
}
