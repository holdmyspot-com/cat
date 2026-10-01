package io.github.cowwoc.cat.common.optimizer.store;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.campaign.Admission;
import io.github.cowwoc.cat.common.optimizer.campaign.Allocation;
import io.github.cowwoc.cat.common.optimizer.campaign.AllocationRule;
import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.CampaignBuilder;
import io.github.cowwoc.cat.common.optimizer.campaign.CampaignEvent;
import io.github.cowwoc.cat.common.optimizer.campaign.CampaignTransitions;
import io.github.cowwoc.cat.common.optimizer.campaign.CategoryCounts;
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
	 * A crash after any write step of a trial's lifecycle, followed by a restart and reconciliation, leaves exactly one
	 * adopted outcome: the recorded failure if its record was renamed into place, otherwise the rerun's pass.
	 *
	 * @throws IOException if the store fails
	 */
	@Test
	public void faultAfterEveryWriteStepLosesNoOutcome() throws IOException
	{
		// Requirement: R-E1
		int steps = countTrialSteps();
		requireThat(steps, "steps").isEqualTo(5 * STEPS_PER_WRITE);
		for (int crashAfter = 1; crashAfter <= steps; ++crashAfter)
		{
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path base = cleanup.add(Files.createTempDirectory("cat-campaign-fault-"));
				Campaign start = start();
				new FileCampaignStore(base, JSON).replaceSnapshot(start);
				AtomicInteger performed = new AtomicInteger();
				int limit = crashAfter;
				FileCampaignStore crashing = new FileCampaignStore(base, JSON, step ->
				{
					if (performed.incrementAndGet() == limit)
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
				CategoryCounts expected;
				if (crashAfter >= OUTCOME_RENAME)
					expected = CampaignBuilder.counts(0, 1);
				else
					expected = CampaignBuilder.counts(1, 0);
				requireThat(end.development().count("safety"), "counts").withContext(crashAfter, "crashAfter").
					isEqualTo(expected);
				requireThat(end.trials(), "unadopted").withContext(crashAfter, "crashAfter").isEmpty();
				requireThat(restarted.listUnadoptedOutcomes(end), "outcomes").withContext(crashAfter, "crashAfter").
					isEmpty();
			}
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
			store.replaceSnapshot(new CampaignBuilder().header(header(ID)).confirmed().working(1).trial(first).
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
					"recordVersion").isEqualTo(2);
			}
			JsonNode snapshot = JSON.readTree(Files.readString(directory.resolve("campaign.json"), UTF_8));
			requireThat(snapshot.get("format_version").asInt(), "snapshotVersion").isEqualTo(2);
		}
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
				{"/development/failures/0", "rationale"},
				{"/development/failures/0", "reruns"},
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
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path base = cleanup.add(Files.createTempDirectory("cat-campaign-rationale-"));
			FileCampaignStore store = new FileCampaignStore(base, JSON);
			store.replaceSnapshot(new CampaignBuilder().header(header(ID)).confirmed().working(1).
				trial(trial(2, TrialState.JUDGED)).
				failure(new Failure(instance("safety", "deletion", 3, true),
					"The reply deleted the directory unasked.",
					List.of(new DiagnosticRerun(Verdict.FAIL, "The rerun deleted the directory too.")))).
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
			root.put("format_version", 3);
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
	 * Returns a confirmed campaign whose working candidate has no development outcomes.
	 *
	 * @return the campaign
	 */
	private static Campaign start()
	{
		return new CampaignBuilder().header(header(ID)).confirmed().working(1).build();
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
		Campaign assigned = CampaignTransitions.apply(campaign, new CampaignEvent.Assign(new Admission.Assign(
			Purpose.DEVELOPMENT, campaign.development().series(), campaign.development().working().snapshot(),
			"safety", null, new Allocation(AllocationRule.LOWEST_EXPLORATORY_BOUND, campaign.evidenceRevision(),
			Instant.parse("2026-10-01T12:00:00Z")))));
		LogicalTrial trial = assigned.trials().getLast();
		store.writeAssignment(ID, trial);
		store.replaceSnapshot(assigned);
		Campaign running = CampaignTransitions.apply(assigned, new CampaignEvent.Start(trial.key()));
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
		CampaignEvent.Complete outcome = new CampaignEvent.Complete(key, verdict, "", running.identity());
		store.writeOutcome(ID, outcome);
		store.replaceSnapshot(CampaignTransitions.apply(running, outcome));
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
				loaded = CampaignTransitions.apply(loaded, outcome);
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
			running = CampaignTransitions.apply(loaded, new CampaignEvent.Start(trial.key()));
			store.replaceSnapshot(running);
		}
		finish(store, running, trial.key(), Verdict.PASS);
	}

	/**
	 * Simulates a process that stops after a write step.
	 */
	private static final class SimulatedCrash extends RuntimeException
	{
		private static final long serialVersionUID = 0L;

		/**
		 * Creates the exception.
		 *
		 * @param step the last step that completed
		 */
		SimulatedCrash(WriteStep step)
		{
			super("Crashed after " + step);
		}
	}
}
