package io.github.cowwoc.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.git.Git;
import io.github.cowwoc.cat.common.optimizer.campaign.Applicability;
import io.github.cowwoc.cat.common.optimizer.campaign.Builder;
import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.Candidate;
import io.github.cowwoc.cat.common.optimizer.campaign.DeliveryRequest;
import io.github.cowwoc.cat.common.optimizer.campaign.Harness;
import io.github.cowwoc.cat.common.optimizer.campaign.Header;
import io.github.cowwoc.cat.common.optimizer.campaign.Lifecycle;
import io.github.cowwoc.cat.common.optimizer.campaign.Outcome;
import io.github.cowwoc.cat.common.optimizer.delivery.DeliveryResult;
import io.github.cowwoc.cat.common.optimizer.delivery.DeliveryResult.Conflicted;
import io.github.cowwoc.cat.common.optimizer.delivery.DeliveryResult.Delivered;
import io.github.cowwoc.cat.common.optimizer.store.FileCampaignStore;
import io.github.cowwoc.cat.common.optimizer.workspace.ForkView;
import io.github.cowwoc.cat.common.optimizer.workspace.Forks;
import io.github.cowwoc.cat.common.optimizer.workspace.GitForks;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests {@link Cleanup} against a test-owned base worktree, which is a real Git repository whose campaigns hold real
 * optimization worktrees, and two test-owned run roots.
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
				Optional.of(delivered(Outcome.CERTIFIED)));

			requireThat(removal, "removal").isEqualTo(new Cleanup.Removal.Removed());
			fixture.requireRemoved();
		}
	}

	/**
	 * When the user retains the incumbent, cleanup removes every file of the campaign as after a certified delivery.
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

			Cleanup.Removal removal = fixture.cleanup(NO_HOOK).end(campaign, Optional.empty());

			requireThat(removal, "removal").isEqualTo(new Cleanup.Removal.Removed());
			fixture.requireRemoved();
		}
	}

	/**
	 * Every other ending keeps the campaign's files: an uncertified delivery, an accepted uncertified candidate, a
	 * cancelled campaign, a certified delivery that stopped on a conflict, and a certified campaign whose delivery has
	 * not run.
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
				Optional.of(delivered(Outcome.IMPROVED_NOT_CERTIFIED))),
			new Ending("accepted", new Builder().lifecycle(Lifecycle.FINISHED).outcome(Outcome.NOT_CERTIFIED).
				delivery(new DeliveryRequest(CANDIDATE, 0, Outcome.NOT_CERTIFIED)),
				Optional.of(delivered(Outcome.NOT_CERTIFIED))),
			new Ending("cancelled", new Builder().lifecycle(Lifecycle.CANCELLED), Optional.empty()),
			new Ending("conflicted", certified(certifiedRequest), Optional.of(new Conflicted(List.of()))),
			new Ending("undelivered", certified(certifiedRequest), Optional.empty()));
		for (Ending ending : endings)
		{
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Fixture fixture = Fixture.create(cleanup);
				Campaign campaign = fixture.store(ending.campaign());

				Cleanup.Removal removal = fixture.cleanup(NO_HOOK).end(campaign, ending.delivery());

				requireThat(removal, "removal").withContext(ending.name(), "ending").
					isEqualTo(new Cleanup.Removal.Kept());
				fixture.requireKept(ending.name());
			}
		}
	}

	/**
	 * A fault before any removal step leaves the removal recorded, and the next command's {@link Cleanup#resume()}
	 * completes it without touching the other campaign. Resuming again changes nothing.
	 *
	 * @throws IOException if the fixture or cleanup fails
	 */
	@Test
	public void nextCommandCompletesInterruptedStep() throws IOException
	{
		// Requirement: R-W9
		for (Cleanup.Step failing : Cleanup.Step.values())
		{
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Fixture fixture = Fixture.create(cleanup);
				Campaign campaign = fixture.store(certified(new DeliveryRequest(CANDIDATE, 1, Outcome.CERTIFIED)));
				Consumer<Cleanup.Step> crashing = step ->
				{
					if (step == failing)
						throw new SimulatedCrash(step);
				};
				boolean crashed = false;
				try
				{
					fixture.cleanup(crashing).end(campaign, Optional.of(delivered(Outcome.CERTIFIED)));
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
				fixture.cleanup(NO_HOOK).resume();
				fixture.requireRemoved();
			}
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
				Optional.of(delivered(Outcome.CERTIFIED)));

			requireThat(removal, "removal").isEqualTo(new Cleanup.Removal.Failed(warning));
			requireThat(FileCampaignStore.removals(fixture.base()), "removals").isEqualTo(List.of(CAMPAIGN));
			requireThat(fixture.cleanup(denying).resume(), "retryWarnings").isEqualTo(List.of(warning));
			requireThat(FileCampaignStore.removals(fixture.base()), "removalsAfterRetry").
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
				Optional.of(delivered(Outcome.CERTIFIED)));

			requireThat(removal, "removal").isInstanceOf(Cleanup.Removal.Failed.class);
			String warning = ((Cleanup.Removal.Failed) removal).warning();
			requireThat(warning, "warning").startsWith("Could not record the removal of the files of campaign " +
				CAMPAIGN + ": " + blocker + ": ").endsWith(". The files stay in .cat/work/.");
			requireThat(FileCampaignStore.removals(fixture.base()), "removals").isEmpty();
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
	 * @param label the label of the delivered candidate
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
	 * @param delivery the result of the delivery that the last command ran, or empty if it ran none
	 */
	private record Ending(String name, Builder campaign, Optional<DeliveryResult> delivery)
	{
	}

	/**
	 * A base worktree with two campaigns and two run roots.
	 *
	 * @param base the base worktree, a Git repository
	 * @param runRoots the run root and the held-out run root
	 */
	private record Fixture(Path base, List<Path> runRoots)
	{
		/**
		 * Creates the base worktree with one commit, the other campaign with its worktree and runs, and the run roots.
		 *
		 * @param cleanup the scope that deletes the fixture
		 * @return the fixture
		 * @throws IOException if the fixture cannot be created
		 */
		static Fixture create(TemporaryDirectories cleanup) throws IOException
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-cleanup-"));
			Path base = Files.createDirectory(root.resolve("base"));
			Files.writeString(base.resolve("CLAUDE.md"), "prompt v1\n", UTF_8);
			Git git = new Git(base);
			git.run("init", "--quiet");
			git.run("add", "--all");
			git.run("-c", "user.name=CAT Test", "-c", "user.email=cat-test@example.com", "-c", "commit.gpgsign=false",
				"commit", "--quiet", "--no-verify", "--message", "Fork point");
			Fixture fixture = new Fixture(base, List.of(Files.createDirectory(root.resolve("runs")),
				Files.createDirectory(root.resolve("held-out-runs"))));
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
			Campaign built = builder.build();
			Header header = built.header();
			if (!header.id().equals(OTHER))
				built = builder.header(header(CAMPAIGN, header.lifecycle())).build();
			String id = built.header().id();
			new FileCampaignStore(base, JSON).replaceSnapshot(built);
			Path directory = directory(id);
			new GitForks().create(base, directory, Set.of());
			for (Path runRoot : runRoots)
			{
				Path run = Files.createDirectories(runRoot.resolve("claude/run-" + id));
				Files.writeString(run.resolve("run.json"), "{\"project_path\": \"" +
					directory.resolve("staged/project") + "\", \"state\": \"completed\"}", UTF_8);
				Files.createDirectories(run.resolve("project"));
				Files.writeString(run.resolve("project/CLAUDE.md"), "prompt v1\n", UTF_8);
			}
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
			return FileCampaignStore.directory(base, id);
		}

		/**
		 * Returns a cleanup of the base worktree.
		 *
		 * @param beforeStep receives each step before it runs
		 * @return the cleanup
		 */
		Cleanup cleanup(Consumer<Cleanup.Step> beforeStep)
		{
			return new Cleanup(base, new GitForks(), runRoots, JSON, beforeStep);
		}

		/**
		 * Returns a cleanup of the base worktree that removes optimization worktrees through other forks.
		 *
		 * @param forks removes the campaigns' optimization worktrees
		 * @return the cleanup
		 */
		Cleanup cleanup(Forks forks)
		{
			return new Cleanup(base, forks, runRoots, JSON, NO_HOOK);
		}

		/**
		 * Requires every file of {@link #CAMPAIGN} to be removed, and every file of {@link #OTHER} and the malformed
		 * runs to remain.
		 *
		 * @throws IOException if Git fails
		 */
		void requireRemoved() throws IOException
		{
			requireThat(Files.notExists(directory(CAMPAIGN)), "campaignDirectoryIsAbsent").isTrue();
			for (Path runRoot : runRoots)
			{
				requireThat(Files.notExists(runRoot.resolve("claude/run-" + CAMPAIGN)), "runIsAbsent").
					withContext(runRoot, "runRoot").isTrue();
			}
			String worktrees = new Git(base).run("worktree", "list", "--porcelain");
			requireThat(worktrees, "worktrees").doesNotContain(directory(CAMPAIGN).toString());
			try (Stream<Path> entries = Files.list(directory(CAMPAIGN).getParent()))
			{
				requireThat(entries.map(path -> path.getFileName().toString()).toList(), "campaigns").
					isEqualTo(List.of(OTHER));
			}
			requireOtherKept("removed");
		}

		/**
		 * Requires every file of both campaigns and the malformed runs to remain.
		 *
		 * @param context the case that the check belongs to
		 * @throws IOException if Git fails
		 */
		void requireKept(String context) throws IOException
		{
			requireThat(directory(CAMPAIGN).resolve("held-out/campaign.json"), "snapshot").
				withContext(context, "context").isRegularFile();
			requireThat(directory(CAMPAIGN).resolve("worktree/CLAUDE.md"), "worktree").
				withContext(context, "context").isRegularFile();
			for (Path runRoot : runRoots)
			{
				requireThat(runRoot.resolve("claude/run-" + CAMPAIGN + "/run.json"), "run").
					withContext(context, "context").withContext(runRoot, "runRoot").isRegularFile();
			}
			requireOtherKept(context);
		}

		/**
		 * Requires every file of {@link #OTHER} and the malformed runs to remain.
		 *
		 * @param context the case that the check belongs to
		 * @throws IOException if Git fails
		 */
		void requireOtherKept(String context) throws IOException
		{
			requireThat(directory(OTHER).resolve("held-out/campaign.json"), "otherSnapshot").
				withContext(context, "context").isRegularFile();
			requireThat(directory(OTHER).resolve("worktree/CLAUDE.md"), "otherWorktree").
				withContext(context, "context").isRegularFile();
			requireThat(new Git(base).run("worktree", "list", "--porcelain"), "worktrees").
				withContext(context, "context").contains(directory(OTHER).toString());
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
	 * Real Git forks whose removal of one optimization worktree is denied, as a file system that refuses the
	 * deletion would.
	 *
	 * @param denied the worktree whose removal fails
	 */
	private record DenyingForks(Path denied) implements Forks
	{
		@Override
		public ForkView create(Path base, Path campaign, Set<Path> participants) throws IOException
		{
			return new GitForks().create(base, campaign, participants);
		}

		@Override
		public ForkView open(Path base, Path campaign) throws IOException
		{
			return new GitForks().open(base, campaign);
		}

		@Override
		public boolean exists(Path campaign)
		{
			return new GitForks().exists(campaign);
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
