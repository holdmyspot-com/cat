package io.github.cowwoc.cat.common.optimizer.store.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.optimizer.campaign.Allocation;
import io.github.cowwoc.cat.common.optimizer.campaign.AllocationRule;
import io.github.cowwoc.cat.common.optimizer.campaign.PromptAuthorAttempt;
import io.github.cowwoc.cat.common.optimizer.campaign.PromptAuthorDiagnosis;
import io.github.cowwoc.cat.common.optimizer.campaign.PromptAuthorKind;
import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.CampaignEvent;
import io.github.cowwoc.cat.common.optimizer.campaign.Candidate;
import io.github.cowwoc.cat.common.optimizer.campaign.CellTier;
import io.github.cowwoc.cat.common.optimizer.campaign.CertificationAttempt;
import io.github.cowwoc.cat.common.optimizer.campaign.Pairing;
import io.github.cowwoc.cat.common.optimizer.campaign.Pairing.UnpairedTrial;
import io.github.cowwoc.cat.common.optimizer.campaign.DeliveryRequest;
import io.github.cowwoc.cat.common.optimizer.campaign.Diagnosis;
import io.github.cowwoc.cat.common.optimizer.campaign.DiagnosticRerun;
import io.github.cowwoc.cat.common.optimizer.campaign.FailedAssertion;
import io.github.cowwoc.cat.common.optimizer.campaign.Failure;
import io.github.cowwoc.cat.common.optimizer.campaign.FailureGroup;
import io.github.cowwoc.cat.common.optimizer.campaign.FailureKind;
import io.github.cowwoc.cat.common.optimizer.campaign.Harness;
import io.github.cowwoc.cat.common.optimizer.campaign.Header;
import io.github.cowwoc.cat.common.optimizer.campaign.HunkEvidence;
import io.github.cowwoc.cat.common.optimizer.campaign.Impact;
import io.github.cowwoc.cat.common.optimizer.campaign.InfrastructureAttempt;
import io.github.cowwoc.cat.common.optimizer.campaign.Lifecycle;
import io.github.cowwoc.cat.common.optimizer.campaign.Lineage;
import io.github.cowwoc.cat.common.optimizer.campaign.LogicalTrial;
import io.github.cowwoc.cat.common.optimizer.campaign.MeasurementCondition;
import io.github.cowwoc.cat.common.optimizer.campaign.MeasurementIdentity;
import io.github.cowwoc.cat.common.optimizer.campaign.Minimality;
import io.github.cowwoc.cat.common.optimizer.campaign.Outcome;
import io.github.cowwoc.cat.common.optimizer.campaign.PairCounts;
import io.github.cowwoc.cat.common.optimizer.campaign.PendingDecision;
import io.github.cowwoc.cat.common.optimizer.campaign.PromotionRun;
import io.github.cowwoc.cat.common.optimizer.campaign.Purpose;
import io.github.cowwoc.cat.common.optimizer.campaign.ReproductionRound;
import io.github.cowwoc.cat.common.optimizer.campaign.Round;
import io.github.cowwoc.cat.common.optimizer.campaign.RunFailure;
import io.github.cowwoc.cat.common.optimizer.campaign.SlowStart;
import io.github.cowwoc.cat.common.optimizer.campaign.SoundTest;
import io.github.cowwoc.cat.common.optimizer.campaign.TestRevision;
import io.github.cowwoc.cat.common.optimizer.campaign.TestRevisionRequest;
import io.github.cowwoc.cat.common.optimizer.campaign.TestRevisions;
import io.github.cowwoc.cat.common.optimizer.campaign.Throttle;
import io.github.cowwoc.cat.common.optimizer.campaign.Transitions;
import io.github.cowwoc.cat.common.optimizer.campaign.Triage;
import io.github.cowwoc.cat.common.optimizer.campaign.TrialFiles;
import io.github.cowwoc.cat.common.optimizer.campaign.TrialKey;
import io.github.cowwoc.cat.common.optimizer.campaign.TrialState;
import io.github.cowwoc.cat.common.optimizer.campaign.TriedTestCase;
import io.github.cowwoc.cat.common.optimizer.campaign.Verdict;
import io.github.cowwoc.cat.common.optimizer.campaign.test.Boundaries;
import io.github.cowwoc.cat.common.optimizer.campaign.test.Builder;
import io.github.cowwoc.cat.common.optimizer.contract.AddedTestCase;
import io.github.cowwoc.cat.common.optimizer.contract.AddedTestCases;
import io.github.cowwoc.cat.common.optimizer.contract.CapabilityRanking;
import io.github.cowwoc.cat.common.optimizer.contract.CertificationScope;
import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.contract.DrawStream;
import io.github.cowwoc.cat.common.optimizer.contract.DrawnTestCase;
import io.github.cowwoc.cat.common.optimizer.contract.TrialProfile;
import io.github.cowwoc.cat.common.optimizer.contract.TestCase;
import io.github.cowwoc.cat.common.optimizer.contract.test.ContractFixtures;
import io.github.cowwoc.cat.common.optimizer.contract.test.TierFixtures;
import io.github.cowwoc.cat.common.optimizer.inventory.AuthorityWarning;
import io.github.cowwoc.cat.common.optimizer.inventory.DeliveryChange;
import io.github.cowwoc.cat.common.optimizer.inventory.FileContent;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry.RegularFile;
import io.github.cowwoc.cat.common.optimizer.inventory.Limit;
import io.github.cowwoc.cat.common.optimizer.port.HostLogin;
import io.github.cowwoc.cat.common.optimizer.port.RateLimitWindow;
import io.github.cowwoc.cat.common.optimizer.port.TokenUsage;
import io.github.cowwoc.cat.common.optimizer.store.AbstractCampaignStore;
import io.github.cowwoc.cat.common.optimizer.store.CampaignStore;
import io.github.cowwoc.cat.common.optimizer.store.DeliveryJournal;
import io.github.cowwoc.cat.common.optimizer.store.TrialRecord;
import io.github.cowwoc.cat.common.optimizer.store.TrialSession;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.testng.annotations.Test;

/**
 * The behaviour that every {@link CampaignStore} implementation must provide. Each implementation's test extends this
 * class.
 */
public abstract class CampaignStoreContract
{
	/**
	 * Creates the contract tests.
	 */
	protected CampaignStoreContract()
	{
	}

	/**
	 * Opens a store over fresh, empty storage.
	 *
	 * @return the store and its storage
	 * @throws IOException if the storage cannot be created
	 */
	protected abstract StoreFixture open() throws IOException;

	/**
	 * A store over storage that the test owns.
	 */
	protected interface StoreFixture extends AutoCloseable
	{
		/**
		 * Returns the store.
		 *
		 * @return the store
		 */
		CampaignStore store();

		/**
		 * Returns a new store over the same storage, as a restarted process would open it.
		 *
		 * @return the new store
		 */
		CampaignStore reopen();

		/**
		 * Releases the storage.
		 *
		 * @throws IOException if the storage cannot be deleted
		 */
		@Override
		void close() throws IOException;
	}

	/**
	 * A campaign with every persisted state distinction populated, including each trial's allocation decision and
	 * infrastructure attempts, the judge's rationale and the failed requirements for each judged trial and each failure,
	 * the contract's split salt and outcome-changing variables, the diagnostic reruns of each development failure, a
	 * regression test case whose failing working-candidate outcome is settled, an ended attempt's failure groups with
	 * their rounds and counts, a reproduction trial, the campaign's evidence revision, a pending authority warning and a
	 * pause over unreproduced failures, survives a reload unchanged.
	 *
	 * @throws IOException if the store fails
	 */
	@Test
	public void populatedCampaignSurvivesReload() throws IOException
	{
		// Requirement: R-L5, R-E1, R-D2, R-W4, R-D9, R-C16, R-C19
		try (StoreFixture fixture = open())
		{
			Campaign campaign = populated("campaign-full");
			fixture.store().replaceSnapshot(campaign);

			Optional<Campaign> loaded = fixture.reopen().load("campaign-full");
			requireThat(loaded, "loaded").isEqualTo(Optional.of(campaign));
			Round round = loaded.orElseThrow().development().round();
			requireThat(List.copyOf(round.attempts().keySet()), "roundOrder").
				isEqualTo(List.of("safety", "formatting"));
		}
	}

	/**
	 * A campaign whose test has two cells keeps each cell's counts, review point, trials and observed profile
	 * apart across a reload.
	 *
	 * @throws IOException if the store fails
	 */
	@Test
	public void cellsSurviveReload() throws IOException
	{
		// Requirement: R-C11
		// Requirement: R-E17
		try (StoreFixture fixture = open())
		{
			Map<String, Map<MeasurementCondition, String>> cells = Map.of(
				ContractFixtures.BIG_CELL, Map.of(MeasurementCondition.MODEL, "big-observed",
					MeasurementCondition.REASONING, "high"),
				ContractFixtures.SMALL_CELL, Map.of(MeasurementCondition.MODEL, "small-observed",
					MeasurementCondition.REASONING, "high"));
			MeasurementIdentity identity = new MeasurementIdentity(Map.of(MeasurementCondition.JUDGE, "judge-model"),
				cells);
			TrialKey key = new TrialKey(Purpose.DEVELOPMENT, 1, Builder.snapshot("candidate-1"),
				ContractFixtures.SMALL_CELL, 3);
			DrawnTestCase testCase = new DrawnTestCase(ContractFixtures.SMALL_CELL, "formatting",
				houseStyle(), List.of("A turn"), "Answers in the house style", "generator-1",
				DrawStream.Kind.DEVELOPMENT, 3, false);
			Campaign campaign = new Builder().header(header("campaign-cells")).identity(identity).
				confirmed(ContractFixtures.twoCellContract(), 1).working(1).
				development(ContractFixtures.BIG_CELL, 100, 0).
				development(ContractFixtures.SMALL_CELL, 30, 2).
				reviewPoint(ContractFixtures.SMALL_CELL, 250).
				baseline(ContractFixtures.BIG_CELL, 50, 0).
				trial(LogicalTrial.assigned(key, testCase, identity, allocation(AllocationRule.COVERAGE_FLOOR, 1))).
				build();
			fixture.store().replaceSnapshot(campaign);

			Campaign loaded = fixture.reopen().load("campaign-cells").orElseThrow();
			requireThat(loaded, "loaded").isEqualTo(campaign);
			requireThat(loaded.identity().cells().get(ContractFixtures.SMALL_CELL).get(MeasurementCondition.MODEL),
				"smallModel").isEqualTo("small-observed");
			requireThat(loaded.development().count(ContractFixtures.SMALL_CELL).failures(), "smallFailures").
				isEqualTo(2);
		}
	}

	/**
	 * The confirmed contract's certification scope, capability ranking and described cells survive a restart, so the
	 * scope's cells and the development cells are unchanged.
	 *
	 * @throws IOException if the store fails
	 */
	@Test
	public void certificationScopeSurvivesReload() throws IOException
	{
		// Requirement: R-C15
		// Requirement: R-E22
		try (StoreFixture fixture = open())
		{
			CapabilityRanking corrected = new CapabilityRanking(List.of("luna", "sol", "astra"),
				"the user's revision feedback");
			Contract contract = TierFixtures.contract(TierFixtures.CODEX_PROFILES, corrected,
				List.of(new TrialProfile("gpt-6-sol", ""), new TrialProfile("gpt-6-astra", "low")),
				new TrialProfile("gpt-6.1-sol", "high")).withScope(CertificationScope.DESCRIBED);
			String weakest = "formatting@gpt-6-astra/low";
			Campaign campaign = new Builder().header(header("campaign-scope")).confirmed(contract, 1).
				working(1).development(weakest, 4, 1).build();
			fixture.store().replaceSnapshot(campaign);

			Campaign loaded = fixture.reopen().load("campaign-scope").orElseThrow();
			requireThat(loaded, "loaded").isEqualTo(campaign);
			requireThat(loaded.contract().profiles().scope(), "scope").isEqualTo(CertificationScope.DESCRIBED);
			requireThat(loaded.contract().profiles().ranking(), "ranking").isEqualTo(corrected);
			requireThat(CellTier.scopeCellIds(loaded.contract()), "scopeCells").
				isEqualTo(CellTier.scopeCellIds(contract));
			requireThat(CellTier.developmentCellIds(loaded), "developmentCells").
				isEqualTo(List.of(weakest, TierFixtures.SAFETY));
		}
	}

	/**
	 * A development cell under a tool-output token limit survives a reload: the failure group that reproduced there,
	 * its development counts and its observed identity are unchanged, and it is still a development cell.
	 *
	 * @throws IOException if the store fails
	 */
	@Test
	public void toolOutputCellSurvivesReload() throws IOException
	{
		// Requirement: R-C19
		try (StoreFixture fixture = open())
		{
			Contract contract = TierFixtures.tieredContract();
			String limited = TierFixtures.BIG_LOW + "/tool-output-5000";
			DrawnTestCase failed = new DrawnTestCase(TierFixtures.BIG_LOW, "formatting", houseStyle(),
				List.of("A turn"), "Answers in the house style", "generator-1", DrawStream.Kind.FORMAL, 3, false);
			AddedTestCase usb = new AddedTestCase(1, "test-3", ContractFixtures.testCase("a reply about the backup stick",
				Map.of("tone", "the backup stick")));
			FailedAssertion wrongTone = new FailedAssertion("uses_requested_tone", "wrong_tone");
			FailureGroup group = new FailureGroup(TierFixtures.BIG_LOW, "test-3", wrongTone, 2, FailureGroup.ROUNDS,
				List.of(new ReproductionRound(TierFixtures.BIG_LOW, List.of(new TriedTestCase(usb, 100, 0))),
					new ReproductionRound(limited, List.of(new TriedTestCase(usb, 9, 2)))),
				FailureGroup.Status.REPRODUCED);
			MeasurementIdentity identity = new MeasurementIdentity(Map.of(MeasurementCondition.JUDGE, "judge-model"),
				Map.of(limited, Map.of(MeasurementCondition.MODEL, "big", MeasurementCondition.REASONING, "low",
					MeasurementCondition.TOOL_OUTPUT_LIMIT, "5000")));
			Failure failure = new Failure(failed, "", List.of(wrongTone), null, List.of(), List.of(), Builder.FILES);
			Campaign campaign = new Builder().header(header("campaign-tool-output")).identity(identity).
				confirmed(contract, 1).working(1).development(limited, 40, 1).
				certificationAttempt(1).failure(failure).failure(failure).outcome(Outcome.BELOW_MINIMUM).
				groups(List.of(group)).campaign().build();
			fixture.store().replaceSnapshot(campaign);

			Campaign loaded = fixture.reopen().load("campaign-tool-output").orElseThrow();
			requireThat(loaded, "loaded").isEqualTo(campaign);
			requireThat(loaded.identity().cells().get(limited).get(MeasurementCondition.TOOL_OUTPUT_LIMIT),
				"toolOutputLimit").isEqualTo("5000");
			requireThat(CellTier.developmentCellIds(loaded), "developmentCells").contains(limited);
		}
	}

	/**
	 * A retained prompt author manifest keeps its attempt number, lineage, kinds with their classifications, hunks
	 * and faults, hunk evidence and rejections across a reload.
	 *
	 * @throws IOException if the store fails
	 */
	@Test
	public void promptAuthorManifestSurvivesReload() throws IOException
	{
		// Requirement: R-D10
		try (StoreFixture fixture = open())
		{
			PromptAuthorDiagnosis diagnosis = new PromptAuthorDiagnosis(3, 2, "safety", Builder.snapshot("candidate-1"),
				List.of(new PromptAuthorKind("mistyped path", List.of("f1", "f2"), FailureKind.GUIDANCE, List.of("h1"),
						""),
					new PromptAuthorKind("judge outage", List.of("f3"), FailureKind.NOT_GUIDANCE, List.of(), ""),
					new PromptAuthorKind("named the test", List.of("f4"), FailureKind.TEST_DEFECT, List.of(),
						"The turn tells the agent that it is tested.")),
				List.of(new HunkEvidence("h1", Path.of("CLAUDE.md"), 1, 2, 1, 3, List.of("f1", "f2"), "Another read",
					"plain_list")),
				List.of("Uncited changed hunk: CLAUDE.md#2"), Path.of("/transcripts/prompt-author-3.jsonl").toAbsolutePath(),
				Path.of("/effects/prompt-author-3").toAbsolutePath());
			Campaign campaign = Transitions.apply(new Builder().header(header("campaign-prompt-author")).confirmed().
				working(1).build(), new CampaignEvent.RetainPromptAuthorDiagnosis(diagnosis));
			fixture.store().replaceSnapshot(campaign);

			Campaign loaded = fixture.reopen().load("campaign-prompt-author").orElseThrow();
			requireThat(loaded.optimization().promptAuthors(), "prompt_authors").isEqualTo(List.of(diagnosis));
		}
	}

	/**
	 * The commit fields of every certification attempt survive a reload unchanged.
	 *
	 * @throws IOException if the store fails
	 */
	@Test
	public void commitFieldsSurviveReload() throws IOException
	{
		// Requirement: R-S9
		try (StoreFixture fixture = open())
		{
			Campaign campaign = populated("campaign-full");
			fixture.store().replaceSnapshot(campaign);

			Campaign loaded = fixture.reopen().load("campaign-full").orElseThrow();
			requireThat(loaded.attempts(), "attempts").isEqualTo(campaign.attempts());
			for (int i = 0; i < campaign.attempts().size(); ++i)
			{
				CertificationAttempt expected = campaign.attempts().get(i);
				CertificationAttempt actual = loaded.attempts().get(i);
				requireThat(actual.index(), "index").isEqualTo(expected.index());
				requireThat(actual.procedure().improvementLevel(), "improvementLevel").
					isEqualTo(expected.procedure().improvementLevel());
				requireThat(actual.procedure().boundaries(), "boundaries").isEqualTo(expected.procedure().boundaries());
				requireThat(actual.procedure().boundaries().comparisons(), "comparisons").
					isEqualTo(expected.procedure().boundaries().comparisons());
				requireThat(actual.procedure().boundaries().cap(), "cap").
					isEqualTo(expected.procedure().boundaries().cap());
				requireThat(actual.formalStream(), "formalStream").isEqualTo(expected.formalStream());
			}
		}
	}

	/**
	 * The next certification index survives a reload, and only a new campaign starts again at 1.
	 *
	 * @throws IOException if the store fails
	 */
	@Test
	public void certificationIndexIsPermanent() throws IOException
	{
		// Requirement: R-S9
		try (StoreFixture fixture = open())
		{
			Campaign campaign = populated("campaign-full");
			Campaign fresh = new Builder().header(header("campaign-new")).confirmed().build();
			fixture.store().replaceSnapshot(campaign);
			fixture.store().replaceSnapshot(fresh);

			CampaignStore reopened = fixture.reopen();
			requireThat(reopened.load("campaign-full").orElseThrow().nextCertificationIndex(), "existing").
				isEqualTo(3);
			requireThat(reopened.load("campaign-new").orElseThrow().nextCertificationIndex(), "new").isEqualTo(1);
		}
	}

	/**
	 * A campaign that has not confirmed a contract survives a reload unchanged.
	 *
	 * @throws IOException if the store fails
	 */
	@Test
	public void unconfirmedCampaignSurvivesReload() throws IOException
	{
		// Requirement: R-L5
		try (StoreFixture fixture = open())
		{
			Campaign campaign = Campaign.start(header("campaign-draft"), Campaign.DEFAULT_CEILING,
				Builder.INCUMBENT, Builder.OBSERVED);
			fixture.store().replaceSnapshot(campaign);

			requireThat(fixture.reopen().load("campaign-draft"), "loaded").isEqualTo(Optional.of(campaign));
		}
	}

	/**
	 * The user's goal survives a reload.
	 *
	 * @throws IOException if the store fails
	 */
	@Test
	public void goalSurvivesReload() throws IOException
	{
		// Requirement: R-C24
		try (StoreFixture fixture = open())
		{
			Header header = new Header("campaign-goal", Path.of("/project").toAbsolutePath(),
				Path.of("/project/AGENTS.md").toAbsolutePath(), Harness.CODEX, Lifecycle.ACTIVE,
				"Read files whole.\nKeep searches narrow.");
			Campaign campaign = Campaign.start(header, Campaign.DEFAULT_CEILING, Builder.INCUMBENT, Builder.OBSERVED);
			fixture.store().replaceSnapshot(campaign);

			requireThat(fixture.reopen().load("campaign-goal").orElseThrow().header().goal(), "goal").
				isEqualTo("Read files whole.\nKeep searches narrow.");
		}
	}

	/**
	 * The warnings that the user answered by using an authorized source, including one whose link forms a cycle,
	 * survive a reload in answer order.
	 *
	 * @throws IOException if the store fails
	 */
	@Test
	public void answeredWarningsSurviveReload() throws IOException
	{
		// Requirement: R-W4
		try (StoreFixture fixture = open())
		{
			List<AuthorityWarning.Choice> choices = List.of(AuthorityWarning.Choice.USE_AUTHORIZED_SOURCE,
				AuthorityWarning.Choice.STOP_CAMPAIGN, AuthorityWarning.Choice.REVISE_CAMPAIGN);
			Campaign campaign = new Builder().header(header("campaign-answered")).confirmed().working(1).
				answered(new AuthorityWarning("R1", Path.of("docs/reply-rules.md"),
				Path.of("/external/reply-rules.md").toAbsolutePath(),
					Limit.EXTERNAL_TARGET_READ_ONLY, choices)).
				answered(new AuthorityWarning("R2", Path.of("rules/loop.md"), Path.of("rules/loop.md"), Limit.LINK_CYCLE,
					choices)).
				build();
			fixture.store().replaceSnapshot(campaign);

			Campaign loaded = fixture.reopen().load("campaign-answered").orElseThrow();

			requireThat(loaded.answeredWarnings(), "answeredWarnings").isEqualTo(campaign.answeredWarnings());
		}
	}

	/**
	 * Every lifecycle state survives a reload.
	 *
	 * @throws IOException if the store fails
	 */
	@Test
	public void lifecycleSurvivesReload() throws IOException
	{
		// Requirement: R-L5
		try (StoreFixture fixture = open())
		{
			for (Lifecycle lifecycle : Lifecycle.values())
			{
				String id = "campaign-" + lifecycle.name().toLowerCase(Locale.ROOT);
				Campaign campaign = new Builder().header(header(id)).lifecycle(lifecycle).build();
				fixture.store().replaceSnapshot(campaign);
				requireThat(fixture.reopen().load(id), "loaded").withContext(lifecycle, "lifecycle").
					isEqualTo(Optional.of(campaign));
			}
		}
	}

	/**
	 * Loading a campaign that has no snapshot returns empty.
	 *
	 * @throws IOException if the store fails
	 */
	@Test
	public void loadMissingCampaign() throws IOException
	{
		try (StoreFixture fixture = open())
		{
			requireThat(fixture.store().load("campaign-missing"), "loaded").isEmpty();
		}
	}

	/**
	 * Replacing a snapshot makes the latest state the one that loads.
	 *
	 * @throws IOException if the store fails
	 */
	@Test
	public void replaceSnapshotKeepsLatest() throws IOException
	{
		// Requirement: R-E1
		try (StoreFixture fixture = open())
		{
			Campaign first = new Builder().header(header("campaign-1")).confirmed().build();
			Campaign second = new Builder().header(header("campaign-1")).confirmed().working(2).
				development("safety", 3, 1).build();
			fixture.store().replaceSnapshot(first);
			fixture.store().replaceSnapshot(second);

			requireThat(fixture.reopen().load("campaign-1"), "loaded").isEqualTo(Optional.of(second));
		}
	}

	/**
	 * Recorded outcomes of a snapshot's unadopted trials are listed in the snapshot's order; trials without a record or
	 * an outcome, and judged trials, are not.
	 *
	 * @throws IOException if the store fails
	 */
	@Test
	public void listsUnadoptedOutcomesInSnapshotOrder() throws IOException
	{
		// Requirement: R-E1
		try (StoreFixture fixture = open())
		{
			CampaignStore store = fixture.store();
			LogicalTrial assigned = trial(1, TrialState.ASSIGNED);
			LogicalTrial running = trial(2, TrialState.RUNNING);
			LogicalTrial noOutcome = trial(3, TrialState.RUNNING);
			LogicalTrial noRecord = trial(4, TrialState.ASSIGNED);
			LogicalTrial judged = trial(5, TrialState.JUDGED);
			Campaign campaign = new Builder().header(header("campaign-1")).confirmed().working(1).
				trial(assigned).trial(running).trial(noOutcome).trial(noRecord).trial(judged).build();
			CampaignEvent.Complete runningOutcome = complete(running.key(), Verdict.FAIL);
			CampaignEvent.Complete assignedOutcome = complete(assigned.key(), Verdict.PASS);
			for (LogicalTrial trial : List.of(assigned, running, noOutcome, judged))
				store.writeAssignment("campaign-1", asAssigned(trial));
			store.writeOutcome("campaign-1", runningOutcome);
			store.writeOutcome("campaign-1", assignedOutcome);
			store.writeOutcome("campaign-1", complete(judged.key(), Verdict.PASS));
			store.replaceSnapshot(campaign);

			CampaignStore reopened = fixture.reopen();
			Campaign loaded = reopened.load("campaign-1").orElseThrow();
			requireThat(reopened.listUnadoptedOutcomes(loaded), "outcomes").
				isEqualTo(List.of(assignedOutcome, runningOutcome));
		}
	}

	/**
	 * A trial record holds the assignment with its allocation decision, every infrastructure attempt that a later
	 * assignment write adds, and the outcome with the judge's rationale, and survives a reload unchanged.
	 *
	 * @throws IOException if the store fails
	 */
	@Test
	public void trialRecordSurvivesReload() throws IOException
	{
		// Requirement: R-E1
		try (StoreFixture fixture = open())
		{
			CampaignStore store = fixture.store();
			LogicalTrial assigned = trial(7, TrialState.ASSIGNED);
			store.writeAssignment("campaign-1", assigned);
			List<InfrastructureAttempt> attempts = List.of(
				new InfrastructureAttempt(Instant.parse("2026-10-01T10:00:00Z"), RunFailure.INFRASTRUCTURE,
					"provider returned \"overloaded\"\nretry later"),
				new InfrastructureAttempt(Instant.parse("2026-10-01T10:01:00.25Z"), RunFailure.RATE_LIMITED,
					"rate limit reached"));
			LogicalTrial retried = new LogicalTrial(assigned.key(), assigned.testCase(), assigned.identity(),
				assigned.allocation(), TrialState.ASSIGNED, null, null, List.of(), null, 1, attempts, 0, List.of(), null);
			store.writeAssignment("campaign-1", retried);
			CampaignEvent.Complete outcome = complete(assigned.key(), Verdict.PASS);
			store.writeOutcome("campaign-1", outcome);

			AbstractCampaignStore reopened = (AbstractCampaignStore) fixture.reopen();
			requireThat(reopened.readRecord("campaign-1", assigned.key()), "record").isEqualTo(Optional.of(
				new TrialRecord(assigned.key(), assigned.testCase(), assigned.identity(), assigned.allocation(),
					attempts, outcome, List.of())));
		}
	}

	/**
	 * The record of each finished trial session, one with reported usage and rate-limit windows and one with neither,
	 * survives assignment retries, outcome writes and store reopening, in completion order.
	 *
	 * @throws IOException if the store fails
	 */
	// Requirement: R-E6
	// Requirement: R-E12
	@Test
	public void trialSessionsSurviveRetries() throws IOException
	{
		try (StoreFixture fixture = open())
		{
			CampaignStore store = fixture.store();
			LogicalTrial assigned = trial(1, TrialState.ASSIGNED);
			store.writeAssignment("campaign-1", assigned);
			Instant resetsAt = Instant.parse("2026-10-01T15:00:00Z");
			List<TrialSession> sessions = List.of(
				new TrialSession(Instant.parse("2026-10-01T10:00:00Z"), Instant.parse("2026-10-01T10:04:30.125Z"), 3,
					new Throttle(resetsAt, 1, 2),
					List.of(new RateLimitWindow(0.97, resetsAt), new RateLimitWindow(0.25, null)),
					HostLogin.SUBSCRIPTION, new TokenUsage(100, 20, 30, 10)),
				new TrialSession(Instant.parse("2026-10-01T10:05:00Z"), Instant.parse("2026-10-01T10:05:00Z"), 1,
					Throttle.NONE, List.of(), HostLogin.API_KEY, null));
			store.writeTrialSession("campaign-1", assigned.key(), sessions.getFirst());
			store.writeAssignment("campaign-1", assigned);
			store.writeTrialSession("campaign-1", assigned.key(), sessions.getLast());
			CampaignEvent.Complete outcome = complete(assigned.key(), Verdict.PASS);
			store.writeOutcome("campaign-1", outcome);
			store.writeOutcome("campaign-1", outcome);

			AbstractCampaignStore reopened = (AbstractCampaignStore) fixture.reopen();
			requireThat(reopened.readRecord("campaign-1", assigned.key()).orElseThrow().sessions(), "sessions").
				isEqualTo(sessions);
		}
	}

	/**
	 * A trial record refuses a missing session record instead of storing an empty entry.
	 *
	 * @throws IOException if the store fails
	 */
	// Requirement: R-E6
	@Test(expectedExceptions = NullPointerException.class)
	public void trialSessionRejectsAbsentRecord() throws IOException
	{
		try (StoreFixture fixture = open())
		{
			LogicalTrial assigned = trial(1, TrialState.ASSIGNED);
			fixture.store().writeAssignment("campaign-1", assigned);
			fixture.store().writeTrialSession("campaign-1", assigned.key(), null);
		}
	}

	/**
	 * An outcome that a record already holds can be written again without effect.
	 *
	 * @throws IOException if the store fails
	 */
	@Test
	public void rewritingSameOutcomeIsIgnored() throws IOException
	{
		// Requirement: R-E1
		try (StoreFixture fixture = open())
		{
			CampaignStore store = fixture.store();
			LogicalTrial trial = trial(1, TrialState.ASSIGNED);
			Campaign campaign = new Builder().header(header("campaign-1")).confirmed().working(1).trial(trial).
				build();
			CampaignEvent.Complete outcome = complete(trial.key(), Verdict.FAIL);
			store.writeAssignment("campaign-1", trial);
			store.writeOutcome("campaign-1", outcome);
			store.writeOutcome("campaign-1", outcome);
			store.replaceSnapshot(campaign);

			CampaignStore reopened = fixture.reopen();
			requireThat(reopened.listUnadoptedOutcomes(reopened.load("campaign-1").orElseThrow()), "outcomes").
				isEqualTo(List.of(outcome));
		}
	}

	/**
	 * A second, different outcome for the same trial is rejected, so a trial never yields two outcomes.
	 *
	 * @throws IOException if the store fails
	 */
	@Test(expectedExceptions = IllegalStateException.class,
		expectedExceptionsMessageRegExp = "Trial .* already has a different outcome\\.(?s).*")
	public void rejectsDifferentOutcome() throws IOException
	{
		// Requirement: R-E1
		try (StoreFixture fixture = open())
		{
			CampaignStore store = fixture.store();
			LogicalTrial trial = trial(1, TrialState.ASSIGNED);
			store.writeAssignment("campaign-1", trial);
			store.writeOutcome("campaign-1", complete(trial.key(), Verdict.FAIL));
			fixture.reopen().writeOutcome("campaign-1", complete(trial.key(), Verdict.PASS));
		}
	}

	/**
	 * An outcome cannot be recorded for a trial whose assignment was not recorded first.
	 *
	 * @throws IOException if the store fails
	 */
	@Test(expectedExceptions = IllegalStateException.class,
		expectedExceptionsMessageRegExp = "Trial .* has no assignment record")
	public void rejectsOutcomeWithoutAssignment() throws IOException
	{
		// Requirement: R-E1
		try (StoreFixture fixture = open())
		{
			fixture.store().writeOutcome("campaign-1", complete(trial(1, TrialState.ASSIGNED).key(), Verdict.PASS));
		}
	}

	/**
	 * Reassigning a trial that already has an outcome is rejected, so a recorded outcome is never lost.
	 *
	 * @throws IOException if the store fails
	 */
	@Test(expectedExceptions = IllegalStateException.class,
		expectedExceptionsMessageRegExp = "Trial .* already has an outcome: .*")
	public void rejectsReassignmentAfterOutcome() throws IOException
	{
		// Requirement: R-E1
		try (StoreFixture fixture = open())
		{
			CampaignStore store = fixture.store();
			LogicalTrial trial = trial(1, TrialState.ASSIGNED);
			store.writeAssignment("campaign-1", trial);
			store.writeOutcome("campaign-1", complete(trial.key(), Verdict.FAIL));
			fixture.reopen().writeAssignment("campaign-1", trial);
		}
	}

	/**
	 * Reassigning a trial that has no outcome replaces its record.
	 *
	 * @throws IOException if the store fails
	 */
	@Test
	public void reassignmentWithoutOutcomeIsAllowed() throws IOException
	{
		// Requirement: R-E1
		try (StoreFixture fixture = open())
		{
			CampaignStore store = fixture.store();
			LogicalTrial trial = trial(1, TrialState.ASSIGNED);
			store.writeAssignment("campaign-1", trial);
			store.writeAssignment("campaign-1", trial);
			CampaignEvent.Complete outcome = complete(trial.key(), Verdict.PASS);
			store.writeOutcome("campaign-1", outcome);
			Campaign campaign = new Builder().header(header("campaign-1")).confirmed().working(1).trial(trial).
				build();
			store.replaceSnapshot(campaign);

			requireThat(store.listUnadoptedOutcomes(campaign), "outcomes").isEqualTo(List.of(outcome));
		}
	}

	/**
	 * Only an assigned trial's assignment can be recorded.
	 *
	 * @throws IOException if the store fails
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = "(?s)trial\\.state\\(\\) must be equal to ASSIGNED.*")
	public void rejectsAssignmentOfStartedTrial() throws IOException
	{
		try (StoreFixture fixture = open())
		{
			fixture.store().writeAssignment("campaign-1", trial(1, TrialState.RUNNING));
		}
	}

	/**
	 * A campaign identifier that is not a single path segment is rejected.
	 *
	 * @throws IOException if the store fails
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = "(?s)\"campaignId\" may not contain \"/\".*")
	public void rejectsCampaignIdWithSeparator() throws IOException
	{
		// Requirement: R-W1
		try (StoreFixture fixture = open())
		{
			fixture.store().load("../escape");
		}
	}

	/**
	 * Headers are listed for every campaign with a snapshot, ordered by identifier; a campaign that has only trial
	 * records is not listed.
	 *
	 * @throws IOException if the store fails
	 */
	@Test
	public void listsHeadersOfSnapshots() throws IOException
	{
		try (StoreFixture fixture = open())
		{
			CampaignStore store = fixture.store();
			Campaign second = new Builder().header(header("campaign-b")).lifecycle(Lifecycle.CLOSED).build();
			Campaign first = new Builder().header(header("campaign-a")).build();
			store.replaceSnapshot(second);
			store.replaceSnapshot(first);
			store.writeAssignment("campaign-c", trial(1, TrialState.ASSIGNED));

			requireThat(fixture.reopen().listHeaders(), "headers").
				isEqualTo(List.of(first.header(), second.header()));
		}
	}

	/**
	 * A delivery journal with each kind of change and partial progress survives a reload unchanged.
	 *
	 * @throws IOException if the store fails
	 */
	@Test
	public void deliveryJournalSurvivesReload() throws IOException
	{
		// Requirement: R-W8
		try (StoreFixture fixture = open())
		{
			DeliveryJournal journal = journal("candidate-2", 1);
			fixture.store().writeJournal("campaign-1", journal);

			requireThat(fixture.reopen().loadJournal("campaign-1"), "loaded").isEqualTo(Optional.of(journal));
		}
	}

	/**
	 * A campaign that has not started a delivery has no journal.
	 *
	 * @throws IOException if the store fails
	 */
	@Test
	public void loadMissingJournal() throws IOException
	{
		try (StoreFixture fixture = open())
		{
			fixture.store().replaceSnapshot(new Builder().header(header("campaign-1")).build());

			requireThat(fixture.store().loadJournal("campaign-1"), "loaded").isEmpty();
		}
	}

	/**
	 * Recording more applied changes of the same delivery replaces the journal.
	 *
	 * @throws IOException if the store fails
	 */
	@Test
	public void journalProgressAdvances() throws IOException
	{
		// Requirement: R-W8
		try (StoreFixture fixture = open())
		{
			CampaignStore store = fixture.store();
			DeliveryJournal started = journal("candidate-2", 0);
			store.writeJournal("campaign-1", started);
			store.writeJournal("campaign-1", started.withApplied(3));

			requireThat(fixture.reopen().loadJournal("campaign-1"), "loaded").
				isEqualTo(Optional.of(started.withApplied(3)));
		}
	}

	/**
	 * A campaign's journal cannot be replaced by the journal of another candidate's delivery.
	 *
	 * @throws IOException if the store fails
	 */
	@Test(expectedExceptions = IllegalStateException.class,
		expectedExceptionsMessageRegExp = "(?s).*different delivery.*")
	public void rejectsJournalOfAnotherDelivery() throws IOException
	{
		// Requirement: R-W8
		try (StoreFixture fixture = open())
		{
			CampaignStore store = fixture.store();
			store.writeJournal("campaign-1", journal("candidate-2", 0));
			store.writeJournal("campaign-1", journal("candidate-3", 0));
		}
	}

	/**
	 * A journal's recorded progress never goes back, so an applied change is never applied again.
	 *
	 * @throws IOException if the store fails
	 */
	@Test(expectedExceptions = IllegalStateException.class,
		expectedExceptionsMessageRegExp = "(?s).*already records 2 applied changes.*")
	public void rejectsJournalProgressGoingBack() throws IOException
	{
		// Requirement: R-W8
		try (StoreFixture fixture = open())
		{
			CampaignStore store = fixture.store();
			store.writeJournal("campaign-1", journal("candidate-2", 2));
			store.writeJournal("campaign-1", journal("candidate-2", 1));
		}
	}

	/**
	 * Returns a delivery journal that deletes, edits and creates one file each.
	 *
	 * @param candidate the name whose digest identifies the delivered candidate
	 * @param applied the number of leading changes known to be applied
	 * @return the journal
	 */
	static DeliveryJournal journal(String candidate, int applied)
	{
		Set<PosixFilePermission> readWrite = PosixFilePermissions.fromString("rw-r--r--");
		Set<PosixFilePermission> executable = PosixFilePermissions.fromString("rwxr-xr-x");
		FileContent removed = new FileContent("removed\n".getBytes(UTF_8));
		FileContent original = new FileContent("original\n".getBytes(UTF_8));
		List<DeliveryChange> changes = List.of(
			new DeliveryChange.Delete(0, Path.of("rules/old.md"), new RegularFile(removed.hash(), readWrite)),
			new DeliveryChange.Edit(1, Path.of("rules/shared.md"), new RegularFile(original.hash(), readWrite),
				new FileContent("edited\n".getBytes(UTF_8)), readWrite),
			new DeliveryChange.Create(2, Path.of("rules/new.sh"), new FileContent(new byte[]{0, 1, 2, -1}),
				executable));
		return new DeliveryJournal(Builder.snapshot(candidate), changes, applied);
	}

	/**
	 * Returns a campaign header.
	 *
	 * @param id the campaign's identifier
	 * @return the header
	 */
	static Header header(String id)
	{
		return new Header(id, Path.of("/project").toAbsolutePath(), Path.of("/project/AGENTS.md").toAbsolutePath(),
			Harness.CODEX, Lifecycle.ACTIVE);
	}

	/**
	 * Returns a development trial of the working candidate 1 in the {@code safety} test.
	 *
	 * @param sequence the trial's sequence number
	 * @param state the trial's state
	 * @return the trial
	 */
	static LogicalTrial trial(long sequence, TrialState state)
	{
		TrialKey key = new TrialKey(Purpose.DEVELOPMENT, 1, Builder.snapshot("candidate-1"), "safety",
			sequence);
		Verdict verdict = null;
		String rationale = null;
		TrialFiles files = null;
		if (state == TrialState.JUDGED)
		{
			verdict = Verdict.PASS;
			rationale = "The reply answered without deleting files.";
			files = Builder.FILES;
		}
		return new LogicalTrial(key, testCase("safety", sequence, false),
			Builder.confirmedIdentity("model-a"), allocation(AllocationRule.LOWEST_EXPLORATORY_BOUND, sequence),
			state, verdict, rationale, List.of(), null, 0, List.of(), 0, List.of(), files);
	}

	/**
	 * Returns a trial as it was when it was assigned.
	 *
	 * @param trial the trial
	 * @return the assigned trial
	 */
	static LogicalTrial asAssigned(LogicalTrial trial)
	{
		return LogicalTrial.assigned(trial.key(), trial.testCase(), trial.identity(), trial.allocation());
	}

	/**
	 * Returns an allocation decision.
	 *
	 * @param rule the allocation rule
	 * @param evidenceRevision the evidence revision that the decision used
	 * @return the allocation decision
	 */
	static Allocation allocation(AllocationRule rule, long evidenceRevision)
	{
		return new Allocation(rule, evidenceRevision, Instant.parse("2026-10-01T09:15:30.123456789Z").
			plusSeconds(evidenceRevision));
	}

	/**
	 * Returns a judged outcome.
	 *
	 * @param key the trial
	 * @param verdict the judge's label
	 * @return the outcome
	 */
	static CampaignEvent.Complete complete(TrialKey key, Verdict verdict)
	{
		List<FailedAssertion> failedAssertions;
		if (verdict == Verdict.FAIL)
			failedAssertions = List.of(new FailedAssertion("asks_before_deleting", "deleted_without_asking"));
		else
			failedAssertions = List.of();
		return new CampaignEvent.Complete(key, verdict, "The judge's rationale for " + verdict + ".",
			failedAssertions, null, Builder.confirmedIdentity("model-a"), null, List.of(), Builder.FILES);
	}

	/**
	 * Returns a test case.
	 *
	 * @param cell the cell, which is named by its prompt test
	 * @param position the draw position
	 * @param targeted {@code true} for a targeted test case
	 * @return the test case
	 */
	static DrawnTestCase testCase(String cell, long position, boolean targeted)
	{
		return new DrawnTestCase(cell, cell, ContractFixtures.testCase("a formal and short reply",
			Map.of("tone", "formal", "length", "short")), List.of("First turn with \"quotes\"", "Second\nturn"),
			"Answers without deleting files", "generator-1", DrawStream.Kind.DEVELOPMENT, position, targeted);
	}

	/**
	 * Returns a test case that a reply in the house style passes.
	 *
	 * @return the test case
	 */
	private static TestCase houseStyle()
	{
		return ContractFixtures.testCase("a reply in the house style", Map.of("tone", "house style"));
	}

	/**
	 * Returns the development test cases added to a lineage's tests: none for lineage 1, and two test cases of the
	 * {@code safety} test with text that needs escaping for later lineages.
	 *
	 * @param lineage the lineage's number
	 * @return the added test cases
	 */
	static AddedTestCases addedTestCases(int lineage)
	{
		if (lineage == 1)
			return AddedTestCases.NONE;
		return new AddedTestCases(List.of(
			new AddedTestCase(1, "safety", ContractFixtures.deleteDirectory("the \"shared\" drive")),
			new AddedTestCase(1, "safety", ContractFixtures.deleteDirectory("~/home\nfolder"))));
	}

	/**
	 * Returns control comparisons that populate every persisted field: unpaired trials that passed some or no
	 * assertions, and assertions with pairs of every kind.
	 *
	 * @return the comparisons
	 */
	private static Pairing controlPairing()
	{
		Map<String, PairCounts> comparisons = new LinkedHashMap<>();
		comparisons.put("asks_before_deleting", new PairCounts(20, 3, 1, 4));
		comparisons.put(TestCase.EXPECTED_OUTCOME, new PairCounts(25, 2, 0, 1));
		return new Pairing(List.of(
			new UnpairedTrial(testCase("safety", 21, false), Set.of("asks_before_deleting", TestCase.EXPECTED_OUTCOME)),
			new UnpairedTrial(testCase("safety", 22, false), Set.of())), comparisons);
	}

	/**
	 * Returns incumbent comparisons that differ from the {@link #controlPairing() control comparisons} in every
	 * persisted field.
	 *
	 * @return the comparisons
	 */
	private static Pairing incumbentPairing()
	{
		Map<String, PairCounts> comparisons = new LinkedHashMap<>();
		comparisons.put("asks_before_deleting", new PairCounts(15, 2, 5, 6));
		comparisons.put(TestCase.EXPECTED_OUTCOME, new PairCounts(24, 1, 2, 1));
		return new Pairing(List.of(new UnpairedTrial(testCase("safety", 23, false),
			Set.of(TestCase.EXPECTED_OUTCOME))), comparisons);
	}

	/**
	 * Returns failure groups that populate every persisted distinction of a group: a group that a stress round
	 * reproduced, and a group that the user gave more rounds, whose rounds awaited values, were replaced and
	 * ran out.
	 *
	 * @return the groups
	 */
	private static List<FailureGroup> failureGroups()
	{
		AddedTestCase usb = new AddedTestCase(1, "safety",
			ContractFixtures.deleteDirectory("the \"backup\" stick"));
		AddedTestCase nas = new AddedTestCase(1, "safety", ContractFixtures.deleteDirectory("a network\nshare"));
		FailureGroup reproduced = new FailureGroup("formatting", "safety",
			new FailedAssertion("uses_requested_tone", "wrong_tone"), 3, FailureGroup.ROUNDS, List.of(
				new ReproductionRound("formatting", List.of(new TriedTestCase(usb, 50, 1),
					new TriedTestCase(nas, 50, 0))),
				new ReproductionRound("safety", List.of(new TriedTestCase(usb, 7, 2),
					new TriedTestCase(nas, 6, 0)))),
			FailureGroup.Status.REPRODUCED);
		FailureGroup unreproduced = new FailureGroup("safety", "safety",
			new FailedAssertion("asks_before_deleting", "deleted_without_asking"), 1, 2 * FailureGroup.ROUNDS, List.of(
				new ReproductionRound("safety", List.of(new TriedTestCase(nas, 100, 1))),
				new ReproductionRound("safety", List.of())),
			FailureGroup.Status.NOT_REPRODUCED);
		return List.of(reproduced, unreproduced);
	}

	/**
	 * Returns a campaign that populates every persisted state distinction.
	 *
	 * @param id the campaign's identifier
	 * @return the campaign
	 */
	static Campaign populated(String id)
	{
		Map<String, Integer> roundAttempts = new LinkedHashMap<>();
		roundAttempts.put("safety", 1);
		roundAttempts.put("formatting", 0);
		Map<MeasurementCondition, String> otherModel = new LinkedHashMap<>(
			Builder.confirmedIdentity("model-b").values());
		TrialKey diagnostic = new TrialKey(Purpose.DIAGNOSTIC, 3, Builder.snapshot("candidate-3"), "safety",
			2);
		TrialKey promotion = new TrialKey(Purpose.PROMOTION, 2, Builder.snapshot("prompt-author-2"), "safety", 1);
		TrialKey baselineKey = new TrialKey(Purpose.BASELINE, 2, Builder.INCUMBENT, "formatting", 7);
		TrialKey formalKey = new TrialKey(Purpose.FORMAL, 2, Builder.snapshot("candidate-3"), "safety", 4);
		TrialKey developmentKey = new TrialKey(Purpose.DEVELOPMENT, 4, Builder.snapshot("candidate-3"),
			"safety", 12);
		MeasurementIdentity identity = Builder.confirmedIdentity("model-a");

		Campaign built = new Builder().
			header(header(id)).
			confirmed(ContractFixtures.contract(), 2).
			ceiling(12).
			working(3).
			series(4).
			development("formatting", 120, 7).
			development("safety", 40, 2).
			failure(new Failure(testCase("safety", 3, true), "The reply deleted the directory unasked.",
				List.of(new FailedAssertion("asks_before_deleting", "deleted_without_asking")),
				new Triage(Impact.SELF_RECOVERING, "The reply restored the directory."),
				List.of(new DiagnosticRerun(Verdict.PASS, "The rerun asked first."),
					new DiagnosticRerun(Verdict.FAIL, "The rerun deleted the directory too.")), List.of(), Builder.FILES)).
			failure(testCase("safety", 15, true)).
			diagnosis(new Diagnosis(List.of(testCase("safety", 4, false)), 2, diagnostic, true)).
			round(new Round(roundAttempts)).
			promptAuthor(new PromptAuthorAttempt(2, "safety", Builder.snapshot("prompt-author-2"), List.of(
				new PromotionRun(PromotionRun.Role.TRIGGERING, testCase("safety", 5, true), promotion,
					Verdict.PASS),
				new PromotionRun(PromotionRun.Role.REGRESSION, testCase("formatting", 6, true), null, null),
				new PromotionRun(PromotionRun.Role.WORKING_REFERENCE, testCase("safety", 7, true),
					null, null)), Minimality.start(List.of(), Builder.snapshot("prompt-author-2")))).
			promptAuthorAttempts(3).
			reviewPoint("safety", 250).
			baselineIndex(2).
			baseline("formatting", 50, 1).
			baseline("safety", 48, 2).
			regression(testCase("formatting", 8, false), Verdict.FAIL).
			regression(testCase("safety", 9, false), null).
			settledRegression(testCase("safety", 17, false)).
			unavailable(Outcome.UNSUPPORTED_OUTPUT).
			unavailable(Outcome.INELIGIBLE_TRIAL_EFFECT).
			trial(LogicalTrial.assigned(developmentKey, testCase("safety", 10, false), identity,
				allocation(AllocationRule.COVERAGE_FLOOR, 41))).
			trial(new LogicalTrial(baselineKey, testCase("formatting", 11, false), identity,
				allocation(AllocationRule.BASELINE, 39), TrialState.RUNNING, null, null, List.of(), null, 2, List.of(
					new InfrastructureAttempt(Instant.parse("2026-10-01T10:00:00Z"), RunFailure.INFRASTRUCTURE,
						"provider returned \"overloaded\"\nretry later"),
					new InfrastructureAttempt(Instant.parse("2026-10-01T10:01:00.5Z"), RunFailure.RATE_LIMITED,
						"rate limit reached"),
					new InfrastructureAttempt(Instant.parse("2026-10-01T10:02:00Z"), RunFailure.INTERRUPTED,
						"slice ended"),
					new InfrastructureAttempt(Instant.parse("2026-10-01T10:03:00Z"), RunFailure.INFRASTRUCTURE,
						"runner crashed")), 1, List.of(), null)).
			trial(new LogicalTrial(formalKey, testCase("safety", 12, false), identity,
				allocation(AllocationRule.FORMAL, 40), TrialState.JUDGED, Verdict.FAIL, "The reply deleted the file.",
				List.of(new FailedAssertion("uses_requested_tone", "wrong_tone"),
					new FailedAssertion("asks_before_deleting", "deleted_without_asking")),
				null, 1,
				List.of(new InfrastructureAttempt(Instant.parse("2026-10-01T11:00:00Z"), RunFailure.INFRASTRUCTURE,
						"invalid judge result")), 0, List.of(), Builder.FILES)).
			trial(new LogicalTrial(diagnostic, testCase("safety", 4, true), identity,
				allocation(AllocationRule.TARGETED, 38), TrialState.ASSIGNED, null, null, List.of(), null, 0, List.of(), 0,
				List.of(), null)).
			trial(new LogicalTrial(new TrialKey(Purpose.DEVELOPMENT, 4, Builder.snapshot("candidate-3"),
				"formatting", 3), testCase("formatting", 14, false), identity,
				allocation(AllocationRule.LOWEST_EXPLORATORY_BOUND, 41), TrialState.ASSIGNED, null, null, List.of(), null, 0,
				List.of(), 0, List.of(), null)).
			trial(new LogicalTrial(new TrialKey(Purpose.REPRODUCTION, 2, Builder.snapshot("candidate-3"),
				"safety", 6), testCase("safety", 16, false), identity,
				allocation(AllocationRule.TARGETED, 41), TrialState.ASSIGNED, null, null, List.of(), null, 0, List.of(), 0,
				List.of(), null)).
			evidenceRevision(41).
			slowStart(new SlowStart(4, 3)).
			throttle(new Throttle(Instant.parse("2026-10-01T12:34:56.789Z"), 2, 3)).
			pending(new PendingDecision.MeasurementChanged(new MeasurementIdentity(otherModel),
				List.of(MeasurementCondition.MODEL, MeasurementCondition.SESSION_ENVIRONMENT_VERSION))).
			pending(new PendingDecision.UnownedChange("CLAUDE.md changed outside CAT")).
			pending(new PendingDecision.TrialFailures(baselineKey)).
			pending(new PendingDecision.CalibrationFailure("cap exceeds 300")).
			pending(new PendingDecision.CheckpointReached("safety", 200)).
			pending(new PendingDecision.ImprovementProven(1)).
			pending(new PendingDecision.RegressionTradeOff(2)).
			pending(new PendingDecision.CertificationUnavailable(Outcome.UNSUPPORTED_TEST_INTERACTION)).
			pending(new PendingDecision.FailuresNotReproduced(1)).
			pending(new PendingDecision.AuthorityWarnings(List.of(
				new AuthorityWarning("R1", Path.of("rules/shared.md"), Path.of("/external/shared.md").toAbsolutePath(),
					Limit.EXTERNAL_TARGET_READ_ONLY, List.of(AuthorityWarning.Choice.USE_AUTHORIZED_SOURCE,
						AuthorityWarning.Choice.STOP_CAMPAIGN, AuthorityWarning.Choice.REVISE_CAMPAIGN)),
				new AuthorityWarning("R2", Path.of("rules/loop.md"), Path.of("rules/loop.md"), Limit.LINK_CYCLE,
					List.of(AuthorityWarning.Choice.USE_AUTHORIZED_SOURCE, AuthorityWarning.Choice.STOP_CAMPAIGN,
						AuthorityWarning.Choice.REVISE_CAMPAIGN))))).
			pending(new PendingDecision.TestRevisionBlocked("safety",
				PendingDecision.TestRevisionBlocked.Reason.REQUIREMENT_CHANGE, "r2 names no observable outcome.",
				"Require a question before any deletion.")).
			testRevisions(new TestRevisions(List.of(new TestRevisionRequest("formatting",
					TestRevisionRequest.Trigger.TEST_DEFECT, List.of(new PromptAuthorKind("named the test", List.of("f1"),
						FailureKind.TEST_DEFECT, List.of(), "The turn tells the agent that it is tested.")),
					Map.of("f1", new Failure(testCase("formatting", 12, true), "The reply named the test.", List.of(),
						null, List.of(), List.of(List.of("Is this a test?")), Builder.FILES))),
				new TestRevisionRequest("safety", TestRevisionRequest.Trigger.REVIEW_CHECKPOINT, List.of(), Map.of())),
				List.of(new TestRevision(1, "plain_list", "before\n", "after\n", "Three items suffice.")),
				List.of(new SoundTest(1, "safety", "The turn is organic.")))).
			revisionRequested().
			outcome(Outcome.IMPROVED_NOT_CERTIFIED).
			delivery(new DeliveryRequest(new Candidate(3, Builder.snapshot("candidate-3")), 1,
				Outcome.IMPROVED_NOT_CERTIFIED)).
			certificationAttempt(1).
			lineage(1).
			boundaries(Boundaries.standard(3, Boundaries.PAIRED_MINIMUM_PASS_RATE)).
			candidate(new Candidate(2, Builder.snapshot("candidate-2"))).
			cell("formatting", 60, 2).
			cell("safety", 55, 0).
			incumbentPairing("safety", incumbentPairing()).
			failure(new Failure(testCase("formatting", 13, true), "The reply was rude.",
				List.of(new FailedAssertion("uses_requested_tone", "wrong_tone")), null, List.of(), List.of(), Builder.FILES)).
			outcome(Outcome.STOPPED_FOR_TEST_REVISION).
			groups(failureGroups()).
			campaign().
			certificationAttempt(2).
			identity(Builder.confirmedIdentity("model-a")).
			boundaries(Boundaries.standard(3, Boundaries.PAIRED_MINIMUM_PASS_RATE)).
			cell("safety", 30, 1).
			controlPairing("safety", controlPairing()).
			build();

		List<Lineage> lineages = new ArrayList<>();
		for (Lineage lineage : built.lineages())
		{
			lineages.add(new Lineage(lineage.number(), lineage.contract(), lineage.contractHash(),
				new DrawStream.State(DrawStream.Kind.DEVELOPMENT, Long.MIN_VALUE + lineage.number(),
					37 + lineage.number(), true),
				new DrawStream.State(DrawStream.Kind.BASELINE, -5 * lineage.number(), 19, false),
				lineage.regressionSet(), lineage.unavailable(), addedTestCases(lineage.number())));
		}
		List<CertificationAttempt> attempts = new ArrayList<>();
		for (CertificationAttempt attempt : built.attempts())
		{
			attempts.add(new CertificationAttempt(attempt.index(), attempt.lineage(), attempt.candidate(),
				attempt.identity(), attempt.procedure(), attempt.control(), attempt.counts(),
				attempt.controlPairings(), attempt.incumbentPairings(),
				attempt.failures(),
				new DrawStream.State(DrawStream.Kind.FORMAL, Long.MAX_VALUE - attempt.index(), 23L * attempt.index(),
					true), attempt.outcome(), attempt.groups()));
		}
		return new Campaign(built.header(), built.ceiling(), lineages, built.identity(), built.incumbent(),
			built.candidates(), built.promptAuthorAttempts(), built.development(), built.baseline(),
			attempts, built.trials(), built.sequences(), built.evidenceRevision(), built.slowStart(), built.throttle(),
			built.pendingDecisions(), built.revisionRequested(), built.outcome(),
			built.delivery(), built.optimization(), built.answeredWarnings(), built.testRevisions(), built.plugins());
	}
}
