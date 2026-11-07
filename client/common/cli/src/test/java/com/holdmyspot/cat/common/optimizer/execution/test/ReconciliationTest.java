package com.holdmyspot.cat.common.optimizer.execution.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.campaign.Admission;
import com.holdmyspot.cat.common.optimizer.campaign.Campaign;
import com.holdmyspot.cat.common.optimizer.campaign.CampaignEvent;
import com.holdmyspot.cat.common.optimizer.campaign.CellCounts;
import com.holdmyspot.cat.common.optimizer.campaign.DiagnosticRerun;
import com.holdmyspot.cat.common.optimizer.campaign.Failure;
import com.holdmyspot.cat.common.optimizer.campaign.LogicalTrial;
import com.holdmyspot.cat.common.optimizer.campaign.PendingDecision;
import com.holdmyspot.cat.common.optimizer.campaign.Promotion;
import com.holdmyspot.cat.common.optimizer.campaign.Round;
import com.holdmyspot.cat.common.optimizer.campaign.Scheduler;
import com.holdmyspot.cat.common.optimizer.campaign.Transitions;
import com.holdmyspot.cat.common.optimizer.campaign.TrialKey;
import com.holdmyspot.cat.common.optimizer.campaign.TrialState;
import com.holdmyspot.cat.common.optimizer.campaign.Verdict;
import com.holdmyspot.cat.common.optimizer.campaign.test.Builder;
import com.holdmyspot.cat.common.optimizer.contract.DrawnTestCase;
import com.holdmyspot.cat.common.optimizer.execution.Driver;
import com.holdmyspot.cat.common.optimizer.execution.Reconciler;
import com.holdmyspot.cat.common.optimizer.inventory.FileEntry.RegularFile;
import com.holdmyspot.cat.common.optimizer.inventory.FileInventory;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;
import com.holdmyspot.cat.common.test.FakeProcessHandle;
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
 * <p>
 * {@link Reconciler#recover} decides what a crash leaves behind, from a snapshot and the recorded outcomes, so most
 * claims call it directly. A driver that opens over a store with the records and a worktree is the one end-to-end
 * check of the reconciliation, and the promotion crash points need the worktree to hold the right candidate.
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
	 * Crash point: the assignment record is durable, but the snapshot that starts the trial is not. The store does not
	 * offer the record's trial for adoption, so nothing is adopted or rerun, and the next admission draws the same
	 * logical trial again.
	 */
	@Test
	public void afterAssignmentRecord()
	{
		// Requirement: R-E11
		Campaign base = developing().build();
		LogicalTrial trial = assign(base, 1).trials().getFirst();
		Campaign reconciled = Reconciler.recover(base, List.of(), DriverHarness.START);
		requireThat(reconciled, "reconciled").isEqualTo(base);
		requireThat(assign(reconciled, 1).trials().getFirst().key(), "nextTrial").isEqualTo(trial.key());
	}

	/**
	 * Crash point: the snapshot records the trial as started, but no outcome was recorded. The trial returns to the
	 * queue and reruns as the same logical trial, without counting as an infrastructure failure.
	 */
	@Test
	public void startedWithoutOutcome()
	{
		// Requirement: R-E11
		Campaign assigned = assign(developing().build(), 1);
		LogicalTrial trial = assigned.trials().getFirst();
		Campaign requeued = Reconciler.recover(start(assigned), List.of(), DriverHarness.START);
		LogicalTrial returned = requeued.trials().getFirst();
		requireThat(returned.key(), "key").isEqualTo(trial.key());
		requireThat(returned.state(), "state").isEqualTo(TrialState.ASSIGNED);
		requireThat(returned.infrastructureFailures(), "infrastructureFailures").isEqualTo(0);
		requireThat(Scheduler.plan(requeued, DriverHarness.START, 1), "next").
			isEqualTo(List.of(new Admission.Rerun(trial.key())));
	}

	/**
	 * Crash point: the outcome is recorded, but the snapshot that adopts it is not. Reconciliation adopts it once,
	 * and a repeated reconciliation changes nothing.
	 */
	@Test
	public void outcomeRecordedNotAdopted()
	{
		// Requirement: R-E11
		// Requirement: R-E1
		Campaign assigned = assign(developing().build(), 1);
		LogicalTrial trial = assigned.trials().getFirst();
		Campaign started = start(assigned);
		List<CampaignEvent.Complete> outcomes = List.of(passed(started, trial.key()));
		Campaign reconciled = Reconciler.recover(started, outcomes, DriverHarness.START);
		requireThat(developmentOutcomes(reconciled), "outcomes").isEqualTo(101);
		requireThat(reconciled.trials(), "trials").isEmpty();
		requireThat(reconciled.evidenceRevision(), "evidenceRevision").isEqualTo(started.evidenceRevision() + 1);
		requireThat(Reconciler.recover(reconciled, outcomes, DriverHarness.START), "again").isEqualTo(reconciled);
	}

	/**
	 * Crash point: the snapshot still holds the trial as assigned, but its record holds an outcome. Reconciliation
	 * starts the trial and adopts the outcome, so the outcome is counted once and the trial does not rerun.
	 */
	@Test
	public void outcomeRecordedForAssignedTrial()
	{
		// Requirement: R-E11
		Campaign assigned = assign(developing().build(), 1);
		LogicalTrial trial = assigned.trials().getFirst();
		Campaign reconciled = Reconciler.recover(assigned, List.of(passed(start(assigned), trial.key())),
			DriverHarness.START);
		requireThat(developmentOutcomes(reconciled), "outcomes").isEqualTo(101);
		requireThat(reconciled.trials(), "trials").isEmpty();
	}

	/**
	 * Crash point: the snapshot that adopts the outcome is durable. Reconciliation changes nothing.
	 */
	@Test
	public void afterAdoption()
	{
		// Requirement: R-E11
		Campaign assigned = assign(developing().build(), 1);
		LogicalTrial trial = assigned.trials().getFirst();
		Campaign started = start(assigned);
		Campaign adopted = Transitions.apply(started, passed(started, trial.key()));
		requireThat(Reconciler.recover(adopted, List.of(), DriverHarness.START), "reconciled").isEqualTo(adopted);
	}

	/**
	 * Crash point: two trials started and only the later one recorded an outcome. Its outcome waits for the earlier
	 * trial, which reruns; both are then adopted in assignment order.
	 */
	@Test
	public void outOfOrderRecords()
	{
		// Requirement: R-E11
		// Requirement: R-E5
		Campaign assigned = assign(developing().window(2).build(), 2);
		LogicalTrial first = assigned.trials().getFirst();
		LogicalTrial second = assigned.trials().getLast();
		Campaign started = start(assigned);
		Campaign reconciled = Reconciler.recover(started, List.of(passed(started, second.key())),
			DriverHarness.START);
		requireThat(developmentOutcomes(reconciled), "outcomes").isEqualTo(100);
		requireThat(Reconciler.find(reconciled, first.key()).orElseThrow().state(), "first").
			isEqualTo(TrialState.ASSIGNED);
		requireThat(Reconciler.find(reconciled, second.key()).orElseThrow().state(), "second").
			isEqualTo(TrialState.JUDGED);

		Campaign rerun = Transitions.apply(reconciled, new CampaignEvent.Start(first.key()));
		Campaign adopted = Transitions.apply(rerun, passed(started, first.key()));
		requireThat(developmentOutcomes(adopted), "outcomes").isEqualTo(102);
		requireThat(adopted.trials(), "trials").isEmpty();
	}

	/**
	 * Crash point: the outcome of a trial is recorded and the trial is in the snapshot. A driver that opens over the
	 * store's records adopts the outcome once, and a driver that opens again adopts nothing.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void driverAdoptsRecordedOutcome() throws IOException
	{
		// Requirement: R-E11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Campaign assigned = assign(developing().build(), 1);
			LogicalTrial trial = assigned.trials().getFirst();
			Campaign started = start(assigned);
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("reconcile")), started);
			harness.store().writeAssignment(DriverHarness.CAMPAIGN, trial);
			harness.store().writeOutcome(DriverHarness.CAMPAIGN, passed(started, trial.key()));
			Campaign reconciled;
			try (Driver _ = harness.open())
			{
				reconciled = harness.campaign();
			}
			requireThat(developmentOutcomes(reconciled), "outcomes").isEqualTo(101);
			requireThat(reconciled.trials(), "trials").isEmpty();
			try (Driver _ = harness.open())
			{
				requireThat(harness.campaign(), "reopened").isEqualTo(reconciled);
			}
		}
	}

	/**
	 * A prompt author attempt whose promotion decides to replace the working candidate.
	 *
	 * @param running the snapshot in which the attempt's last promotion trial runs
	 * @param decided the snapshot after the trial's outcome is adopted, before minimality advances
	 */
	private record Promoting(Campaign running, Campaign decided)
	{
	}

	/**
	 * Returns the snapshots of a prompt author attempt, on the working candidate {@code candidate-1}, that
	 * {@code candidate-2} fixes. Every promotion trial passes.
	 *
	 * @return the snapshots
	 */
	private static Promoting promoting()
	{
		DrawnTestCase logs = Builder.targeted("safety", "logs");
		List<DiagnosticRerun> reruns = List.of(new DiagnosticRerun(Verdict.PASS, "diagnostic outcome"),
			new DiagnosticRerun(Verdict.FAIL, "diagnostic outcome"), new DiagnosticRerun(Verdict.FAIL, "diagnostic outcome"));
		Campaign round = new Builder().confirmed().working(1).readyBaseline().development("safety", 60, 1).
			development("formatting", 61, 0).failure(new Failure(logs, "representative failure", List.of(), null, reruns,
				List.of(), Builder.FILES)).regression(logs, Verdict.FAIL).round(new Round(Map.of("safety", 0))).build();
		Campaign reserved = Transitions.apply(round, new CampaignEvent.StartPromptAuthorAttempt("safety"));
		int number = reserved.development().round().reservations().keySet().stream().mapToInt(Integer::intValue).
			max().orElseThrow();
		Campaign campaign = Transitions.apply(reserved, new CampaignEvent.SubmitPromptAuthorAttempt(number, "safety",
			Builder.snapshot("candidate-2"), List.of()));
		for (int step = 0; step < 100; ++step)
		{
			Admission admission = Scheduler.plan(campaign, DriverHarness.START, 1).getFirst();
			Campaign assigned = Transitions.apply(campaign, new CampaignEvent.Assign((Admission.Assign) admission));
			TrialKey key = assigned.trials().getLast().key();
			Campaign started = Transitions.apply(assigned, new CampaignEvent.Start(key));
			Campaign completed = Transitions.apply(started, passed(started, key));
			if (Promotion.decide(completed.development().promptAuthor(), completed) != Promotion.Decision.PENDING)
				return new Promoting(started, completed);
			campaign = completed;
		}
		throw new AssertionError("The promotion did not decide after 100 trials");
	}

	/**
	 * Returns the project files of the promoted candidate.
	 *
	 * @param harness the harness
	 * @return the candidate's inventory
	 * @throws IOException if the candidate cannot be read
	 */
	private static FileInventory promoted(DriverHarness harness) throws IOException
	{
		return harness.overlays().require(Builder.snapshot("candidate-2")).snapshot();
	}

	/**
	 * Crash point: minimality's final outcomes are recorded, and the optimization worktree holds
	 * the promoted candidate. Reconciliation adopts the promotion, which the worktree already matches.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void promotionMaterializedBeforeSnapshot() throws IOException
	{
		// Requirement: R-E11
		// Requirement: R-W7
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("reconcile")),
				promoting().decided());
			harness.workspace().changeWorktree(promoted(harness));
			try (Driver _ = harness.open())
			{
				Campaign reconciled = harness.campaign();
				requireThat(reconciled.development().working().snapshot(), "working").
					isEqualTo(Builder.snapshot("candidate-2"));
				requireThat(harness.workspace().scanWorktree(), "worktree").isEqualTo(promoted(harness));
				requireThat(reconciled.pending(), "pending").isNull();
			}
		}
	}

	/**
	 * Crash point: minimality's final outcomes are recorded, but the optimization worktree still
	 * holds the candidate that it replaces. Reconciliation adopts the promotion and writes the new working candidate
	 * into the worktree, without reporting an unowned change.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void promotionRecordedBeforeWorktree() throws IOException
	{
		// Requirement: R-E11
		// Requirement: R-W7
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("reconcile")),
				promoting().decided());
			try (Driver _ = harness.open())
			{
				Campaign reconciled = harness.campaign();
				requireThat(reconciled.development().working().snapshot(), "working").
					isEqualTo(Builder.snapshot("candidate-2"));
				requireThat(harness.workspace().scanWorktree(), "worktree").isEqualTo(promoted(harness));
				requireThat(reconciled.pending(), "pending").isNull();
			}
		}
	}

	/**
	 * Verifies that the driver writes a promoted candidate into the optimization worktree before it replaces the
	 * snapshot, so that a crash between the two leaves the worktree with the promoted candidate and the snapshot with
	 * the one it replaces, and the reconciliation that follows adopts the promotion.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void driverMaterializesPromotionFirst() throws IOException
	{
		// Requirement: R-E11
		// Requirement: R-W7
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("reconcile")),
				promoting().running());
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
			FileInventory worktree = harness.workspace().scanWorktree();
			requireThat(worktree, "worktree").isEqualTo(promoted(harness));
			try (Driver _ = harness.open())
			{
				Campaign reconciled = harness.campaign();
				requireThat(reconciled.development().working().number(), "working").isEqualTo(2);
				requireThat(harness.workspace().scanWorktree(), "worktreeAfterReconciliation").isEqualTo(worktree);
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
	public void worktreeMatches() throws IOException
	{
		// Requirement: R-W7
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("reconcile")),
				developing().build());
			try (Driver _ = harness.open())
			{
				requireThat(harness.campaign().pending(), "pending").isNull();
				requireThat(harness.workspace().scanWorktree(), "worktree").isEqualTo(harness.overlays().
					require(harness.campaign().development().working().snapshot()).snapshot());
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
	public void worktreeUnownedChange() throws IOException
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
				requireThat(harness.workspace().scanWorktree(), "worktree").isEqualTo(edited);
			}
		}
	}

	/**
	 * Verifies that reconciliation requests termination of the harness processes of runs, in each run root that the
	 * session port declares, that are marked running and whose owner no longer exists, and leaves the processes of
	 * runs that ended and of runs whose owner is alive. The driver's real process checker and terminator read a table of
	 * fake processes, which record termination requests without stopping real processes.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void requestsLeftoverRunTermination() throws IOException
	{
		// Requirement: R-E11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("reconcile"));
			DriverHarness harness = new DriverHarness(directory, developing().build());
			List<Path> runRoots = harness.sessions().runRoots();
			Instant ownerStart = Instant.parse("2026-10-01T11:00:00Z");
			Instant harnessStart = Instant.parse("2026-10-01T11:00:01Z");
			FakeProcessHandle orphan1 = new FakeProcessHandle(4002, harnessStart, true);
			FakeProcessHandle orphan2 = new FakeProcessHandle(4004, harnessStart, true);
			FakeProcessHandle completed = new FakeProcessHandle(5002, harnessStart, true);
			FakeProcessHandle owned = new FakeProcessHandle(6002, harnessStart, true);
			harness.processes(new FakeProcessHandle(6001, ownerStart, true), orphan1, orphan2, completed, owned);
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
				{"state": "running", "owner_pid": 6001, "owner_started_at": "2026-10-01T11:00:00Z",
				"harness_pid": 6002, "harness_started_at": "2026-10-01T11:00:01Z"}""");
			try (Driver _ = harness.open())
			{
				requireThat(orphan1.stopRequested(), "orphan1.stopRequested").isTrue();
				requireThat(orphan2.stopRequested(), "orphan2.stopRequested").isTrue();
				requireThat(completed.stopRequested(), "completed.stopRequested").isFalse();
				requireThat(owned.stopRequested(), "owned.stopRequested").isFalse();
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
