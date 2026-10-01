package io.github.cowwoc.cat.common.optimizer.store;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.campaign.Admission;
import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.CampaignBuilder;
import io.github.cowwoc.cat.common.optimizer.campaign.CampaignEvent;
import io.github.cowwoc.cat.common.optimizer.campaign.CampaignTransitions;
import io.github.cowwoc.cat.common.optimizer.campaign.CategoryCounts;
import io.github.cowwoc.cat.common.optimizer.campaign.LogicalTrial;
import io.github.cowwoc.cat.common.optimizer.campaign.Purpose;
import io.github.cowwoc.cat.common.optimizer.campaign.TrialKey;
import io.github.cowwoc.cat.common.optimizer.campaign.TrialState;
import io.github.cowwoc.cat.common.optimizer.campaign.Verdict;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

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
					"recordVersion").isEqualTo(1);
			}
			JsonNode snapshot = JSON.readTree(Files.readString(directory.resolve("campaign.json"), UTF_8));
			requireThat(snapshot.get("format_version").asInt(), "snapshotVersion").isEqualTo(1);
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
			root.put("format_version", 2);
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
			"safety", null)));
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
		CampaignEvent.Complete outcome = new CampaignEvent.Complete(key, verdict, running.identity());
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
