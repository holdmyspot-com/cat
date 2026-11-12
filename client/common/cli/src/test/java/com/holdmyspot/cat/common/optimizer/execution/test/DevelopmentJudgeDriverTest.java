package com.holdmyspot.cat.common.optimizer.execution.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.Yielding;
import com.holdmyspot.cat.common.optimizer.campaign.Campaign;
import com.holdmyspot.cat.common.optimizer.campaign.CarriedJudge;
import com.holdmyspot.cat.common.optimizer.campaign.CommitGate;
import com.holdmyspot.cat.common.optimizer.campaign.DevelopmentJudge;
import com.holdmyspot.cat.common.optimizer.campaign.DevelopmentJudge.Stage;
import com.holdmyspot.cat.common.optimizer.campaign.ExaminedJudge;
import com.holdmyspot.cat.common.optimizer.campaign.JudgeBasis;
import com.holdmyspot.cat.common.optimizer.campaign.Lifecycle;
import com.holdmyspot.cat.common.optimizer.campaign.LogicalTrial;
import com.holdmyspot.cat.common.optimizer.campaign.Pairing;
import com.holdmyspot.cat.common.optimizer.campaign.PairCounts;
import com.holdmyspot.cat.common.optimizer.campaign.Purpose;
import com.holdmyspot.cat.common.optimizer.campaign.RejectedJudge;
import com.holdmyspot.cat.common.optimizer.campaign.RejectedJudge.Rejection;
import com.holdmyspot.cat.common.optimizer.campaign.TrialState;
import com.holdmyspot.cat.common.optimizer.campaign.Verdict;
import com.holdmyspot.cat.common.optimizer.campaign.test.Boundaries;
import com.holdmyspot.cat.common.optimizer.campaign.test.Builder;
import com.holdmyspot.cat.common.optimizer.contract.Assertion;
import com.holdmyspot.cat.common.optimizer.contract.Cell;
import com.holdmyspot.cat.common.optimizer.contract.Contract;
import com.holdmyspot.cat.common.optimizer.contract.NearMisses;
import com.holdmyspot.cat.common.optimizer.contract.PromptTest;
import com.holdmyspot.cat.common.optimizer.contract.ProposalSchema;
import com.holdmyspot.cat.common.optimizer.contract.TrialProfile;
import com.holdmyspot.cat.common.optimizer.contract.test.ContractFixtures;
import com.holdmyspot.cat.common.optimizer.execution.Driver;
import com.holdmyspot.cat.common.optimizer.inventory.InventoryScanner;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;
import com.holdmyspot.cat.common.optimizer.port.SessionRequest;
import com.holdmyspot.cat.common.optimizer.port.SessionResult;
import com.holdmyspot.cat.common.optimizer.statistics.DecisionBoundaries;
import com.holdmyspot.cat.common.optimizer.statistics.PairTally;
import com.holdmyspot.cat.common.optimizer.statistics.PairedComparison;
import com.holdmyspot.cat.common.optimizer.store.DevelopmentJudgeRecords;
import com.holdmyspot.cat.common.optimizer.store.FileCampaignStore;
import com.holdmyspot.cat.common.optimizer.store.JudgeRecordingStore;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Function;

import tools.jackson.databind.json.JsonMapper;

/**
 * Tests how the driver chooses the profile that judges each development trial, from the near misses that the frozen
 * judge labels to the spot checks of a qualified candidate.
 */
public final class DevelopmentJudgeDriverTest
{
	private static final TrialProfile CHEAP = new TrialProfile("cheap-model", "low");
	private static final TrialProfile MIDDLE = new TrialProfile("middle-model", "medium");
	private static final String TEST = "formatting";
	private static final List<String> TESTS = List.of("formatting", "plain_list", "safety");
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * Creates the test.
	 */
	public DevelopmentJudgeDriverTest()
	{
	}

	/**
	 * Verifies that a candidate that disagrees with the frozen judge on a near miss is rejected without judging a
	 * development trial, and that the next candidate, which agrees, starts to shadow the frozen judge. The frozen
	 * judge's labels are kept with the test.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void nearMissDisagreementRejectsCandidate() throws IOException
	{
		Contract contract = ContractFixtures.withNearMisses(ContractFixtures.contract());
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("near-miss")),
				developing(contract).build());
			harness.candidateJudges(List.of(CHEAP, MIDDLE));
			Fixture fixture = new Fixture(harness);
			fixture.judges(_ -> Verdict.PASS, (profile, satisfying) ->
			{
				if (profile.equals(CHEAP))
					return Verdict.FAIL;
				if (satisfying)
					return Verdict.PASS;
				return Verdict.FAIL;
			});
			try (Driver driver = harness.open())
			{
				Campaign campaign = harness.runUntil(driver, state ->
				{
					for (String test : TESTS)
					{
						DevelopmentJudge judge = state.judges().get(test);
						if (judge == null || judge.stage() != Stage.SHADOWING)
							return false;
					}
					return true;
				});
				int replies = 0;
				for (PromptTest test : contract.tests())
					replies += test.nearMisses().size() * 2 * NearMisses.REPLIES_PER_SIDE;
				for (String test : TESTS)
				{
					DevelopmentJudge judge = campaign.judges().get(test);
					requireThat(judge.current().orElseThrow(), "current").isEqualTo(MIDDLE);
					requireThat(judge.rejected(), "rejected").
						isEqualTo(List.of(new RejectedJudge(CHEAP, Rejection.NEAR_MISS, 0, 0, 0, 0)));
					requireThat(judge.labels(), "labels").isEqualTo(labels(contract.tests().stream().
						filter(candidate -> candidate.id().equals(test)).findFirst().orElseThrow()));
				}
				requireThat(fixture.uses(frozen(contract), true).size(), "frozenLabels").isEqualTo(replies);
				requireThat(fixture.uses(CHEAP, true).size(), "cheapChecks").isEqualTo(replies);
				requireThat(fixture.uses(MIDDLE, true).size(), "middleChecks").isEqualTo(replies);
				requireThat(fixture.uses(CHEAP, false), "cheapDevelopmentJudgments").isEmpty();
			}
		}
	}

	/**
	 * Verifies that the evidence of near-miss replies is created under the directory that the driver's context names
	 * for temporary directories.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void nearMissEvidenceInTempDirectory() throws IOException
	{
		Contract contract = ContractFixtures.withNearMisses(ContractFixtures.contract());
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("near-miss-location"));
			Path scratch = Files.createDirectory(directory.resolve("scratch"));
			DriverHarness harness = new DriverHarness(directory, developing(contract).build());
			harness.candidateJudges(List.of(CHEAP));
			harness.temporaryDirectory(scratch);
			Fixture fixture = new Fixture(harness);
			fixture.judges(_ -> Verdict.PASS, (_, satisfying) ->
			{
				if (satisfying)
					return Verdict.PASS;
				return Verdict.FAIL;
			});
			try (Driver driver = harness.open())
			{
				harness.runUntil(driver, _ -> !fixture.nearMissUses().isEmpty());
				requireThat(fixture.nearMissEvidence.isEmpty(), "nearMissEvidence.isEmpty()").isFalse();
				for (Path evidence : fixture.nearMissEvidence)
					requireThat(evidence.startsWith(scratch), "evidence.startsWith(scratch)").isTrue();
			}
		}
	}

	/**
	 * Verifies that shadowing is judged by both judges, that the frozen judge's verdict counts, that a disagreement is
	 * counted, and that the candidate qualifies when its fourth trial after 295 shadowed trials makes 299.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void shadowingCountsFrozenVerdicts() throws IOException
	{
		Contract contract = ContractFixtures.withNearMisses(ContractFixtures.contract());
		DevelopmentJudge shadowing = shadowingAt(contract, 295, 0, List.of(MIDDLE), 0, 0);
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("shadowing")),
				developing(contract).judge(TEST, shadowing).judge("plain_list", frozenOnly()).
					judge("safety", frozenOnly()).build());
			harness.candidateJudges(List.of(CHEAP, MIDDLE));
			Fixture fixture = new Fixture(harness);
			AtomicBoolean disagreed = new AtomicBoolean();
			fixture.judges(use ->
			{
				if (use.profile().equals(CHEAP) && disagreed.compareAndSet(false, true))
					return Verdict.FAIL;
				return Verdict.PASS;
			}, (_, _) -> Verdict.PASS);
			try (Driver driver = harness.open())
			{
				harness.runUntil(driver, state -> state.judges().get(TEST).stage() == Stage.QUALIFIED);
				harness.settle(driver);
				Campaign campaign = harness.campaign();
				DevelopmentJudge judge = campaign.judges().get(TEST);
				requireThat(judge.stage(), "stage").isEqualTo(Stage.QUALIFIED);
				requireThat(judge.shadowed(), "shadowed").isEqualTo(299);
				requireThat(judge.lenient(), "lenient").isEqualTo(0);
				requireThat(judge.strict(), "strict").isEqualTo(1);
				requireThat(campaign.development().count(TEST).failures(), "failures").isEqualTo(0);
				int shadowedTrials = fixture.uses(TEST, Purpose.DEVELOPMENT, frozen(contract)).size();
				int candidateTrials = fixture.uses(TEST, Purpose.DEVELOPMENT, CHEAP).size();
				requireThat(shadowedTrials, "frozenJudgments").isGreaterThanOrEqualTo(judge.shadowed() - 295);
				requireThat(candidateTrials, "candidateJudgments").isGreaterThanOrEqualTo(judge.shadowed() - 295);
				requireThat(fixture.uses(TEST, Purpose.DEVELOPMENT, MIDDLE), "middleJudgments").isEmpty();
			}
		}
	}

	/**
	 * Verifies that a qualified candidate decides alone, that the frozen judge also judges exactly the trials that
	 * count as spot checks, and that nothing disagrees.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void qualifiedCandidateIsSpotChecked() throws IOException
	{
		Contract contract = ContractFixtures.withNearMisses(ContractFixtures.contract());
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("qualified")),
				developing(contract).judge(TEST, qualified(contract)).judge("plain_list", frozenOnly()).
					judge("safety", frozenOnly()).build());
			harness.candidateJudges(List.of(CHEAP));
			Fixture fixture = new Fixture(harness);
			fixture.judges(_ -> Verdict.PASS, (_, _) -> Verdict.PASS);
			try (Driver driver = harness.open())
			{
				harness.runUntil(driver, state -> state.judges().get(TEST).spotChecked() >= 2);
				harness.settle(driver);
				DevelopmentJudge judge = harness.campaign().judges().get(TEST);
				requireThat(judge.stage(), "stage").isEqualTo(Stage.QUALIFIED);
				requireThat(judge.lenient(), "lenient").isEqualTo(0);
				requireThat(judge.strict(), "strict").isEqualTo(0);
				int frozenJudgments = fixture.uses(TEST, Purpose.DEVELOPMENT, frozen(contract)).size();
				int candidateJudgments = fixture.uses(TEST, Purpose.DEVELOPMENT, CHEAP).size();
				requireThat(frozenJudgments, "frozenJudgments").isEqualTo(judge.spotChecked());
				requireThat(candidateJudgments, "candidateJudgments").isGreaterThan(frozenJudgments);
			}
		}
	}

	/**
	 * Verifies that a spot check in which the frozen judge fails what the qualified candidate passes returns the test
	 * to the frozen judge, so that every later development trial of the test is judged by the frozen judge alone.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void lenientSpotCheckReturnsFrozenJudge() throws IOException
	{
		Contract contract = ContractFixtures.withNearMisses(ContractFixtures.contract());
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("spot-reversion")),
				developing(contract).judge(TEST, qualified(contract)).judge("plain_list", frozenOnly()).
					judge("safety", frozenOnly()).build());
			harness.candidateJudges(List.of(CHEAP));
			Fixture fixture = new Fixture(harness);
			AtomicBoolean failed = new AtomicBoolean();
			TrialProfile frozen = frozen(contract);
			fixture.judges(use ->
			{
				if (use.profile().equals(frozen) && use.test().equals(TEST) && failed.compareAndSet(false, true))
					return Verdict.FAIL;
				return Verdict.PASS;
			}, (_, _) -> Verdict.PASS);
			try (Driver driver = harness.open())
			{
				Campaign campaign = harness.runUntil(driver, state -> state.judges().get(TEST).stage() == Stage.FROZEN);
				DevelopmentJudge judge = campaign.judges().get(TEST);
				requireThat(judge.current().isEmpty(), "current().isEmpty()").isTrue();
				requireThat(judge.rejected().size(), "rejected").isEqualTo(1);
				requireThat(judge.rejected().getFirst().reason(), "reason").isEqualTo(Rejection.SPOT_CHECK);
				requireThat(judge.rejected().getFirst().lenient(), "rejectedLenient").isEqualTo(1);
				harness.settle(driver);
				int mark = fixture.uses(TEST, Purpose.DEVELOPMENT, null).size();
				harness.runUntil(driver, () -> fixture.uses(TEST, Purpose.DEVELOPMENT, null).size() >= mark + 3);
				List<Fixture.Use> later = fixture.uses(TEST, Purpose.DEVELOPMENT, null).subList(mark,
					fixture.uses(TEST, Purpose.DEVELOPMENT, null).size());
				for (Fixture.Use use : later)
					requireThat(use.profile(), "laterProfile").isEqualTo(frozen);
			}
		}
	}

	/**
	 * Verifies that a candidate whose verdict cannot be used counts as a strict disagreement, that the frozen
	 * verdict still decides each trial, and that the candidate is rejected once strict disagreements make the bound
	 * unreachable, leaving the frozen judge when no candidate remains.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void unusableCandidateCountsStrict() throws IOException
	{
		Contract contract = ContractFixtures.withNearMisses(ContractFixtures.contract());
		// Rounds of 1,000 development trials make shadowing development trials pay off from the first one
		DevelopmentJudge shadowing = shadowingAt(contract, 0, 0, List.of(), 1000, 1);
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("unusable")),
				developing(contract).judge(TEST, shadowing).judge("plain_list", frozenOnly()).
					judge("safety", frozenOnly()).build());
			harness.candidateJudges(List.of(CHEAP));
			Fixture fixture = new Fixture(harness);
			fixture.judges(use ->
			{
				if (use.profile().equals(CHEAP))
					return null;
				return Verdict.PASS;
			}, (_, _) -> Verdict.PASS);
			try (Driver driver = harness.open())
			{
				Campaign campaign = harness.runUntil(driver, state -> state.judges().get(TEST).stage() == Stage.FROZEN);
				DevelopmentJudge judge = campaign.judges().get(TEST);
				requireThat(judge.current().isEmpty(), "current().isEmpty()").isTrue();
				requireThat(judge.rejected().size(), "rejected").isEqualTo(1);
				RejectedJudge rejected = judge.rejected().getFirst();
				requireThat(rejected.reason(), "reason").isEqualTo(Rejection.SHADOWING);
				requireThat(rejected.strict(), "strict").isEqualTo(13);
				requireThat(rejected.lenient(), "lenient").isEqualTo(0);
				requireThat(campaign.development().count(TEST).failures(), "failures").isEqualTo(0);
			}
		}
	}

	/**
	 * Verifies that a campaign without a candidate profile, and a test whose contract has no near misses, keep the
	 * frozen judge: no session labels or checks near misses and no judge state is recorded.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void withoutCandidatesFrozenJudgeStays() throws IOException
	{
		Contract contract = ContractFixtures.withNearMisses(ContractFixtures.contract());
		Contract bare = ContractFixtures.contract();
		for (Contract used : List.of(contract, bare))
		{
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("frozen")),
					developing(used).build());
				if (used == bare)
					harness.candidateJudges(List.of(CHEAP, MIDDLE));
				Fixture fixture = new Fixture(harness);
				fixture.judges(_ -> Verdict.PASS, (_, _) -> Verdict.PASS);
				try (Driver driver = harness.open())
				{
					Campaign campaign = harness.runUntil(driver, state ->
						state.development().count(TEST).passes() >= 53);
					requireThat(campaign.judges(), "judges").isEmpty();
					requireThat(fixture.nearMissUses(), "nearMissUses").isEmpty();
					for (Fixture.Use use : fixture.uses)
						requireThat(use.profile(), "profile").isEqualTo(frozen(used));
					requireThat(fixture.uses.isEmpty(), "uses.isEmpty()").isFalse();
				}
			}
		}
	}

	/**
	 * Verifies that baseline trials are judged by the frozen judge alone once the test's candidate qualified, and that
	 * they leave the candidate's counts as they were.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void baselineTrialsUseFrozenJudge() throws IOException
	{
		Contract contract = ContractFixtures.withNearMisses(ContractFixtures.contract());
		Builder campaign = new Builder().confirmed(contract, 1).working(1);
		for (String test : TESTS)
			campaign.judge(test, qualified(contract));
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("baseline")),
				campaign.build());
			harness.candidateJudges(List.of(CHEAP));
			Fixture fixture = new Fixture(harness);
			fixture.judges(_ -> Verdict.PASS, (_, _) -> Verdict.PASS);
			try (Driver driver = harness.open())
			{
				harness.runUntil(driver, () -> fixture.purposeUses(Purpose.BASELINE).size() >= 6);
				harness.settle(driver);
				for (Fixture.Use use : fixture.uses)
				{
					if (use.purpose() != Purpose.DEVELOPMENT)
						requireThat(use.profile(), use.purpose().toString()).isEqualTo(frozen(contract));
				}
				for (String test : TESTS)
				{
					DevelopmentJudge judge = harness.campaign().judges().get(test);
					requireThat(judge.shadowed(), "shadowed").isEqualTo(299);
					requireThat(judge.spotChecked(), "spotChecked").isEqualTo(0);
				}
			}
		}
	}

	/**
	 * Verifies that formal, control and incumbent trials of a certification attempt are judged by the frozen judge
	 * alone once the test's candidate qualified, and that they leave the candidate's counts as they were.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void certificationAttemptUsesFrozenJudge() throws IOException
	{
		Contract contract = ContractFixtures.withNearMisses(ContractFixtures.contract());
		Builder start = new Builder().confirmed(contract, 1).working(1).readyBaseline();
		for (String cell : contract.cellIds())
			start.development(cell, CommitGate.MINIMUM_TRIALS, 0);
		Map<String, DevelopmentJudge> judges = new LinkedHashMap<>();
		for (String test : TESTS)
		{
			DevelopmentJudge qualified = qualified(contract);
			judges.put(test, qualified);
			start.judge(test, qualified);
		}
		DecisionBoundaries boundaries = Boundaries.paired(contract.cellIds().size());
		Builder.AttemptBuilder running = start.certificationAttempt(1).boundaries(boundaries);
		for (String cell : contract.cellIds())
			oneReferencePairShort(running, boundaries, contract, cell);
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("certification"));
			Path base = Files.createDirectory(root.resolve("base"));
			Files.writeString(base.resolve(DriverHarness.PROMPT), "fork");
			DriverHarness harness = new DriverHarness(root.resolve("driver"), running.build(),
				InventoryScanner.scan(base, Set.of()), base);
			harness.overlays().put(Builder.CONTROL, harness.candidate(""));
			harness.candidateJudges(List.of(CHEAP));
			Fixture fixture = new Fixture(harness);
			fixture.judges(_ -> Verdict.PASS, (_, _) -> Verdict.PASS);
			try (Driver driver = harness.open())
			{
				harness.runSlice(driver);
				Set<Purpose> purposes = EnumSet.noneOf(Purpose.class);
				for (Fixture.Use use : fixture.uses)
				{
					if (use.purpose() != Purpose.DEVELOPMENT)
					{
						purposes.add(use.purpose());
						requireThat(use.profile(), use.purpose().toString()).isEqualTo(frozen(contract));
					}
				}
				requireThat(purposes, "purposes").contains(Purpose.FORMAL).contains(Purpose.CONTROL).
					contains(Purpose.INCUMBENT);
				Campaign campaign = harness.campaign();
				for (Map.Entry<String, DevelopmentJudge> entry : judges.entrySet())
					requireThat(campaign.judges().get(entry.getKey()), entry.getKey()).isEqualTo(entry.getValue());
			}
		}
	}

	/**
	 * Verifies that a shadowed candidate also judges the test's baseline trials, which the frozen judge judges anyway,
	 * and that only the frozen judge's verdicts count.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void heldOutTrialsAreShadowed() throws IOException
	{
		Contract contract = ContractFixtures.withNearMisses(ContractFixtures.contract());
		Builder campaign = new Builder().confirmed(contract, 1).working(1).judge(TEST, shadowingAt(contract, 295, 0,
			List.of(), 0, 0)).judge("plain_list", frozenOnly()).judge("safety", frozenOnly());
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("held-out")),
				campaign.build());
			harness.candidateJudges(List.of(CHEAP));
			Fixture fixture = new Fixture(harness);
			fixture.judges(use ->
			{
				if (use.profile().equals(CHEAP))
					return Verdict.FAIL;
				return Verdict.PASS;
			}, (_, _) -> Verdict.PASS);
			try (Driver driver = harness.open())
			{
				Campaign state = harness.runUntil(driver, current -> current.judges().get(TEST).shadowed() >= 297);
				requireThat(state.judges().get(TEST).strict(), "strict").isGreaterThan(0);
				requireThat(fixture.uses(TEST, Purpose.BASELINE, CHEAP).isEmpty(), "candidateBaselineJudgments").
					isFalse();
				requireThat(state.baseline().count(TEST).failures(), "baselineFailures").isEqualTo(0);
				for (Fixture.Use use : fixture.uses(CHEAP, false))
					requireThat(use.purpose(), "candidatePurpose").isNotEqualTo(Purpose.DEVELOPMENT);
			}
		}
	}

	/**
	 * Verifies that held-out trials count toward a candidate's qualification, alongside the development trials it
	 * shadows, when they bring it from 295 to 299 shadowed trials without a disagreement.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void heldOutTrialsQualifyCandidate() throws IOException
	{
		Contract contract = ContractFixtures.withNearMisses(ContractFixtures.contract());
		Builder campaign = new Builder().confirmed(contract, 1).working(1).judge(TEST, shadowingAt(contract, 295, 0,
			List.of(), 0, 0)).judge("plain_list", frozenOnly()).judge("safety", frozenOnly());
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("held-out-qualify")),
				campaign.build());
			harness.candidateJudges(List.of(CHEAP));
			Fixture fixture = new Fixture(harness);
			fixture.judges(_ -> Verdict.PASS, (_, _) -> Verdict.PASS);
			try (Driver driver = harness.open())
			{
				Campaign state = harness.runUntil(driver, current ->
					current.judges().get(TEST).stage() == Stage.QUALIFIED);
				DevelopmentJudge judge = state.judges().get(TEST);
				requireThat(judge.shadowed(), "shadowed").isGreaterThanOrEqualTo(DevelopmentJudge.QUALIFYING_TRIALS);
				requireThat(judge.heldOut(), "heldOut").isPositive();
				requireThat(fixture.uses(TEST, Purpose.BASELINE, CHEAP).size(), "candidateBaselineJudgments").
					isGreaterThanOrEqualTo(judge.heldOut());
			}
		}
	}

	/**
	 * Verifies that a candidate shadows no development trial while the trials that the test is expected to run before
	 * the next certification attempt cannot bring it to qualification, and that it still shadows held-out trials.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void developmentShadowingWaitsForPayOff() throws IOException
	{
		Contract contract = ContractFixtures.withNearMisses(ContractFixtures.contract());
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("pay-off")),
				developing(contract).judge(TEST, shadowingAt(contract, 0, 0, List.of(), 0, 0)).
					judge("plain_list", frozenOnly()).judge("safety", frozenOnly()).build());
			harness.candidateJudges(List.of(CHEAP));
			Fixture fixture = new Fixture(harness);
			fixture.judges(_ -> Verdict.PASS, (_, _) -> Verdict.PASS);
			try (Driver driver = harness.open())
			{
				Campaign campaign = harness.runUntil(driver, state -> state.development().count(TEST).passes() >= 60);
				requireThat(fixture.uses(TEST, Purpose.DEVELOPMENT, CHEAP), "candidateDevelopmentJudgments").isEmpty();
				DevelopmentJudge judge = campaign.judges().get(TEST);
				requireThat(judge.shadowed(), "shadowed").isEqualTo(judge.heldOut());
				requireThat(judge.developmentTrials(), "developmentTrials").isGreaterThanOrEqualTo(10);
			}
		}
	}

	/**
	 * Verifies that the next candidate judges the near misses while the current one keeps shadowing, once the current
	 * one's disagreements rule out qualifying at 299 shadowed trials, and that both then judge the same development
	 * trials next to the frozen judge.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void nextCandidateOverlapsCurrent() throws IOException
	{
		Contract contract = ContractFixtures.withNearMisses(ContractFixtures.contract());
		DevelopmentJudge overlapping = new DevelopmentJudge(labels(contract.tests().getFirst()), List.of(),
			List.of(new ExaminedJudge(CHEAP, Stage.SHADOWING, 9, 0, 0, 0, 9), ExaminedJudge.started(MIDDLE)), List.of(),
			Optional.empty(), 1000, 1);
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("overlap")),
				developing(contract).judge(TEST, overlapping).judge("plain_list", frozenOnly()).
					judge("safety", frozenOnly()).build());
			harness.candidateJudges(List.of(CHEAP, MIDDLE));
			Fixture fixture = new Fixture(harness);
			fixture.judges(_ -> Verdict.PASS, (_, satisfying) ->
			{
				if (satisfying)
					return Verdict.PASS;
				return Verdict.FAIL;
			});
			try (Driver driver = harness.open())
			{
				harness.runUntil(driver, () -> !fixture.uses(TEST, Purpose.DEVELOPMENT, MIDDLE).isEmpty());
				harness.settle(driver);
				DevelopmentJudge judge = harness.campaign().judges().get(TEST);
				requireThat(judge.shadowing().size(), "shadowing").isEqualTo(2);
				requireThat(judge.current().orElseThrow(), "current").isEqualTo(CHEAP);
				requireThat(judge.shadowing().getFirst().shadowed(), "cheapShadowed").isGreaterThan(9);
				requireThat(judge.shadowing().getLast().shadowed(), "middleShadowed").isGreaterThan(0);
				PromptTest test = contract.tests().getFirst();
				requireThat(fixture.uses(MIDDLE, true).size(), "middleChecks").
					isEqualTo(test.nearMisses().size() * 2 * NearMisses.REPLIES_PER_SIDE);
				requireThat(fixture.uses(CHEAP, true), "cheapChecks").isEmpty();
			}
		}
	}

	/**
	 * Verifies that a campaign whose test's near misses are labeled starts the test with the judge that qualified when
	 * an earlier campaign on the same project and target ended, without checking a candidate's near misses again, and
	 * that a changed judge version discards it.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void carriedQualificationSkipsExamination() throws IOException
	{
		Contract contract = ContractFixtures.withNearMisses(ContractFixtures.contract());
		PromptTest test = contract.tests().getFirst();
		for (boolean sameJudge : List.of(true, false))
		{
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path directory = cleanup.add(Files.createTempDirectory("carried"));
				DriverHarness harness = new DriverHarness(directory.resolve("driver"), developing(contract).build());
				Sha256 version = Sha256.of(new ProposalSchema(JSON).toJson(contract, test).getBytes(UTF_8));
				int judgeVersion = harness.sessions().judgeVersion();
				if (!sameJudge)
					++judgeVersion;
				DevelopmentJudgeRecords records = new DevelopmentJudgeRecords(directory.resolve("data"),
					JSON, Yielding.fileReplacer());
				records.campaignEnded(developing(contract).judge(TEST, new DevelopmentJudge(labels(test), List.of(),
					List.of(new ExaminedJudge(MIDDLE, Stage.QUALIFIED, 305, 0, 4, 0, 2)), List.of(),
					Optional.of(new JudgeBasis(version, judgeVersion)), 0, 0)).build());
				harness.judgeRecords(records);
				harness.candidateJudges(List.of(CHEAP, MIDDLE));
				Fixture fixture = new Fixture(harness);
				fixture.judges(_ -> Verdict.PASS, (_, satisfying) ->
				{
					if (satisfying)
						return Verdict.PASS;
					return Verdict.FAIL;
				});
				try (Driver driver = harness.open())
				{
					Campaign campaign = harness.runUntil(driver, state -> state.judges().containsKey(TEST));
					DevelopmentJudge judge = campaign.judges().get(TEST);
					if (sameJudge)
					{
						requireThat(judge.stage(), "stage").isEqualTo(Stage.QUALIFIED);
						requireThat(judge.current().orElseThrow(), "current").isEqualTo(MIDDLE);
						requireThat(judge.shadowed(), "shadowed").isEqualTo(305);
						requireThat(judge.spotChecked(), "spotChecked").isEqualTo(4);
						requireThat(judge.strict(), "strict").isEqualTo(2);
					}
					else
					{
						requireThat(judge.stage(), "stage_changed_judge").isEqualTo(Stage.NEAR_MISSES);
						requireThat(judge.current().orElseThrow(), "current_changed_judge").isEqualTo(CHEAP);
					}
				}
			}
		}
	}

	/**
	 * Verifies that a campaign store records a test's qualified judge in the data directory when the campaign ends,
	 * not while it is active, that a later end replaces the record, and that a test whose judge is no longer qualified
	 * at the end removes its record.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void campaignEndRecordsQualifiedJudge() throws IOException
	{
		Contract contract = ContractFixtures.withNearMisses(ContractFixtures.contract());
		PromptTest test = contract.tests().getFirst();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("campaign-end"));
			DevelopmentJudgeRecords records = new DevelopmentJudgeRecords(directory.resolve("data"), JSON,
				Yielding.fileReplacer());
			JudgeRecordingStore store = new JudgeRecordingStore(new FileCampaignStore(directory.resolve("campaigns"),
				JSON, Yielding.fileReplacer()), records);
			JudgeBasis basis = new JudgeBasis(Sha256.of("definition".getBytes(UTF_8)), 5);
			DevelopmentJudge qualified = new DevelopmentJudge(labels(test), List.of(),
				List.of(new ExaminedJudge(CHEAP, Stage.QUALIFIED, 299, 20, 3, 0, 1)), List.of(), Optional.of(basis), 0,
				0);
			Builder active = developing(contract).judge(TEST, qualified);
			Campaign campaign = active.build();
			store.replaceSnapshot(campaign);
			requireThat(records.find(campaign.header().root(), campaign.header().target(), TEST), "whileActive").
				isEmpty();

			store.replaceSnapshot(developing(contract).judge(TEST, qualified).lifecycle(Lifecycle.CANCELLED).build());
			CarriedJudge carried = records.find(campaign.header().root(), campaign.header().target(), TEST).
				orElseThrow();
			requireThat(carried, "carried").isEqualTo(new CarriedJudge(CHEAP, 299, 3, 0, 1, basis.testVersion(),
				labels(test), 5));
			requireThat(records.file(campaign.header().root(), campaign.header().target()).orElseThrow().
				startsWith(directory.resolve("data").resolve(DevelopmentJudgeRecords.DIRECTORY)), "inDataDirectory").
				isTrue();

			DevelopmentJudge reverted = new DevelopmentJudge(labels(test), List.of(), List.of(),
				List.of(new RejectedJudge(CHEAP, Rejection.SPOT_CHECK, 299, 4, 1, 1)), Optional.of(basis), 0, 0);
			store.replaceSnapshot(developing(contract).judge(TEST, reverted).build());
			store.replaceSnapshot(developing(contract).judge(TEST, reverted).lifecycle(Lifecycle.CLOSED).build());
			requireThat(records.find(campaign.header().root(), campaign.header().target(), TEST), "afterReversion").
				isEmpty();
		}
	}

	/**
	 * Returns a builder for a confirmed campaign that develops candidate 1 and has met its baseline.
	 *
	 * @param contract the contract
	 * @return the builder
	 */
	private static Builder developing(Contract contract)
	{
		return new Builder().confirmed(contract, 1).working(1).readyBaseline().development("formatting", 50, 0).
			development("plain_list", 50, 0).development("safety", 50, 0);
	}

	/**
	 * Returns the frozen judge's profile.
	 *
	 * @param contract the contract
	 * @return the profile
	 */
	private static TrialProfile frozen(Contract contract)
	{
		return contract.judge().profile();
	}

	/**
	 * Returns the choice of a test that keeps the frozen judge.
	 *
	 * @return the choice
	 */
	private static DevelopmentJudge frozenOnly()
	{
		return DevelopmentJudge.start(Map.of(), List.of());
	}

	/**
	 * Returns the choice of a test whose cheaper candidate qualified.
	 *
	 * @param contract the contract
	 * @return the choice
	 */
	private static DevelopmentJudge qualified(Contract contract)
	{
		return new DevelopmentJudge(labels(contract.tests().getFirst()), List.of(),
			List.of(new ExaminedJudge(CHEAP, Stage.QUALIFIED, 299, 0, 0, 0, 0)), List.of(), Optional.empty(), 0, 0);
	}

	/**
	 * Returns the choice of a test whose cheapest candidate shadows the frozen judge.
	 *
	 * @param contract          the contract
	 * @param shadowed          the candidate's shadowed trials, none of which disagreed strictly beyond {@code strict}
	 * @param strict            the candidate's trials with a strict disagreement
	 * @param waiting           the candidates that have not started
	 * @param developmentTrials the test's development trials judged so far
	 * @param rounds            the development rounds completed so far
	 * @return the choice
	 */
	private static DevelopmentJudge shadowingAt(Contract contract, int shadowed, int strict,
		List<TrialProfile> waiting, int developmentTrials, int rounds)
	{
		return new DevelopmentJudge(labels(contract.tests().getFirst()), waiting,
			List.of(new ExaminedJudge(CHEAP, Stage.SHADOWING, shadowed, 0, 0, 0, strict)), List.of(), Optional.empty(),
			developmentTrials, rounds);
	}

	/**
	 * Returns the frozen judge's expected label of each near-miss reply of a test.
	 *
	 * @param test the test
	 * @return {@code true} for the replies that only just satisfy their assertion
	 */
	private static Map<String, Boolean> labels(PromptTest test)
	{
		Map<String, Boolean> labels = new LinkedHashMap<>();
		for (NearMisses nearMiss : test.nearMisses())
		{
			for (NearMisses.Reply reply : nearMiss.replies(Map.of()))
				labels.put(reply.key(), reply.satisfying());
		}
		return labels;
	}

	/**
	 * Sets a cell of a running attempt to the fewest passes that certify its minimum-pass-rate part, with each of its
	 * control and incumbent comparisons one tied pair short of passing.
	 *
	 * @param attempt the attempt
	 * @param boundaries the attempt's boundaries
	 * @param contract the attempt's contract
	 * @param cell the cell identifier
	 */
	private static void oneReferencePairShort(Builder.AttemptBuilder attempt, DecisionBoundaries boundaries,
		Contract contract, String cell)
	{
		int passes = 1;
		while (!boundaries.certifies(passes, 0))
			++passes;
		int pairs = PairedComparison.fewestEvenPairsToPass(PairTally.EMPTY, boundaries.level(), boundaries.cap()) - 1;
		Map<String, PairCounts> comparisons = new LinkedHashMap<>();
		for (Assertion assertion : contract.testOf(contract.findCell(cell).orElseThrow()).allAssertions())
			comparisons.put(assertion.id(), new PairCounts(pairs, 0, 0, 0));
		Pairing pairing = new Pairing(List.of(), comparisons);
		attempt.cell(cell, Math.max(passes, pairing.pairs()), 0).controlPairing(cell, pairing).
			incumbentPairing(cell, pairing);
	}

	/**
	 * Scripts the trial and judge sessions of a driver and records which profile judged what.
	 */
	private static final class Fixture
	{
		/**
		 * One judge session.
		 *
		 * @param test the test of the judged trial, or {@code null} for a near-miss reply
		 * @param purpose the purpose of the judged trial, or {@code null} for a near-miss reply
		 * @param profile the profile of the judge session
		 */
		private record Use(String test, Purpose purpose, TrialProfile profile)
		{
		}

		private final DriverHarness harness;
		private final Map<Path, Use> trials = new ConcurrentHashMap<>();
		private final List<Use> uses = new CopyOnWriteArrayList<>();
		private final List<Path> nearMissEvidence = new CopyOnWriteArrayList<>();

		/**
		 * Scripts the trial sessions to pass.
		 *
		 * @param harness the ports of the driver
		 */
		private Fixture(DriverHarness harness)
		{
			this.harness = harness;
			harness.trials(request ->
			{
				SessionResult.Completed result = harness.passed();
				LogicalTrial trial = runningTrial(harness, request);
				trials.put(result.effects(), new Use(trial.testCase().testId(), trial.key().purpose(), null));
				return result;
			});
		}

		/**
		 * Scripts the judge sessions.
		 *
		 * @param onTrial returns the verdict of a trial under a profile, or {@code null} for a response that cannot
		 *   be used
		 * @param onNearMiss returns the verdict of a near-miss reply under a profile, given whether the reply only
		 *   just satisfies its assertion
		 */
		private void judges(Function<Use, Verdict> onTrial, BiFunction<TrialProfile, Boolean, Verdict> onNearMiss)
		{
			harness.judges(request ->
			{
				Use trial = trials.get(request.readOnly().getFirst());
				if (trial == null)
				{
					String turn = request.turns().getFirst();
					boolean satisfying = turn.contains(ContractFixtures.SATISFYING_REPLY);
					if (!satisfying && !turn.contains(ContractFixtures.FAILING_REPLY))
						throw new AssertionError("Neither a trial nor a near miss: " + request.readOnly());
					uses.add(new Use(null, null, request.profile()));
					nearMissEvidence.addAll(request.readOnly());
					Verdict verdict = onNearMiss.apply(request.profile(), satisfying);
					return harness.judged(request, verdict, "The near miss was labeled.");
				}
				Use use = new Use(trial.test(), trial.purpose(), request.profile());
				uses.add(use);
				Verdict verdict = onTrial.apply(use);
				if (verdict == null)
					return harness.judged("{}");
				return harness.judged(request, verdict, "The trial was judged.");
			});
		}

		/**
		 * Returns the near-miss judge sessions of a profile, or its judgments of development trials.
		 *
		 * @param profile the profile
		 * @param nearMiss {@code true} for the near-miss sessions, {@code false} for the judgments of development
		 *   trials
		 * @return the sessions
		 */
		private List<Use> uses(TrialProfile profile, boolean nearMiss)
		{
			List<Use> matches = new ArrayList<>();
			for (Use use : uses)
			{
				if (use.profile().equals(profile) && (use.test() == null) == nearMiss)
					matches.add(use);
			}
			return matches;
		}

		/**
		 * Returns the judge sessions of trials of a test, of one purpose, under a profile.
		 *
		 * @param test the test
		 * @param purpose the purpose
		 * @param profile the profile, or {@code null} for every profile
		 * @return the sessions, in the order they ran
		 */
		private List<Use> uses(String test, Purpose purpose, TrialProfile profile)
		{
			List<Use> matches = new ArrayList<>();
			for (Use use : uses)
			{
				if (test.equals(use.test()) && use.purpose() == purpose &&
					(profile == null || use.profile().equals(profile)))
				{
					matches.add(use);
				}
			}
			return matches;
		}

		/**
		 * Returns the judge sessions of trials of one purpose.
		 *
		 * @param purpose the purpose
		 * @return the sessions
		 */
		private List<Use> purposeUses(Purpose purpose)
		{
			return uses.stream().filter(use -> use.purpose() == purpose).toList();
		}

		/**
		 * Returns the judge sessions of near-miss replies.
		 *
		 * @return the sessions
		 */
		private List<Use> nearMissUses()
		{
			return uses.stream().filter(use -> use.test() == null).toList();
		}
	}

	/**
	 * Returns the logical trial that a trial session runs.
	 *
	 * @param harness the ports of the driver
	 * @param request the session's request
	 * @return the trial
	 */
	private static LogicalTrial runningTrial(DriverHarness harness, SessionRequest request)
	{
		Campaign campaign;
		try
		{
			campaign = harness.campaign();
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
		Set<String> cells = new HashSet<>();
		for (Cell cell : campaign.contract().profiles().cells())
		{
			if (cell.profile().equals(request.profile()))
				cells.add(cell.id());
		}
		return campaign.trials().stream().filter(trial -> trial.state() == TrialState.RUNNING &&
			cells.contains(trial.key().cell()) && trial.testCase().turns().equals(request.turns()) &&
			harness.stages(request, trial.key().snapshot())).findFirst().orElseThrow();
	}
}
