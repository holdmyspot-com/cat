package com.holdmyspot.cat.common;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

import com.holdmyspot.cat.common.git.Git;
import com.holdmyspot.cat.common.secrets.SessionIgnoreSecrets;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * Keeps the CAT session directories out of Git by adding them to the {@code .gitignore} of the project's root
 * directory.
 * <p>
 * CAT appends {@link #BLOCK}, a comment that names the plugin and the entry {@link #ENTRY}, when the project is in a
 * Git work tree whose ignore sources do not already ignore the CAT session directories. It never stages or commits the
 * change: the user commits it. Outside a Git work tree it writes nothing.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe. Concurrent additions, in this process or in others, leave one
 * entry, because each holds an exclusive lock on {@code .gitignore} itself while it reads the file and appends to it.
 */
public final class SessionIgnore
{
	static
	{
		SessionIgnoreSecrets.setSessionIgnoreAccess(SessionIgnore::gitProcess);
	}

	/**
	 * The {@code .gitignore} entry that ignores the CAT session directories of the project.
	 */
	public static final String ENTRY = "/" + SessionDirectory.ROOT + "/";
	/**
	 * The lines that CAT appends to {@code .gitignore}, each ending with a line feed.
	 */
	public static final String BLOCK = "# Per-session files of the CAT plugin for Claude Code and Codex " +
		"(https://github.com/holdmyspot-com/cat)\n" + ENTRY + "\n";
	/**
	 * The file name of the ignore file that CAT changes.
	 */
	public static final String FILE_NAME = ".gitignore";
	/**
	 * Serializes this process's additions: closing any channel on a file releases every lock that this process holds on
	 * it, so two threads must not hold channels on {@code .gitignore} at once.
	 */
	private static final Object MONITOR = new Object();
	/**
	 * Answers {@link #ensure(Path)}'s questions by running Git in the project, as every CAT session directory that a
	 * harness's hook or tool opens asks them.
	 */
	public static final GitQueries GIT_PROCESS = gitProcess(ProcessBuilder::start);

	/**
	 * Prevents instantiation.
	 */
	private SessionIgnore()
	{
	}

	/**
	 * Returns questions that run Git in the project, starting each Git process through a launcher.
	 *
	 * @param launcher starts the configured Git process
	 * @return the questions
	 * @throws NullPointerException if {@code launcher} is null
	 */
	private static GitQueries gitProcess(ExecutableFiles.ProcessLauncher launcher)
	{
		requireThat(launcher, "launcher").isNotNull();
		return new GitQueries()
		{
			@Override
			public int checkIgnore(Path directory, String path) throws IOException
			{
				return new Git(directory, launcher).status("check-ignore", "--quiet", "--no-index", path);
			}

			@Override
			public List<String> isInsideWorkTree(Path directory) throws IOException
			{
				return new Git(directory, launcher).lines("rev-parse", "--is-inside-work-tree");
			}
		};
	}

	/**
	 * Adds {@link #BLOCK} to the {@code .gitignore} of a project's root directory unless Git already ignores the CAT
	 * session directories there, or the project is not in a Git work tree.
	 *
	 * A project on a file system other than the default one, where Git cannot run, counts as outside a Git work tree.
	 *
	 * @param projectRoot the project's root directory
	 * @return the notice that CAT added the lines, or that it could not add them and why; empty if CAT changed nothing
	 *   because nothing needed to change
	 * @throws NullPointerException if {@code projectRoot} is null
	 */
	public static Optional<IgnoreNotice> ensure(Path projectRoot)
	{
		return ensure(projectRoot, GIT_PROCESS);
	}

	/**
	 * Adds {@link #BLOCK} to the {@code .gitignore} of a project's root directory, as {@link #ensure(Path)} does,
	 * deciding from the answers that a given Git gives.
	 *
	 * @param projectRoot the project's root directory
	 * @param git         answers what Git ignores in the project and whether the project is in a work tree
	 * @return the notice that CAT added the lines, or that it could not add them and why; empty if CAT changed nothing
	 *   because nothing needed to change
	 * @throws NullPointerException if any argument is null
	 */
	static Optional<IgnoreNotice> ensure(Path projectRoot, GitQueries git)
	{
		requireThat(projectRoot, "projectRoot").isNotNull();
		requireThat(git, "git").isNotNull();
		// Git runs in a directory of the default file system only
		if (!projectRoot.getFileSystem().equals(FileSystems.getDefault()))
			return Optional.empty();
		Path project = projectRoot.toAbsolutePath().normalize();
		Path file = project.resolve(FILE_NAME);
		Integer status;
		try
		{
			status = git.checkIgnore(project, SessionDirectory.ROOT + "/probe");
		}
		catch (IOException _)
		{
			// Git cannot start, so the project is not in a Git work tree that CAT can inspect
			status = null;
		}
		Action action = decide(status, () -> isInsideWorkTree(git, project));
		if (action == Action.NOTHING)
			return Optional.empty();
		if (action == Action.REPORT_UNKNOWN)
		{
			return Optional.of(failure(file, "Git could not tell whether it ignores " + SessionDirectory.ROOT +
				" (git check-ignore exited with status " + status + ")"));
		}
		try
		{
			if (!append(file))
				return Optional.empty();
		}
		catch (IOException e)
		{
			return Optional.of(failure(file, describe(e)));
		}
		String added = "CAT added " + ENTRY + " to " + file + " so that its per-session files stay out of Git.";
		return Optional.of(new IgnoreNotice(added + " Commit the change when convenient.",
			added + " Tell the user that this change is theirs to commit; do not stage or commit it."));
	}

	/**
	 * Decides what CAT does about a project's {@code .gitignore} from what Git said about the project.
	 *
	 * @param checkIgnoreStatus the exit status of {@code git check-ignore --quiet --no-index} for a path in the CAT
	 *   session directories, or {@code null} if Git cannot start
	 * @param insideWorkTree    tells whether Git reports that the project is inside a work tree; it is consulted only
	 *   when {@code checkIgnoreStatus} is neither {@code 0} nor {@code 1}
	 * @return what CAT does
	 * @throws NullPointerException if {@code insideWorkTree} is null
	 */
	public static Action decide(Integer checkIgnoreStatus, BooleanSupplier insideWorkTree)
	{
		requireThat(insideWorkTree, "insideWorkTree").isNotNull();
		if (checkIgnoreStatus == null || checkIgnoreStatus == 0)
			return Action.NOTHING;
		if (checkIgnoreStatus == 1)
			return Action.ADD_ENTRY;
		if (insideWorkTree.getAsBoolean())
			return Action.REPORT_UNKNOWN;
		return Action.NOTHING;
	}

	/**
	 * Indicates whether a text is a {@code .gitignore} content followed by {@link #BLOCK}, as {@link #ensure(Path)}
	 * appends it.
	 *
	 * @param before the content before CAT appended the lines, empty if the file did not exist
	 * @param after the current content
	 * @return {@code true} if {@code after} is {@code before}, ended with a line feed if it was not empty, followed by
	 *   {@link #BLOCK}, ignoring the difference between CRLF and LF line endings
	 * @throws NullPointerException if any argument is null
	 */
	public static boolean isAppended(String before, String after)
	{
		requireThat(before, "before").isNotNull();
		requireThat(after, "after").isNotNull();
		String original = before.replace("\r\n", "\n");
		if (!original.isEmpty() && !original.endsWith("\n"))
			original += "\n";
		return after.replace("\r\n", "\n").equals(original + BLOCK);
	}

	/**
	 * Indicates whether a directory is in a Git work tree.
	 *
	 * @param git       answers whether the directory is in a work tree
	 * @param directory the directory
	 * @return {@code true} if Git reports that the directory is inside a work tree
	 */
	private static boolean isInsideWorkTree(GitQueries git, Path directory)
	{
		try
		{
			List<String> lines = git.isInsideWorkTree(directory);
			return lines.equals(List.of("true"));
		}
		catch (IOException _)
		{
			return false;
		}
	}

	/**
	 * Appends {@link #BLOCK} to an ignore file unless one of its lines is {@link #ENTRY}.
	 *
	 * @param file the ignore file, which is created if absent
	 * @return {@code true} if this call appended the lines
	 * @throws IOException if the file cannot be opened, locked, read or written
	 */
	private static boolean append(Path file) throws IOException
	{
		synchronized (MONITOR)
		{
			try (FileChannel channel = FileChannel.open(file, CREATE, READ, WRITE);
				FileLock _ = channel.lock())
			{
				String content = read(channel);
				for (String line : content.split("\n", -1))
				{
					if (line.strip().equals(ENTRY))
						return false;
				}
				String addition = BLOCK;
				if (!content.isEmpty() && !content.endsWith("\n"))
					addition = "\n" + addition;
				ByteBuffer bytes = ByteBuffer.wrap(addition.getBytes(UTF_8));
				long position = channel.size();
				while (bytes.hasRemaining())
					position += channel.write(bytes, position);
				channel.force(false);
				return true;
			}
		}
	}

	/**
	 * Reads a file's content through its channel, which keeps the lock that the channel holds.
	 *
	 * @param channel the file's channel
	 * @return the content
	 * @throws IOException if the file cannot be read
	 */
	private static String read(FileChannel channel) throws IOException
	{
		ByteArrayOutputStream content = new ByteArrayOutputStream();
		ByteBuffer buffer = ByteBuffer.allocate(8192);
		long position = 0;
		while (true)
		{
			buffer.clear();
			int count = channel.read(buffer, position);
			if (count < 0)
				break;
			content.write(buffer.array(), 0, count);
			position += count;
		}
		return content.toString(UTF_8);
	}

	/**
	 * Returns the notice that CAT could not add the lines.
	 *
	 * @param file the ignore file
	 * @param cause why CAT could not add them
	 * @return the notice
	 */
	private static IgnoreNotice failure(Path file, String cause)
	{
		String failed = "CAT could not add " + ENTRY + " to " + file + ": " + cause + ". Until that file holds the " +
			"line " + ENTRY + ", Git lists CAT's per-session files as untracked.";
		return new IgnoreNotice(failed, failed + " Tell the user; do not change that file yourself.");
	}

	/**
	 * Describes a failure in one line.
	 *
	 * @param failure the failure
	 * @return the failure's type and message, without line breaks
	 */
	private static String describe(IOException failure)
	{
		String description = failure.getClass().getSimpleName();
		String message = failure.getMessage();
		if (message != null)
			description += ": " + message;
		return description.replaceAll("\\R", " ");
	}

	/**
	 * The questions that {@link #ensure(Path)} asks Git about a project, each answered as Git answers it.
	 */
	public interface GitQueries
	{
		/**
		 * Returns how {@code git check-ignore --quiet --no-index <path>} exits in a directory: {@code 0} if Git ignores
		 * the path, {@code 1} if it does not, and another status if Git fails.
		 *
		 * @param directory the directory that Git runs in
		 * @param path      the path, relative to {@code directory}, to check
		 * @return Git's exit status
		 * @throws IOException if Git cannot start
		 */
		int checkIgnore(Path directory, String path) throws IOException;

		/**
		 * Returns what {@code git rev-parse --is-inside-work-tree} prints in a directory: {@code true} inside a work
		 * tree and {@code false} elsewhere in a repository.
		 *
		 * @param directory the directory that Git runs in
		 * @return the lines of Git's output
		 * @throws IOException if Git cannot start or fails, such as outside any repository
		 */
		List<String> isInsideWorkTree(Path directory) throws IOException;
	}

	/**
	 * What CAT does about a project's {@code .gitignore}.
	 */
	public enum Action
	{
		/**
		 * CAT leaves {@code .gitignore} alone and tells nothing: Git already ignores the CAT session directories, or
		 * the project is not in a Git work tree that CAT can inspect.
		 */
		NOTHING,
		/**
		 * CAT adds {@link #BLOCK} to {@code .gitignore}, unless a line of the file is already {@link #ENTRY}.
		 */
		ADD_ENTRY,
		/**
		 * CAT leaves {@code .gitignore} alone and tells that Git could not say whether it ignores the CAT session
		 * directories in a work tree.
		 */
		REPORT_UNKNOWN
	}

	/**
	 * What CAT tells about the lines that it added to a project's {@code .gitignore}, or could not add, in the form for
	 * each reader.
	 *
	 * @param forUser the text for a channel that only the user sees, such as a hook's {@code systemMessage}
	 * @param forAgent the text for a channel that an agent reads, such as a command's output; it tells the agent that
	 *   the change is the user's to commit and not the agent's
	 */
	public record IgnoreNotice(String forUser, String forAgent)
	{
		/**
		 * Creates a notice.
		 *
		 * @param forUser the text for a channel that only the user sees
		 * @param forAgent the text for a channel that an agent reads
		 * @throws NullPointerException if any argument is null
		 */
		public IgnoreNotice
		{
			requireThat(forUser, "forUser").isNotNull();
			requireThat(forAgent, "forAgent").isNotNull();
		}
	}
}
