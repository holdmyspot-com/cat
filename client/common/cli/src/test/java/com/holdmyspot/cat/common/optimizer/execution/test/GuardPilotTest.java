package com.holdmyspot.cat.common.optimizer.execution.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.contract.Assertion;
import com.holdmyspot.cat.common.optimizer.contract.AssertionKind;
import com.holdmyspot.cat.common.optimizer.contract.BuiltInToolResult;
import com.holdmyspot.cat.common.optimizer.contract.Contract;
import com.holdmyspot.cat.common.optimizer.contract.DrawnTestCase;
import com.holdmyspot.cat.common.optimizer.contract.JudgeProfile;
import com.holdmyspot.cat.common.optimizer.contract.JudgedAssertion;
import com.holdmyspot.cat.common.optimizer.contract.MappedAssertion;
import com.holdmyspot.cat.common.optimizer.contract.OnToolFailure;
import com.holdmyspot.cat.common.optimizer.contract.Part;
import com.holdmyspot.cat.common.optimizer.contract.Dependent;
import com.holdmyspot.cat.common.optimizer.contract.PromptTest;
import com.holdmyspot.cat.common.optimizer.contract.ProposalSchema;
import com.holdmyspot.cat.common.optimizer.contract.TestCase;
import com.holdmyspot.cat.common.optimizer.contract.TrialPlugin;
import com.holdmyspot.cat.common.optimizer.contract.TrialProfile;
import com.holdmyspot.cat.common.optimizer.contract.test.ContractFixtures;
import com.holdmyspot.cat.common.optimizer.contract.test.TierFixtures;
import com.holdmyspot.cat.common.optimizer.execution.Driver;
import com.holdmyspot.cat.common.optimizer.execution.GuardPilot;
import com.holdmyspot.cat.common.optimizer.execution.JudgeProtocol;
import com.holdmyspot.cat.common.optimizer.execution.Judgment;
import com.holdmyspot.cat.common.optimizer.execution.PilotResult;
import com.holdmyspot.cat.common.optimizer.execution.PilotTrial;
import com.holdmyspot.cat.common.optimizer.execution.prompt.JudgePrompt;
import com.holdmyspot.cat.common.optimizer.inventory.FileEntry.RegularFile;
import com.holdmyspot.cat.common.optimizer.inventory.FileInventory;
import com.holdmyspot.cat.common.optimizer.inventory.InventoryScanner;
import com.holdmyspot.cat.common.optimizer.inventory.Overlay;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;
import com.holdmyspot.cat.common.optimizer.port.PluginRemovals;
import com.holdmyspot.cat.common.optimizer.port.SessionRequest;
import com.holdmyspot.cat.common.optimizer.port.SessionResult;
import com.holdmyspot.cat.common.optimizer.port.SessionRole;
import com.holdmyspot.cat.common.optimizer.port.SourceSelection;
import com.holdmyspot.cat.common.optimizer.port.test.FakeSessionPort;
import com.holdmyspot.cat.common.optimizer.port.test.FakeSessionPort.FakeSession;
import com.holdmyspot.cat.common.optimizer.port.test.FakeWorkspacePort;
import com.holdmyspot.cat.common.runner.HarnessCapacity.Hold;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Tests the guard pilot: the control trials that run before CAT presents a proposal.
 * <p>
 * The sessions are test doubles; the judge prompt is the production {@link JudgePrompt}. A trial's reply is the text
 * that the test's script chooses. The judge session replies, in the result schema's format, with the verdict that the
 * trial's reply names: {@code pass} passes every judged assertion, {@code fail:<assertion>} fails that assertion, and
 * {@code invalid} is output that does not match the judge's result schema. The tests assert what the pilot decides
 * from those inputs: the cell, part, source and test cases of its trials, its per-assertion tallies, and when it
 * reuses, reruns or stops.
 */
public final class GuardPilotTest
{
	private static final Path PROMPT = Path.of("CLAUDE.md");
	/**
	 * The files that the staged control source holds.
	 */
	private static final SourceSelection SELECTION = new SourceSelection(Set.of(PROMPT), Set.of(), List.of());
	/**
	 * The reply that fails the {@code forbidden-text} check of {@link #checkedContract()}.
	 */
	private static final String BROKEN = "BROKEN";
	private final JsonMapper json = JsonMapper.builder().build();

	/**
	 * The pilot runs 20 control trials of each prompt test in its weakest cell, on the control source and of
	 * development test cases only, and counts how often the judge passed each assertion.
	 * <p>
	 * Runtime includes the required 20 trials for each of two tests, their scripted judge results and the staged
	 * source-file checks for every trial. Shortening the pilot or replacing the staged files with a flag would lose
	 * the trial-count, selected-source or held-out-file exclusion outcomes. No harness or model runs.
	 *
	 * @throws IOException if the test directory cannot be used
	 * @throws InterruptedException if the thread is interrupted
	 */
	@Test
	public void runsControlTrialsInWeakestCell() throws IOException, InterruptedException
	{
		// Requirement: R-C23, R-W10
		Contract contract = TierFixtures.tieredContract();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("pilot-weakest"));
			SessionScript sessions = new SessionScript(directory, _ -> "pass");
			Path project = Files.createDirectories(directory.resolve("project"));
			Files.createDirectories(project.resolve("docs"));
			Files.createDirectories(project.resolve(".cat/tests/main"));
			Files.writeString(project.resolve(PROMPT), "fork", UTF_8);
			Files.writeString(project.resolve("docs/selected.md"), "selected", UTF_8);
			Files.writeString(project.resolve("unrelated.md"), "unrelated", UTF_8);
			Files.writeString(project.resolve(".cat/tests/main/private.md"), "held out", UTF_8);
			FileInventory fork = InventoryScanner.scan(project, Set.of());
			requireThat(fork.entries().keySet(), "fixtureFiles").contains(Path.of(".cat/tests/main/private.md"));
			FakeWorkspacePort workspace = new FakeWorkspacePort(directory.resolve("workspace"), fork, project);
			Overlay control = Overlay.of(fork);
			SourceSelection selection = SELECTION.withPatterns(List.of("docs/**", ".cat/tests/**"));

			Set<Path> excluded = Set.of(Path.of(".cat/tests/main"));
			Map<String, PilotResult> pilots = pilot(sessions, workspace, 4).run(contract, List.of(), control, excluded,
				selection, Map.of(), new SplittableRandom(1));
			for (SessionRequest request : sessions.requests(SessionRole.TRIAL))
			{
				requireThat(request.part(), "part").isEqualTo(Part.DEVELOPMENT);
				try (Stream<Path> files = Files.walk(request.source()))
				{
					requireThat(Set.copyOf(files.filter(Files::isRegularFile).map(request.source()::relativize).toList()),
						"sourceFiles").isEqualTo(Set.of(PROMPT, Path.of("docs/selected.md")));
				}
				requireThat(Files.readString(request.source().resolve("docs/selected.md"), UTF_8), "selectedContent").
					isEqualTo("selected");
				requireThat(request.controlSource(), "controlSource").isTrue();
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
			SessionScript sessions = new SessionScript(directory, request ->
			{
				if (!isSafety(request) || safetyTrials.incrementAndGet() % 4 != 0)
					return "pass";
				return "fail:asks_before_deleting";
			});

			PilotResult safety = pilot(sessions, workspace(directory), 4).run(contract, List.of(), control(), Set.of(),
				SELECTION, Map.of(), new SplittableRandom(2)).get("safety");

			requireThat(safety.decided("asks_before_deleting"), "decided").isEqualTo(GuardPilot.TRIALS);
			requireThat(safety.passes("asks_before_deleting"), "passes").isEqualTo(15);
			requireThat(safety.passes(TestCase.EXPECTED_OUTCOME), "expectedOutcomePasses").
				isEqualTo(GuardPilot.TRIALS);
			requireThat(safety.isBelow("asks_before_deleting", new BigDecimal("0.95")), "belowNinetyFive").isTrue();
			requireThat(safety.isBelow("asks_before_deleting", new BigDecimal("0.75")), "belowSeventyFive").isFalse();
		}
	}

	/**
	 * A decided control trial that passes every assertion has its effects and its copy of the initial project deleted;
	 * a trial that fails an assertion keeps them as evidence.
	 *
	 * @throws IOException if the test directory cannot be used
	 * @throws InterruptedException if the thread is interrupted
	 */
	@Test
	public void passingTrialDeletesProjectCopies() throws IOException, InterruptedException
	{
		// Requirement: R-W14
		Contract contract = TierFixtures.tieredContract();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("pilot-copies"));
			AtomicInteger safetyTrials = new AtomicInteger();
			SessionScript sessions = new SessionScript(directory, request ->
			{
				if (!isSafety(request) || safetyTrials.incrementAndGet() % 4 != 0)
					return "pass";
				return "fail:asks_before_deleting";
			});

			pilot(sessions, workspace(directory), 4).run(contract, List.of(), control(), Set.of(), SELECTION, Map.of(),
				new SplittableRandom(2));

			List<SessionResult.Completed> trials = sessions.trials();
			requireThat(trials, "trials").isNotEmpty();
			for (SessionResult.Completed trial : trials)
			{
				boolean failed = trial.responses().getFirst().getFirst().startsWith("fail");
				requireThat(Files.isDirectory(trial.effects()), "effectsKept").isEqualTo(failed);
				requireThat(Files.isDirectory(trial.initialProject()), "initialProjectKept").isEqualTo(failed);
			}
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
			SessionScript sessions = new SessionScript(directory, request ->
			{
				if (isSafety(request))
					return BROKEN;
				return "pass";
			});

			PilotResult safety = pilot(sessions, workspace(directory), 4).run(contract, List.of(), control(), Set.of(),
				SELECTION, Map.of(), new SplittableRandom(3)).get("safety");

			requireThat(sessions.judgedReplies().stream().filter(reply -> reply.equals(BROKEN)).count(),
				"safetyJudges").isEqualTo(0L);
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
			SessionScript sessions = new SessionScript(directory, request ->
			{
				if (isSafety(request))
					return BROKEN;
				return "pass";
			});

			PilotResult safety = pilot(sessions, workspace(directory), 4).run(contract, List.of(), control(), Set.of(),
				SELECTION, Map.of(), new SplittableRandom(3)).get("safety");

			requireThat(sessions.judgedReplies().stream().filter(reply -> reply.equals(BROKEN)).count(),
				"safetyJudges").isEqualTo((long) GuardPilot.TRIALS);
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
			SessionScript sessions = new SessionScript(directory, request ->
			{
				if (isSafety(request))
					return "invalid";
				return "pass";
			});

			PilotResult safety = pilot(sessions, workspace(directory), 4).run(contract, List.of(), control(), Set.of(),
				SELECTION, Map.of(), new SplittableRandom(4)).get("safety");

			requireThat(safety.trials(), "trials").isEmpty();
			requireThat(safety.failedRuns(), "failedRuns").isEqualTo(Collections.nCopies(GuardPilot.TRIALS,
				"The judge failed " + Driver.JUDGE_ATTEMPTS + " times. Last failure: The judge's output does not match " +
					"its result schema"));
			requireThat(sessions.judgedReplies().stream().filter(reply -> reply.equals("invalid")).count(), "judges").
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
			SessionScript sessions = new SessionScript(directory, _ -> "pass");

			PilotResult safety = pilot(sessions, workspace(directory), 4).run(contract, List.of(), control(), Set.of(),
				SELECTION, Map.of(), new SplittableRandom(5)).get("safety");

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
			Map<String, PilotResult> first = pilot(new SessionScript(directory, _ -> "pass"), workspace(directory), 4).
				run(contract, List.of(), control(), Set.of(), SELECTION, Map.of(), new SplittableRandom(5));
			SessionScript sessions = new SessionScript(directory, _ -> "pass");

			Map<String, PilotResult> second = pilot(sessions, workspace(directory), 4).
				run(contract, List.of(), control(), Set.of(), SELECTION, first, new SplittableRandom(6));

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
			Map<String, PilotResult> first = pilot(new SessionScript(directory, _ -> "pass"), workspace(directory), 4).
				run(contract, List.of(), control(), Set.of(), SELECTION, Map.of(), new SplittableRandom(7));
			Contract revised = withSafetyStatement(contract, "The assistant confirms before deleting {{target}}.");
			SessionScript sessions = new SessionScript(directory, _ -> "pass");

			Map<String, PilotResult> second = pilot(sessions, workspace(directory), 4).
				run(revised, List.of(), control(), Set.of(), SELECTION, first, new SplittableRandom(8));

			List<SessionRequest> trials = sessions.requests(SessionRole.TRIAL);
			requireThat(trials.size(), "trials").isEqualTo(GuardPilot.TRIALS);
			requireThat(trials.stream().allMatch(GuardPilotTest::isSafety), "onlySafety").isTrue();
			requireThat(second.get("formatting"), "formatting").isEqualTo(first.get("formatting"));
			requireThat(second.get("safety"), "safety").isNotEqualTo(first.get("safety"));
		}
	}

	/**
	 * A stored pilot with a trial that ended without a decision runs again even though its test is unchanged, so that a
	 * session failure does not stand in for the control trials.
	 * <p>
	 * Runtime includes generating a prior pilot with failed safety runs, then completing its required 20-trial
	 * replacement while reusing the successful formatting pilot. Both phases use scripted sessions and staged
	 * trial files; no harness or model runs.
	 *
	 * @throws IOException if the test directory cannot be used
	 * @throws InterruptedException if the thread is interrupted
	 */
	@Test
	public void rerunsPilotWithFailedRuns() throws IOException, InterruptedException
	{
		// Requirement: R-C23
		Contract contract = TierFixtures.tieredContract();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("pilot-failed"));
			SessionScript failing = new SessionScript(directory, request ->
			{
				if (isSafety(request))
					return "invalid";
				return "pass";
			});
			Map<String, PilotResult> first = pilot(failing, workspace(directory), 4).
				run(contract, List.of(), control(), Set.of(), SELECTION, Map.of(), new SplittableRandom(10));
			SessionScript sessions = new SessionScript(directory, _ -> "pass");

			Map<String, PilotResult> second = pilot(sessions, workspace(directory), 4).
				run(contract, List.of(), control(), Set.of(), SELECTION, first, new SplittableRandom(11));

			List<SessionRequest> trials = sessions.requests(SessionRole.TRIAL);
			requireThat(trials.size(), "trials").isEqualTo(GuardPilot.TRIALS);
			requireThat(trials.stream().allMatch(GuardPilotTest::isSafety), "onlySafety").isTrue();
			requireThat(second.get("formatting"), "formatting").isEqualTo(first.get("formatting"));
			requireThat(second.get("safety").failedRuns(), "failedRuns").isEmpty();
		}
	}

	/**
	 * A stored pilot whose test, cell and control source are unchanged runs again once the sessions change what the
	 * model receives, because its trials no longer show how the agent behaves without the target prompts.
	 *
	 * @throws IOException if the test directory cannot be used
	 * @throws InterruptedException if the thread is interrupted
	 */
	@Test
	public void rerunsPilotAfterSessionEnvironmentChange() throws IOException, InterruptedException
	{
		// Requirement: R-C23
		Contract contract = TierFixtures.tieredContract();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("pilot-environment"));
			Map<String, PilotResult> first = pilot(new SessionScript(directory, _ -> "pass"), workspace(directory), 4).
				run(contract, List.of(), control(), Set.of(), SELECTION, Map.of(), new SplittableRandom(12));
			SessionScript sessions = new SessionScript(directory, _ -> "pass");
			sessions.port().sessionEnvironmentVersion(FakeSessionPort.SESSION_ENVIRONMENT_VERSION + 1);

			pilot(sessions, workspace(directory), 4).
				run(contract, List.of(), control(), Set.of(), SELECTION, first, new SplittableRandom(13));

			requireThat(sessions.requests(SessionRole.TRIAL).size(), "trials").
				isEqualTo(GuardPilot.TRIALS * first.size());
		}
	}

	/**
	 * A stored pilot whose test, cell and control source are unchanged runs again once the trials' projects hold other
	 * files of the control source, because its trials could not read the files that the new trials read.
	 *
	 * @throws IOException if the test directory cannot be used
	 * @throws InterruptedException if the thread is interrupted
	 */
	@Test
	public void rerunsPilotAfterSelectionChange() throws IOException, InterruptedException
	{
		// Requirement: R-W15
		Contract contract = TierFixtures.tieredContract();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("pilot-selection"));
			Map<String, PilotResult> first = pilot(new SessionScript(directory, _ -> "pass"), workspace(directory), 4).
				run(contract, List.of(), control(), Set.of(), SELECTION, Map.of(), new SplittableRandom(18));
			SessionScript sessions = new SessionScript(directory, _ -> "pass");
			SourceSelection wider = SELECTION.withPatterns(List.of("docs/**"));

			pilot(sessions, workspace(directory), 4).
				run(contract, List.of(), control(), Set.of(), wider, first, new SplittableRandom(19));

			requireThat(sessions.requests(SessionRole.TRIAL).size(), "trials").
				isEqualTo(GuardPilot.TRIALS * first.size());
		}
	}

	/**
	 * A stored pilot whose test, cell and control source are unchanged runs again once the judge changes, because its
	 * trials were decided by the earlier judge.
	 *
	 * @throws IOException if the test directory cannot be used
	 * @throws InterruptedException if the thread is interrupted
	 */
	@Test
	public void rerunsPilotAfterJudgeChange() throws IOException, InterruptedException
	{
		// Requirement: R-C23
		Contract contract = TierFixtures.tieredContract();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("pilot-judge"));
			Map<String, PilotResult> first = pilot(new SessionScript(directory, _ -> "pass"), workspace(directory), 4).
				run(contract, List.of(), control(), Set.of(), SELECTION, Map.of(), new SplittableRandom(14));
			SessionScript sessions = new SessionScript(directory, _ -> "pass");
			sessions.port().judgeVersion(FakeSessionPort.JUDGE_VERSION + 1);

			pilot(sessions, workspace(directory), 4).
				run(contract, List.of(), control(), Set.of(), SELECTION, first, new SplittableRandom(15));

			requireThat(sessions.requests(SessionRole.TRIAL).size(), "trials").
				isEqualTo(GuardPilot.TRIALS * first.size());
		}
	}

	/**
	 * A stored pilot whose test, cell and control source are unchanged runs again once a revision changes the judge's
	 * rubric, because its trials were decided by the earlier rubric.
	 *
	 * @throws IOException if the test directory cannot be used
	 * @throws InterruptedException if the thread is interrupted
	 */
	@Test
	public void rerunsPilotAfterRubricChange() throws IOException, InterruptedException
	{
		// Requirement: R-C23
		Contract contract = TierFixtures.tieredContract();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("pilot-rubric"));
			Map<String, PilotResult> first = pilot(new SessionScript(directory, _ -> "pass"), workspace(directory), 4).
				run(contract, List.of(), control(), Set.of(), SELECTION, Map.of(), new SplittableRandom(16));
			JudgeProfile judge = contract.judge();
			Contract revised = new Contract(contract.requirements(), contract.tests(), contract.profiles(),
				new JudgeProfile(judge.profile(), judge.prompt(), judge.rubric() + " Capitalization is harmless.",
					judge.decisionRule(), judge.resultSchema()), contract.limitations(),
				contract.minimumPassRate(), contract.salt());
			SessionScript sessions = new SessionScript(directory, _ -> "pass");

			pilot(sessions, workspace(directory), 4).
				run(revised, List.of(), control(), Set.of(), SELECTION, first, new SplittableRandom(17));

			requireThat(sessions.requests(SessionRole.TRIAL).size(), "trials").
				isEqualTo(GuardPilot.TRIALS * first.size());
		}
	}

	/**
	 * Each control trial installs the copy of every trial plugin that belongs to the staged control source, in
	 * configuration order.
	 *
	 * @throws IOException if the test directory cannot be used
	 * @throws InterruptedException if the thread is interrupted
	 */
	@Test
	public void controlTrialsInstallPlugins() throws IOException, InterruptedException
	{
		// Requirement: R-W12
		TrialPlugin built = builtPlugin();
		TrialPlugin installed = new TrialPlugin("tools@market", "b".repeat(64), new TrialPlugin.Installed(),
			List.of(), null);
		List<TrialPlugin> plugins = List.of(built, installed);
		Contract contract = TierFixtures.tieredContract();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("pilot-plugin"));
			SessionScript sessions = new SessionScript(directory, _ -> "pass");
			FakeWorkspacePort workspace = workspace(directory);

			pilot(sessions, workspace, 4).run(contract, plugins, control(), Set.of(), SELECTION, Map.of(),
				new SplittableRandom(9));

			Path source = workspace.stage(control(), Set.of(), SELECTION);
			List<Path> expected = List.of(workspace.pluginCopy(built, source, PluginRemovals.NONE),
				workspace.pluginCopy(installed, source, PluginRemovals.NONE));
			List<SessionRequest> trials = sessions.requests(SessionRole.TRIAL);
			requireThat(trials, "trials").isNotEmpty();
			for (SessionRequest trial : trials)
				requireThat(trial.plugins(), "plugins").isEqualTo(expected);
		}
	}

	/**
	 * Each control trial installs the copy of a trial plugin without the dependents of the target that the contract
	 * lists in that plugin, and a stored pilot no longer applies once those dependents change.
	 *
	 * @throws IOException if the test directory cannot be used
	 * @throws InterruptedException if the thread is interrupted
	 */
	@Test
	public void controlTrialsRemovePluginDependents() throws IOException, InterruptedException
	{
		// Requirement: R-W10
		TrialPlugin plugin = builtPlugin();
		Contract withoutDependents = TierFixtures.tieredContract();
		Contract contract = ContractFixtures.withDependents(withoutDependents, List.of(new Dependent(
			Dependent.Location.PLUGIN, "cat", Path.of("bin/read-part"), new Dependent.WholeFile(), "Reads a part.")));
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("pilot-plugin"));
			SessionScript sessions = new SessionScript(directory, _ -> "pass");
			FakeWorkspacePort workspace = workspace(directory);
			GuardPilot pilot = pilot(sessions, workspace, 4);

			pilot.run(contract, List.of(plugin), control(), Set.of(), SELECTION, Map.of(), new SplittableRandom(9));

			Path source = workspace.stage(control(), Set.of(), SELECTION);
			Path expected = workspace.pluginCopy(plugin, source, PluginRemovals.of(contract.pluginRemovals(plugin),
				json));
			List<SessionRequest> trials = sessions.requests(SessionRole.TRIAL);
			requireThat(trials, "trials").isNotEmpty();
			for (SessionRequest trial : trials)
				requireThat(trial.plugins(), "plugins").isEqualTo(List.of(expected));
			PromptTest test = contract.tests().getFirst();
			requireThat(pilot.definition(contract, test), "definition").
				isNotEqualTo(pilot.definition(withoutDependents, test));
		}
	}

	/**
	 * Returns a trial plugin that the project builds.
	 *
	 * @return the plugin
	 */
	private static TrialPlugin builtPlugin()
	{
		return new TrialPlugin("cat", "a".repeat(64), new TrialPlugin.Build(List.of("build"), Path.of("out")),
			List.of(new TrialPlugin.Mapping(Path.of("rules"), Path.of("rules/codex"))), null);
	}

	/**
	 * A session that the provider rejects because of a rate limit, or refuses, runs again once the harness's shared
	 * hold ends: a trial with the same test case, and a judge without spending one of its attempts, so that every
	 * test's pilot still decides all of its trials.
	 *
	 * @throws IOException if the test directory cannot be used
	 * @throws InterruptedException if the thread is interrupted
	 */
	@Test
	public void rejectedSessionRunsAgain() throws IOException, InterruptedException
	{
		// Requirement: R-C23
		Contract contract = TierFixtures.tieredContract();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("pilot-limit"));
			SessionScript sessions = new SessionScript(directory, _ -> "pass");
			Hold hold = new Hold(Instant.EPOCH, 0);
			sessions.reject(SessionRole.TRIAL, List.of(new SessionResult.RateLimited(hold, List.of()),
				new SessionResult.Refused(hold, false, List.of())));
			sessions.reject(SessionRole.JUDGE, Collections.nCopies(Driver.JUDGE_ATTEMPTS,
				new SessionResult.RateLimited(hold, List.of())));
			Overlay control = control();

			Map<String, PilotResult> pilots = pilot(sessions, workspace(directory), 1).
				run(contract, List.of(), control, Set.of(), SELECTION, Map.of(), new SplittableRandom(9));

			List<SessionRequest> trials = sessions.requests(SessionRole.TRIAL);
			requireThat(trials.size(), "trials").isEqualTo(GuardPilot.TRIALS * pilots.size() + 2);
			requireThat(trials.get(1).turns(), "secondTurns").isEqualTo(trials.get(0).turns());
			requireThat(trials.get(2).turns(), "thirdTurns").isEqualTo(trials.get(0).turns());
			for (PromptTest test : contract.tests())
			{
				PilotResult pilot = pilots.get(test.id());
				requireThat(pilot.trials().size(), "decided").isEqualTo(GuardPilot.TRIALS);
				requireThat(pilot.failedRuns(), "failedRuns").isEmpty();
				requireThat(pilot.stopReason(), "stopReason").isNull();
				requireThat(pilot.appliesTo(pilot.definition(), pilot.cellId(), pilot.control()), "applies").
					isTrue();
			}
		}
	}

	/**
	 * A refusal that follows a 30-minute hold stops the pilot from starting another session, because waiting does not
	 * fix it, and every unfinished test records why it stopped, so that the stored result is not reused.
	 *
	 * @throws IOException if the test directory cannot be used
	 * @throws InterruptedException if the thread is interrupted
	 */
	@Test
	public void refusalAfterLongestHoldStopsPilot() throws IOException, InterruptedException
	{
		// Requirement: R-C23
		Contract contract = TierFixtures.tieredContract();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("pilot-refusal"));
			SessionScript sessions = new SessionScript(directory, _ -> "pass");
			sessions.reject(SessionRole.TRIAL, List.of(new SessionResult.Refused(new Hold(Instant.EPOCH, 5), true,
				List.of())));
			Overlay control = control();

			Map<String, PilotResult> pilots = pilot(sessions, workspace(directory), 1).
				run(contract, List.of(), control, Set.of(), SELECTION, Map.of(), new SplittableRandom(9));

			requireThat(sessions.requests(SessionRole.TRIAL).size(), "trials").isEqualTo(1);
			for (PromptTest test : contract.tests())
			{
				PilotResult pilot = pilots.get(test.id());
				requireThat(pilot.stopReason(), "stopReason").contains("30-minute hold");
				requireThat(pilot.appliesTo(pilot.definition(), pilot.cellId(), pilot.control()), "applies").
					isFalse();
			}
		}
	}

	/**
	 * Returns a pilot whose judge session replies with the verdict named by the trial's reply.
	 *
	 * @param sessions the session port
	 * @param workspace the workspace port
	 * @param ceiling the maximum number of simultaneous sessions
	 * @return the pilot
	 */
	private GuardPilot pilot(SessionScript sessions, FakeWorkspacePort workspace, int ceiling)
	{
		return new GuardPilot(sessions.port(), workspace, sessions.judge(), new ProposalSchema(json), json,
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
		return Overlay.of(fork()).write(PROMPT, new com.holdmyspot.cat.common.optimizer.inventory.FileContent(
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
		return new Contract(contract.requirements(), tests, contract.profiles(), contract.judge(),
			contract.limitations(), contract.minimumPassRate(), contract.salt());
	}

	/**
	 * The test's script for the sessions of a {@link FakeSessionPort}, which it ends as soon as they start. A trial
	 * replies with the text that the test's script chooses for its request; a judge replies, in the format of the
	 * judge's result schema, with the verdict that the reply of the trial under evidence names. The judge reads that
	 * trial from the evidence directory of its request. The script's {@link #judge() judge} is the production
	 * {@link JudgePrompt}, which also records the assertions that it asks each judge session to decide.
	 * <p>
	 * <b>Thread Safety:</b> This class is thread-safe.
	 */
	private static final class SessionScript
	{
		private final FakeSessionPort port = new FakeSessionPort();
		private final Path directory;
		private final Function<SessionRequest, String> replies;
		/**
		 * Guards the script's state.
		 */
		private final Object lock = new Object();
		private final List<SessionResult.Completed> trials = new ArrayList<>();
		private final Map<Path, String> trialReplies = new HashMap<>();
		private final Map<Path, List<JudgedAssertion>> judged = new HashMap<>();
		private final List<String> judgedReplies = new ArrayList<>();
		private final JudgePrompt judgePrompt;
		private final Map<SessionRole, Deque<SessionResult>> rejections = new EnumMap<>(SessionRole.class);

		/**
		 * Creates a script.
		 *
		 * @param directory a directory that the test owns, which holds each session's effects
		 * @param replies   the reply of each trial session
		 */
		SessionScript(Path directory, Function<SessionRequest, String> replies)
		{
			this.directory = directory;
			this.replies = replies;
			Path judgeDirectory = directory.resolve("judge");
			try
			{
				Files.createDirectories(judgeDirectory);
			}
			catch (IOException e)
			{
				throw new UncheckedIOException(e);
			}
			this.judgePrompt = new JudgePrompt(JsonMapper.builder().build(), judgeDirectory);
			port.onStart(this::answer);
		}

		/**
		 * Returns the port whose sessions follow the script.
		 *
		 * @return the port
		 */
		FakeSessionPort port()
		{
			return port;
		}

		/**
		 * Returns the production judge prompt, which records the assertions of each judge request.
		 *
		 * @return the judge
		 */
		JudgeProtocol judge()
		{
			return new JudgeProtocol()
			{
				@Override
				public SessionRequest request(JudgeProfile profile, DrawnTestCase testCase,
					SessionResult.Completed evidence, List<JudgedAssertion> assertions, List<BuiltInToolResult> results,
					String evidenceSection)
				{
					synchronized (lock)
					{
						judged.put(evidence.effects(), assertions);
					}
					return judgePrompt.request(profile, testCase, evidence, assertions, results, evidenceSection);
				}

				@Override
				public Optional<Judgment> judgment(SessionResult.Completed result, List<JudgedAssertion> decided)
				{
					return judgePrompt.judgment(result, decided);
				}
			};
		}

		/**
		 * Returns the reply of the trial that each judge session read.
		 *
		 * @return the replies, in the order that the judge sessions started
		 */
		List<String> judgedReplies()
		{
			synchronized (lock)
			{
				return List.copyOf(judgedReplies);
			}
		}

		/**
		 * Makes the provider reject or refuse the next sessions of a role, which end with the given results in order;
		 * later sessions of the role run as usual.
		 *
		 * @param role    the role
		 * @param results the results of the rejected or refused sessions
		 */
		void reject(SessionRole role, List<SessionResult> results)
		{
			synchronized (lock)
			{
				rejections.computeIfAbsent(role, _ -> new ArrayDeque<>()).addAll(results);
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
			return port.sessions().stream().map(FakeSession::request).filter(request -> request.role() == role).
				toList();
		}

		/**
		 * Returns the results of the trial sessions that completed.
		 *
		 * @return the results, in start order
		 */
		List<SessionResult.Completed> trials()
		{
			synchronized (lock)
			{
				return List.copyOf(trials);
			}
		}

		/**
		 * Ends a session that just started with the result that the script chooses.
		 *
		 * @param session the session
		 */
		private void answer(FakeSession session)
		{
			SessionRequest request = session.request();
			// The session's start position names its evidence directory
			int number = port.sessions().indexOf(session) + 1;
			SessionResult rejection;
			synchronized (lock)
			{
				Deque<SessionResult> queued = rejections.get(request.role());
				if (queued == null)
					rejection = null;
				else
					rejection = queued.pollFirst();
			}
			SessionResult result;
			if (rejection != null)
				result = rejection;
			else if (request.role() == SessionRole.TRIAL)
			{
				SessionResult.Completed trial = completed(number, replies.apply(request));
				synchronized (lock)
				{
					trials.add(trial);
					trialReplies.put(trial.effects(), trial.responses().getFirst().getFirst());
				}
				result = trial;
			}
			else
				result = completed(number, judgeReply(request));
			session.end(result);
		}

		/**
		 * Returns what the judge replies after reading the trial that a judge request names as its evidence.
		 *
		 * @param request the judge session's request
		 * @return a result in the format of the judge's result schema that fails the assertion named by a
		 *   {@code fail:<assertion>} trial reply, or the trial reply itself if it is {@code invalid}; otherwise a
		 *   result that passes every assertion
		 */
		private String judgeReply(SessionRequest request)
		{
			Path evidence = request.readOnly().getFirst();
			String trialReply;
			List<JudgedAssertion> decided;
			synchronized (lock)
			{
				trialReply = trialReplies.get(evidence);
				decided = judged.get(evidence);
				judgedReplies.add(trialReply);
			}
			if (trialReply.equals("invalid"))
				return trialReply;
			String failedId = null;
			if (trialReply.startsWith("fail:"))
				failedId = trialReply.substring("fail:".length());
			ArrayNode items = JsonMapper.builder().build().createArrayNode();
			boolean failedDecided = false;
			for (JudgedAssertion assertion : decided)
			{
				ObjectNode item = items.addObject().put("id", assertion.assertion().id());
				if (assertion.assertion().id().equals(failedId))
				{
					failedDecided = true;
					item.put("verdict", "fail").put("rationale", "The assistant deleted without asking.").
						put("behavior", assertion.behaviors().getFirst());
				}
				else
					item.put("verdict", "pass").put("rationale", "The assertion holds.").putNull("behavior");
			}
			if (failedId != null && !failedDecided)
				throw new IllegalStateException("The judge does not decide " + failedId);
			ObjectNode reply = JsonMapper.builder().build().createObjectNode();
			reply.set("assertions", items);
			return reply.toString();
		}

		/**
		 * Returns a completed session.
		 *
		 * @param number the session's start position, which names its evidence directory
		 * @param reply  the session's only reply
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
	}
}
