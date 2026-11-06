package com.holdmyspot.cat.common.optimizer.workspace;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.SessionIgnore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What {@link Fork} decides from the output of Git: the entries that make a base worktree dirty, the files that Git
 * tracks, the registrations directory, and the form of a diff. Each method takes the text that Git printed and returns
 * the decision, so that a test gives it Git's recorded output instead of running Git.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class ForkGit
{
	/**
	 * The start of a {@code git status --porcelain} entry for an untracked path, which the path follows.
	 */
	private static final String UNTRACKED_STATUS = "?? ";
	/**
	 * The {@code git status --porcelain} entry of a {@code .gitignore} that differs from the index only in the work
	 * tree.
	 */
	private static final String UNSTAGED_IGNORE_FILE = " M " + SessionIgnore.FILE_NAME;
	/**
	 * The mode that a Git index records for a symbolic link.
	 */
	private static final String SYMBOLIC_LINK_MODE = "120000";

	/**
	 * Prevents instantiation.
	 */
	private ForkGit()
	{
	}

	/**
	 * Indicates whether a directory is the top-level directory of its repository.
	 *
	 * @param showCdup the lines that {@code git rev-parse --show-cdup} printed, which name the path from the directory
	 *   to the top-level directory
	 * @return {@code true} if every line is empty, which is the case at the top level
	 * @throws NullPointerException if {@code showCdup} is null
	 */
	public static boolean isTopLevel(List<String> showCdup)
	{
		requireThat(showCdup, "showCdup").isNotNull();
		return showCdup.stream().allMatch(String::isEmpty);
	}

	/**
	 * Lists the entries of {@code git status --porcelain -z} that count as changes of a base worktree. With
	 * {@code -z}, Git quotes no path, ends each entry with NUL and follows the entry of a rename or copy with its
	 * source path, which is not a change of its own.
	 *
	 * @param status the output of {@code git status --porcelain -z}
	 * @param exemption tells which entries do not count as changes
	 * @return the entries that count, each in the form {@code XY path}, in Git's order
	 * @throws NullPointerException if any argument is null
	 * @throws IOException if {@code exemption} fails
	 */
	public static List<String> changes(String status, Exemption exemption) throws IOException
	{
		requireThat(status, "status").isNotNull();
		requireThat(exemption, "exemption").isNotNull();
		List<String> changes = new ArrayList<>();
		String[] fields = status.split("\0");
		for (int i = 0; i < fields.length; ++i)
		{
			String entry = fields[i];
			if (entry.isEmpty())
				continue;
			char staged = entry.charAt(0);
			boolean sourcePathFollows = staged == 'R' || staged == 'C';
			if (sourcePathFollows)
				i += 1;
			if (!exemption.exempts(entry))
				changes.add(entry);
		}
		return changes;
	}

	/**
	 * Tells which status entries do not count as changes of a base worktree.
	 */
	@FunctionalInterface
	public interface Exemption
	{
		/**
		 * Indicates whether an entry is no change.
		 *
		 * @param entry a {@code git status --porcelain} entry
		 * @return {@code true} if the entry does not count as a change
		 * @throws IOException if the answer needs a file or Git, and it fails
		 */
		boolean exempts(String entry) throws IOException;
	}

	/**
	 * Returns the path of an untracked entry.
	 *
	 * @param entry a {@code git status --porcelain} entry
	 * @return the path relative to the base, or {@code null} if the entry is not an untracked path
	 * @throws NullPointerException if {@code entry} is null
	 */
	public static String untrackedPath(String entry)
	{
		requireThat(entry, "entry").isNotNull();
		if (!entry.startsWith(UNTRACKED_STATUS))
			return null;
		return entry.substring(UNTRACKED_STATUS.length());
	}

	/**
	 * Tells how a status entry relates to the base's {@code .gitignore}.
	 *
	 * @param entry a {@code git status --porcelain} entry
	 * @return {@link IgnoreFileChange#UNSTAGED} if only the work tree's copy differs from the index,
	 *   {@link IgnoreFileChange#UNTRACKED} if Git does not track the file, and {@link IgnoreFileChange#NONE} for any
	 *   other entry, including a staged change to the file
	 * @throws NullPointerException if {@code entry} is null
	 */
	public static IgnoreFileChange ignoreFileChange(String entry)
	{
		requireThat(entry, "entry").isNotNull();
		if (entry.equals(UNSTAGED_IGNORE_FILE))
			return IgnoreFileChange.UNSTAGED;
		if (entry.equals(UNTRACKED_STATUS + SessionIgnore.FILE_NAME))
			return IgnoreFileChange.UNTRACKED;
		return IgnoreFileChange.NONE;
	}

	/**
	 * How a status entry changes the base's {@code .gitignore}.
	 */
	public enum IgnoreFileChange
	{
		/**
		 * The entry does not concern an unstaged or untracked {@code .gitignore}.
		 */
		NONE,
		/**
		 * The work tree's copy differs from the index, and nothing is staged.
		 */
		UNSTAGED,
		/**
		 * Git does not track the file.
		 */
		UNTRACKED
	}

	/**
	 * Returns the refusal, for the user, of a base worktree that has changes.
	 *
	 * @param base the base worktree
	 * @param changes the status entries that count as changes
	 * @return the refusal
	 * @throws NullPointerException if any argument is null
	 */
	public static WorkspaceException dirtyBase(Path base, List<String> changes)
	{
		requireThat(base, "base").isNotNull();
		requireThat(changes, "changes").isNotNull();
		return new WorkspaceException("The base worktree " + base + " has staged, unstaged or untracked changes, so " +
			"CAT cannot fork it: commit, stash or remove the changes, then start the campaign again. CAT stopped " +
			"before it created anything. Changes:\n" + String.join("\n", changes));
	}

	/**
	 * Lists the files that Git tracks.
	 *
	 * @param lsFiles the output of {@code git ls-files -z}
	 * @return the files, relative to the worktree
	 * @throws NullPointerException if {@code lsFiles} is null
	 */
	public static Set<Path> trackedFiles(String lsFiles)
	{
		requireThat(lsFiles, "lsFiles").isNotNull();
		Set<Path> paths = new HashSet<>();
		for (String tracked : lsFiles.split("\0"))
		{
			if (!tracked.isEmpty())
				paths.add(Path.of(tracked));
		}
		return paths;
	}

	/**
	 * Lists the files that the index records as symbolic links.
	 *
	 * @param lsFilesStage the output of {@code git ls-files --stage -z}, whose entries have the form
	 *   {@code <mode> <object> <stage>\t<path>}
	 * @return the links, relative to the worktree
	 * @throws NullPointerException if {@code lsFilesStage} is null
	 */
	public static Set<Path> trackedLinks(String lsFilesStage)
	{
		requireThat(lsFilesStage, "lsFilesStage").isNotNull();
		Set<Path> links = new HashSet<>();
		for (String entry : lsFilesStage.split("\0"))
		{
			if (entry.startsWith(SYMBOLIC_LINK_MODE + " "))
				links.add(Path.of(entry.substring(entry.indexOf('\t') + 1)));
		}
		return links;
	}

	/**
	 * Returns the directory in which Git registers a repository's linked worktrees: {@code worktrees} in its common Git
	 * directory, which the main worktree and every linked worktree share.
	 *
	 * @param gitCommonDirectory the output of {@code git rev-parse --path-format=absolute --git-common-dir}
	 * @return the directory's absolute path
	 * @throws NullPointerException if {@code gitCommonDirectory} is null
	 */
	public static Path worktreeRegistrations(String gitCommonDirectory)
	{
		requireThat(gitCommonDirectory, "gitCommonDirectory").isNotNull();
		return Path.of(gitCommonDirectory.strip()).resolve("worktrees").normalize();
	}

	/**
	 * Indicates whether the message of a failed {@code git worktree add} says that Git could not write the directory in
	 * which it registers linked worktrees. Git names the entry that it could not create below that directory and the
	 * system's reason.
	 *
	 * @param message the message of the failure, or {@code null} if it has none
	 * @param registrations the directory in which Git registers linked worktrees
	 * @return {@code true} if the system denied the write
	 * @throws NullPointerException if {@code registrations} is null
	 */
	public static boolean deniedRegistrationWrite(String message, Path registrations)
	{
		requireThat(registrations, "registrations").isNotNull();
		if (message == null)
			return false;
		boolean deniedWrite = message.contains("Read-only file system") || message.contains("Permission denied");
		return deniedWrite && message.contains(registrations.getFileName().toString());
	}

	/**
	 * Returns the arguments of {@code git add} that mark paths as intended additions.
	 *
	 * @param paths the paths that exist
	 * @return the arguments
	 * @throws NullPointerException if {@code paths} is null
	 */
	public static String[] intentToAdd(List<String> paths)
	{
		requireThat(paths, "paths").isNotNull();
		return concatenate(List.of("add", "--intent-to-add", "--force", "--"), paths);
	}

	/**
	 * Returns the arguments of {@code git diff} that compare paths against {@code HEAD}.
	 *
	 * @param paths the paths to compare
	 * @return the arguments
	 * @throws NullPointerException if {@code paths} is null
	 */
	public static String[] diffAgainstHead(List<String> paths)
	{
		requireThat(paths, "paths").isNotNull();
		return concatenate(List.of("diff", "--no-color", "--no-ext-diff", "HEAD", "--"), paths);
	}

	/**
	 * Returns the text of a diff.
	 *
	 * @param lines the lines that {@code git diff} printed
	 * @return the diff, or an empty string if Git printed nothing
	 * @throws NullPointerException if {@code lines} is null
	 */
	public static String diffText(List<String> lines)
	{
		requireThat(lines, "lines").isNotNull();
		if (lines.isEmpty())
			return "";
		return String.join("\n", lines) + "\n";
	}

	/**
	 * Concatenates a Git command and its paths.
	 *
	 * @param command the subcommand and options
	 * @param paths the paths
	 * @return the arguments
	 */
	private static String[] concatenate(List<String> command, List<String> paths)
	{
		List<String> arguments = new ArrayList<>(command);
		arguments.addAll(paths);
		return arguments.toArray(String[]::new);
	}
}
