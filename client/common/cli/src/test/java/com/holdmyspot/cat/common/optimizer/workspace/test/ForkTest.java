package com.holdmyspot.cat.common.optimizer.workspace.test;

import com.holdmyspot.cat.common.fakes.test.Yielding;
import static com.holdmyspot.cat.common.optimizer.workspace.test.ProjectFixtures.EXECUTABLE;
import static com.holdmyspot.cat.common.optimizer.workspace.test.ProjectFixtures.READ_WRITE;
import static com.holdmyspot.cat.common.optimizer.workspace.test.ProjectFixtures.content;
import static com.holdmyspot.cat.common.optimizer.workspace.test.ProjectFixtures.file;
import static com.holdmyspot.cat.common.optimizer.workspace.test.ProjectFixtures.writeFile;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.git.Git;
import com.holdmyspot.cat.common.optimizer.inventory.FileEntry.SymbolicLink;
import com.holdmyspot.cat.common.optimizer.inventory.Overlay;
import com.holdmyspot.cat.common.optimizer.workspace.Fork;
import com.holdmyspot.cat.common.optimizer.workspace.ForkTree;
import com.holdmyspot.cat.common.optimizer.workspace.ForkView;
import com.holdmyspot.cat.common.optimizer.workspace.Forks;
import com.holdmyspot.cat.common.optimizer.workspace.GitForks;
import com.holdmyspot.cat.common.optimizer.workspace.WorkspaceException;
import com.holdmyspot.cat.common.test.FileTrees;
import com.holdmyspot.cat.common.test.ProgramFixture;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Verifies the fork. Creating one runs against a real Git repository that each test owns: the clean-base refusal, the
 * {@code .gitignore} lines that ignore CAT's session directories, the recorded participants and the detached
 * optimization worktree at {@code HEAD}. The diff of the selected paths runs against such a repository too. Changing a
 * fork's participants reads and writes only campaign storage, so those tests store a fork without Git.
 * <p>
 * These are the end-to-end checks of how Git reports and does what a fork asks of it. What the fork decides from
 * Git's output, such as which status entries count as changes, how the tracked files and links are read and when a
 * failure is a denied registration, is checked on recorded output in {@link ForkGitTest}, so only the kinds of change
 * that exercise a different Git behavior run here.
 */
public final class ForkTest
{
	private static final String IGNORE_LINES = "# Per-session files of the CAT plugin for Claude Code and Codex " +
		"(https://github.com/holdmyspot-com/cat)\n/.cat/sessions/\n";
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
				{"unstaged modification"},
				{"untracked file"},
				{"empty untracked file"},
				{"ignore lines and another line"},
				{"staged ignore lines"}
			};
	}

	/**
	 * Verifies that a base worktree with a change of the given kind is refused before anything is created: no
	 * campaign storage, no worktree, no {@code .gitignore} change.
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
				case "unstaged modification" -> writeFile(base.resolve("prompt.md"), "unstaged edit", READ_WRITE);
				case "untracked file" -> writeFile(base.resolve("untracked.md"), "untracked", READ_WRITE);
				// An empty regular file on the host is a change, unlike the device that a sandbox lays over a path
				case "empty untracked file" -> writeFile(base.resolve(".bashrc"), "", READ_WRITE);
				case "ignore lines and another line" ->
					writeFile(ignoreFile(base), "ignored.md\n" + IGNORE_LINES + "other.md\n", READ_WRITE);
				case "staged ignore lines" ->
				{
					writeFile(ignoreFile(base), "ignored.md\n" + IGNORE_LINES, READ_WRITE);
					git.run("add", ".gitignore");
				}
				default -> throw new AssertionError("Unknown change: " + change);
			}
			String ignoreBefore = Files.readString(ignoreFile(base), UTF_8);

			new ForksContract(new GitForks(Yielding.fileReplacer()), base, campaign).refusesDirtyBase();
			requireThat(Files.readString(ignoreFile(base), UTF_8), "gitignore").isEqualTo(ignoreBefore);
		}
	}

	/**
	 * Verifies that a base whose only untracked entries are character devices, as a sandboxed Claude Code session lays
	 * over the paths it protects, forks without them: neither the fork's files nor the optimization worktree hold them.
	 * An unprivileged test cannot create a device, so empty files stand in for the devices and a classifier names them
	 * as devices.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void forksBaseWhoseUntrackedEntriesAreDevices() throws IOException
	{
		// Requirement: R-W2
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-fork-"));
			Path base = repository(root);
			Set<Path> devices = Set.of(base.resolve(".bashrc"), base.resolve(".vscode"));
			for (Path device : devices)
				writeFile(device, "", READ_WRITE);

			Fork fork = Fork.create(base, base.resolve(CAMPAIGNS + "/c1"), Set.of(), Yielding.fileReplacer(),
				(file, _) -> devices.contains(file));

			Path worktreeDirectory = base.resolve(CAMPAIGNS + "/c1/worktree");
			requireThat(fork.inventory().entries().keySet(), "forkFiles").
				doesNotContain(Path.of(".bashrc")).doesNotContain(Path.of(".vscode"));
			requireThat(Files.exists(worktreeDirectory.resolve(".bashrc"), NOFOLLOW_LINKS), "worktreeBashrc").isFalse();
			requireThat(Files.exists(worktreeDirectory.resolve(".vscode"), NOFOLLOW_LINKS), "worktreeVscode").isFalse();
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

			requireThat(fork.ignoreNotice().orElseThrow(), "ignoreNotice").
				contains("this change is theirs to commit; do not stage or commit it.");
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

			new ForksContract(new GitForks(Yielding.fileReplacer()), base, campaign).removesExistingWorktree();
			requireThat(worktreeCount(git), "worktreesAfter").isEqualTo(1L);
		}
	}

	/**
	 * Verifies that a removal that Git refuses is reported and leaves the optimization worktree in place. The base's
	 * {@code .git} is replaced with a file that is not a Git file, so that every Git command in the base fails.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void reportsAFailedRemoval() throws IOException
	{
		// Requirement: R-W9
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-fork-"));
			Path base = repository(root);
			Path campaign = base.resolve(CAMPAIGNS + "/c1");
			Fork.create(base, campaign, Set.of(), Yielding.fileReplacer());

			new ForksContract(new GitForks(Yielding.fileReplacer()), base, campaign).reportsAFailedRemoval(() ->
			{
				TemporaryDirectories.deleteRecursively(base.resolve(".git"));
				Files.writeString(base.resolve(".git"), "not a Git file\n");
			});
		}
	}

	/**
	 * Verifies that removing one campaign's optimization worktree keeps another campaign's worktree of the same base.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void removalKeepsAnotherCampaignsFork() throws IOException
	{
		// Requirement: R-W9
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-fork-"));
			Path base = repository(root);
			Path campaign = base.resolve(CAMPAIGNS + "/c1");
			Fork.create(base, campaign, Set.of(), Yielding.fileReplacer());

			new ForksContract(new GitForks(Yielding.fileReplacer()), base, campaign).removalKeepsAnotherCampaignsFork();
		}
	}

	/**
	 * Verifies that a created optimization worktree exists and reopens with the inventory that creation returned.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void createdForkCanBeReopened() throws IOException
	{
		// Requirement: R-W2
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-fork-"));
			Path base = repository(root);
			Path campaign = base.resolve(CAMPAIGNS + "/c1");
			Fork created = Fork.create(base, campaign, Set.of(), Yielding.fileReplacer());

			new ForksContract(new GitForks(Yielding.fileReplacer()), base, campaign).createdForkCanBeReopened(created);
		}
	}

	/**
	 * Verifies that a missing optimization worktree is neither reported present nor opened, even when the recorded
	 * fork inventory remains. The stored fixture needs no Git repository.
	 *
	 * @throws IOException if the fixture or contract check fails
	 */
	@Test
	public void missingWorktreeIsNotAFork() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-fork-"));
			Path base = project(root);
			Path campaign = root.resolve("campaign");
			storeFork(base, campaign);
			new ForksContract(new GitForks(Yielding.fileReplacer()), base, campaign).missingWorktreeIsNotAFork();
		}
	}

	/**
	 * Verifies that a copy of a project whose campaign holds an optimization worktree reconnects that worktree to the
	 * copy's repository before the worktree is checked, leaves the original project's worktree linked to the original,
	 * and keeps the working candidate in the worktree, so the check finds no change that CAT did not make.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void copiedProjectReconnectsWorktree() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-fork-"));
			Path base = repository(root);
			Path campaign = base.resolve(CAMPAIGNS + "/c1");
			Fork original = Fork.create(base, campaign, Set.of(), Yielding.fileReplacer());
			original.workspace().materialize(edited(original));
			Path copy = root.resolve("copy");
			FileTrees.copy(base, copy);
			Path copiedCampaign = copy.resolve(CAMPAIGNS + "/c1");
			Path copiedWorktree = copiedCampaign.resolve("worktree");
			requireThat(commonDirectory(copiedWorktree), "commonBefore").isEqualTo(base.resolve(".git").toRealPath());

			ForkView fork = new GitForks(Yielding.fileReplacer()).open(copy, copiedCampaign);
			Overlay working = edited(fork);
			fork.workspace().reconnectWorktree(working);

			requireThat(commonDirectory(copiedWorktree), "commonAfter").isEqualTo(copy.resolve(".git").toRealPath());
			requireThat(registeredWorktrees(new Git(copy)), "copiedWorktrees").
				isEqualTo(List.of(copy.toRealPath(), copiedWorktree.toRealPath()));
			requireThat(prunable(new Git(copy)), "copiedPrunable").isEqualTo(0L);
			requireThat(commonDirectory(campaign.resolve("worktree")), "originalCommon").
				isEqualTo(base.resolve(".git").toRealPath());
			requireThat(registeredWorktrees(new Git(base)), "originalWorktrees").
				isEqualTo(List.of(base.toRealPath(), campaign.resolve("worktree").toRealPath()));
			requireThat(fork.workspace().scanWorktree().entries(), "worktreeEntries").
				isEqualTo(working.snapshot().entries());
		}
	}

	/**
	 * Verifies that an optimization worktree whose registration the copied project lacks, so that its links cannot be
	 * repaired, is recreated as a worktree of the copy's repository that holds exactly the working candidate's files.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void unrepairableWorktreeIsRecreated() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-fork-"));
			Path base = repository(root);
			Fork.create(base, base.resolve(CAMPAIGNS + "/c1"), Set.of(), Yielding.fileReplacer());
			Path copy = root.resolve("copy");
			FileTrees.copy(base, copy);
			TemporaryDirectories.deleteRecursively(copy.resolve(".git/worktrees"));
			Path copiedCampaign = copy.resolve(CAMPAIGNS + "/c1");
			Path copiedWorktree = copiedCampaign.resolve("worktree");
			writeFile(copiedWorktree.resolve("leftover.md"), "leftover", READ_WRITE);

			ForkView fork = new GitForks(Yielding.fileReplacer()).open(copy, copiedCampaign);
			Overlay working = edited(fork);
			fork.workspace().reconnectWorktree(working);

			requireThat(commonDirectory(copiedWorktree), "common").isEqualTo(copy.resolve(".git").toRealPath());
			requireThat(registeredWorktrees(new Git(copy)), "worktrees").
				isEqualTo(List.of(copy.toRealPath(), copiedWorktree.toRealPath()));
			requireThat(fork.workspace().scanWorktree().entries(), "worktreeEntries").
				isEqualTo(working.snapshot().entries());
		}
	}

	/**
	 * Verifies that removing the campaign of a moved project removes its optimization worktree and the record that
	 * the moved repository holds of it, although the worktree's links named the old location.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void removesMovedProjectsWorktree() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-fork-"));
			Path base = repository(root);
			Fork.create(base, base.resolve(CAMPAIGNS + "/c1"), Set.of(), Yielding.fileReplacer());
			Path moved = Files.move(base, root.resolve("moved"));
			Git git = new Git(moved);
			requireThat(prunable(git), "prunableBefore").isEqualTo(1L);
			Path campaign = moved.resolve(CAMPAIGNS + "/c1");

			new GitForks(Yielding.fileReplacer()).remove(moved, campaign);

			requireThat(Files.exists(campaign.resolve("worktree"), NOFOLLOW_LINKS), "worktreeExists").isFalse();
			requireThat(registeredWorktrees(git), "worktrees").isEqualTo(List.of(moved.toRealPath()));
		}
	}

	/**
	 * Returns a working candidate that differs from the fork's files, as one that a prompt author wrote.
	 *
	 * @param fork the fork
	 * @return the candidate
	 */
	private static Overlay edited(ForkView fork)
	{
		return Overlay.of(fork.inventory()).write(Path.of("prompt.md"), content("edited"), READ_WRITE);
	}

	/**
	 * Returns the common Git directory that a worktree's links lead Git to.
	 *
	 * @param worktree the worktree
	 * @return the directory's real path
	 * @throws IOException if Git fails
	 */
	private static Path commonDirectory(Path worktree) throws IOException
	{
		return Path.of(new Git(worktree).run("rev-parse", "--path-format=absolute", "--git-common-dir").strip()).
			toRealPath();
	}

	/**
	 * Returns the worktrees that a repository registers, its main worktree first.
	 *
	 * @param git the repository's Git
	 * @return the worktrees' real paths
	 * @throws IOException if Git fails or a worktree does not exist
	 */
	private static List<Path> registeredWorktrees(Git git) throws IOException
	{
		List<Path> worktrees = new ArrayList<>();
		for (String line : git.lines("worktree", "list", "--porcelain"))
		{
			if (line.startsWith("worktree "))
				worktrees.add(Path.of(line.substring("worktree ".length())).toRealPath());
		}
		return worktrees;
	}

	/**
	 * Returns how many worktrees of a repository Git reports as prunable, because their links name a location that
	 * does not exist.
	 *
	 * @param git the repository's Git
	 * @return the count
	 * @throws IOException if Git fails
	 */
	private static long prunable(Git git) throws IOException
	{
		return git.lines("worktree", "list", "--porcelain").stream().filter(line -> line.startsWith("prunable")).
			count();
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
	 * Verifies that CAT's lines are appended to the base's {@code .gitignore}, unstaged and nowhere else, when Git does
	 * not ignore CAT's session directories, so campaign files never make the base dirty, and that the fork reports it.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void addsIgnoreLinesWhenSessionsAreNotIgnored() throws IOException
	{
		// Requirement: R-W1
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-fork-"));
			Path base = repository(root);
			String excludeBefore = Files.readString(excludeFile(base), UTF_8);

			Fork fork = Fork.create(base, base.resolve(CAMPAIGNS + "/c1"), Set.of(), Yielding.fileReplacer());

			new ForksContract(new GitForks(Yielding.fileReplacer()), base, base.resolve(CAMPAIGNS + "/c1")).
				reportsIgnoreNoticeOnlyAtCreation(fork, true);
			requireThat(Files.readString(ignoreFile(base), UTF_8), "gitignore").isEqualTo("ignored.md\n" + IGNORE_LINES);
			requireThat(Files.readString(excludeFile(base), UTF_8), "exclude").isEqualTo(excludeBefore);
			requireThat(Files.isDirectory(base.resolve(CAMPAIGNS + "/c1")), "campaignExists").isTrue();
			requireThat(new Git(base).lines("status", "--porcelain", "--untracked-files=all"), "status").
				isEqualTo(List.of(" M .gitignore"));
		}
	}

	/**
	 * Verifies that {@code .gitignore} is left alone when Git already ignores CAT's session directories through another
	 * ignore source, here the repository's local exclude.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void leavesGitignoreWhenAlreadyIgnored() throws IOException
	{
		// Requirement: R-W1
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-fork-"));
			Path base = repository(root);
			Files.writeString(excludeFile(base), ".cat/sessions\n", UTF_8, StandardOpenOption.APPEND);
			String ignoreBefore = Files.readString(ignoreFile(base), UTF_8);

			Fork fork = Fork.create(base, base.resolve(CAMPAIGNS + "/c1"), Set.of(), Yielding.fileReplacer());

			new ForksContract(new GitForks(Yielding.fileReplacer()), base, base.resolve(CAMPAIGNS + "/c1")).
				reportsIgnoreNoticeOnlyAtCreation(fork, false);
			requireThat(Files.readString(ignoreFile(base), UTF_8), "gitignore").isEqualTo(ignoreBefore);
		}
	}

	/**
	 * Verifies that a second campaign in the same repository accepts a base whose only change is the lines that the
	 * first campaign appended to {@code .gitignore}, and does not append them again.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void appendsIgnoreLinesOnlyOnce() throws IOException
	{
		// Requirement: R-W1
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-fork-"));
			Path base = repository(root);

			Fork first = Fork.create(base, base.resolve(CAMPAIGNS + "/c1"), Set.of(), Yielding.fileReplacer());
			Fork second = Fork.create(base, base.resolve(CAMPAIGNS + "/c2"), Set.of(), Yielding.fileReplacer());

			requireThat(first.ignoreNotice(), "firstNotice").isPresent();
			requireThat(second.ignoreNotice(), "secondNotice").isEmpty();
			requireThat(Files.readString(ignoreFile(base), UTF_8), "gitignore").isEqualTo("ignored.md\n" + IGNORE_LINES);
			requireThat(base.resolve(CAMPAIGNS + "/c2/worktree"), "secondWorktree").isDirectory();
		}
	}

	/**
	 * Verifies that a base without a committed {@code .gitignore} receives one that holds only CAT's lines, and that a
	 * later campaign accepts that untracked file as the base's only change.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void acceptsUntrackedGitignoreOfIgnoreLines() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-fork-"));
			Path base = repository(root);
			Git git = new Git(base);
			git.run("rm", "--quiet", "--cached", ".gitignore");
			git.run("commit", "--quiet", "-m", "no ignore file");
			Files.delete(ignoreFile(base));
			Files.delete(base.resolve("ignored.md"));

			Fork first = Fork.create(base, base.resolve(CAMPAIGNS + "/c1"), Set.of(), Yielding.fileReplacer());
			Fork second = Fork.create(base, base.resolve(CAMPAIGNS + "/c2"), Set.of(), Yielding.fileReplacer());

			requireThat(first.ignoreNotice(), "firstNotice").isPresent();
			requireThat(second.ignoreNotice(), "secondNotice").isEmpty();
			requireThat(Files.readString(ignoreFile(base), UTF_8), "gitignore").isEqualTo(IGNORE_LINES);
			requireThat(git.lines("status", "--porcelain", "--untracked-files=all"), "status").
				isEqualTo(List.of("?? .gitignore"));
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
			// The branch header names the checked-out commit and the branch. The only change is the recorded base's
			// .gitignore, which holds the lines that CAT appended to the base
			List<String> worktreeStatus = new Git(worktreeDirectory).lines("status", "--porcelain=v2", "--branch");
			requireThat(worktreeStatus.subList(0, 2), "worktreeStatus").
				isEqualTo(List.of("# branch.oid " + head, "# branch.head (detached)"));
			requireThat(worktreeStatus.size(), "worktreeStatus.size").isEqualTo(3);
			requireThat(worktreeStatus.getLast(), "worktreeChange").startsWith("1 .M ").endsWith(" .gitignore");
			requireThat(Files.readString(worktreeDirectory.resolve(".gitignore"), UTF_8), "worktreeGitignore").
				isEqualTo("ignored.md\n" + IGNORE_LINES);
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
			new ForksContract(new GitForks(Yielding.fileReplacer()), base, campaign).leavesOutDeselectedParticipants();
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
			new ForksContract(new GitForks(Yielding.fileReplacer()), base, campaign).extensionBecomesCurrentWhenAdopted();
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
			Path campaign = root.resolve("campaign");
			storeFork(base, campaign);
			new ForksContract(new GitForks(Yielding.fileReplacer()), base, campaign).addsParticipantsAfterCreation();
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

			new ForksContract(new GitForks(Yielding.fileReplacer()), base, base.resolve(CAMPAIGNS + "/c1")).
				recordsTrackedAndParticipantFiles(fork, external);
			requireThat(fork.inventory().entries().get(Path.of("alias.md")), "link").
				isEqualTo(new SymbolicLink(Path.of("prompt.md")));
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

			new ForksContract(new GitForks(Yielding.fileReplacer()), base, campaign).missingParticipantCreatesNoWorktree();
		}
	}

	/**
	 * Verifies that a failure while the first candidate is written into the new worktree removes the worktree
	 * registration, the worktree and the fork storage, keeps the {@code .gitignore} lines, and lets a retry succeed. The
	 * failure comes from a regular file where the mirror of external files must be a directory.
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
			requireThat(Files.readString(ignoreFile(base), UTF_8), "gitignore").isEqualTo("ignored.md\n" + IGNORE_LINES);
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
	 * Verifies that a Git failure while checking whether CAT's session directories are ignored is not taken for "not
	 * ignored": nothing is appended, and the fork carries on with a notice that names the failure. Git cannot check a
	 * path beyond a symbolic link, so an ignored link at {@code .cat} makes the check fail with exit status 128.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void reportsGitFailureOfIgnoreCheck() throws IOException
	{
		// Requirement: R-W1
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-fork-"));
			Path base = repository(root);
			Files.createSymbolicLink(base.resolve(".cat"), Path.of("elsewhere"));
			// Ignoring the link keeps the base clean, so the fork reaches the ignore check
			Files.writeString(excludeFile(base), "/.cat\n", UTF_8, StandardOpenOption.APPEND);
			String ignoreBefore = Files.readString(ignoreFile(base), UTF_8);
			Path campaign = root.resolve("campaign");

			Fork fork = Fork.create(base, campaign, Set.of(), Yielding.fileReplacer());

			requireThat(fork.ignoreNotice().orElseThrow(), "notice").contains("CAT could not add /.cat/sessions/").
				contains("check-ignore").contains("status 128");
			requireThat(campaign.resolve("worktree"), "worktree").isDirectory();
			requireThat(Files.readString(ignoreFile(base), UTF_8), "gitignore").isEqualTo(ignoreBefore);
		}
	}

	/**
	 * Verifies that a session that cannot write the directory in which Git registers linked worktrees is refused with
	 * a message for the user that names that directory, before anything is created: no campaign storage, no worktree,
	 * no {@code .gitignore} change. A check that denies every directory stands in for a sandbox that mounts the
	 * repository's Git directory read-only.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void refusesUnwritableRegistrations() throws IOException
	{
		// Requirement: R-W2
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-fork-"));
			Path base = repository(root);
			Git git = new Git(base);
			Path campaign = base.resolve(CAMPAIGNS + "/c1");
			String ignoreBefore = Files.readString(ignoreFile(base), UTF_8);
			Path registrations = base.toRealPath().resolve(".git/worktrees");

			try
			{
				Fork.create(base, campaign, Set.of(), Yielding.fileReplacer(), (_, _) -> false, directory ->
				{
					throw new AccessDeniedException(directory.toString(), null, "Read-only file system");
				});
				throw new AssertionError("A session that cannot write the worktree registrations must be refused");
			}
			catch (WorkspaceException e)
			{
				requireThat(e.getMessage(), "message").contains("cannot create the optimization worktree").
					contains("cannot write " + registrations + ".").contains("then run start again");
			}
			requireThat(Files.exists(campaign, NOFOLLOW_LINKS), "campaignExists").isFalse();
			requireThat(worktreeCount(git), "worktrees").isEqualTo(1L);
			requireThat(Files.readString(ignoreFile(base), UTF_8), "gitignore").isEqualTo(ignoreBefore);
		}
	}

	/**
	 * Verifies that a fork of a linked worktree checks the registrations directory of the repository's common Git
	 * directory, where Git registers every linked worktree, and that a writable one lets the fork add its worktree.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void checksCommonRegistrationsOfLinkedBase() throws IOException
	{
		// Requirement: R-W2
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-fork-"));
			Path main = repository(root);
			Path base = root.resolve("linked");
			new Git(main).run("worktree", "add", "--quiet", "--detach", base.toString(), "HEAD");
			writeFile(base.resolve("ignored.md"), "ignored", READ_WRITE);
			List<Path> checked = new ArrayList<>();

			Fork.create(base, base.resolve(CAMPAIGNS + "/c1"), Set.of(), Yielding.fileReplacer(), (_, _) -> false,
				checked::add);

			requireThat(checked, "checked").isEqualTo(List.of(main.toRealPath().resolve(".git/worktrees")));
			requireThat(base.resolve(CAMPAIGNS + "/c1/worktree/prompt.md"), "prompt").isRegularFile();
		}
	}

	/**
	 * Verifies that the tracked files are the files that the Git index records, that the tracked links are those it
	 * records as symbolic links, without its regular or executable files, that unchanged files have no diff, and that a
	 * directory outside a Git work tree has no tracked links.
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

			ForksContract repositoryForks = new ForksContract(new GitForks(Yielding.fileReplacer()), base,
				base.resolve(CAMPAIGNS + "/c1"));
			repositoryForks.reportsTrackedFiles(Set.of(Path.of(".gitignore"), Path.of("prompt.md"),
				Path.of(ProgramFixture.name("run")), Path.of("alias.md")));
			repositoryForks.reportsTrackedLinks(Set.of(Path.of("alias.md")));
			repositoryForks.unchangedSelectionHasNoDiff(List.of(Path.of("prompt.md")));
			new ForksContract(new GitForks(Yielding.fileReplacer()), unmanaged, root.resolve("campaign")).
				hasNoTrackedLinksOutsideRepository();
		}
	}

	/**
	 * Verifies the empty-selection diff contract with an unmanaged project, without invoking Git in the check.
	 *
	 * @throws IOException if the fixture or contract check fails
	 */
	@Test
	public void emptySelectionHasNoDiff() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-fork-"));
			new ForksContract(new GitForks(Yielding.fileReplacer()), project(root), root.resolve("campaign")).
				emptySelectionHasNoDiff();
		}
	}

	/**
	 * Verifies that the diff of the selected paths holds a patch for each selected path that differs from
	 * {@code HEAD}: an edited file, a file that {@code HEAD} lacks and a deleted file. A changed tracked file and an
	 * untracked file that the selection leaves out have no patch, and reporting the diff leaves the repository's
	 * index unchanged.
	 *
	 * @throws IOException if the fixture cannot be created or Git fails
	 */
	@Test
	public void diffHoldsOnlySelectedChanges() throws IOException
	{
		// Requirement: R-W8
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-fork-"));
			Path base = repository(root);
			Git git = new Git(base);
			String program = ProgramFixture.name("run");
			writeFile(base.resolve("prompt.md"), "edited prompt\n", READ_WRITE);
			writeFile(base.resolve("added.md"), "added rule\n", READ_WRITE);
			Files.delete(base.resolve(program));
			writeFile(base.resolve(".gitignore"), "ignored.md\nunselected edit\n", READ_WRITE);
			writeFile(base.resolve("other.md"), "unselected file\n", READ_WRITE);
			List<String> statusBefore = git.lines("status", "--porcelain", "--untracked-files=all");

			String diff = new GitForks(Yielding.fileReplacer()).diff(base,
				List.of(Path.of("prompt.md"), Path.of("added.md"), Path.of(program)));

			// Each file's patch starts with a "diff --git a/<path> b/<path>" header
			Set<String> patched = diff.lines().filter(line -> line.startsWith("diff --git ")).
				map(line -> line.substring(line.lastIndexOf(" b/") + " b/".length())).
				collect(Collectors.toSet());
			requireThat(patched, "patched").isEqualTo(Set.of("prompt.md", "added.md", program));
			requireThat(diff, "diff").contains("-prompt").contains("+edited prompt").
				contains("new file mode").contains("+added rule").contains("deleted file mode").
				doesNotContain("unselected");
			requireThat(git.lines("status", "--porcelain", "--untracked-files=all"), "status").
				isEqualTo(statusBefore);
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
	 * Returns the {@code .gitignore} of the repository's top-level directory.
	 *
	 * @param base the base worktree
	 * @return the file
	 */
	private static Path ignoreFile(Path base)
	{
		return base.resolve(".gitignore");
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
