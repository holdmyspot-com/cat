package com.holdmyspot.cat.common.optimizer.workspace.test;

import com.holdmyspot.cat.common.fakes.test.Yielding;
import static com.holdmyspot.cat.common.optimizer.workspace.test.ProjectFixtures.EXECUTABLE;
import static com.holdmyspot.cat.common.optimizer.workspace.test.ProjectFixtures.READ_WRITE;
import static com.holdmyspot.cat.common.optimizer.workspace.test.ProjectFixtures.file;
import static com.holdmyspot.cat.common.optimizer.workspace.test.ProjectFixtures.writeFile;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.git.Git;
import com.holdmyspot.cat.common.optimizer.inventory.FileEntry.SymbolicLink;
import com.holdmyspot.cat.common.optimizer.inventory.FileInventory;
import com.holdmyspot.cat.common.optimizer.workspace.Fork;
import com.holdmyspot.cat.common.optimizer.workspace.ForkTree;
import com.holdmyspot.cat.common.optimizer.workspace.ForkView;
import com.holdmyspot.cat.common.optimizer.workspace.Forks;
import com.holdmyspot.cat.common.optimizer.workspace.GitForks;
import com.holdmyspot.cat.common.optimizer.workspace.WorkspaceException;
import com.holdmyspot.cat.common.test.FileTrees;
import com.holdmyspot.cat.common.test.ProgramFixture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Verifies the fork. Creating one runs against a real Git repository that each test owns: the clean-base refusal, the
 * local exclude, the recorded participants and the detached optimization worktree at {@code HEAD}. Changing a fork's
 * participants reads and writes only campaign storage, so those tests store a fork without Git.
 */
public final class ForkTest
{
	private static final String EXCLUDE_LINE = "/.cat/sessions/";
	/**
	 * The directory, relative to the repository, that holds the campaigns of one session.
	 */
	private static final String CAMPAIGNS = ".cat/sessions/2c4e6a8b-1d3f-4a5c-9e7b-0f2d4c6e8a1b/optimizer";
	/**
	 * The directory that holds the repository that each test copies, and the empty global exclude file that the
	 * repository's configuration names.
	 */
	private final Path template;

	/**
	 * Creates the test and the empty directory that will hold the repository that each test copies.
	 *
	 * @throws IOException if the directory cannot be created
	 */
	public ForkTest() throws IOException
	{
		template = Files.createTempDirectory("cat-fork-template-");
	}

	/**
	 * Creates the repository that each test copies. Copying it starts no Git process, and each Git process takes tens
	 * of milliseconds on Windows.
	 *
	 * @throws IOException if the repository cannot be created
	 */
	@BeforeClass
	public void createTemplate() throws IOException
	{
		Path base = Files.createDirectories(template.resolve("repository"));
		Path noExcludes = writeFile(template.resolve("no-excludes"), "", READ_WRITE);
		Git git = new Git(base);
		git.run("init", "--quiet");
		// The sample hooks are disabled, so leaving them out only saves copying them
		TemporaryDirectories.deleteRecursively(base.resolve(".git/hooks"));
		// A quoted configuration value escapes each backslash and double quote
		String excludes = noExcludes.toString().replace("\\", "\\\\").replace("\"", "\\\"");
		Files.writeString(base.resolve(".git/config"), """
			[user]
				name = Test
				email = test@example.com
			[commit]
				gpgsign = false
			[core]
				excludesFile = "%s"
				autocrlf = false
			""".formatted(excludes), UTF_8, StandardOpenOption.APPEND);
		writeFile(base.resolve(".gitignore"), "ignored.md\n", READ_WRITE);
		writeFile(base.resolve("prompt.md"), "prompt", READ_WRITE);
		// Windows records a file as executable only by its name, and Linux only by the mode set here
		writeFile(base.resolve(ProgramFixture.name("run")), ProgramFixture.TEXT, EXECUTABLE);
		Files.createSymbolicLink(base.resolve("alias.md"), Path.of("prompt.md"));
		git.run("add", "--all");
		git.run("commit", "--quiet", "-m", "initial");
		writeFile(base.resolve("ignored.md"), "ignored", READ_WRITE);
		List<String> status = git.lines("status", "--porcelain");
		requireThat(status, "status").isEmpty();
	}

	/**
	 * Deletes the repository that each test copies.
	 *
	 * @throws IOException if the repository cannot be deleted
	 */
	@AfterClass(alwaysRun = true)
	public void deleteTemplate() throws IOException
	{
		TemporaryDirectories.deleteRecursively(template);
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
				Fork.create(base, campaign, Set.of(), Yielding.fileReplacer());
				throw new AssertionError("A dirty base must be refused: " + change);
			}
			catch (WorkspaceException e)
			{
				requireThat(e.getMessage(), "message").contains("commit, stash or remove");
			}
			requireThat(Files.exists(campaign), "campaignExists").isFalse();
			requireThat(Files.readString(excludeFile(base), UTF_8), "exclude").isEqualTo(excludeBefore);
		}
	}

	/**
	 * Verifies that CAT's session directories do not make the base dirty, even when Git does not ignore them yet.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void sessionDirectoriesDoNotDirtyBase() throws IOException
	{
		// Requirement: R-W2
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-fork-"));
			Path base = repository(root);
			writeFile(base.resolve(".cat/sessions/temp/earlier.txt"), "earlier", READ_WRITE);

			Fork fork = Fork.create(base, base.resolve(CAMPAIGNS + "/c1"), Set.of(), Yielding.fileReplacer());

			requireThat(fork.excludeAdded(), "excludeAdded").isTrue();
		}
	}

	/**
	 * Verifies that removing the optimization worktree of a campaign in a session's CAT session directory deletes the
	 * worktree and leaves the repository no record of it.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void removesSessionCampaignWorktree() throws IOException
	{
		// Requirement: R-W9
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-fork-"));
			Path base = repository(root);
			Path campaign = base.resolve(CAMPAIGNS + "/c1");
			Fork.create(base, campaign, Set.of(), Yielding.fileReplacer());
			Git git = new Git(base);
			requireThat(worktreeCount(git), "worktreesBefore").isEqualTo(2L);

			Fork.remove(base, campaign);

			requireThat(Files.exists(campaign.resolve("worktree")), "worktreeExists").isFalse();
			requireThat(worktreeCount(git), "worktreesAfter").isEqualTo(1L);
		}
	}

	/**
	 * Returns how many worktrees a repository records, its main worktree included.
	 *
	 * @param git the repository's Git
	 * @return the count
	 * @throws IOException if Git fails
	 */
	private static long worktreeCount(Git git) throws IOException
	{
		return git.lines("worktree", "list", "--porcelain").stream().filter(line -> line.startsWith("worktree ")).
			count();
	}

	/**
	 * Verifies that the exclude is appended when Git does not ignore CAT's session directories, so campaign files never
	 * make the base dirty, and that the fork reports that it added it.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void addsExcludeWhenSessionsAreNotIgnored() throws IOException
	{
		// Requirement: R-W1
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-fork-"));
			Path base = repository(root);

			Fork fork = Fork.create(base, base.resolve(CAMPAIGNS + "/c1"), Set.of(), Yielding.fileReplacer());

			requireThat(fork.excludeAdded(), "excludeAdded").isTrue();
			requireThat(Files.readAllLines(excludeFile(base), UTF_8).stream().filter(EXCLUDE_LINE::equals).count(),
				"excludeLines").isEqualTo(1L);
			requireThat(Files.isDirectory(base.resolve(CAMPAIGNS + "/c1")), "campaignExists").isTrue();
			requireThat(new Git(base).lines("status", "--porcelain", "--untracked-files=all"), "status").isEmpty();
		}
	}

	/**
	 * Verifies that the exclude is left alone when Git already ignores CAT's session directories through a pattern other
	 * than the line that CAT appends.
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
			Files.writeString(excludeFile(base), ".cat/sessions\n", UTF_8, StandardOpenOption.APPEND);
			String excludeBefore = Files.readString(excludeFile(base), UTF_8);

			Fork fork = Fork.create(base, base.resolve(CAMPAIGNS + "/c1"), Set.of(), Yielding.fileReplacer());

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

			Fork first = Fork.create(base, base.resolve(CAMPAIGNS + "/c1"), Set.of(), Yielding.fileReplacer());
			Fork second = Fork.create(base, base.resolve(CAMPAIGNS + "/c2"), Set.of(), Yielding.fileReplacer());

			requireThat(first.excludeAdded(), "firstAdded").isTrue();
			requireThat(second.excludeAdded(), "secondAdded").isFalse();
			requireThat(Files.readAllLines(excludeFile(base), UTF_8).stream().filter(EXCLUDE_LINE::equals).count(),
				"excludeLines").isEqualTo(1L);
			requireThat(base.resolve(CAMPAIGNS + "/c2/worktree"), "secondWorktree").isDirectory();
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

			Fork fork = Fork.create(base, base.resolve(CAMPAIGNS + "/c1"), Set.of(Path.of("ignored.md"), external),
				Yielding.fileReplacer());

			Path worktreeDirectory = base.resolve(CAMPAIGNS + "/c1/worktree");
			// The base is listed first, with its commit on the line that starts with "HEAD "
			List<String> worktrees = git.lines("worktree", "list", "--porcelain");
			requireThat(worktrees.stream().filter(line -> line.startsWith("worktree ")).count(), "worktrees").
				isEqualTo(2L);
			String head = worktrees.stream().filter(line -> line.startsWith("HEAD ")).findFirst().orElseThrow().
				substring("HEAD ".length());
			// The branch header names the checked-out commit and the branch, and no line follows for a clean checkout
			requireThat(new Git(worktreeDirectory).lines("status", "--porcelain=v2", "--branch"), "worktreeStatus").
				isEqualTo(List.of("# branch.oid " + head, "# branch.head (detached)"));
			requireThat(Files.readString(worktreeDirectory.resolve("prompt.md"), UTF_8), "trackedFile").
				isEqualTo("prompt");
			requireThat(Files.isSymbolicLink(worktreeDirectory.resolve("alias.md")), "trackedLink").isTrue();
			requireThat(Files.readString(worktreeDirectory.resolve("ignored.md"), UTF_8), "ignoredParticipant").
				isEqualTo("ignored");
			requireThat(fork.workspace().scanWorktree().entries(), "worktreeEntries").
				isEqualTo(fork.inventory().entries());
		}
	}

	/**
	 * Verifies that a participant that a later selection omits leaves the fork's files, the worktree and the reopened
	 * fork, that the files the fork was created with stay, and that selecting it again restores its recorded copy
	 * rather than the live file.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void leavesOutDeselectedParticipants() throws IOException
	{
		// Requirement: R-W2
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-fork-"));
			Path base = project(root);
			Path campaign = root.resolve("campaign");
			storeFork(base, campaign);
			Forks forks = new GitForks(Yielding.fileReplacer());
			ForkView fork = forks.open(base, campaign).selectParticipants(base, Set.of(Path.of("ignored.md")));

			ForkView narrowed = fork.selectParticipants(base, Set.of());

			requireThat(narrowed.inventory().entries().keySet(), "narrowed").doesNotContain(Path.of("ignored.md")).
				contains(Path.of("prompt.md"));
			requireThat(Files.exists(campaign.resolve("worktree/ignored.md")), "worktreeFile").isFalse();
			requireThat(forks.open(base, campaign).inventory(), "reopened").isEqualTo(narrowed.inventory());

			writeFile(base.resolve("ignored.md"), "changed", READ_WRITE);
			ForkView restored = narrowed.selectParticipants(base, Set.of(Path.of("ignored.md")));
			requireThat(restored.inventory().entries().get(Path.of("ignored.md")), "restored").
				isEqualTo(file("ignored"));
		}
	}

	/**
	 * Verifies that the targets outside the project that a participant's link reaches leave the fork's files with the
	 * participant, unless a participant that stays selected reaches them too, and that selecting the participant again
	 * restores their recorded copies rather than the live files.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void leavesOutTargetsOfDeselectedParticipants() throws IOException
	{
		// Requirement: R-W2
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-fork-"));
			Path base = project(root);
			Path shared = writeFile(root.resolve("external/shared.md"), "shared", READ_WRITE);
			Path only = writeFile(root.resolve("external/only.md"), "only", READ_WRITE);
			Path alias = Files.createSymbolicLink(Files.createDirectories(root.resolve("elsewhere")).
				resolve("alias.md"), shared);
			Files.delete(base.resolve("ignored.md"));
			Files.createSymbolicLink(base.resolve("ignored.md"), only);
			Path campaign = root.resolve("campaign");
			storeFork(base, campaign);
			Forks forks = new GitForks(Yielding.fileReplacer());
			ForkView fork = forks.open(base, campaign).selectParticipants(base, Set.of(Path.of("ignored.md"), alias));
			requireThat(fork.inventory().entries().keySet(), "selected").contains(only).contains(shared);

			ForkView narrowed = fork.selectParticipants(base, Set.of(alias));

			requireThat(narrowed.inventory().entries().keySet(), "narrowed").doesNotContain(only).contains(shared);
			requireThat(forks.open(base, campaign).inventory(), "reopened").isEqualTo(narrowed.inventory());

			writeFile(only, "changed", READ_WRITE);
			ForkView restored = narrowed.selectParticipants(base, Set.of(Path.of("ignored.md"), alias));
			requireThat(restored.inventory().entries().get(only), "restored").isEqualTo(file("only"));
		}
	}

	/**
	 * Verifies that extending a fork records a participant without changing the campaign's current fork or its
	 * worktree, that a reopened fork includes the recorded participant but not one that was never recorded, and that
	 * adopting the extension makes it current while the earlier fork stays available as a version.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void extensionBecomesCurrentWhenAdopted() throws IOException
	{
		// Requirement: R-W2
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-fork-"));
			Path base = project(root);
			Path campaign = root.resolve("campaign");
			storeFork(base, campaign);
			Forks forks = new GitForks(Yielding.fileReplacer());
			FileInventory earlier = forks.open(base, campaign).inventory();

			ForkView extended = forks.open(base, campaign).extend(base, Set.of(Path.of("ignored.md")));

			requireThat(extended.inventory().entries().get(Path.of("ignored.md")), "ignored").
				isEqualTo(file("ignored"));
			requireThat(forks.open(base, campaign).inventory(), "current").isEqualTo(earlier);
			requireThat(Files.exists(campaign.resolve("worktree/ignored.md")), "worktreeFile").isFalse();
			writeFile(base.resolve("unrecorded.md"), "unrecorded", READ_WRITE);
			ForkView including = forks.open(base, campaign).including(Set.of(Path.of("ignored.md"),
				Path.of("unrecorded.md")));
			requireThat(including.inventory(), "including").isEqualTo(extended.inventory());

			extended.adopt();

			ForkView reopened = forks.open(base, campaign);
			requireThat(reopened.inventory(), "adopted").isEqualTo(extended.inventory());
			requireThat(reopened.version(earlier.identity()), "earlier").isEqualTo(Optional.of(earlier));
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
			Path base = project(root);
			Path external = writeFile(root.resolve("external/shared.md"), "shared", READ_WRITE);
			Path campaign = root.resolve("campaign");
			storeFork(base, campaign);
			Forks forks = new GitForks(Yielding.fileReplacer());
			ForkView fork = forks.open(base, campaign);

			ForkView extended = fork.selectParticipants(base, Set.of(Path.of("ignored.md"), Path.of("prompt.md"),
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
				extended.selectParticipants(base, Set.of(Path.of("missing.md")));
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

			Fork fork = Fork.create(base, base.resolve(CAMPAIGNS + "/c1"), Set.of(Path.of("ignored.md"), external),
				Yielding.fileReplacer());

			FileInventory inventory = fork.inventory();
			requireThat(inventory.root(), "root").isEqualTo(base);
			requireThat(inventory.entries().get(Path.of("prompt.md")), "prompt").isEqualTo(file("prompt"));
			requireThat(inventory.entries().get(Path.of(ProgramFixture.name("run"))), "executable").
				isEqualTo(file(ProgramFixture.TEXT, EXECUTABLE));
			requireThat(inventory.entries().get(Path.of("alias.md")), "link").
				isEqualTo(new SymbolicLink(Path.of("prompt.md")));
			requireThat(inventory.entries().get(Path.of("ignored.md")), "ignored").isEqualTo(file("ignored"));
			requireThat(inventory.entries().get(external), "external").isEqualTo(file("shared"));
			requireThat(inventory.entries().keySet().stream().filter(path -> path.startsWith(".cat")).toList(),
				"campaignFiles").isEmpty();
			requireThat(Fork.open(base, base.resolve(CAMPAIGNS + "/c1"), Yielding.fileReplacer()).inventory(), "reopened").
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
			Path campaign = base.resolve(CAMPAIGNS + "/c1");

			try
			{
				Fork.create(base, campaign, Set.of(Path.of("missing.md")), Yielding.fileReplacer());
				throw new AssertionError("A missing participant must stop the fork");
			}
			catch (WorkspaceException e)
			{
				requireThat(e.getMessage(), "message").contains("missing.md");
			}
			requireThat(Files.exists(campaign.resolve("worktree")), "worktreeExists").isFalse();
			requireThat(Files.exists(campaign.resolve("fork")), "forkExists").isFalse();
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
			Path campaign = base.resolve(CAMPAIGNS + "/c1");
			Path blocker = writeFile(campaign.resolve("worktree-external"), "not a directory", READ_WRITE);

			try
			{
				Fork.create(base, campaign, Set.of(external), Yielding.fileReplacer());
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

			Fork.create(base, campaign, Set.of(external), Yielding.fileReplacer());

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
			Path campaign = base.resolve(CAMPAIGNS + "/c1");
			Path foreign = writeFile(campaign.resolve("worktree/foreign.txt"), "not CAT's", READ_WRITE);

			try
			{
				Fork.create(base, campaign, Set.of(), Yielding.fileReplacer());
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

			Fork.create(base, campaign, Set.of(), Yielding.fileReplacer());

			requireThat(campaign.resolve("worktree/prompt.md"), "prompt").isRegularFile();
		}
	}

	/**
	 * Verifies that a Git failure while checking whether CAT's session directories are ignored propagates, instead of
	 * being taken for "not ignored", and that nothing is created or appended. Git cannot check a path beyond a symbolic
	 * link, so an ignored link at {@code .cat} makes the check fail with exit status 128, which the failure names.
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
			Files.createSymbolicLink(base.resolve(".cat"), Path.of("elsewhere"));
			// Ignoring the link keeps the base clean, so the fork reaches the ignore check
			Files.writeString(excludeFile(base), "/.cat\n", UTF_8, StandardOpenOption.APPEND);
			String excludeBefore = Files.readString(excludeFile(base), UTF_8);
			Path campaign = root.resolve("campaign");

			try
			{
				Fork.create(base, campaign, Set.of(), Yielding.fileReplacer());
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
	 * Verifies that the tracked links are the files that the Git index records as symbolic links, without its
	 * regular or executable files, and that a directory outside a Git work tree has none.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void listsTrackedLinks() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-fork-"));
			Path base = repository(root);
			Path unmanaged = project(root);

			requireThat(Fork.trackedLinks(base), "trackedLinks").isEqualTo(Set.of(Path.of("alias.md")));
			requireThat(Fork.trackedLinks(unmanaged), "unmanagedLinks").isEmpty();
		}
	}

	/**
	 * Creates a project directory that holds {@code prompt.md} and {@code ignored.md}, without Git.
	 *
	 * @param root the directory that owns the fixture
	 * @return the project directory
	 * @throws IOException if the project cannot be created
	 */
	private static Path project(Path root) throws IOException
	{
		Path base = Files.createDirectories(root.resolve("project"));
		writeFile(base.resolve("prompt.md"), "prompt", READ_WRITE);
		writeFile(base.resolve("ignored.md"), "ignored", READ_WRITE);
		return base;
	}

	/**
	 * Stores in campaign storage what creating a fork of a clean base leaves there: the recorded files, here only
	 * {@code prompt.md}, and the optimization worktree. Changing a fork's participants reads and writes only that
	 * storage, so the fork needs no Git repository.
	 *
	 * @param base the project directory
	 * @param campaign the campaign storage directory
	 * @throws IOException if the storage cannot be written
	 */
	private static void storeFork(Path base, Path campaign) throws IOException
	{
		ForkTree.record(base, campaign, Set.of(Path.of("prompt.md")));
		Files.createDirectories(campaign.resolve("worktree"));
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
	 * Copies the repository that {@link #createTemplate()} created: one commit that holds ordinary files, an
	 * executable and a link, and an ignored file. Its configuration owns every setting that a global configuration
	 * could change.
	 *
	 * @param root the directory that owns the fixture
	 * @return the repository directory
	 * @throws IOException if the repository cannot be copied
	 */
	private Path repository(Path root) throws IOException
	{
		Path base = root.resolve("repository");
		FileTrees.copy(template.resolve("repository"), base);
		return base;
	}
}
