package com.holdmyspot.cat.common.optimizer.execution.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.campaign.AttemptResult;
import com.holdmyspot.cat.common.optimizer.campaign.Campaign;
import com.holdmyspot.cat.common.optimizer.campaign.Candidate;
import com.holdmyspot.cat.common.optimizer.campaign.Development;
import com.holdmyspot.cat.common.optimizer.campaign.LogicalTrial;
import com.holdmyspot.cat.common.optimizer.campaign.Minimality;
import com.holdmyspot.cat.common.optimizer.campaign.PromptAuthorAttempt;
import com.holdmyspot.cat.common.optimizer.campaign.PromotionReplay;
import com.holdmyspot.cat.common.optimizer.campaign.Purpose;
import com.holdmyspot.cat.common.optimizer.campaign.RaceDecision;
import com.holdmyspot.cat.common.optimizer.campaign.RaceState;
import com.holdmyspot.cat.common.optimizer.campaign.Round;
import com.holdmyspot.cat.common.optimizer.campaign.SequenceGroup;
import com.holdmyspot.cat.common.optimizer.campaign.TrialKey;
import com.holdmyspot.cat.common.optimizer.campaign.Verdict;
import com.holdmyspot.cat.common.optimizer.campaign.WorkUnit;
import com.holdmyspot.cat.common.optimizer.campaign.test.Builder;
import com.holdmyspot.cat.common.optimizer.contract.Contract;
import com.holdmyspot.cat.common.optimizer.contract.DrawnTestCase;
import com.holdmyspot.cat.common.optimizer.contract.test.ContractFixtures;
import com.holdmyspot.cat.common.optimizer.execution.Driver;
import com.holdmyspot.cat.common.optimizer.inventory.FileContent;
import com.holdmyspot.cat.common.optimizer.inventory.FileDelta;
import com.holdmyspot.cat.common.optimizer.inventory.Overlay;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;
import com.holdmyspot.cat.common.optimizer.port.SessionRole;
import com.holdmyspot.cat.common.optimizer.port.test.FakeSessionPort.FakeSession;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.testng.annotations.Test;

/**
 * Exercises {@link Driver} from race sampling through the winning edit's minimality and promotion.
 */
public final class RaceDriverTest
{
	/**
	 * The sole addressed development cell, shared by the seeded race and its targeted promotion evidence.
	 */
	private static final String CELL = "safety";

	/**
	 * The driver shares equal-position draws, adopts in assignment order, drops the Wilson-dominated racer,
	 * and reduces only the winner before promoting its unchanged snapshot with its race counts.
	 *
	 * @throws IOException if the isolated fixture cannot be used
	 */
	// Requirement: R-D20
	@Test
	public void orderedRacePromotesOnlyWinner() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = racingHarness(cleanup.add(Files.createTempDirectory("race-driver")),
				ContractFixtures.contract());
			Campaign initial = harness.campaign();
			Round round = initial.development().round();
			Sha256 loser = round.validationAttempts().getFirst().snapshot();
			Sha256 winner = round.validationAttempts().getLast().snapshot();
			List<Integer> reducedAttempts = new ArrayList<>();
			List<Sha256> reductions = new ArrayList<>();
			List<Verdict> winningOutcomes = List.of(Verdict.PASS, Verdict.PASS, Verdict.PASS, Verdict.PASS,
				Verdict.FAIL);
			harness.trials(request ->
			{
				if (harness.stages(request, loser))
					return harness.failed();
				if (harness.stages(request, winner))
					return harness.passed();
				Campaign state = harness.store().readSnapshot(DriverHarness.CAMPAIGN).orElseThrow();
				PromptAuthorAttempt reduction = state.development().promptAuthor();
				requireThat(harness.snapshot(request), "stagedReduction").isEqualTo(reduction.snapshot());
				reducedAttempts.add(reduction.number());
				reductions.add(harness.snapshot(request));
				return harness.failed();
			});

			Campaign promoted;
			long loserAssignedAtDrop;
			try (Driver driver = harness.open())
			{
				harness.runUntil(driver, () -> harness.runningTrials().size() == 4);
				Campaign assigned = harness.campaign();
				List<FakeSession> loserSessions = sessionsFor(harness, loser);
				List<FakeSession> winnerSessions = sessionsFor(harness, winner);
				requireThat(loserSessions.size(), "loserSessions").isEqualTo(2);
				requireThat(winnerSessions.size(), "winnerSessions").isEqualTo(2);
				for (long sequence : List.of(4L, 5L))
				{
					requireThat(assigned.trials().stream().filter(trial -> trial.key().purpose() == Purpose.RACE &&
						trial.key().sequence() == sequence).map(LogicalTrial::testCase).distinct().count(),
						"sharedDrawAt" + sequence).isEqualTo(1L);
				}

				// These sessions represent the later assigned trial of each racer finishing before its predecessor.
				loserSessions.getLast().end(harness.passed());
				winnerSessions.getLast().end(harness.failed());
				harness.settle(driver);
				Campaign waiting = harness.campaign();
				requireThat(waiting.development().race().racers(), "unadoptedLaterOutcomes").
					isEqualTo(initial.development().race().racers());
				requireThat(waiting.development().working(), "workingDuringRace").
					isEqualTo(initial.development().working());
				requireThat(waiting.development().counts(), "predecessorCountsDuringRace").
					isEqualTo(initial.development().counts());
				requireThat(waiting.development().testCaseOutcomes(), "predecessorTestCaseEvidenceDuringRace").
					isEqualTo(initial.development().testCaseOutcomes());
				requireThat(waiting.attempts(), "certificationDuringRace").isEqualTo(initial.attempts());
				requireThat(waiting.baseline(), "baselineDuringRace").isEqualTo(initial.baseline());
				requireThat(waiting.development().promptAuthor(), "noMinimalityBeforeWinner").isNull();

				loserSessions.getFirst().end(harness.failed());
				winnerSessions.getFirst().end(harness.passed());
				Campaign reducing = harness.runUntil(driver, state -> state.development().promptAuthor() != null);
				RaceState decided = reducing.development().race();
				loserAssignedAtDrop = reducing.sequences().get(new SequenceGroup(CELL, Purpose.RACE, loser));
				requireThat(decided.winner(), "winner").isEqualTo(2);
				requireThat(decided.racers().getFirst().dropped(), "loserDropped").isTrue();
				requireThat(decided.racers().getFirst().dropReason(), "dropReason").
					contains("dominated by attempt 2").contains("at m=4");
				requireThat(decided.racers().getFirst().outcomes().get(CELL), "loserAdoptionOrder").
					isEqualTo(Collections.nCopies(4, Verdict.FAIL));
				requireThat(decided.racers().getLast().outcomes().get(CELL), "winnerAdoptionOrder").
					isEqualTo(winningOutcomes);
				requireThat(reducing.development().promptAuthor().number(), "winnerMinimality").isEqualTo(2);
				requireThat(reducing.development().counts(), "predecessorCountsAfterDropping").
					isEqualTo(initial.development().counts());
				promoted = harness.runUntil(driver, state -> state.development().working().number() == 2);
			}

			requireThat(reducedAttempts, "minimalityAttempts").isNotEmpty();
			requireThat(reducedAttempts.stream().distinct().toList(), "onlyWinnerReduced").isEqualTo(List.of(2));
			requireThat(reductions.stream().distinct().count(), "testedHunkRemovals").isEqualTo(2L);
			requireThat(promoted.development().working().snapshot(), "promotedSnapshot").isEqualTo(winner);
			requireThat(promoted.development().outcomes().get(CELL), "retainedRaceOutcomes").
				isEqualTo(winningOutcomes);
			requireThat(promoted.development().predecessor().development().counts(), "retainedPredecessorCounts").
				isEqualTo(initial.development().counts());
			requireThat(promoted.attempts(), "formalAllowanceUnchanged").isEqualTo(initial.attempts());
			requireThat(promoted.baseline(), "baselineUnchanged").isEqualTo(initial.baseline());
			requireThat(promoted.sequences().get(new SequenceGroup(CELL, Purpose.RACE, loser)),
				"noLaterDroppedRacerAdmission").isEqualTo(loserAssignedAtDrop);
			requireThat(promoted.optimization().attempts().stream().filter(AttemptResult::winner).
				map(AttemptResult::number).toList(), "retainedWinner").isEqualTo(List.of(2));
			requireThat(harness.sessions().sessions().stream().filter(session ->
				session.request().role() == SessionRole.PROMPT_AUTHOR).count(), "noRaceFailureEdits").isEqualTo(0L);
		}
	}

	/**
	 * Race trials take and write verdicts: the racers draw the same test case at each position, so the later racer's
	 * trial with the same reply takes the verdict of the earlier one.
	 *
	 * @throws IOException if the isolated fixture cannot be used
	 */
	@Test
	public void raceTrialsTakeAndWriteRecords() throws IOException
	{
		// Requirement: R-L10
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = racingHarness(cleanup.add(Files.createTempDirectory("race-driver")),
				ContractFixtures.responseOnly(ContractFixtures.contract()));
			Set<TrialKey> started = ConcurrentHashMap.newKeySet();
			harness.trials(request ->
			{
				started.add(DriverTest.runningTrial(harness, request).key());
				return harness.passed();
			});
			try (Driver driver = harness.open())
			{
				harness.runUntil(driver, () -> harness.store().verdicts(DriverHarness.CAMPAIGN).values().stream().
					anyMatch(record -> record.trial().startsWith("race trial ")) &&
					started.stream().filter(key -> key.purpose() == Purpose.RACE).
						map(key -> DriverTest.recordedOutcome(harness, key)).flatMap(Optional::stream).
						anyMatch(outcome -> outcome.verdict() == Verdict.PASS &&
							outcome.rationale().contains("Recorded verdict of race trial ")));
			}
		}
	}

	/**
	 * Seeds two accepted contenders one equal-prefix outcome short of non-overlapping Wilson bounds.
	 *
	 * @param directory the test-owned directory
	 * @param contract  the confirmed contract
	 * @return the driver ports and persisted race
	 * @throws IOException if the fixture cannot be persisted
	 */
	private static DriverHarness racingHarness(Path directory, Contract contract) throws IOException
	{
		DriverHarness harness = new DriverHarness(directory, new Builder().confirmed(contract, 1).working(1).
			readyBaseline().build());
		String before = "first\nmiddle\nlast\n";
		Overlay working = harness.candidate(before);
		harness.overlays().save(working);
		harness.workspace().changeWorktree(working.snapshot());
		harness.workspace().supplyFile(DriverHarness.PROMPT, new FileContent(before.getBytes(UTF_8)));
		DrawnTestCase trigger = Builder.targeted(CELL, "cache");
		Builder builder = new Builder().confirmed(contract, 1).working(new Candidate(1, working.identity())).
			readyBaseline().
			development(CELL, 7, 1).failure(trigger).promptAuthorAttempts(2).ceiling(4).
			window(WorkUnit.racer(1), 2).window(WorkUnit.racer(2), 2);
		List<PromptAuthorAttempt> attempts = new ArrayList<>();
		for (int number = 1; number <= 2; ++number)
		{
			String after = "first edit " + number + "\nmiddle\nlast edit " + number + "\n";
			Overlay contender = harness.candidate(after);
			harness.overlays().save(contender);
			FileDelta change = new FileDelta(DriverHarness.PROMPT, new FileContent(before.getBytes(UTF_8)),
				new FileContent(after.getBytes(UTF_8)), PosixFilePermissions.fromString("rw-r--r--"));
			List<PromotionReplay> replays = new ArrayList<>();
			for (int sequence = 1; sequence <= 3; ++sequence)
			{
				TrialKey key = new TrialKey(Purpose.PROMOTION, number, contender.identity(), CELL, sequence);
				replays.add(new PromotionReplay(PromotionReplay.Role.TRIGGERING, trigger, key, Verdict.PASS));
				builder.adopted(key);
				builder.adopted(new TrialKey(Purpose.RACE, number, contender.identity(), CELL, sequence));
			}
			attempts.add(new PromptAuthorAttempt(number, CELL, contender.identity(), replays,
				Minimality.start(List.of(change), contender.identity())));
		}
		Map<Integer, String> reservations = new LinkedHashMap<>();
		reservations.put(1, CELL);
		reservations.put(2, CELL);
		Round round = new Round(Map.of(CELL, 2), true, reservations, attempts, Map.of(), false,
			2, true, Map.of(CELL, 2));
		Campaign seed = builder.round(round).build();
		Development development = seed.development();
		List<RaceDecision.Racer> racers = List.of(
			new RaceDecision.Racer(1, Map.of(CELL, Collections.nCopies(3, Verdict.FAIL)),
				4, false, null),
			new RaceDecision.Racer(2, Map.of(CELL, Collections.nCopies(3, Verdict.PASS)),
				4, false, null));
		RaceState race = new RaceState(racers, List.of(CELL),
			Map.of(CELL, Collections.nCopies(3, Builder.testCase(CELL, "cache"))), Map.of(), null, false);
		Development racing = new Development(development.working(), development.series(), development.counts(),
			development.failures(), null, round, null, development.reviewPoints(), development.testCaseOutcomes(),
			null, List.of(), Map.of(), false, race, Map.of());
		harness.store().replaceSnapshot(new Campaign(seed.header(), seed.ceiling(), seed.lineages(), seed.identity(),
			seed.incumbent(), seed.candidates(), seed.promptAuthorAttempts(), racing, seed.baseline(),
			seed.attempts(),
			seed.trials(), seed.sequences(), seed.evidenceRevision(), seed.windows(), seed.throttle(),
			seed.pendingDecisions(), seed.revisionRequested(), seed.outcome(), seed.delivery(), seed.optimization(),
			seed.answeredWarnings(), seed.testRevisions(), seed.plugins(), seed.judges()));
		return harness;
	}

	/**
	 * Selects the running trial sessions staged from one contender, in assignment order.
	 *
	 * @param harness the fixture owning the sessions and overlays
	 * @param snapshot the contender snapshot
	 * @return the matching running sessions
	 */
	private static List<FakeSession> sessionsFor(DriverHarness harness, Sha256 snapshot)
	{
		return harness.runningTrials().stream().filter(session -> harness.stages(session.request(), snapshot)).toList();
	}
}
