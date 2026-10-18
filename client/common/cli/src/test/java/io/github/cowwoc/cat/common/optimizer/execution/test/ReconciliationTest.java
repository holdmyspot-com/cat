package io.github.cowwoc.cat.common.optimizer.execution.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.campaign.Admission;
import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.CampaignEvent;
import io.github.cowwoc.cat.common.optimizer.campaign.CellCounts;
import io.github.cowwoc.cat.common.optimizer.campaign.LogicalTrial;
import io.github.cowwoc.cat.common.optimizer.campaign.PendingDecision;
import io.github.cowwoc.cat.common.optimizer.campaign.Scheduler;
import io.github.cowwoc.cat.common.optimizer.campaign.SlowStart;
import io.github.cowwoc.cat.common.optimizer.campaign.Transitions;
import io.github.cowwoc.cat.common.optimizer.campaign.TrialKey;
import io.github.cowwoc.cat.common.optimizer.campaign.TrialState;
import io.github.cowwoc.cat.common.optimizer.campaign.Verdict;
import io.github.cowwoc.cat.common.optimizer.campaign.test.Builder;
import io.github.cowwoc.cat.common.optimizer.execution.Driver;
import io.github.cowwoc.cat.common.optimizer.execution.LeftoverRuns.RecordedProcess;
import io.github.cowwoc.cat.common.optimizer.execution.Reconciler;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry.RegularFile;
import io.github.cowwoc.cat.common.optimizer.inventory.FileInventory;
import io.github.cowwoc.cat.common.optimizer.inventory.Overlay;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Verifies the reconciliation that every driver runs first, from campaign states that represent
 * each crash point of the write-ahead order: assignment record, snapshot with the started trial, session, outcome
 * record, adoption.
 */
public final class ReconciliationTest
{
	/**
	 * Creates the test.
	 */
	public ReconciliationTest()
	{
	}

	/**
	 * Returns a builder for a confirmed campaign that develops candidate 1 and has met its baseline.
	 *
	 * @return the builder
	 */
	private static Builder developing()
	{
		return new Builder().confirmed().working(1).readyBaseline().development("formatting", 50, 0).
			development("safety", 50, 0);
	}

	/**
	 * Assigns the trials that a campaign admits first.
	 *
	 * @param campaign the campaign
	 * @param count the number of trials
	 * @return the campaign with the trials assigned, but not started
	 */
	private static Campaign assign(Campaign campaign, int count)
	{
		Campaign assigned = campaign;
		List<Admission> admissions = Scheduler.plan(campaign, DriverHarness.START, count);
		requireThat(admissions.size(), "admissions").isEqualTo(count);
		for (Admission admission : admissions)
			assigned = Transitions.apply(assigned, new CampaignEvent.Assign((Admission.Assign) admission));
		return assigned;
	}

	/**
	 * Starts every assigned trial.
	 *
	 * @param campaign the campaign
	 * @return the campaign with its trials running
	 */
	private static Campaign start(Campaign campaign)
	{
		Campaign started = campaign;
		for (LogicalTrial trial : campaign.trials())
			started = Transitions.apply(started, new CampaignEvent.Start(trial.key()));
		return started;
	}

	/**
	 * Returns the passing outcome of a trial under a campaign's measurement conditions.
	 *
	 * @param campaign the campaign
	 * @param key the trial's key
	 * @return the outcome
	 */
	private static CampaignEvent.Complete passed(Campaign campaign, TrialKey key)
	{
		return new CampaignEvent.Complete(key, Verdict.PASS, "", List.of(), null, campaign.identity(), null, List.of(),
			Builder.FILES);
	}

	/**
	 * Returns the number of outcomes that a campaign's development adopted.
	 *
	 * @param campaign the campaign
	 * @return the outcomes of every test
	 */
	private static int developmentOutcomes(Campaign campaign)
	{
		int outcomes = 0;
		for (CellCounts counts : campaign.development().counts().values())
			outcomes += counts.outcomes();
		return outcomes;
	}

	/**
	 * Crash point: the assignment record is durable, but the snapshot that starts the trial is not. Nothing is
	 * adopted or rerun, and the next admission draws the same logical trial again.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void afterAssignmentRecord() throws IOException, InterruptedException
	{
		// Requirement: R-E11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Campaign base = developing().build();
			LogicalTrial trial = assign(base, 1).trials().getFirst();
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("reconcile")), base);
			harness.store().writeAssignment(DriverHarness.CAMPAIGN, trial);
			try (Driver driver = harness.open())
			{
				requireThat(harness.campaign().trials(), "trials").isEmpty();
				requireThat(developmentOutcomes(harness.campaign()), "outcomes").isEqualTo(100);
				driver.step();
				requireThat(harness.campaign().trials().getFirst().key(), "key").isEqualTo(trial.key());
			}
		}
	}

	/**
	 * Crash point: the snapshot records the trial as started, but no outcome was recorded. The trial returns to the
	 * queue and reruns as the same logical trial, without counting as an infrastructure failure.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void startedWithoutOutcome() throws IOException, InterruptedException
	{
		// Requirement: R-E11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Campaign assigned = assign(developing().build(), 1);
			LogicalTrial trial = assigned.trials().getFirst();
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("reconcile")),
				start(assigned));
			harness.store().writeAssignment(DriverHarness.CAMPAIGN, trial);
			try (Driver driver = harness.open())
			{
				LogicalTrial requeued = harness.campaign().trials().getFirst();
				requireThat(requeued.key(), "key").isEqualTo(trial.key());
				requireThat(requeued.state(), "state").isEqualTo(TrialState.ASSIGNED);
				requireThat(requeued.infrastructureFailures(), "infrastructureFailures").isEqualTo(0);
				driver.step();
				requireThat(harness.campaign().trials().getFirst().state(), "state").isEqualTo(TrialState.RUNNING);
				requireThat(harness.sessions().sessions().size(), "sessions").isEqualTo(1);
			}
		}
	}

	/**
	 * Crash point: the outcome is recorded, but the snapshot that adopts it is not. Reconciliation adopts it once.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void outcomeRecordedNotAdopted() throws IOException, InterruptedException
	{
		// Requirement: R-E11
		// Requirement: R-E1
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Campaign assigned = assign(developing().build(), 1);
			LogicalTrial trial = assigned.trials().getFirst();
			Campaign started = start(assigned);
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("reconcile")), started);
			harness.store().writeAssignment(DriverHarness.CAMPAIGN, trial);
			harness.store().writeOutcome(DriverHarness.CAMPAIGN, passed(started, trial.key()));
			try (Driver _ = harness.open())
			{
				Campaign reconciled = harness.campaign();
				requireThat(developmentOutcomes(reconciled), "outcomes").isEqualTo(101);
				requireThat(reconciled.trials(), "trials").isEmpty();
				requireThat(reconciled.evidenceRevision(), "evidenceRevision").
					isEqualTo(started.evidenceRevision() + 1);
			}
			try (Driver _ = harness.open())
			{
				requireThat(developmentOutcomes(harness.campaign()), "outcomes").isEqualTo(101);
			}
		}
	}

	/**
	 * Crash point: the snapshot that adopts the outcome is durable. Reconciliation changes nothing.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void afterAdoption() throws IOException, InterruptedException
	{
		// Requirement: R-E11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Campaign assigned = assign(developing().build(), 1);
			LogicalTrial trial = assigned.trials().getFirst();
			Campaign started = start(assigned);
			Campaign adopted = Transitions.apply(started, passed(started, trial.key()));
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("reconcile")), adopted);
			harness.store().writeAssignment(DriverHarness.CAMPAIGN, trial);
			harness.store().writeOutcome(DriverHarness.CAMPAIGN, passed(started, trial.key()));
			try (Driver _ = harness.open())
			{
				requireThat(harness.campaign(), "campaign").isEqualTo(adopted);
			}
		}
	}

	/**
	 * Crash point: two trials started and only the later one recorded an outcome. Its outcome waits for the earlier
	 * trial, which reruns; both are then adopted in assignment order.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void outOfOrderRecords() throws IOException, InterruptedException
	{
		// Requirement: R-E11
		// Requirement: R-E5
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Campaign assigned = assign(developing().slowStart(new SlowStart(2, 0)).build(), 2);
			LogicalTrial first = assigned.trials().getFirst();
			LogicalTrial second = assigned.trials().getLast();
			Campaign started = start(assigned);
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("reconcile")), started);
			harness.store().writeAssignment(DriverHarness.CAMPAIGN, first);
			harness.store().writeAssignment(DriverHarness.CAMPAIGN, second);
			harness.store().writeOutcome(DriverHarness.CAMPAIGN, passed(started, second.key()));
			try (Driver driver = harness.open())
			{
				Campaign reconciled = harness.campaign();
				requireThat(developmentOutcomes(reconciled), "outcomes").isEqualTo(100);
				requireThat(Reconciler.find(reconciled, first.key()).orElseThrow().state(), "first").
					isEqualTo(TrialState.ASSIGNED);
				requireThat(Reconciler.find(reconciled, second.key()).orElseThrow().state(), "second").
					isEqualTo(TrialState.JUDGED);

				driver.step();
				harness.endSessions();
				harness.settle(driver);
				Campaign adopted = harness.campaign();
				requireThat(Reconciler.find(adopted, first.key()).isEmpty(), "firstAdopted").isTrue();
				requireThat(Reconciler.find(adopted, second.key()).isEmpty(), "secondAdopted").isTrue();
			}
		}
	}

	/**
	 * Runs a campaign until the process crashes after minimality finishes and the working candidate is materialized.
	 * The driver writes the promoted candidate into the optimization worktree before it replaces the snapshot, so
	 * at the crash the worktree holds the promoted candidate and the snapshot the one it replaces.
	 *
	 * @param directory a directory that the test owns
	 * @return the harness after the crash, whose store no longer crashes
	 * @throws IOException if the test directory cannot be used
	 * @throws InterruptedException if the thread is interrupted while it waits for the campaign's lock
	 */
	private static DriverHarness crashAtPromotion(Path directory) throws IOException, InterruptedException
	{
		DriverHarness harness = new DriverHarness(directory, developing().build());
		Sha256 first = Builder.snapshot("candidate-1");
		harness.trials(request ->
		{
			if (DriverHarness.cell(request).equals("safety") && harness.snapshot(request).equals(first))
				return harness.failed();
			return harness.passed();
		});
		try (Driver driver = harness.open())
		{
			harness.crashingStore().crashAfter(CrashingStore.Write.WORKING_CANDIDATE);
			boolean crashed = false;
			try
			{
				harness.runUntil(driver, state -> state.development().working().number() == 2);
			}
			catch (IOException _)
			{
				crashed = true;
			}
			requireThat(crashed, "crashed").isTrue();
		}
		harness.crashingStore().restart();
		requireThat(harness.campaign().development().working().number(), "working").isEqualTo(1);
		return harness;
	}

	/**
	 * Crash point: minimality's final outcomes are recorded, and the optimization worktree holds
	 * the promoted candidate. Reconciliation adopts the promotion, which the worktree already matches.
	 * <p>
	 * The test can take over a second under a parallel build: reaching the crash point needs a campaign that runs
	 * promotion trials and minimality to completion, and each of its steps rebuilds and validates the campaign state.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void promotionMaterializedBeforeSnapshot() throws IOException, InterruptedException
	{
		// Requirement: R-E11
		// Requirement: R-W7
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = crashAtPromotion(cleanup.add(Files.createTempDirectory("reconcile")));
			FileInventory worktree = harness.workspace().scanWorktree();
			int materialized = harness.workspace().materialized().size();
			try (Driver _ = harness.open())
			{
				Campaign reconciled = harness.campaign();
				requireThat(reconciled.development().working().number(), "working").isEqualTo(2);
				Overlay promoted = harness.overlays().require(reconciled.development().working().snapshot());
				requireThat(worktree, "worktree").isEqualTo(promoted.snapshot());
				requireThat(harness.workspace().materialized().size(), "materialized").isEqualTo(materialized);
				requireThat(reconciled.pending(), "pending").isNull();
			}
		}
	}

	/**
	 * Crash point: minimality's final outcomes are recorded, but the optimization worktree still
	 * holds the candidate that it replaces. Reconciliation adopts the promotion and writes the new working candidate
	 * into the worktree, without reporting an unowned change.
	 * <p>
	 * The test can take over a second under a parallel build: reaching the crash point needs a campaign that runs
	 * promotion trials and minimality to completion, and each of its steps rebuilds and validates the campaign state.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void promotionRecordedBeforeWorktree() throws IOException, InterruptedException
	{
		// Requirement: R-E11
		// Requirement: R-W7
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = crashAtPromotion(cleanup.add(Files.createTempDirectory("reconcile")));
			harness.workspace().changeWorktree(harness.overlays().require(Builder.snapshot("candidate-1")).
				snapshot());
			try (Driver _ = harness.open())
			{
				Campaign reconciled = harness.campaign();
				requireThat(reconciled.development().working().number(), "working").isEqualTo(2);
				Overlay promoted = harness.overlays().require(reconciled.development().working().snapshot());
				requireThat(harness.workspace().materialized().getLast(), "materialized").isEqualTo(promoted);
				requireThat(harness.workspace().scanWorktree(), "worktree").isEqualTo(promoted.snapshot());
				requireThat(reconciled.pending(), "pending").isNull();
			}
		}
	}

	/**
	 * Verifies that reconciliation leaves a worktree that holds the working candidate alone.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void worktreeMatches() throws IOException, InterruptedException
	{
		// Requirement: R-W7
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("reconcile")),
				developing().build());
			try (Driver _ = harness.open())
			{
				requireThat(harness.campaign().pending(), "pending").isNull();
				requireThat(harness.workspace().materialized(), "materialized").isEmpty();
			}
		}
	}

	/**
	 * Verifies that reconciliation pauses a campaign whose worktree holds a change that CAT did not make, and does
	 * not overwrite the change.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void worktreeUnownedChange() throws IOException, InterruptedException
	{
		// Requirement: R-W7
		// Requirement: R-E11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("reconcile")),
				developing().build());
			FileInventory edited = new FileInventory(Path.of("/project").toAbsolutePath(), Map.of(DriverHarness.PROMPT,
				new RegularFile(Sha256.of("user edit".getBytes(UTF_8)), PosixFilePermissions.fromString("rw-r--r--"))));
			harness.workspace().changeWorktree(edited);
			try (Driver _ = harness.open())
			{
				requireThat(harness.campaign().pending(), "pending").isInstanceOf(PendingDecision.UnownedChange.class);
				requireThat(harness.workspace().materialized(), "materialized").isEmpty();
				requireThat(harness.workspace().scanWorktree(), "worktree").isEqualTo(edited);
			}
		}
	}

	/**
	 * Verifies that reconciliation requests termination of the harness processes of runs, in each run root that the
	 * session port declares, that are marked running and whose owner no longer exists, and leaves the processes of
	 * runs that ended and of runs whose owner is alive. The recording terminator records requests without stopping
	 * real processes.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void requestsLeftoverRunTermination() throws IOException, InterruptedException
	{
		// Requirement: R-E11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("reconcile"));
			DriverHarness harness = new DriverHarness(directory, developing().build());
			List<Path> runRoots = harness.sessions().runRoots();
			Instant harnessStart = Instant.parse("2026-10-01T11:00:01Z");
			ProcessHandle current = ProcessHandle.current();
			writeManifest(runRoots.get(0).resolve("claude/run-1"), """
				{"state": "running", "owner_pid": 4001, "owner_started_at": "2026-10-01T11:00:00Z",
				"harness_pid": "4002", "harness_started_at": "2026-10-01T11:00:01Z"}""");
			writeManifest(runRoots.get(1).resolve("claude/run-2"), """
				{"state": "running", "owner_pid": 4003, "owner_started_at": "2026-10-01T11:00:00Z",
				"harness_pid": 4004, "harness_started_at": "2026-10-01T11:00:01Z"}""");
			writeManifest(runRoots.get(0).resolve("claude/run-3"), """
				{"state": "completed", "owner_pid": 5001, "owner_started_at": "2026-10-01T11:00:00Z",
				"harness_pid": 5002, "harness_started_at": "2026-10-01T11:00:01Z"}""");
			writeManifest(runRoots.get(1).resolve("claude/run-4"), """
				{"state": "running", "owner_pid": %d, "owner_started_at": "%s",
				"harness_pid": 6002, "harness_started_at": "2026-10-01T11:00:01Z"}""".formatted(current.pid(),
				current.info().startInstant().orElseThrow()));
			try (Driver _ = harness.open())
			{
				requireThat(harness.terminator().terminated(), "terminated").isEqualTo(List.of(
					new RecordedProcess(4002, harnessStart), new RecordedProcess(4004, harnessStart)));
			}
		}
	}

	/**
	 * Writes a run manifest.
	 *
	 * @param run the run's directory
	 * @param manifest the manifest's content
	 * @throws IOException if the file cannot be written
	 */
	private static void writeManifest(Path run, String manifest) throws IOException
	{
		Files.createDirectories(run);
		Files.writeString(run.resolve("run.json"), manifest);
	}
}
