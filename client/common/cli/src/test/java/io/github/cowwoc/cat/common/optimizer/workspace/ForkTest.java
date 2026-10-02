package io.github.cowwoc.cat.common.optimizer.workspace;

import static io.github.cowwoc.cat.common.optimizer.workspace.ProjectFixtures.EXECUTABLE;
import static io.github.cowwoc.cat.common.optimizer.workspace.ProjectFixtures.READ_WRITE;
import static io.github.cowwoc.cat.common.optimizer.workspace.ProjectFixtures.file;
import static io.github.cowwoc.cat.common.optimizer.workspace.ProjectFixtures.writeFile;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.git.Git;
import io.github.cowwoc.cat.common.optimizer.inventory.Authority;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry.SymbolicLink;
import io.github.cowwoc.cat.common.optimizer.inventory.FileInventory;
import io.github.cowwoc.cat.common.optimizer.inventory.Limit;
import io.github.cowwoc.cat.common.optimizer.inventory.Overlay;
import io.github.cowwoc.cat.common.optimizer.workspace.LinkReport.Entry;
import io.github.cowwoc.cat.common.optimizer.workspace.LinkReport.Status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Verifies the fork against a real Git repository that each test owns: the clean-base refusal, the local exclude, the
 * recorded participants and the detached optimization worktree at {@code HEAD}.
 */
public final class ForkTest
{
	private static final String EXCLUDE_LINE = "/.cat/work/";

	/**
	 * Creates the test.
	 */
	public ForkTest()
	{
	}

	/**
	 * Returns the kinds of change that make a base worktree dirty.
	 *
	 * @return the change kinds, one per invocation
	 */
	@DataProvider(name = "dirtyChanges")
	public Object[][] dirtyChanges()
	{
		return new Object[][]
			{
				{"staged new file"},
				{"staged modification"},
				{"staged deletion"},
				{"unstaged modification"},
				{"unstaged deletion"},
				{"untracked file"}
			};
	}

	/**
	 * Verifies that a base worktree with a change of the given kind is refused before anything is created: no
	 * campaign storage, no worktree, no exclude line.
	 *
	 * @param change the kind of change to make
	 * @throws IOException if the fixture cannot be created
	 */
	@Test(dataProvider = "dirtyChanges")
	public void refusesDirtyBase(String change) throws IOException
	{
		// Requirement: R-W2
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-fork-"));
			Path base = repository(root);
			Git git = new Git(base);
			Path campaign = root.resolve("campaign");
			switch (change)
			{
				case "staged new file" ->
				{
					writeFile(base.resolve("added.md"), "added", READ_WRITE);
					git.run("add", "added.md");
				}
				case "staged modification" ->
				{
					writeFile(base.resolve("prompt.md"), "staged edit", READ_WRITE);
					git.run("add", "prompt.md");
				}
				case "staged deletion" -> git.run("rm", "--quiet", "prompt.md");
				case "unstaged modification" -> writeFile(base.resolve("prompt.md"), "unstaged edit", READ_WRITE);
				case "unstaged deletion" -> Files.delete(base.resolve("prompt.md"));
				case "untracked file" -> writeFile(base.resolve("untracked.md"), "untracked", READ_WRITE);
				default -> throw new AssertionError("Unknown change: " + change);
			}
			String excludeBefore = Files.readString(excludeFile(base), UTF_8);

			try
			{
				Fork.create(base, campaign, Set.of());
				throw new AssertionError("A dirty base must be refused: " + change);
			}
			catch (WorkspaceException e)
			{
				requireThat(e.getMessage(), "message").contains("commit, stash or remove");
			}
			requireThat(Files.exists(campaign), "campaignExists").isFalse();
			requireThat(Files.readString(excludeFile(base), UTF_8), "exclude").isEqualTo(excludeBefore);
			requireThat(git.lines("worktree", "list"), "worktrees").size().isEqualTo(1);
		}
	}

	/**
	 * Verifies that CAT's own work directory does not make the base dirty, even when Git does not ignore it yet.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void workDirectoryDoesNotDirtyBase() throws IOException
	{
		// Requirement: R-W2
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-fork-"));
			Path base = repository(root);
			writeFile(base.resolve(".cat/work/temp/earlier.txt"), "earlier", READ_WRITE);

			Fork fork = Fork.create(base, base.resolve(".cat/work/optimizer/c1"), Set.of());

			requireThat(fork.excludeAdded(), "excludeAdded").isTrue();
		}
	}

	/**
	 * Verifies that the exclude is appended when Git does not ignore the work directory, so campaign files never make
	 * the base dirty, and that the fork reports that it added it.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void addsExcludeWhenWorkDirectoryIsNotIgnored() throws IOException
	{
		// Requirement: R-W1
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-fork-"));
			Path base = repository(root);

			Fork fork = Fork.create(base, base.resolve(".cat/work/optimizer/c1"), Set.of());

			requireThat(fork.excludeAdded(), "excludeAdded").isTrue();
			requireThat(Files.readAllLines(excludeFile(base), UTF_8).stream().filter(EXCLUDE_LINE::equals).count(),
				"excludeLines").isEqualTo(1L);
			requireThat(Files.isDirectory(base.resolve(".cat/work/optimizer/c1")), "campaignExists").isTrue();
			requireThat(new Git(base).lines("status", "--porcelain", "--untracked-files=all"), "status").isEmpty();
		}
	}

	/**
	 * Verifies that the exclude is left alone when the repository already ignores the work directory.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void leavesExcludeWhenAlreadyIgnored() throws IOException
	{
		// Requirement: R-W1
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-fork-"));
			Path base = repository(root);
			Git git = new Git(base);
			writeFile(base.resolve(".gitignore"), "ignored.md\n.cat/work/\n", READ_WRITE);
			git.run("add", ".gitignore");
			git.run("commit", "--quiet", "-m", "ignore work directory");
			String excludeBefore = Files.readString(excludeFile(base), UTF_8);

			Fork fork = Fork.create(base, base.resolve(".cat/work/optimizer/c1"), Set.of());

			requireThat(fork.excludeAdded(), "excludeAdded").isFalse();
			requireThat(Files.readString(excludeFile(base), UTF_8), "exclude").isEqualTo(excludeBefore);
		}
	}

	/**
	 * Verifies that a second campaign in the same repository does not append the exclude again.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void appendsExcludeOnlyOnce() throws IOException
	{
		// Requirement: R-W1
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-fork-"));
			Path base = repository(root);

			Fork first = Fork.create(base, base.resolve(".cat/work/optimizer/c1"), Set.of());
			Fork second = Fork.create(base, base.resolve(".cat/work/optimizer/c2"), Set.of());

			requireThat(first.excludeAdded(), "firstAdded").isTrue();
			requireThat(second.excludeAdded(), "secondAdded").isFalse();
			requireThat(Files.readAllLines(excludeFile(base), UTF_8).stream().filter(EXCLUDE_LINE::equals).count(),
				"excludeLines").isEqualTo(1L);
			requireThat(base.resolve(".cat/work/optimizer/c2/worktree"), "secondWorktree").isDirectory();
		}
	}

	/**
	 * Verifies that the optimization worktree is a detached checkout at the base's {@code HEAD}, and that it holds the
	 * recorded ignored and external participants as well as the tracked files.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void createsDetachedWorktreeAtHead() throws IOException
	{
		// Requirement: R-W2
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-fork-"));
			Path base = repository(root);
			Git git = new Git(base);
			Path external = writeFile(root.resolve("external/shared.md"), "shared", READ_WRITE);

			Fork fork = Fork.create(base, base.resolve(".cat/work/optimizer/c1"), Set.of(Path.of("ignored.md"), external));

			Path worktreeDirectory = base.resolve(".cat/work/optimizer/c1/worktree");
			Git worktree = new Git(worktreeDirectory);
			String head = git.lines("rev-parse", "HEAD").getFirst();
			requireThat(worktree.lines("rev-parse", "HEAD").getFirst(), "worktreeHead").isEqualTo(head);
			requireThat(worktree.lines("rev-parse", "--abbrev-ref", "HEAD").getFirst(), "branch").isEqualTo("HEAD");
			requireThat(Files.readString(worktreeDirectory.resolve("prompt.md"), UTF_8), "trackedFile").
				isEqualTo("prompt");
			requireThat(Files.isSymbolicLink(worktreeDirectory.resolve("alias.md")), "trackedLink").isTrue();
			requireThat(Files.readString(worktreeDirectory.resolve("ignored.md"), UTF_8), "ignoredParticipant").
				isEqualTo("ignored");
			requireThat(worktree.lines("status", "--porcelain"), "worktreeStatus").isEmpty();
			requireThat(git.lines("worktree", "list"), "worktrees").size().isEqualTo(2);
			requireThat(fork.workspace().scanWorktree().entries(), "worktreeEntries").
				isEqualTo(fork.inventory().entries());
		}
	}

	/**
	 * Verifies that participants added after creation are recorded and written into the worktree, that held paths
	 * are left alone, and that a missing path is refused by name.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void addsParticipantsAfterCreation() throws IOException
	{
		// Requirement: R-W2
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-fork-"));
			Path base = repository(root);
			Path external = writeFile(root.resolve("external/shared.md"), "shared", READ_WRITE);
			Path campaign = base.resolve(".cat/work/optimizer/c1");
			Forks forks = new GitForks();
			ForkView fork = forks.create(base, campaign, Set.of());

			ForkView extended = fork.addParticipants(base, Set.of(Path.of("ignored.md"), Path.of("prompt.md"),
				external));

			FileInventory inventory = extended.inventory();
			requireThat(inventory.entries().get(Path.of("ignored.md")), "ignored").isEqualTo(file("ignored"));
			requireThat(inventory.entries().containsKey(external), "external").isTrue();
			requireThat(Files.readString(campaign.resolve("worktree/ignored.md"), UTF_8), "worktreeFile").
				isEqualTo("ignored");
			requireThat(extended.workspace().scanWorktree().entries(), "worktreeEntries").
				isEqualTo(inventory.entries());
			requireThat(forks.open(base, campaign).inventory(), "reopened").isEqualTo(inventory);
			requireThat(forks.exists(campaign), "exists").isTrue();
			requireThat(forks.exists(campaign.resolveSibling("c2")), "otherExists").isFalse();
			try
			{
				extended.addParticipants(base, Set.of(Path.of("missing.md")));
				throw new AssertionError("A missing participant must be refused.");
			}
			catch (WorkspaceException e)
			{
				requireThat(e.getMessage(), "message").contains("missing.md");
			}
		}
	}

	/**
	 * Verifies that the fork records the tracked files, modes and links, and copies the ignored and external
	 * participants into campaign storage.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void recordsTrackedAndParticipantFiles() throws IOException
	{
		// Requirement: R-W2
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-fork-"));
			Path base = repository(root);
			Path external = writeFile(root.resolve("external/shared.md"), "shared", READ_WRITE);

			Fork fork = Fork.create(base, base.resolve(".cat/work/optimizer/c1"), Set.of(Path.of("ignored.md"), external));

			FileInventory inventory = fork.inventory();
			requireThat(inventory.root(), "root").isEqualTo(base);
			requireThat(inventory.entries().get(Path.of("prompt.md")), "prompt").isEqualTo(file("prompt"));
			requireThat(inventory.entries().get(Path.of("run.sh")), "executable").
				isEqualTo(file("#!/bin/sh\n", EXECUTABLE));
			requireThat(inventory.entries().get(Path.of("alias.md")), "link").
				isEqualTo(new SymbolicLink(Path.of("prompt.md")));
			requireThat(inventory.entries().get(Path.of("ignored.md")), "ignored").isEqualTo(file("ignored"));
			requireThat(inventory.entries().get(external), "external").isEqualTo(file("shared"));
			requireThat(inventory.entries().keySet().stream().filter(path -> path.startsWith(".cat")).toList(),
				"campaignFiles").isEmpty();
			requireThat(Fork.open(base, base.resolve(".cat/work/optimizer/c1")).inventory(), "reopened").
				isEqualTo(inventory);
		}
	}

	/**
	 * Verifies that a participant that does not exist stops the fork before the worktree is added.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void missingParticipantCreatesNoWorktree() throws IOException
	{
		// Requirement: R-W2
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-fork-"));
			Path base = repository(root);
			Path campaign = base.resolve(".cat/work/optimizer/c1");

			try
			{
				Fork.create(base, campaign, Set.of(Path.of("missing.md")));
				throw new AssertionError("A missing participant must stop the fork");
			}
			catch (WorkspaceException e)
			{
				requireThat(e.getMessage(), "message").contains("missing.md");
			}
			requireThat(Files.exists(campaign.resolve("worktree")), "worktreeExists").isFalse();
			requireThat(Files.exists(campaign.resolve("fork")), "forkExists").isFalse();
			requireThat(new Git(base).lines("worktree", "list"), "worktrees").size().isEqualTo(1);
		}
	}

	/**
	 * Verifies that a failure while the first candidate is written into the new worktree removes the worktree
	 * registration, the worktree and the fork storage, keeps the exclude line, and lets a retry succeed. The failure
	 * comes from a regular file where the mirror of external files must be a directory.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void undoesForkWhenMaterializeFails() throws IOException
	{
		// Requirement: R-W2
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-fork-"));
			Path base = repository(root);
			Git git = new Git(base);
			Path external = writeFile(root.resolve("external/shared.md"), "shared", READ_WRITE);
			Path campaign = base.resolve(".cat/work/optimizer/c1");
			Path blocker = writeFile(campaign.resolve("worktree-external"), "not a directory", READ_WRITE);

			try
			{
				Fork.create(base, campaign, Set.of(external));
				throw new AssertionError("A failure while materializing must fail the fork");
			}
			catch (WorkspaceException e)
			{
				throw new AssertionError("The failure must come from the file system, not a refusal", e);
			}
			catch (IOException _)
			{
				// Expected
			}
			requireThat(git.lines("worktree", "list"), "worktrees").size().isEqualTo(1);
			requireThat(Files.exists(campaign.resolve("worktree")), "worktreeExists").isFalse();
			requireThat(Files.exists(campaign.resolve("fork")), "forkExists").isFalse();
			requireThat(Files.readAllLines(excludeFile(base), UTF_8), "exclude").contains(EXCLUDE_LINE);
			Files.delete(blocker);

			Fork.create(base, campaign, Set.of(external));

			requireThat(campaign.resolve("worktree"), "worktree").isDirectory();
			requireThat(git.lines("worktree", "list"), "retryWorktrees").size().isEqualTo(2);
		}
	}

	/**
	 * Verifies that a failure to add the worktree removes the fork storage, leaves a directory that CAT did not create
	 * alone, and lets a retry succeed.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void undoesForkWhenWorktreeCannotBeAdded() throws IOException
	{
		// Requirement: R-W2
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-fork-"));
			Path base = repository(root);
			Git git = new Git(base);
			Path campaign = base.resolve(".cat/work/optimizer/c1");
			Path foreign = writeFile(campaign.resolve("worktree/foreign.txt"), "not CAT's", READ_WRITE);

			try
			{
				Fork.create(base, campaign, Set.of());
				throw new AssertionError("A worktree directory that is not empty must fail the fork");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").contains("worktree");
			}
			requireThat(git.lines("worktree", "list"), "worktrees").size().isEqualTo(1);
			requireThat(Files.exists(campaign.resolve("fork")), "forkExists").isFalse();
			requireThat(Files.readString(foreign, UTF_8), "foreign").isEqualTo("not CAT's");
			Files.delete(foreign);

			Fork.create(base, campaign, Set.of());

			requireThat(campaign.resolve("worktree/prompt.md"), "prompt").isRegularFile();
		}
	}

	/**
	 * Verifies that a Git failure while checking whether the work directory is ignored propagates, instead of being
	 * taken for "not ignored", and that nothing is created or appended. Git cannot check a path beyond a symbolic
	 * link, so a tracked link at {@code .cat} makes the check fail with exit status 128, which the failure names.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void propagatesGitFailureOfIgnoreCheck() throws IOException
	{
		// Requirement: R-W1
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-fork-"));
			Path base = repository(root);
			Git git = new Git(base);
			Files.createSymbolicLink(base.resolve(".cat"), Path.of("elsewhere"));
			git.run("add", ".cat");
			git.run("commit", "--quiet", "-m", "link the CAT directory");
			String excludeBefore = Files.readString(excludeFile(base), UTF_8);
			Path campaign = root.resolve("campaign");

			try
			{
				Fork.create(base, campaign, Set.of());
				throw new AssertionError("A Git failure of the ignore check must propagate");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").contains("check-ignore").contains("status 128");
			}
			requireThat(Files.exists(campaign), "campaignExists").isFalse();
			requireThat(Files.readString(excludeFile(base), UTF_8), "exclude").isEqualTo(excludeBefore);
		}
	}

	/**
	 * Verifies that a tracked link to an existing file outside the project and a tracked link to an existing directory
	 * outside the project are staged with their targets' content after the fork is created, without the targets being
	 * named as participants.
	 *
	 * @throws IOException if the fixture cannot be created or staged
	 */
	@Test
	public void stagesTrackedLinksToExternalTargets() throws IOException
	{
		// Requirement: R-W2, R-W5
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-fork-"));
			Path base = repository(root);
			Path rules = writeFile(root.resolve("outside/reply-rules.md"), "reply rules", READ_WRITE);
			Path kit = root.resolve("outside/kit");
			writeFile(kit.resolve("guide.md"), "guide", READ_WRITE);
			commitLinks(base, Map.of("docs/reply-rules.md", rules, "kit", kit));

			Fork fork = Fork.create(base, base.resolve(".cat/work/optimizer/c1"), Set.of());
			Path staged = fork.workspace().stage(Overlay.of(fork.inventory()));

			requireThat(Files.readString(staged.resolve("docs/reply-rules.md"), UTF_8), "fileLink").
				isEqualTo("reply rules");
			requireThat(Files.readString(staged.resolve("kit/guide.md"), UTF_8), "directoryLink").isEqualTo("guide");
			requireThat(fork.inventory().entries().get(rules), "recordedFile").isEqualTo(file("reply rules"));
		}
	}

	/**
	 * Verifies that the fork follows links that leave the project through recorded external targets: an external
	 * file that is itself an absolute link, and an external directory holding a relative link that leaves it.
	 *
	 * @throws IOException if the fixture cannot be created or staged
	 */
	@Test
	public void stagesExternalTargetsReachedTransitively() throws IOException
	{
		// Requirement: R-W2, R-W5
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-fork-"));
			Path base = repository(root);
			Path outside = root.resolve("outside");
			Path kit = outside.resolve("kit");
			writeFile(kit.resolve("guide.md"), "guide", READ_WRITE);
			writeFile(outside.resolve("common/shared.md"), "shared", READ_WRITE);
			Files.createSymbolicLink(kit.resolve("shared.md"), Path.of("../common/shared.md"));
			Path last = writeFile(outside.resolve("final.md"), "final", READ_WRITE);
			Path alias = Files.createSymbolicLink(outside.resolve("alias.md"), last);
			commitLinks(base, Map.of("kit", kit, "chain.md", alias));

			Fork fork = Fork.create(base, base.resolve(".cat/work/optimizer/c1"), Set.of());
			Path staged = fork.workspace().stage(Overlay.of(fork.inventory()));

			requireThat(Files.readString(staged.resolve("kit/shared.md"), UTF_8), "relativeLinkLeavingTarget").
				isEqualTo("shared");
			requireThat(Files.readString(staged.resolve("chain.md"), UTF_8), "externalLinkChain").isEqualTo("final");
		}
	}

	/**
	 * Verifies that the link report of a newly created fork lists a tracked link to an external file with its
	 * target, outside the project, resolved and read-only.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void reportsExternalLinkOfNewFork() throws IOException
	{
		// Requirement: R-W6
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-fork-"));
			Path base = repository(root);
			Path rules = writeFile(root.resolve("outside/reply-rules.md"), "reply rules", READ_WRITE);
			commitLinks(base, Map.of("docs/reply-rules.md", rules));

			Fork fork = Fork.create(base, base.resolve(".cat/work/optimizer/c1"), Set.of());
			LinkReport report = LinkReport.of(fork.inventory(),
				new Authority(Set.of(Path.of("prompt.md")), Set.of(), Set.of()));

			requireThat(report.entries(), "entries").contains(new Entry(Path.of("docs/reply-rules.md"),
				Optional.of(rules), Status.RESOLVED, Optional.of(Limit.EXTERNAL_TARGET_READ_ONLY)));
			requireThat(report.blocking(), "blocking").isEmpty();
		}
	}

	/**
	 * Verifies that links the fork cannot record still block staging as missing targets: a link to a file that does
	 * not exist, a link to a directory that contains the project, and a link to an external directory that cannot be
	 * read. The unreadable directory is empty so that the link is unstageable whether or not the process can read it.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void unrecordableExternalTargetsStillBlock() throws IOException
	{
		// Requirement: R-W5
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-fork-"));
			Path base = repository(root);
			Path missing = root.resolve("outside/missing.md");
			Path sealed = Files.createDirectories(root.resolve("outside/sealed"));
			commitLinks(base, Map.of("missing.md", missing, "up", root, "sealed", sealed));
			Files.setPosixFilePermissions(sealed, Set.of());
			try
			{
				Fork fork = Fork.create(base, base.resolve(".cat/work/optimizer/c1"), Set.of());
				try
				{
					fork.workspace().stage(Overlay.of(fork.inventory()));
					throw new AssertionError("Links to unrecordable targets must block staging");
				}
				catch (WorkspaceException e)
				{
					requireThat(e.getMessage(), "message").
						contains("missing.md -> " + missing + ": missing target").
						contains("up -> " + root + ": missing target").
						contains("sealed -> " + sealed + ": missing target");
				}
			}
			finally
			{
				Files.setPosixFilePermissions(sealed, EXECUTABLE);
			}
		}
	}

	/**
	 * Verifies that a link to an external directory that cannot be listed leaves an external participant below that
	 * directory recorded. Without the permission to list it, the directory's own copy fails, while the participant is
	 * still reachable by name.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void unreadableTargetKeepsParticipantBelowIt() throws IOException
	{
		// Requirement: R-W2
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-fork-"));
			Path base = repository(root);
			Path hidden = root.resolve("outside/hidden");
			Path participant = writeFile(hidden.resolve("shared.md"), "shared", READ_WRITE);
			commitLinks(base, Map.of("hidden", hidden));
			Files.setPosixFilePermissions(hidden, PosixFilePermissions.fromString("--x--x--x"));
			try
			{
				Fork fork = Fork.create(base, base.resolve(".cat/work/optimizer/c1"), Set.of(participant));

				requireThat(fork.inventory().entries().get(participant), "participant").isEqualTo(file("shared"));
			}
			finally
			{
				Files.setPosixFilePermissions(hidden, EXECUTABLE);
			}
		}
	}

	/**
	 * Commits absolute links in a repository.
	 *
	 * @param base the repository directory
	 * @param links each link's path relative to {@code base}, and its absolute destination
	 * @throws IOException if a link cannot be created or committed
	 */
	private static void commitLinks(Path base, Map<String, Path> links) throws IOException
	{
		for (Map.Entry<String, Path> link : links.entrySet())
		{
			Path location = base.resolve(link.getKey());
			Files.createDirectories(location.getParent());
			Files.createSymbolicLink(location, link.getValue());
		}
		Git git = new Git(base);
		git.run("add", "--all");
		git.run("commit", "--quiet", "-m", "link outside the project");
	}

	/**
	 * Returns the repository's local exclude file.
	 *
	 * @param base the repository directory
	 * @return the exclude file
	 */
	private static Path excludeFile(Path base)
	{
		return base.resolve(".git/info/exclude");
	}

	/**
	 * Creates a repository with one commit that holds ordinary files, an executable and a link, and an ignored file.
	 * Its configuration owns every setting that a global configuration could change.
	 *
	 * @param root the directory that owns the fixture
	 * @return the repository directory
	 * @throws IOException if the repository cannot be created
	 */
	private static Path repository(Path root) throws IOException
	{
		Path base = Files.createDirectories(root.resolve("repository"));
		Path noExcludes = writeFile(root.resolve("no-excludes"), "", READ_WRITE);
		Git git = new Git(base);
		git.run("init", "--quiet");
		git.run("config", "user.name", "Test");
		git.run("config", "user.email", "test@example.com");
		git.run("config", "commit.gpgsign", "false");
		git.run("config", "core.excludesFile", noExcludes.toString());
		writeFile(base.resolve(".gitignore"), "ignored.md\n", READ_WRITE);
		writeFile(base.resolve("prompt.md"), "prompt", READ_WRITE);
		writeFile(base.resolve("run.sh"), "#!/bin/sh\n", EXECUTABLE);
		Files.createSymbolicLink(base.resolve("alias.md"), Path.of("prompt.md"));
		git.run("add", "--all");
		git.run("commit", "--quiet", "-m", "initial");
		writeFile(base.resolve("ignored.md"), "ignored", READ_WRITE);
		List<String> status = git.lines("status", "--porcelain");
		requireThat(status, "status").isEmpty();
		return base;
	}
}
