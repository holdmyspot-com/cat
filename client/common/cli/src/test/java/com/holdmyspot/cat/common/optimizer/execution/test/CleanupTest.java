package com.holdmyspot.cat.common.optimizer.execution.test;

import com.holdmyspot.cat.common.FileLocks;
import com.holdmyspot.cat.common.fakes.test.Yielding;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.SessionDirectory;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.campaign.Applicability;
import com.holdmyspot.cat.common.optimizer.campaign.Campaign;
import com.holdmyspot.cat.common.optimizer.campaign.Candidate;
import com.holdmyspot.cat.common.optimizer.campaign.DeliveryRequest;
import com.holdmyspot.cat.common.optimizer.campaign.Harness;
import com.holdmyspot.cat.common.optimizer.campaign.Header;
import com.holdmyspot.cat.common.optimizer.campaign.Lifecycle;
import com.holdmyspot.cat.common.optimizer.campaign.Outcome;
import com.holdmyspot.cat.common.optimizer.campaign.test.Builder;
import com.holdmyspot.cat.common.optimizer.delivery.DeliveryResult.Conflicted;
import com.holdmyspot.cat.common.optimizer.delivery.DeliveryResult.Delivered;
import com.holdmyspot.cat.common.optimizer.delivery.DeliveryResult;
import com.holdmyspot.cat.common.optimizer.execution.Cleanup;
import com.holdmyspot.cat.common.optimizer.store.FileCampaignStore;
import com.holdmyspot.cat.common.optimizer.workspace.ForkView;
import com.holdmyspot.cat.common.optimizer.workspace.Forks;
import com.holdmyspot.cat.common.test.SessionHarnesses;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests {@link Cleanup} against a test-owned base worktree whose campaigns hold optimization worktrees, and the two run
 * roots of the CAT session directory that holds the campaigns. The worktrees are directories that {@link FakeForks}
 * records instead of Git worktrees, since {@code ForkTest} covers removing a Git worktree.
 * <p>
 * Every test stores two campaigns in the base: the campaign under test and another campaign. Each run root holds a run
 * of each campaign, identified by a project path below that campaign's directory, and a run whose manifest is
 * malformed.
 */
public final class CleanupTest
{
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final String CAMPAIGN = "campaign-1";
	private static final String OTHER = "campaign-2";
	private static final String SESSION_ID = "6b1f7c1e-5d0a-4c55-9a8e-2f6c0f1d2e3a";

	private static final Candidate CANDIDATE = new Candidate(2, Builder.snapshot("candidate-2"));
	private static final Consumer<Cleanup.Step> NO_HOOK = _ ->
	{
	};

	/**
	 * Creates the test.
	 */
	public CleanupTest()
	{
	}

	/**
	 * After a certified delivery's final verification, cleanup removes the campaign's optimization worktree, its runs in
	 * both run roots and its campaign directory, and keeps the other campaign's files and the unattributable run.
	 *
	 * @throws IOException if the fixture or cleanup fails
	 */
	@Test
	public void certifiedDeliveryRemovesEverything() throws IOException
	{
		// Requirement: R-W9
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = Fixture.create(cleanup);
			Campaign campaign = fixture.store(certified(new DeliveryRequest(CANDIDATE, 1, Outcome.CERTIFIED)));

			Cleanup.Removal removal = fixture.cleanup(NO_HOOK).end(campaign,
				delivered(Outcome.CERTIFIED));

			requireThat(removal, "removal").isEqualTo(new Cleanup.Removal.Removed());
			fixture.requireRemoved();
		}
	}

	/**
	 * When the user retains the incumbent, cleanup removes every file of the campaign once the incumbent's prompt tests
	 * are delivered, as after a certified delivery.
	 *
	 * @throws IOException if the fixture or cleanup fails
	 */
	@Test
	public void retainedIncumbentRemovesEverything() throws IOException
	{
		// Requirement: R-W9
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = Fixture.create(cleanup);
			Campaign campaign = fixture.store(certified(null));

			Cleanup.Removal removal = fixture.cleanup(NO_HOOK).end(campaign, delivered(null));

			requireThat(removal, "removal").isEqualTo(new Cleanup.Removal.Removed());
			fixture.requireRemoved();
		}
	}

	/**
	 * Every other ending keeps the campaign's files: an uncertified delivery, an accepted uncertified candidate, a
	 * cancelled campaign, a certified delivery that stopped on a conflict, a certified campaign whose delivery has not
	 * run, and a retained incumbent whose prompt tests are not delivered yet. The endings share one fixture, because an
	 * ending that keeps every file leaves the fixture as it found it; each ending replaces the campaign's snapshot.
	 *
	 * @throws IOException if the fixture or cleanup fails
	 */
	@Test
	public void otherEndingsKeepFiles() throws IOException
	{
		// Requirement: R-W9
		DeliveryRequest certifiedRequest = new DeliveryRequest(CANDIDATE, 1, Outcome.CERTIFIED);
		List<Ending> endings = List.of(
			new Ending("improved", new Builder().lifecycle(Lifecycle.FINISHED).outcome(Outcome.IMPROVED_NOT_CERTIFIED).
				delivery(new DeliveryRequest(CANDIDATE, 1, Outcome.IMPROVED_NOT_CERTIFIED)),
				delivered(Outcome.IMPROVED_NOT_CERTIFIED)),
			new Ending("accepted", new Builder().lifecycle(Lifecycle.FINISHED).outcome(Outcome.NOT_CERTIFIED).
				delivery(new DeliveryRequest(CANDIDATE, 0, Outcome.NOT_CERTIFIED)),
				delivered(Outcome.NOT_CERTIFIED)),
			new Ending("cancelled", new Builder().lifecycle(Lifecycle.CANCELLED), null),
			new Ending("conflicted", certified(certifiedRequest), new Conflicted(List.of())),
			new Ending("undelivered", certified(certifiedRequest), null),
			new Ending("retained, undelivered", certified(null), null));
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = Fixture.create(cleanup);
			fixture.store(endings.getFirst().campaign());
			for (Ending ending : endings)
			{
				Campaign campaign = fixture.snapshot(ending.campaign());

				Cleanup.Removal removal = fixture.cleanup(NO_HOOK).end(campaign, ending.delivery());

				requireThat(removal, "removal").withContext(ending.name(), "ending").
					isEqualTo(new Cleanup.Removal.Kept());
				fixture.requireKept(ending.name());
			}
		}
	}

	/**
	 * A fault before any removal step leaves the removal recorded, and the next command's {@link Cleanup#resume()}
	 * completes it without touching the other campaign. Resuming again changes nothing. The steps share one fixture,
	 * whose campaign is stored again for each step after the previous step's removal. A fault after the worktree step
	 * finds the optimization worktree removed, so only the fault before that step needs a campaign that has one.
	 *
	 * @throws IOException if the fixture or cleanup fails
	 */
	@Test
	public void nextCommandCompletesInterruptedStep() throws IOException
	{
		// Requirement: R-W9
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = Fixture.create(cleanup);
			for (Cleanup.Step failing : Cleanup.Step.values())
			{
				Campaign campaign = fixture.store(certified(new DeliveryRequest(CANDIDATE, 1, Outcome.CERTIFIED)),
					failing == Cleanup.Step.WORKTREE);
				Consumer<Cleanup.Step> crashing = step ->
				{
					if (step == failing)
						throw new SimulatedCrash(step);
				};
				boolean crashed = false;
				try
				{
					fixture.cleanup(crashing).end(campaign, delivered(Outcome.CERTIFIED));
				}
				catch (SimulatedCrash _)
				{
					crashed = true;
				}
				requireThat(crashed, "crashed").withContext(failing, "failing").isTrue();
				requireThat(Files.isDirectory(fixture.directory(CAMPAIGN)), "directoryAfterCrash").
					withContext(failing, "failing").isTrue();

				// The next command runs in a new process
				fixture.cleanup(NO_HOOK).resume();

				fixture.requireRemoved();
			}
			// Every step ends in the same state, which another resume leaves unchanged
			fixture.cleanup(NO_HOOK).resume();
			fixture.requireRemoved();
		}
	}

	/**
	 * A removal step that fails returns a warning that names the path and the cause instead of failing, and keeps the
	 * removal recorded, so each later command retries it and warns again until a retry succeeds.
	 *
	 * @throws IOException if the fixture or cleanup fails
	 */
	@Test
	public void failingRemovalWarnsAndIsRetried() throws IOException
	{
		// Requirement: R-W9
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = Fixture.create(cleanup);
			Campaign campaign = fixture.store(certified(new DeliveryRequest(CANDIDATE, 1, Outcome.CERTIFIED)));
			Path worktree = fixture.directory(CAMPAIGN).resolve("worktree");
			Forks denying = new DenyingForks(worktree);
			String warning = "Could not remove the files of campaign " + CAMPAIGN + ": " + worktree +
				": Permission denied. The next command retries the removal.";

			Cleanup.Removal removal = fixture.cleanup(denying).end(campaign,
				delivered(Outcome.CERTIFIED));

			requireThat(removal, "removal").isEqualTo(new Cleanup.Removal.Failed(warning));
			requireThat(FileCampaignStore.removals(fixture.campaigns()), "removals").isEqualTo(List.of(CAMPAIGN));
			requireThat(fixture.cleanup(denying).resume(), "retryWarnings").isEqualTo(List.of(warning));
			requireThat(FileCampaignStore.removals(fixture.campaigns()), "removalsAfterRetry").
				isEqualTo(List.of(CAMPAIGN));

			requireThat(fixture.cleanup(NO_HOOK).resume(), "warnings").isEmpty();
			fixture.requireRemoved();
		}
	}

	/**
	 * When the removal cannot be recorded, ending the campaign returns a warning that names the path and the cause
	 * and says that the files stay, instead of failing, and removes nothing.
	 *
	 * @throws IOException if the fixture or cleanup fails
	 */
	@Test
	public void unrecordedRemovalWarns() throws IOException
	{
		// Requirement: R-W9
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = Fixture.create(cleanup);
			Campaign campaign = fixture.store(certified(new DeliveryRequest(CANDIDATE, 1, Outcome.CERTIFIED)));
			// A directory where the record's temporary file belongs cannot be opened for writing
			Path blocker = Files.createDirectory(fixture.directory(CAMPAIGN).resolve("removal.tmp"));
			Files.writeString(blocker.resolve("child"), "x", UTF_8);

			Cleanup.Removal removal = fixture.cleanup(NO_HOOK).end(campaign,
				delivered(Outcome.CERTIFIED));

			requireThat(removal, "removal").isInstanceOf(Cleanup.Removal.Failed.class);
			String warning = ((Cleanup.Removal.Failed) removal).warning();
			requireThat(warning, "warning").startsWith("Could not record the removal of the files of campaign " +
				CAMPAIGN + ": " + blocker + ": ").endsWith(". The files stay in .cat/sessions/.");
			requireThat(FileCampaignStore.removals(fixture.campaigns()), "removals").isEmpty();
			Files.delete(blocker.resolve("child"));
			Files.delete(blocker);
			fixture.requireKept("unrecorded");
		}
	}

	/**
	 * Resuming when no removal was recorded changes nothing.
	 *
	 * @throws IOException if the fixture or cleanup fails
	 */
	@Test
	public void resumeWithoutRemovalKeepsFiles() throws IOException
	{
		// Requirement: R-W9
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = Fixture.create(cleanup);
			fixture.store(certified(new DeliveryRequest(CANDIDATE, 1, Outcome.CERTIFIED)));

			requireThat(fixture.cleanup(NO_HOOK).resume(), "warnings").isEmpty();

			fixture.requireKept("resume");
		}
	}

	/**
	 * Returns the builder of a finished campaign whose outcome is {@code certified}.
	 *
	 * @param request the delivery request, or {@code null} if the user retained the incumbent
	 * @return the builder
	 */
	private static Builder certified(DeliveryRequest request)
	{
		return new Builder().lifecycle(Lifecycle.FINISHED).outcome(Outcome.CERTIFIED).delivery(request);
	}

	/**
	 * Returns a delivery that passed its final verification.
	 *
	 * @param label the label of the delivered candidate, or {@code null} if the user retained the incumbent
	 * @return the result
	 */
	private static DeliveryResult delivered(Outcome label)
	{
		return new Delivered(label, new Applicability(Applicability.Status.CURRENT, false, List.of()), List.of(),
			List.of(), List.of(), "");
	}

	/**
	 * One way that a campaign ends without removing its files.
	 *
	 * @param name the ending's name
	 * @param campaign the builder of the ended campaign
	 * @param delivery the result of the delivery that the last command ran, or null if it ran none
	 */
	private record Ending(String name, Builder campaign, DeliveryResult delivery)
	{
	}

	/**
	 * A base worktree with a CAT session directory that holds two campaigns and two run roots.
	 *
	 * @param fileLocks the process-owned lock coordinator
	 * @param base the base worktree
	 * @param campaigns the session's directory that holds the campaigns
	 * @param runRoots the session's run root and held-out run root
	 * @param forks the campaigns' optimization worktrees
	 */
	private record Fixture(FileLocks fileLocks, Path base, Path campaigns, List<Path> runRoots, FakeForks forks)
	{
		/**
		 * Creates the base worktree, the other campaign with its worktree and runs, and the run roots.
		 *
		 * @param cleanup the scope that deletes the fixture
		 * @return the fixture
		 * @throws IOException if the fixture cannot be created
		 */
		static Fixture create(TemporaryDirectories cleanup) throws IOException
		{
			// The optimizer resolves the base worktree to its real path, so the fixture does too; the temporary folder can
			// be a symbolic link, as on macOS
			Path root = cleanup.add(Files.createTempDirectory("cat-cleanup-")).toRealPath();
			Path base = Files.createDirectory(root.resolve("base"));
			Files.writeString(base.resolve("CLAUDE.md"), "prompt v1\n", UTF_8);
			SessionDirectory session = SessionDirectory.open(base, SESSION_ID, SessionHarnesses.HARNESS_A,
				root.resolve("configuration"), Instant.parse("2026-10-16T12:00:00Z"), JSON);
			Path sessionRuns = Files.createDirectories(session.runRoot("claude"));
			Fixture fixture = new Fixture(new FileLocks(), base, session.campaigns(), List.of(sessionRuns,
				Files.createDirectories(sessionRuns.resolveSibling("claude-held_out"))), new FakeForks());
			fixture.store(new Builder().header(fixture.header(OTHER, Lifecycle.ACTIVE)));
			for (Path runRoot : fixture.runRoots())
			{
				Path malformed = Files.createDirectories(runRoot.resolve("claude/run-malformed"));
				Files.writeString(malformed.resolve("run.json"), "{ not json", UTF_8);
			}
			return fixture;
		}

		/**
		 * Returns the header of a campaign of the base worktree.
		 *
		 * @param id the campaign's identifier
		 * @param lifecycle the campaign's lifecycle
		 * @return the header
		 */
		Header header(String id, Lifecycle lifecycle)
		{
			return new Header(id, base, base.resolve("CLAUDE.md"), Harness.CLAUDE, lifecycle);
		}

		/**
		 * Stores a campaign, forks the base for it, and adds one of its runs to each run root.
		 *
		 * @param builder the campaign's builder, whose identifier becomes {@link #CAMPAIGN} unless it is {@link #OTHER}
		 * @return the stored campaign
		 * @throws IOException if the campaign cannot be stored
		 */
		Campaign store(Builder builder) throws IOException
		{
			return store(builder, true);
		}

		/**
		 * Stores a campaign and adds one of its runs to each run root.
		 *
		 * @param builder the campaign's builder, whose identifier becomes {@link #CAMPAIGN} unless it is {@link #OTHER}
		 * @param forked {@code true} to fork the base for the campaign, or {@code false} for a campaign whose
		 *               optimization worktree a removal already deleted
		 * @return the stored campaign
		 * @throws IOException if the campaign cannot be stored
		 */
		Campaign store(Builder builder, boolean forked) throws IOException
		{
			Campaign built = snapshot(builder);
			String id = built.header().id();
			Path directory = directory(id);
			if (forked)
				forks.fork(base, directory);
			for (Path runRoot : runRoots)
			{
				Path run = Files.createDirectories(runRoot.resolve("claude/run-" + id));
				Files.writeString(run.resolve("run.json"), JSON.writeValueAsString(Map.of(
					"project_path", directory.resolve("staged/project").toString(), "state", "completed")), UTF_8);
				Files.createDirectories(run.resolve("project"));
				Files.writeString(run.resolve("project/CLAUDE.md"), "prompt v1\n", UTF_8);
			}
			return built;
		}

		/**
		 * Stores a campaign's snapshot, replacing any earlier snapshot of the campaign.
		 *
		 * @param builder the campaign's builder, whose identifier becomes {@link #CAMPAIGN} unless it is {@link #OTHER}
		 * @return the stored campaign
		 * @throws IOException if the campaign cannot be stored
		 */
		Campaign snapshot(Builder builder) throws IOException
		{
			Campaign built = builder.build();
			Header header = built.header();
			if (!header.id().equals(OTHER))
				built = builder.header(header(CAMPAIGN, header.lifecycle())).build();
			new FileCampaignStore(campaigns, JSON, Yielding.fileReplacer()).replaceSnapshot(built);
			return built;
		}

		/**
		 * Returns a campaign's directory.
		 *
		 * @param id the campaign's identifier
		 * @return the directory
		 */
		Path directory(String id)
		{
			return FileCampaignStore.directory(campaigns, id);
		}

		/**
		 * Returns a cleanup of the base worktree.
		 *
		 * @param beforeStep receives each step before it runs
		 * @return the cleanup
		 */
		Cleanup cleanup(Consumer<Cleanup.Step> beforeStep)
		{
			return new Cleanup(fileLocks, base, campaigns, forks, runRoots, JSON, Yielding.fileReplacer(), beforeStep);
		}

		/**
		 * Returns a cleanup of the base worktree that removes optimization worktrees through other forks.
		 *
		 * @param forks removes the campaigns' optimization worktrees
		 * @return the cleanup
		 */
		Cleanup cleanup(Forks forks)
		{
			return new Cleanup(fileLocks, base, campaigns, forks, runRoots, JSON, Yielding.fileReplacer(), NO_HOOK);
		}

		/**
		 * Requires every file of {@link #CAMPAIGN} to be removed, and every file of {@link #OTHER} and the malformed
		 * runs to remain.
		 *
		 * @throws IOException if a directory cannot be listed
		 */
		void requireRemoved() throws IOException
		{
			requireThat(Files.notExists(directory(CAMPAIGN)), "campaignDirectoryIsAbsent").isTrue();
			for (Path runRoot : runRoots)
			{
				requireThat(Files.notExists(runRoot.resolve("claude/run-" + CAMPAIGN)), "runIsAbsent").
					withContext(runRoot, "runRoot").isTrue();
			}
			Set<Path> worktrees = forks.worktrees();
			requireThat(worktrees.stream().noneMatch(worktree -> worktree.startsWith(directory(CAMPAIGN))),
				"campaignWorktreeIsAbsent").withContext(worktrees, "worktrees").isTrue();
			try (Stream<Path> entries = Files.list(directory(CAMPAIGN).getParent()))
			{
				requireThat(entries.map(path -> path.getFileName().toString()).toList(), "campaigns").
					isEqualTo(List.of(OTHER));
			}
			requireOtherKept("removed", worktrees);
		}

		/**
		 * Requires every file of both campaigns and the malformed runs to remain.
		 *
		 * @param context the case that the check belongs to
		 * @throws IOException if a file's type cannot be read
		 */
		void requireKept(String context) throws IOException
		{
			requireThat(directory(CAMPAIGN).resolve("held_out/campaign.json"), "snapshot").
				withContext(context, "context").isRegularFile();
			requireThat(directory(CAMPAIGN).resolve("worktree/CLAUDE.md"), "worktree").
				withContext(context, "context").isRegularFile();
			for (Path runRoot : runRoots)
			{
				requireThat(runRoot.resolve("claude/run-" + CAMPAIGN + "/run.json"), "run").
					withContext(context, "context").withContext(runRoot, "runRoot").isRegularFile();
			}
			requireOtherKept(context, forks.worktrees());
		}

		/**
		 * Requires every file of {@link #OTHER} and the malformed runs to remain.
		 *
		 * @param context the case that the check belongs to
		 * @param worktrees the optimization worktrees that the forks hold
		 * @throws IOException if a file's type cannot be read
		 */
		void requireOtherKept(String context, Set<Path> worktrees) throws IOException
		{
			requireThat(directory(OTHER).resolve("held_out/campaign.json"), "otherSnapshot").
				withContext(context, "context").isRegularFile();
			requireThat(directory(OTHER).resolve("worktree/CLAUDE.md"), "otherWorktree").
				withContext(context, "context").isRegularFile();
			requireThat(worktrees.stream().anyMatch(worktree -> worktree.startsWith(directory(OTHER))),
				"otherWorktreeIsListed").withContext(context, "context").withContext(worktrees, "worktrees").isTrue();
			for (Path runRoot : runRoots)
			{
				requireThat(runRoot.resolve("claude/run-" + OTHER + "/run.json"), "otherRun").
					withContext(context, "context").withContext(runRoot, "runRoot").isRegularFile();
				requireThat(runRoot.resolve("claude/run-malformed/run.json"), "malformedRun").
					withContext(context, "context").withContext(runRoot, "runRoot").isRegularFile();
			}
		}
	}

	/**
	 * Forks whose optimization worktrees are directories that the fake records instead of Git worktrees. Removing a
	 * worktree deletes its directory and its record, and removing one that does not exist does nothing, as
	 * {@link Forks#remove(Path, Path)} documents. {@link Cleanup} only removes worktrees, so the fake creates them
	 * through {@link #fork(Path, Path)} and refuses the other operations.
	 * <p>
	 * <b>Thread Safety:</b> This class is not thread-safe.
	 */
	private static final class FakeForks implements Forks
	{
		/**
		 * The optimization worktree of each campaign that the fake forked and has not removed.
		 */
		private final Set<Path> worktrees = new HashSet<>();

		/**
		 * Creates forks that hold no worktree.
		 */
		FakeForks()
		{
		}

		/**
		 * Gives a campaign an optimization worktree that holds the base's {@code CLAUDE.md}.
		 *
		 * @param base the base worktree
		 * @param campaign the campaign storage directory
		 * @throws IOException if the worktree cannot be created
		 */
		void fork(Path base, Path campaign) throws IOException
		{
			Path worktree = Files.createDirectories(campaign.resolve("worktree"));
			Files.copy(base.resolve("CLAUDE.md"), worktree.resolve("CLAUDE.md"));
			worktrees.add(worktree);
		}

		/**
		 * Returns the optimization worktrees that the fake holds.
		 *
		 * @return the worktrees' directories
		 */
		Set<Path> worktrees()
		{
			return Set.copyOf(worktrees);
		}

		@Override
		public ForkView create(Path base, Path campaign, Set<Path> participants)
		{
			throw new UnsupportedOperationException("Cleanup never forks a project");
		}

		@Override
		public Set<Path> tracked(Path base)
		{
			throw new UnsupportedOperationException("Cleanup never lists tracked files");
		}

		@Override
		public Set<Path> trackedLinks(Path base)
		{
			throw new UnsupportedOperationException("Cleanup never lists tracked links");
		}

		@Override
		public String diff(Path base, List<Path> paths)
		{
			throw new UnsupportedOperationException("Cleanup never compares files with HEAD");
		}

		@Override
		public ForkView open(Path base, Path campaign)
		{
			throw new UnsupportedOperationException("Cleanup never opens a fork");
		}

		@Override
		public boolean exists(Path campaign)
		{
			return worktrees.contains(campaign.resolve("worktree"));
		}

		@Override
		public void remove(Path base, Path campaign) throws IOException
		{
			Path worktree = campaign.resolve("worktree");
			TemporaryDirectories.deleteRecursively(worktree);
			worktrees.remove(worktree);
		}
	}

	/**
	 * Forks whose removal of one optimization worktree is denied, as a file system that refuses the deletion would.
	 *
	 * @param denied the worktree whose removal fails
	 */
	private record DenyingForks(Path denied) implements Forks
	{
		@Override
		public ForkView create(Path base, Path campaign, Set<Path> participants)
		{
			throw new UnsupportedOperationException("Cleanup never forks a project");
		}

		@Override
		public ForkView open(Path base, Path campaign)
		{
			throw new UnsupportedOperationException("Cleanup never opens a fork");
		}

		@Override
		public boolean exists(Path campaign)
		{
			throw new UnsupportedOperationException("Cleanup never checks for a fork");
		}

		@Override
		public Set<Path> tracked(Path base)
		{
			throw new UnsupportedOperationException("Cleanup never lists tracked files");
		}

		@Override
		public Set<Path> trackedLinks(Path base)
		{
			throw new UnsupportedOperationException("Cleanup never lists tracked links");
		}

		@Override
		public String diff(Path base, List<Path> paths)
		{
			throw new UnsupportedOperationException("Cleanup never compares files with HEAD");
		}

		@Override
		public void remove(Path base, Path campaign) throws IOException
		{
			throw new AccessDeniedException(denied.toString(), null, "Permission denied");
		}
	}

	/**
	 * Simulates a process that stops before a removal step.
	 */
	private static final class SimulatedCrash extends RuntimeException
	{
		private static final long serialVersionUID = 0L;

		/**
		 * Creates the exception.
		 *
		 * @param step the step that did not run
		 */
		SimulatedCrash(Cleanup.Step step)
		{
			super("Crashed before " + step);
		}
	}
}
