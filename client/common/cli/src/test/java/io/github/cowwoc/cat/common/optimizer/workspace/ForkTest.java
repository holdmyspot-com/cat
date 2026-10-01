package io.github.cowwoc.cat.common.optimizer.workspace;

import static io.github.cowwoc.cat.common.optimizer.workspace.ProjectFixtures.EXECUTABLE;
import static io.github.cowwoc.cat.common.optimizer.workspace.ProjectFixtures.READ_WRITE;
import static io.github.cowwoc.cat.common.optimizer.workspace.ProjectFixtures.file;
import static io.github.cowwoc.cat.common.optimizer.workspace.ProjectFixtures.writeFile;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.git.Git;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry.SymbolicLink;
import io.github.cowwoc.cat.common.optimizer.inventory.FileInventory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
			requireThat(second.worktree(), "secondWorktree").isDirectory();
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

			Git worktree = new Git(fork.worktree());
			String head = git.lines("rev-parse", "HEAD").getFirst();
			requireThat(worktree.lines("rev-parse", "HEAD").getFirst(), "worktreeHead").isEqualTo(head);
			requireThat(worktree.lines("rev-parse", "--abbrev-ref", "HEAD").getFirst(), "branch").isEqualTo("HEAD");
			requireThat(Files.readString(fork.worktree().resolve("prompt.md"), UTF_8), "trackedFile").isEqualTo("prompt");
			requireThat(Files.isSymbolicLink(fork.worktree().resolve("alias.md")), "trackedLink").isTrue();
			requireThat(Files.readString(fork.worktree().resolve("ignored.md"), UTF_8), "ignoredParticipant").
				isEqualTo("ignored");
			requireThat(worktree.lines("status", "--porcelain"), "worktreeStatus").isEmpty();
			requireThat(git.lines("worktree", "list"), "worktrees").size().isEqualTo(2);
			requireThat(fork.workspace().scanWorktree().entries(), "worktreeEntries").
				isEqualTo(fork.tree().inventory().entries());
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

			FileInventory inventory = fork.tree().inventory();
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
			requireThat(Fork.open(base, base.resolve(".cat/work/optimizer/c1")).tree().inventory(), "reopened").
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

			Fork retry = Fork.create(base, campaign, Set.of(external));

			requireThat(retry.worktree(), "worktree").isDirectory();
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

			Fork retry = Fork.create(base, campaign, Set.of());

			requireThat(retry.worktree().resolve("prompt.md"), "prompt").isRegularFile();
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
