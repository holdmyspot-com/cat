package io.github.cowwoc.cat.common.optimizer.store;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.campaign.Admission;
import io.github.cowwoc.cat.common.optimizer.campaign.AuthorAttempt;
import io.github.cowwoc.cat.common.optimizer.campaign.Minimality;
import io.github.cowwoc.cat.common.optimizer.campaign.OptimizationEvidence;
import io.github.cowwoc.cat.common.optimizer.campaign.MeasurementIdentity;
import io.github.cowwoc.cat.common.optimizer.campaign.PromptSize;
import io.github.cowwoc.cat.common.optimizer.campaign.TrialInputs;
import io.github.cowwoc.cat.common.optimizer.campaign.Promotion;
import io.github.cowwoc.cat.common.optimizer.campaign.PromotionRun;
import io.github.cowwoc.cat.common.optimizer.campaign.Round;
import io.github.cowwoc.cat.common.optimizer.inventory.FileContent;
import io.github.cowwoc.cat.common.optimizer.inventory.FileDelta;
import java.nio.file.attribute.PosixFilePermissions;
import io.github.cowwoc.cat.common.optimizer.campaign.Allocation;
import io.github.cowwoc.cat.common.optimizer.campaign.AllocationRule;
import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.Builder;
import io.github.cowwoc.cat.common.optimizer.campaign.CampaignEvent;
import io.github.cowwoc.cat.common.optimizer.campaign.Transitions;
import io.github.cowwoc.cat.common.optimizer.campaign.CellCounts;
import io.github.cowwoc.cat.common.optimizer.campaign.DiagnosticRerun;
import io.github.cowwoc.cat.common.optimizer.campaign.Failure;
import io.github.cowwoc.cat.common.optimizer.campaign.LogicalTrial;
import io.github.cowwoc.cat.common.optimizer.campaign.Purpose;
import io.github.cowwoc.cat.common.optimizer.campaign.TrialKey;
import io.github.cowwoc.cat.common.optimizer.campaign.TrialState;
import io.github.cowwoc.cat.common.optimizer.campaign.Verdict;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Runs the {@link CampaignStore} contract against {@link FileCampaignStore}, and tests its file layout and crash
 * consistency.
 */
public final class FileCampaignStoreTest extends CampaignStoreContract
{
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final String ID = "campaign-1";
	/**
	 * The number of write steps that one atomic file replacement performs.
	 */
	private static final int STEPS_PER_WRITE = WriteStep.values().length;
	/**
	 * The write step, counted from 1 across {@link #runTrial}, that renames the outcome over the trial record.
	 */
	private static final int OUTCOME_RENAME = 3 * STEPS_PER_WRITE + WriteStep.RENAME.ordinal() + 1;

	/**
	 * Creates the test.
	 */
	public FileCampaignStoreTest()
	{
	}

	@Override
	@SuppressWarnings("PMD.CloseResource")
	protected StoreFixture open() throws IOException
	{
		// The returned fixture owns the directories and closes them
		TemporaryDirectories cleanup = new TemporaryDirectories();
		Path base = cleanup.add(Files.createTempDirectory("cat-campaign-store-"));
		FileCampaignStore store = new FileCampaignStore(base, JSON);
		return new StoreFixture()
		{
			@Override
			public CampaignStore store()
			{
				return store;
			}

			@Override
			public CampaignStore reopen()
			{
				return new FileCampaignStore(base, JSON);
			}

			@Override
			public void close() throws IOException
			{
				cleanup.close();
			}
		};
	}

	/**
	 * Verifies that an unfinished reduction retains its original bytes and last passing evidence on disk.
	 *
	 * @throws IOException if the fixture cannot be read or written
	 */
	@Test
	public void preservesMinimalityAcrossReload() throws IOException
	{
		// Requirement: R-D11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			FileCampaignStore store = new FileCampaignStore(cleanup.add(Files.createTempDirectory("campaign")), JSON);
			FileDelta file = new FileDelta(Path.of("CLAUDE.md"), new FileContent("first\nseparator\nlast\n".
				getBytes(UTF_8)), new FileContent("needed\nseparator\nunneeded\n".getBytes(UTF_8)),
				PosixFilePermissions.fromString("rw-r--r--"));
			TrialKey key = new TrialKey(Purpose.PROMOTION, 1, Builder.snapshot("author"), "safety", 1);
			PromotionRun passed = new PromotionRun(PromotionRun.Role.TRIGGERING,
				Builder.targeted("safety", "cache"), key, Verdict.PASS);
			Minimality search = Minimality.start(List.of(file), key.snapshot()).advance(key.snapshot(), List.of(passed),
				Promotion.Decision.PROMOTE);
			Campaign campaign = new Builder().confirmed().working(1).
				round(new Round(Map.of("safety", 0))).author(new AuthorAttempt(1, "safety",
					Builder.snapshot("reduction"), List.of(), search)).build();
			store.replaceSnapshot(campaign);
			Campaign loaded = store.load(campaign.header().id()).orElseThrow();
			requireThat(loaded.development().author().minimality(), "search").isEqualTo(search);
			requireThat(loaded.development().author().minimality().files().getFirst().before(), "workingBytes").
				isEqualTo(file.before());
		}
	}

	/**
	 * Verifies that pending author reservations, received contenders and zero-run rejected evidence survive reload.
	 *
	 * @throws IOException if the store fails
	 */
	@Test
	public void preservesConcurrentAttemptProgress() throws IOException
	{
		// Requirement: R-D12
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			FileCampaignStore store = new FileCampaignStore(cleanup.add(Files.createTempDirectory("campaign-batch")), JSON);
			Campaign campaign = new Builder().confirmed().working(1).
				failure(Builder.targeted("safety", "cache")).
				round(new Round(Map.of("safety", 0), true, Map.of(), List.of())).build();
			for (int attempt = 0; attempt < 3; ++attempt)
				campaign = Transitions.apply(campaign, new CampaignEvent.StartAuthorAttempt("safety"));
			campaign = Transitions.apply(campaign, new CampaignEvent.SubmitAuthorAttempt(3, "safety",
				Builder.snapshot("contender"), List.of()));
			campaign = Transitions.apply(campaign, new CampaignEvent.SubmitAuthorAttempt(2, "safety",
				campaign.development().working().snapshot(), List.of()));
			store.replaceSnapshot(campaign);
			Campaign loaded = store.load(campaign.header().id()).orElseThrow();
			requireThat(loaded.development().round(), "round").isEqualTo(campaign.development().round());
			requireThat(loaded.development().round().pendingAuthorAttempts(), "pendingAuthor").
				isEqualTo(Map.of(1, "safety"));
			requireThat(loaded.optimization(), "retainedEvidence").isEqualTo(campaign.optimization());
			requireThat(loaded.optimization().attempts().getFirst().accepted(), "noEditRejected").isFalse();
			requireThat(loaded.development().counts(), "counts").isEmpty();
			requireThat(loaded.attempts(), "allowance").isEmpty();
		}
	}

	/**
	 * Verifies that descriptive prompt sizes and input observations survive a store reload.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	@Test
	public void preservesOptimizationMeasurements() throws IOException
	{
		// Requirement: R-E18
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			FileCampaignStore store = new FileCampaignStore(cleanup.add(Files.createTempDirectory("measurements")), JSON);
			OptimizationEvidence evidence = new OptimizationEvidence(List.of(), List.of(),
				Map.of(Builder.INCUMBENT, new PromptSize(4L, 1L, null)),
				Map.of(Builder.confirmedIdentity("model-a"),
					Map.of(Builder.INCUMBENT, Map.of("safety", new TrialInputs(3, List.of(0L, 10L))))));
			Campaign campaign = new Builder().confirmed().optimization(evidence).build();
			store.replaceSnapshot(campaign);
			requireThat(store.load(campaign.header().id()).orElseThrow().optimization(), "measurements").
				isEqualTo(evidence);
		}
	}

	/**
	 * Verifies that reload preserves the identities of baseline counts, frozen bounds, and separate token samples.
	 *
	 * @throws IOException if the store fails
	 */
	@Test
	public void preservesComparisonIdentities() throws IOException
	{
		// Requirement: R-M4, R-L5
		MeasurementIdentity earlier = Builder.confirmedIdentity("model-a");
		MeasurementIdentity current = Builder.confirmedIdentity("model-b");
		OptimizationEvidence evidence = OptimizationEvidence.EMPTY.
			addTrial(earlier, Builder.INCUMBENT, "safety", 10L).
			addTrial(current, Builder.INCUMBENT, "safety", 100L);
		Campaign campaign = new Builder().confirmed().identity(current).baselineIdentity(earlier).
			readyBaseline().optimization(evidence).certificationAttempt(1).baselineIdentity(earlier).
			cell("safety", 100, 0).build();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			FileCampaignStore store = new FileCampaignStore(cleanup.add(Files.createTempDirectory("comparison-identities")),
				JSON);
			store.replaceSnapshot(campaign);
			Campaign loaded = store.load(campaign.header().id()).orElseThrow();
			requireThat(loaded.baseline().identity(), "baselineIdentity").isEqualTo(earlier);
			requireThat(loaded.attempt(1).procedure().baselineIdentity(), "frozenBaselineIdentity").isEqualTo(earlier);
			requireThat(loaded.identity(), "currentIdentity").isEqualTo(current);
			requireThat(loaded.optimization(), "tokenEvidence").isEqualTo(evidence);
		}
	}

	/**
	 * Verifies that a crash after a token-bearing outcome is written preserves usage and adopts it once.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	@Test
	public void recoversInputUsageWithOutcome() throws IOException
	{
		// Requirement: R-E18
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path base = cleanup.add(Files.createTempDirectory("input-usage"));
			FileCampaignStore store = new FileCampaignStore(base, JSON);
			Campaign campaign = start();
			Campaign assigned = Transitions.apply(campaign, new CampaignEvent.Assign(new Admission.Assign(
				Purpose.DEVELOPMENT, campaign.development().series(), campaign.development().working().snapshot(),
				"safety", null, new Allocation(AllocationRule.LOWEST_EXPLORATORY_BOUND, campaign.evidenceRevision(),
					Instant.parse("2026-10-01T12:00:00Z")))));
			LogicalTrial trial = assigned.trials().getLast();
			store.writeAssignment(ID, trial);
			Campaign running = Transitions.apply(assigned, new CampaignEvent.Start(trial.key()));
			store.replaceSnapshot(running);
			store.writeOutcome(ID, new CampaignEvent.Complete(trial.key(), Verdict.PASS, "", running.identity(), 0L,
				List.of()));
			reconcile(new FileCampaignStore(base, JSON));
			Campaign recovered = store.load(ID).orElseThrow();
			TrialInputs inputs = recovered.optimization().trialInputs().get(recovered.identity()).
				get(trial.key().snapshot()).get("safety");
			requireThat(inputs, "inputs").isEqualTo(new TrialInputs(1, List.of(0L)));
			reconcile(new FileCampaignStore(base, JSON));
			requireThat(store.load(ID).orElseThrow().optimization().trialInputs().get(recovered.identity()).
				get(trial.key().snapshot()).get("safety"), "inputs").isEqualTo(inputs);
		}
	}

	/**
	 * A crash after any write step of a delivery journal update leaves the earlier progress until the new journal is
	 * renamed into place, and the new progress afterwards; the journal file carries the format version.
	 *
	 * @throws IOException if the store fails
	 */
	@Test
	public void journalWriteFaultKeepsOldOrNew() throws IOException
	{
		// Requirement: R-W8
		for (WriteStep crashStep : WriteStep.values())
		{
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path base = cleanup.add(Files.createTempDirectory("cat-campaign-journal-"));
				DeliveryJournal started = journal("candidate-2", 0);
				new FileCampaignStore(base, JSON).writeJournal(ID, started);
				FileCampaignStore crashing = new FileCampaignStore(base, JSON, step ->
				{
					if (step == crashStep)
						throw new SimulatedCrash(step);
				});
				boolean crashed = false;
				try
				{
					crashing.writeJournal(ID, started.withApplied(1));
				}
				catch (SimulatedCrash _)
				{
					crashed = true;
				}
				requireThat(crashed, "crashed").withContext(crashStep, "crashStep").isTrue();

				DeliveryJournal expected;
				if (crashStep.compareTo(WriteStep.RENAME) >= 0)
					expected = started.withApplied(1);
				else
					expected = started;
				requireThat(new FileCampaignStore(base, JSON).loadJournal(ID), "loaded").
					withContext(crashStep, "crashStep").isEqualTo(Optional.of(expected));
				JsonNode document = JSON.readTree(Files.readString(campaignDirectory(base).resolve("delivery.json"),
					UTF_8));
				requireThat(document.get("format_version").asInt(), "formatVersion").isEqualTo(1);
			}
		}
	}

	/**
	 * Returns every write step at which the trial lifecycle can crash.
	 *
	 * @return each crash position, counted from one
	 * @throws IOException if the lifecycle cannot be measured
	 */
	@DataProvider(name = "trialWriteSteps")
	public static Object[][] trialWriteSteps() throws IOException
	{
		int steps = countTrialSteps();
		requireThat(steps, "steps").isEqualTo(5 * STEPS_PER_WRITE);
		Object[][] positions = new Object[steps][1];
		for (int index = 0; index < steps; ++index)
			positions[index][0] = index + 1;
		return positions;
	}

	/**
	 * A crash after any write step of a trial's lifecycle, followed by a restart and reconciliation, leaves exactly one
	 * adopted outcome: the recorded failure if its record was renamed into place, otherwise the rerun's pass.
	 *
	 * @param crashAfter the write step after which the process crashes, counted from one
	 * @throws IOException if the store fails
	 */
	@Test(dataProvider = "trialWriteSteps")
	public void faultAfterEveryWriteStepLosesNoOutcome(int crashAfter) throws IOException
	{
		// Requirement: R-E1
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path base = cleanup.add(Files.createTempDirectory("cat-campaign-fault-"));
			Campaign start = start();
			new FileCampaignStore(base, JSON).replaceSnapshot(start);
			AtomicInteger performed = new AtomicInteger();
			FileCampaignStore crashing = new FileCampaignStore(base, JSON, step ->
			{
				if (performed.incrementAndGet() == crashAfter)
					throw new SimulatedCrash(step);
			});
			boolean crashed = false;
			try
			{
				runTrial(crashing, start, Verdict.FAIL);
			}
			catch (SimulatedCrash _)
			{
				crashed = true;
			}
			requireThat(crashed, "crashed").withContext(crashAfter, "crashAfter").isTrue();

			FileCampaignStore restarted = new FileCampaignStore(base, JSON);
			reconcile(restarted);

			Campaign end = new FileCampaignStore(base, JSON).load(ID).orElseThrow();
			CellCounts expected;
			if (crashAfter >= OUTCOME_RENAME)
				expected = Builder.counts(0, 1);
			else
				expected = Builder.counts(1, 0);
			requireThat(end.development().count("safety"), "counts").withContext(crashAfter, "crashAfter").
				isEqualTo(expected);
			requireThat(end.trials(), "unadopted").withContext(crashAfter, "crashAfter").isEmpty();
			requireThat(restarted.listUnadoptedOutcomes(end), "outcomes").withContext(crashAfter, "crashAfter").
				isEmpty();
		}
	}

	/**
	 * Each atomic replacement reports its steps to the hook in order.
	 *
	 * @throws IOException if the store fails
	 */
	@Test
	public void reportsWriteStepsInOrder() throws IOException
	{
		// Requirement: R-E1
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path base = cleanup.add(Files.createTempDirectory("cat-campaign-steps-"));
			List<WriteStep> steps = new ArrayList<>();
			Consumer<WriteStep> hook = steps::add;
			new FileCampaignStore(base, JSON, hook).replaceSnapshot(start());

			requireThat(steps, "steps").isEqualTo(List.of(WriteStep.values()));
		}
	}

	/**
	 * Loading reads only {@code campaign.json}: replacing every trial record with an unreadable file does not affect
	 * it.
	 *
	 * @throws IOException if the store fails
	 */
	@Test
	public void loadingIgnoresUnreadableTrialRecords() throws IOException
	{
		// Requirement: R-W1
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path base = cleanup.add(Files.createTempDirectory("cat-campaign-unreadable-"));
			FileCampaignStore store = new FileCampaignStore(base, JSON);
			Campaign campaign = populated(ID);
			store.replaceSnapshot(campaign);
			for (LogicalTrial trial : campaign.trials())
				store.writeAssignment(ID, asAssigned(trial));
			store.writeOutcome(ID, complete(campaign.trials().getFirst().key(), Verdict.PASS));

			List<Path> records = list(campaignDirectory(base).resolve("trials"));
			requireThat(records.size(), "records").isEqualTo(campaign.trials().size());
			for (Path record : records)
			{
				Files.writeString(record, "{ not json", UTF_8);
				Files.setPosixFilePermissions(record, Set.of());
			}

			FileCampaignStore restarted = new FileCampaignStore(base, JSON);
			requireThat(restarted.load(ID), "loaded").isEqualTo(Optional.of(campaign));
			// Reconciliation does read the records, which shows that they are unreadable
			boolean unreadable = false;
			try
			{
				restarted.listUnadoptedOutcomes(campaign);
			}
			catch (IOException _)
			{
				unreadable = true;
			}
			requireThat(unreadable, "unreadable").isTrue();
		}
	}

	/**
	 * A campaign is stored as {@code .cat/work/optimizer/<id>/campaign.json} with a format version, and each logical
	 * trial as one record file.
	 *
	 * @throws IOException if the store fails
	 */
	@Test
	public void storesSnapshotAndOneRecordPerTrial() throws IOException
	{
		// Requirement: R-W1
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path base = cleanup.add(Files.createTempDirectory("cat-campaign-layout-"));
			FileCampaignStore store = new FileCampaignStore(base, JSON);
			LogicalTrial first = trial(1, TrialState.ASSIGNED);
			LogicalTrial second = trial(2, TrialState.ASSIGNED);
			store.writeAssignment(ID, first);
			store.writeAssignment(ID, second);
			store.writeOutcome(ID, complete(first.key(), Verdict.PASS));
			store.replaceSnapshot(new Builder().header(header(ID)).confirmed().working(1).trial(first).
				trial(second).build());

			Path directory = campaignDirectory(base);
			requireThat(list(directory), "campaignFiles").
				isEqualTo(List.of(directory.resolve("campaign.json"), directory.resolve("trials")));
			List<Path> records = list(directory.resolve("trials"));
			requireThat(records.size(), "records").isEqualTo(2);
			for (Path record : records)
			{
				requireThat(record.getFileName().toString(), "record").endsWith(".json");
				requireThat(JSON.readTree(Files.readString(record, UTF_8)).get("format_version").asInt(),
					"recordVersion").isEqualTo(8);
			}
			JsonNode snapshot = JSON.readTree(Files.readString(directory.resolve("campaign.json"), UTF_8));
			requireThat(snapshot.get("format_version").asInt(), "snapshotVersion").isEqualTo(8);
		}
	}

	/**
	 * Verifies that an older campaign without concurrent attempt progress is refused.
	 *
	 * @throws IOException if the store fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "(?s).*version 4 campaign.*[Ss]tart a new campaign.*")
	public void refusesSequentialCampaignFormat() throws IOException
	{
		// Requirement: R-D12
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path base = cleanup.add(Files.createTempDirectory("campaign-old-format"));
			FileCampaignStore store = new FileCampaignStore(base, JSON);
			store.replaceSnapshot(start());
			Path snapshot = campaignDirectory(base).resolve("campaign.json");
			ObjectNode root = (ObjectNode) JSON.readTree(Files.readString(snapshot, UTF_8));
			root.put("format_version", 4);
			Files.writeString(snapshot, JSON.writeValueAsString(root), UTF_8);
			store.load(ID);
		}
	}

	/**
	 * Verifies that a campaign without measurement identities for comparison evidence is refused.
	 *
	 * @throws IOException if the store fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "(?s).*version 5 campaign.*[Ss]tart a new campaign.*")
	public void refusesUnidentifiedComparisonFormat() throws IOException
	{
		// Requirement: R-M4
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path base = cleanup.add(Files.createTempDirectory("campaign-unidentified-format"));
			FileCampaignStore store = new FileCampaignStore(base, JSON);
			store.replaceSnapshot(start());
			Path snapshot = campaignDirectory(base).resolve("campaign.json");
			ObjectNode root = (ObjectNode) JSON.readTree(Files.readString(snapshot, UTF_8));
			root.put("format_version", 5);
			Files.writeString(snapshot, JSON.writeValueAsString(root), UTF_8);
			store.load(ID);
		}
	}

	/**
	 * Verifies that a campaign without regression scenario citations is refused.
	 *
	 * @throws IOException if the store fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "(?s).*version 6 campaign.*[Ss]tart a new campaign.*")
	public void refusesUncitedScenarioFormat() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path base = cleanup.add(Files.createTempDirectory("campaign-uncited-format"));
			FileCampaignStore store = new FileCampaignStore(base, JSON);
			store.replaceSnapshot(start());
			Path snapshot = campaignDirectory(base).resolve("campaign.json");
			ObjectNode root = (ObjectNode) JSON.readTree(Files.readString(snapshot, UTF_8));
			root.put("format_version", 6);
			Files.writeString(snapshot, JSON.writeValueAsString(root), UTF_8);
			store.load(ID);
		}
	}

	/**
	 * Retains each message through trial records, judged snapshots, adoption and a store restart.
	 *
	 * @throws IOException if the store fails
	 */
	@Test
	public void repliesSurviveStoreRestart() throws IOException
	{
		// Requirement: R-C12
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path base = cleanup.add(Files.createTempDirectory("campaign-reply-evidence"));
			FileCampaignStore store = new FileCampaignStore(base, JSON);
			Campaign assigned = Transitions.apply(start(), new CampaignEvent.Assign(new Admission.Assign(
				Purpose.DEVELOPMENT, 1, start().development().working().snapshot(), "safety", null,
				new Allocation(AllocationRule.LOWEST_EXPLORATORY_BOUND, 0, Instant.parse("2026-10-01T12:00:00Z")))));
			LogicalTrial earlier = assigned.trials().getFirst();
			assigned = Transitions.apply(assigned, new CampaignEvent.Assign(new Admission.Assign(
				Purpose.DEVELOPMENT, 1, assigned.development().working().snapshot(), "safety", null,
				new Allocation(AllocationRule.LOWEST_EXPLORATORY_BOUND, 0, Instant.parse("2026-10-01T12:00:01Z")))));
			LogicalTrial trial = assigned.trials().getLast();
			store.writeAssignment(ID, earlier);
			store.writeAssignment(ID, trial);
			Campaign running = Transitions.apply(assigned, new CampaignEvent.Start(earlier.key()));
			running = Transitions.apply(running, new CampaignEvent.Start(trial.key()));
			List<List<String>> responses = List.of(List.of("I'll inspect the directory.", "Should I delete it?"),
				List.of("I'll keep it.", "The directory is unchanged."));
			CampaignEvent.Complete outcome = new CampaignEvent.Complete(trial.key(), Verdict.FAIL, "Unasked narration",
				running.identity(), null, responses);
			store.writeOutcome(ID, outcome);
			store.replaceSnapshot(running);

			FileCampaignStore restarted = new FileCampaignStore(base, JSON);
			Campaign recovered = restarted.load(ID).orElseThrow();
			requireThat(restarted.listUnadoptedOutcomes(recovered), "unadoptedOutcomes").isEqualTo(List.of(outcome));
			Campaign judged = Transitions.apply(recovered, outcome);
			requireThat(judged.trials().getLast().responses(), "judgedResponses").isEqualTo(responses);
			store.replaceSnapshot(judged);
			Campaign loadedJudged = restarted.load(ID).orElseThrow();
			requireThat(loadedJudged.trials().getLast().responses(), "loadedJudgedResponses").isEqualTo(responses);
			Campaign adopted = Transitions.apply(loadedJudged, new CampaignEvent.Complete(earlier.key(),
				Verdict.PASS, "Earlier trial passed",
				loadedJudged.identity(), null, List.of()));
			store.replaceSnapshot(adopted);
			Campaign loadedAdopted = new FileCampaignStore(base, JSON).load(ID).orElseThrow();
			requireThat(loadedAdopted.development().failures().getFirst().responses(), "adoptedFailureResponses").
				isEqualTo(responses);
		}
	}

	/**
	 * Refuses campaign snapshots and trial records that stored only final responses.
	 *
	 * @param trialRecord whether to read a trial record instead of the campaign snapshot
	 * @throws IOException if the store fails
	 */
	@Test(dataProvider = "responseDocuments", expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "(?s).*version 7 campaign.*[Ss]tart a new campaign.*")
	public void refusesFinalResponseFormat(boolean trialRecord) throws IOException
	{
		// Requirement: R-C12
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path base = cleanup.add(Files.createTempDirectory("campaign-final-response-format"));
			FileCampaignStore store = new FileCampaignStore(base, JSON);
			LogicalTrial assigned = trial(1, TrialState.ASSIGNED);
			store.replaceSnapshot(start());
			store.writeAssignment(ID, assigned);
			Path document;
			if (trialRecord)
				document = list(campaignDirectory(base).resolve("trials")).getFirst();
			else
				document = campaignDirectory(base).resolve("campaign.json");
			ObjectNode root = (ObjectNode) JSON.readTree(Files.readString(document, UTF_8));
			root.put("format_version", 7);
			Files.writeString(document, JSON.writeValueAsString(root), UTF_8);
			if (trialRecord)
				store.readRecord(ID, assigned.key());
			else
				store.load(ID);
		}
	}

	/**
	 * Returns both persisted document kinds that carry replies.
	 *
	 * @return whether the document is a trial record
	 */
	@DataProvider(name = "responseDocuments")
	public static Object[][] responseDocuments()
	{
		return new Object[][] {{false}, {true}};
	}

	/**
	 * Returns the snapshot fields that a campaign with a judged trial and a diagnosed failure requires.
	 *
	 * @return the JSON pointer of each field's parent and the field's name
	 */
	@DataProvider(name = "requiredSnapshotFields")
	public static Object[][] requiredSnapshotFields()
	{
		return new Object[][]
			{
				{"/trials/0", "rationale"},
				{"/trials/0", "responses"},
				{"/development/failures/0", "rationale"},
				{"/development/failures/0", "reruns"},
				{"/development/failures/0", "responses"},
				{"/development/failures/0/reruns/0", "rationale"}
			};
	}

	/**
	 * A snapshot that lacks a judge's rationale or a failure's diagnostic reruns is malformed and fails to load with
	 * an error that names the file and the field.
	 *
	 * @param parent the JSON pointer of the object that holds the field
	 * @param name the field to remove
	 * @throws IOException if the store fails
	 */
	@Test(dataProvider = "requiredSnapshotFields")
	public void rejectsSnapshotWithoutJudgeEvidence(String parent, String name) throws IOException
	{
		// Requirement: R-E1
		// Requirement: R-C12
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path base = cleanup.add(Files.createTempDirectory("cat-campaign-rationale-"));
			FileCampaignStore store = new FileCampaignStore(base, JSON);
			store.replaceSnapshot(new Builder().header(header(ID)).confirmed().working(1).
				trial(trial(2, TrialState.JUDGED)).
				failure(new Failure(instance("safety", "deletion", 3, true),
					"The reply deleted the directory unasked.",
					List.of(new DiagnosticRerun(Verdict.FAIL, "The rerun deleted the directory too.")), List.of())).
				build());
			Path snapshot = campaignDirectory(base).resolve("campaign.json");
			ObjectNode root = (ObjectNode) JSON.readTree(Files.readString(snapshot, UTF_8));
			((ObjectNode) root.at(parent)).remove(name);
			Files.writeString(snapshot, JSON.writeValueAsString(root), UTF_8);

			String message = null;
			try
			{
				new FileCampaignStore(base, JSON).load(ID);
			}
			catch (IOException e)
			{
				message = e.getMessage();
			}
			requireThat(message, "message").isEqualTo("The file " + snapshot + " is malformed: " + name +
				": is missing");
		}
	}

	/**
	 * A trial record whose outcome lacks the judge's rationale is malformed and fails to read with an error that names
	 * the file and the field.
	 *
	 * @throws IOException if the store fails
	 */
	@Test
	public void rejectsOutcomeWithoutRationale() throws IOException
	{
		// Requirement: R-E1
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path base = cleanup.add(Files.createTempDirectory("cat-campaign-rationale-"));
			FileCampaignStore store = new FileCampaignStore(base, JSON);
			LogicalTrial assigned = trial(1, TrialState.ASSIGNED);
			store.writeAssignment(ID, assigned);
			store.writeOutcome(ID, complete(assigned.key(), Verdict.FAIL));
			Path record = list(campaignDirectory(base).resolve("trials")).getFirst();
			ObjectNode root = (ObjectNode) JSON.readTree(Files.readString(record, UTF_8));
			((ObjectNode) root.get("outcome")).remove("rationale");
			Files.writeString(record, JSON.writeValueAsString(root), UTF_8);

			String message = null;
			try
			{
				new FileCampaignStore(base, JSON).readRecord(ID, assigned.key());
			}
			catch (IOException e)
			{
				message = e.getMessage();
			}
			requireThat(message, "message").isEqualTo("The file " + record + " is malformed: rationale: is missing");
		}
	}

	/**
	 * A snapshot that is not valid JSON fails to load with an error that names the file.
	 *
	 * @throws IOException if the store fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "(?s).*campaign-1/campaign\\.json.*")
	public void rejectsMalformedSnapshot() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path base = cleanup.add(Files.createTempDirectory("cat-campaign-malformed-"));
			Path snapshot = campaignDirectory(base).resolve("campaign.json");
			Files.createDirectories(snapshot.getParent());
			Files.writeString(snapshot, "{ \"format_version\": 1, ", UTF_8);

			new FileCampaignStore(base, JSON).load(ID);
		}
	}

	/**
	 * A snapshot written in a different format version is detected instead of being misread.
	 *
	 * @throws IOException if the store fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "(?s).*format_version.*")
	public void rejectsUnknownFormatVersion() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path base = cleanup.add(Files.createTempDirectory("cat-campaign-version-"));
			FileCampaignStore store = new FileCampaignStore(base, JSON);
			store.replaceSnapshot(start());
			Path snapshot = campaignDirectory(base).resolve("campaign.json");
			ObjectNode root = (ObjectNode) JSON.readTree(Files.readString(snapshot, UTF_8));
			root.put("format_version", 9);
			Files.writeString(snapshot, JSON.writeValueAsString(root), UTF_8);

			new FileCampaignStore(base, JSON).load(ID);
		}
	}

	/**
	 * A campaign of format version 1, whose files are keyed by category rather than by cell, is refused with a message
	 * that names its version and asks for a new campaign.
	 *
	 * @throws IOException if the store fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "(?s).*version 1 campaign.*[Ss]tart a new campaign.*")
	public void refusesVersionOneCampaign() throws IOException
	{
		// Requirement: R-C11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path base = cleanup.add(Files.createTempDirectory("cat-campaign-version-1-"));
			FileCampaignStore store = new FileCampaignStore(base, JSON);
			store.replaceSnapshot(start());
			Path snapshot = campaignDirectory(base).resolve("campaign.json");
			ObjectNode root = (ObjectNode) JSON.readTree(Files.readString(snapshot, UTF_8));
			root.put("format_version", 1);
			Files.writeString(snapshot, JSON.writeValueAsString(root), UTF_8);

			new FileCampaignStore(base, JSON).load(ID);
		}
	}

	/**
	 * A campaign of format version 2, which lacks the development cell tier, deterministic checks and the core-task
	 * requirement, is refused with a message that names its version and asks for a new campaign.
	 *
	 * @throws IOException if the store fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "(?s).*version 2 campaign.*[Ss]tart a new campaign.*")
	public void refusesVersionTwoCampaign() throws IOException
	{
		// Requirement: R-E19
		// Requirement: R-C9
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path base = cleanup.add(Files.createTempDirectory("cat-campaign-version-2-"));
			FileCampaignStore store = new FileCampaignStore(base, JSON);
			store.replaceSnapshot(start());
			Path snapshot = campaignDirectory(base).resolve("campaign.json");
			ObjectNode root = (ObjectNode) JSON.readTree(Files.readString(snapshot, UTF_8));
			root.put("format_version", 2);
			Files.writeString(snapshot, JSON.writeValueAsString(root), UTF_8);

			new FileCampaignStore(base, JSON).load(ID);
		}
	}

	/**
	 * A campaign of format version 3, which lacks author manifests and resumable minimality progress, is refused
	 * with a message that names its version and asks for a new campaign.
	 *
	 * @throws IOException if the store fails
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "(?s).*version 3 campaign.*[Ss]tart a new campaign.*")
	public void refusesVersionThreeCampaign() throws IOException
	{
		// Requirement: R-D11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path base = cleanup.add(Files.createTempDirectory("cat-campaign-version-3-"));
			FileCampaignStore store = new FileCampaignStore(base, JSON);
			store.replaceSnapshot(start());
			Path snapshot = campaignDirectory(base).resolve("campaign.json");
			ObjectNode root = (ObjectNode) JSON.readTree(Files.readString(snapshot, UTF_8));
			root.put("format_version", 3);
			Files.writeString(snapshot, JSON.writeValueAsString(root), UTF_8);

			new FileCampaignStore(base, JSON).load(ID);
		}
	}

	/**
	 * Returns a confirmed campaign whose working candidate has no development outcomes.
	 *
	 * @return the campaign
	 */
	private static Campaign start()
	{
		return new Builder().header(header(ID)).confirmed().working(1).build();
	}

	/**
	 * Returns a campaign's directory.
	 *
	 * @param base the base worktree
	 * @return the directory
	 */
	private static Path campaignDirectory(Path base)
	{
		return base.resolve(".cat/work/optimizer").resolve(ID);
	}

	/**
	 * Lists a directory's entries in name order.
	 *
	 * @param directory the directory
	 * @return the entries
	 * @throws IOException if the directory cannot be listed
	 */
	private static List<Path> list(Path directory) throws IOException
	{
		try (Stream<Path> entries = Files.list(directory))
		{
			return entries.sorted().toList();
		}
	}

	/**
	 * Counts the write steps of one trial's lifecycle.
	 *
	 * @return the number of steps
	 * @throws IOException if the store fails
	 */
	private static int countTrialSteps() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path base = cleanup.add(Files.createTempDirectory("cat-campaign-count-"));
			Campaign start = start();
			new FileCampaignStore(base, JSON).replaceSnapshot(start);
			AtomicInteger performed = new AtomicInteger();
			runTrial(new FileCampaignStore(base, JSON, _ -> performed.incrementAndGet()), start, Verdict.FAIL);
			return performed.get();
		}
	}

	/**
	 * Assigns, runs and adopts one development trial in the write-ahead order.
	 *
	 * @param store the store
	 * @param campaign the campaign before the assignment
	 * @param verdict the trial's outcome
	 * @throws IOException if the store fails
	 */
	private static void runTrial(CampaignStore store, Campaign campaign, Verdict verdict) throws IOException
	{
		Campaign assigned = Transitions.apply(campaign, new CampaignEvent.Assign(new Admission.Assign(
			Purpose.DEVELOPMENT, campaign.development().series(), campaign.development().working().snapshot(),
			"safety", null, new Allocation(AllocationRule.LOWEST_EXPLORATORY_BOUND, campaign.evidenceRevision(),
			Instant.parse("2026-10-01T12:00:00Z")))));
		LogicalTrial trial = assigned.trials().getLast();
		store.writeAssignment(ID, trial);
		store.replaceSnapshot(assigned);
		Campaign running = Transitions.apply(assigned, new CampaignEvent.Start(trial.key()));
		store.replaceSnapshot(running);
		finish(store, running, trial.key(), verdict);
	}

	/**
	 * Records and adopts the outcome of a running trial.
	 *
	 * @param store the store
	 * @param running the campaign, in which the trial is running
	 * @param key the trial
	 * @param verdict the trial's outcome
	 * @throws IOException if the store fails
	 */
	private static void finish(CampaignStore store, Campaign running, TrialKey key, Verdict verdict)
		throws IOException
	{
		CampaignEvent.Complete outcome = new CampaignEvent.Complete(key, verdict, "", running.identity(), null, List.of());
		store.writeOutcome(ID, outcome);
		store.replaceSnapshot(Transitions.apply(running, outcome));
	}

	/**
	 * Reconciles the campaign after a restart: adopts recorded outcomes, reruns trials that have none with a passing
	 * outcome, and assigns the trial again if the snapshot never held it.
	 *
	 * @param store the restarted store
	 * @throws IOException if the store fails
	 */
	private static void reconcile(CampaignStore store) throws IOException
	{
		Campaign loaded = store.load(ID).orElseThrow();
		List<CampaignEvent.Complete> outcomes = store.listUnadoptedOutcomes(loaded);
		if (!outcomes.isEmpty())
		{
			for (CampaignEvent.Complete outcome : outcomes)
				loaded = Transitions.apply(loaded, outcome);
			store.replaceSnapshot(loaded);
			return;
		}
		if (loaded.trials().isEmpty())
		{
			if (loaded.development().count("safety").outcomes() == 0)
				runTrial(store, loaded, Verdict.PASS);
			return;
		}
		LogicalTrial trial = loaded.trials().getFirst();
		Campaign running = loaded;
		if (trial.state() == TrialState.ASSIGNED)
		{
			running = Transitions.apply(loaded, new CampaignEvent.Start(trial.key()));
			store.replaceSnapshot(running);
		}
		finish(store, running, trial.key(), Verdict.PASS);
	}
}
