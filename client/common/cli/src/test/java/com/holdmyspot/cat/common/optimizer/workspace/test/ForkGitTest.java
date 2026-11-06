package com.holdmyspot.cat.common.optimizer.workspace.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.workspace.ForkGit;
import com.holdmyspot.cat.common.optimizer.workspace.ForkGit.IgnoreFileChange;
import com.holdmyspot.cat.common.optimizer.workspace.WorkspaceException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.testng.annotations.Test;

/**
 * Tests what a fork decides from the output of Git. The outputs below were printed by Git 2.43.0 on Linux, so no test
 * here runs Git: {@link ForkTest} holds the one end-to-end check of how Git reports each change.
 */
public final class ForkGitTest
{
	/**
	 * The output of {@code git status --porcelain -z --untracked-files=all} for a rename that was staged, a staged
	 * addition, a staged modification, a staged deletion that left an untracked file of the same name, an unstaged
	 * modification, an unstaged deletion and an untracked file whose name holds a space.
	 */
	private static final String STATUS = "A  added.md\0R  new.md\0old.md\0M  prompt.md\0D  run\0 M sp ace.md\0" +
		" D alias.md\0?? run\0?? un tracked.md\0";

	/**
	 * Creates the test.
	 */
	public ForkGitTest()
	{
	}

	/**
	 * A clean base prints nothing, so no entry makes it dirty.
	 *
	 * @throws IOException if an exemption fails
	 */
	@Test
	public void cleanStatusHasNoChanges() throws IOException
	{
		requireThat(ForkGit.changes("", _ -> false), "changes").isEmpty();
	}

	/**
	 * Every kind of entry that Git prints is a change, in Git's order, and the source path that follows a rename is
	 * not a change of its own.
	 *
	 * @throws IOException if an exemption fails
	 */
	@Test
	public void everyEntryIsAChangeButARenameSourceIsNot() throws IOException
	{
		List<String> changes = ForkGit.changes(STATUS, _ -> false);

		requireThat(changes, "changes").isEqualTo(List.of("A  added.md", "R  new.md", "M  prompt.md", "D  run",
			" M sp ace.md", " D alias.md", "?? run", "?? un tracked.md"));
	}

	/**
	 * The source path that follows a copy is not a change of its own.
	 *
	 * @throws IOException if an exemption fails
	 */
	@Test
	public void aCopySourceIsNotAChange() throws IOException
	{
		requireThat(ForkGit.changes("C  copy.md\0original.md\0", _ -> false), "changes").
			isEqualTo(List.of("C  copy.md"));
	}

	/**
	 * An entry that the exemption names is no change, and the others stay, so a base whose only entries are exempt is
	 * clean.
	 *
	 * @throws IOException if an exemption fails
	 */
	@Test
	public void exemptEntriesAreNoChange() throws IOException
	{
		List<String> changes = ForkGit.changes(STATUS, entry -> entry.startsWith("??"));

		requireThat(changes, "changes").isEqualTo(List.of("A  added.md", "R  new.md", "M  prompt.md", "D  run",
			" M sp ace.md", " D alias.md"));
		requireThat(ForkGit.changes("?? run\0?? un tracked.md\0", entry -> entry.startsWith("??")), "onlyExempt").
			isEmpty();
	}

	/**
	 * A failure of the exemption reaches the caller instead of being taken for a clean or a dirty base.
	 */
	@Test
	public void aFailedExemptionIsReported()
	{
		try
		{
			ForkGit.changes(STATUS, _ ->
			{
				throw new IOException("cannot read the file");
			});
			throw new AssertionError("The failure must reach the caller");
		}
		catch (IOException e)
		{
			requireThat(e.getMessage(), "message").isEqualTo("cannot read the file");
		}
	}

	/**
	 * An untracked entry names its path, with the spaces that Git leaves unquoted under {@code -z}; every other entry
	 * names none.
	 */
	@Test
	public void onlyAnUntrackedEntryNamesAnUntrackedPath()
	{
		requireThat(ForkGit.untrackedPath("?? un tracked.md"), "untracked").isEqualTo("un tracked.md");
		requireThat(ForkGit.untrackedPath(" M sp ace.md"), "modified").isNull();
		requireThat(ForkGit.untrackedPath("A  added.md"), "added").isNull();
	}

	/**
	 * Only an unstaged change or an untracked copy of {@code .gitignore} can be the lines that CAT appends; a staged
	 * change or another file cannot.
	 */
	@Test
	public void onlyAnUnstagedIgnoreFileIsCatsLines()
	{
		requireThat(ForkGit.ignoreFileChange(" M .gitignore"), "unstaged").isEqualTo(IgnoreFileChange.UNSTAGED);
		requireThat(ForkGit.ignoreFileChange("?? .gitignore"), "untracked").isEqualTo(IgnoreFileChange.UNTRACKED);
		requireThat(ForkGit.ignoreFileChange("M  .gitignore"), "staged").isEqualTo(IgnoreFileChange.NONE);
		requireThat(ForkGit.ignoreFileChange("MM .gitignore"), "stagedAndUnstaged").isEqualTo(IgnoreFileChange.NONE);
		requireThat(ForkGit.ignoreFileChange(" M docs/.gitignore"), "nested").isEqualTo(IgnoreFileChange.NONE);
		requireThat(ForkGit.ignoreFileChange(" M prompt.md"), "other").isEqualTo(IgnoreFileChange.NONE);
	}

	/**
	 * The refusal of a dirty base names the base, tells the user how to recover and lists each change.
	 */
	@Test
	public void dirtyBaseRefusalListsTheChanges()
	{
		Path base = Path.of("project");

		WorkspaceException refusal = ForkGit.dirtyBase(base, List.of(" M prompt.md", "?? added.md"));

		requireThat(refusal.getMessage(), "message").contains("project").contains("commit, stash or remove").
			endsWith("Changes:\n M prompt.md\n?? added.md");
	}

	/**
	 * A directory is the top level of its repository when Git prints no path up to it.
	 */
	@Test
	public void topLevelHasNoPathUpToIt()
	{
		requireThat(ForkGit.isTopLevel(List.of()), "noLines").isTrue();
		requireThat(ForkGit.isTopLevel(List.of("")), "emptyLine").isTrue();
		requireThat(ForkGit.isTopLevel(List.of("../")), "oneLevelDown").isFalse();
		requireThat(ForkGit.isTopLevel(List.of("../../")), "twoLevelsDown").isFalse();
	}

	/**
	 * The tracked files are the NUL-terminated paths that Git prints, whatever their names hold.
	 */
	@Test
	public void trackedFilesAreThePrintedPaths()
	{
		Set<Path> tracked = ForkGit.trackedFiles(".gitignore\0added.md\0alias.md\0sp ace.md\0");

		requireThat(tracked, "tracked").isEqualTo(Set.of(Path.of(".gitignore"), Path.of("added.md"),
			Path.of("alias.md"), Path.of("sp ace.md")));
		requireThat(ForkGit.trackedFiles(""), "nothing").isEmpty();
	}

	/**
	 * Only the index entries of mode 120000 are symbolic links, not the regular or executable files.
	 */
	@Test
	public void trackedLinksAreTheEntriesOfLinkMode()
	{
		String stage = "100644 e2be7aa9633a36c5c2c4389a6a05e097a30d421e 0\t.gitignore\0" +
			"120000 424ea9b154331e3701410f37bdb334480355b508 0\talias.md\0" +
			"100755 b8fed1f54f05532202a349bdab7590b799130394 0\trun\0" +
			"120000 424ea9b154331e3701410f37bdb334480355b508 0\tsub dir/link to.md\0";

		requireThat(ForkGit.trackedLinks(stage), "links").isEqualTo(Set.of(Path.of("alias.md"),
			Path.of("sub dir/link to.md")));
		requireThat(ForkGit.trackedLinks(""), "nothing").isEmpty();
	}

	/**
	 * Git registers linked worktrees in {@code worktrees} below the common Git directory that it prints, whose line
	 * terminator is not part of the path.
	 */
	@Test
	public void registrationsLieInCommonGitDirectory()
	{
		Path registrations = ForkGit.worktreeRegistrations("/home/user/project/.git\n");

		requireThat(registrations, "registrations").isEqualTo(Path.of("/home/user/project/.git/worktrees"));
	}

	/**
	 * A failure to add a worktree is a denied write when the system denies the creation of an entry below the
	 * registrations directory.
	 */
	@Test
	public void deniedRegistrationWriteIsRecognized()
	{
		Path registrations = Path.of("/home/user/project/.git/worktrees");
		String readOnly = "Git command failed: git worktree add --detach campaign/worktree HEAD: fatal: could not " +
			"create directory of '.git/worktrees/worktree': Read-only file system";
		String denied = "fatal: could not create directory of '.git/worktrees/worktree': Permission denied";

		requireThat(ForkGit.deniedRegistrationWrite(readOnly, registrations), "readOnly").isTrue();
		requireThat(ForkGit.deniedRegistrationWrite(denied, registrations), "denied").isTrue();
	}

	/**
	 * A failure that does not deny a write below the registrations directory is not taken for one: another reason, a
	 * denial elsewhere, or no message.
	 */
	@Test
	public void otherFailuresAreNotDeniedWrites()
	{
		Path registrations = Path.of("/home/user/project/.git/worktrees");
		String notEmpty = "fatal: '/home/user/campaign/worktree' already exists";
		String elsewhere = "fatal: could not create directory of 'campaign/worktree': Permission denied";

		requireThat(ForkGit.deniedRegistrationWrite(notEmpty, registrations), "notEmpty").isFalse();
		requireThat(ForkGit.deniedRegistrationWrite(elsewhere, registrations), "elsewhere").isFalse();
		requireThat(ForkGit.deniedRegistrationWrite(null, registrations), "noMessage").isFalse();
	}

	/**
	 * A path is separated from the options by {@code --}, so that Git cannot take a path for an option, and each path
	 * reaches Git as written.
	 */
	@Test
	public void diffArgumentsSeparatePathsFromOptions()
	{
		List<String> paths = List.of("prompt.md", "-odd name.md");

		List<String> intent = List.of(ForkGit.intentToAdd(paths));
		List<String> diff = List.of(ForkGit.diffAgainstHead(paths));

		requireThat(intent, "intent").isEqualTo(List.of("add", "--intent-to-add", "--force", "--", "prompt.md",
			"-odd name.md"));
		requireThat(diff, "diff").isEqualTo(List.of("diff", "--no-color", "--no-ext-diff", "HEAD", "--", "prompt.md",
			"-odd name.md"));
	}

	/**
	 * A diff ends with a line terminator, and a diff with no lines is empty.
	 */
	@Test
	public void diffTextEndsWithALineTerminator()
	{
		requireThat(ForkGit.diffText(List.of("diff --git a/x b/x", "+added")), "diff").
			isEqualTo("diff --git a/x b/x\n+added\n");
		requireThat(ForkGit.diffText(List.of()), "empty").isEmpty();
	}
}
