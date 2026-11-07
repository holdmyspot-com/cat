package com.holdmyspot.cat.common.fakes.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.SessionDirectory;
import com.holdmyspot.cat.common.SessionHarness;
import com.holdmyspot.cat.common.SessionIgnore;
import com.holdmyspot.cat.common.secrets.SessionDirectorySecrets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/**
 * Answers what Git says about a project that holds the CAT session directories, without starting Git: a test does not
 * depend on whether the build's temporary directory sits inside a Git repository, which ignore files the user's Git
 * configuration reads, or how long a process takes to start.
 * <p>
 * The answers are the ones that {@code git check-ignore --quiet --no-index} and
 * {@code git rev-parse --is-inside-work-tree} give for the two kinds of work tree that {@link GitWorkTrees} writes:
 * exit status 0 for {@link GitWorkTrees#ignoring(Path)}, which lists the entry in {@code .git/info/exclude}, and 1 for
 * {@link GitWorkTrees#notIgnoring(Path)}, which lists nothing, and {@code true} for both. Before the first question
 * about a directory that holds no work tree's {@code .git/HEAD}, it writes the files of the work tree, so that tests
 * that read the project see one. A directory that already holds a work tree is left as it is.
 * {@link #reporting(int, boolean)} answers with given values and writes nothing, for the cases that Git does not
 * produce on demand.
 */
public final class GitProjects implements SessionIgnore.GitQueries
{
	/**
	 * Projects whose Git already ignores the CAT session directories, where opening a session directory leaves
	 * {@code .gitignore} alone and reports nothing.
	 */
	public static final GitProjects IGNORING = new GitProjects(true, true, 0, true);
	/**
	 * Projects that are Git work trees that do not ignore the CAT session directories.
	 */
	public static final GitProjects NOT_IGNORING = new GitProjects(true, false, 1, true);
	/**
	 * Serializes the creation of work trees, so that two threads that ask about one directory do not both create it.
	 */
	private static final Object MONITOR = new Object();

	private final boolean preparesWorkTree;
	private final boolean ignoring;
	private final int checkIgnoreStatus;
	private final boolean insideWorkTree;

	/**
	 * Creates the questions.
	 *
	 * @param preparesWorkTree  {@code true} if a project that is not yet a work tree becomes one
	 * @param ignoring          {@code true} if a project that becomes a work tree ignores the CAT session directories
	 * @param checkIgnoreStatus the exit status that the check of the CAT session directories gives
	 * @param insideWorkTree    {@code true} if the project is inside a work tree
	 */
	private GitProjects(boolean preparesWorkTree, boolean ignoring, int checkIgnoreStatus, boolean insideWorkTree)
	{
		this.preparesWorkTree = preparesWorkTree;
		this.ignoring = ignoring;
		this.checkIgnoreStatus = checkIgnoreStatus;
		this.insideWorkTree = insideWorkTree;
	}

	/**
	 * Creates questions that give the same answers for every project, as Git does when it cannot decide, for example
	 * because the project is a bare repository or the check fails. The project stays as it is.
	 *
	 * @param checkIgnoreStatus the exit status that the check of the CAT session directories gives
	 * @param insideWorkTree    {@code true} if the project is inside a work tree
	 * @return the questions
	 */
	public static GitProjects reporting(int checkIgnoreStatus, boolean insideWorkTree)
	{
		return new GitProjects(false, false, checkIgnoreStatus, insideWorkTree);
	}

	/**
	 * Opens a session's CAT session directory in a project whose Git already ignores the CAT session directories.
	 *
	 * @param projectRoot            the session's project
	 * @param sessionId              the harness's session ID, a UUID in canonical form
	 * @param harness                the harness whose session owns the directory
	 * @param configurationDirectory the harness's configuration directory
	 * @param now                    the time that a new marker records
	 * @param json                   the JSON mapper that writes and reads markers
	 * @return the CAT session directory
	 * @throws NullPointerException if any argument is null
	 * @throws IOException          if {@code sessionId} is not a UUID in canonical form, or if the directory or marker
	 *                              cannot be created
	 */
	public static SessionDirectory openSession(Path projectRoot, String sessionId, SessionHarness harness,
		Path configurationDirectory, Instant now, JsonMapper json) throws IOException
	{
		return IGNORING.open(projectRoot, sessionId, harness, configurationDirectory, now, json);
	}

	/**
	 * Opens a session's CAT session directory, deciding about the project's {@code .gitignore} from the answers of these
	 * questions.
	 *
	 * @param projectRoot            the session's project
	 * @param sessionId              the harness's session ID, a UUID in canonical form
	 * @param harness                the harness whose session owns the directory
	 * @param configurationDirectory the harness's configuration directory
	 * @param now                    the time that a new marker records
	 * @param json                   the JSON mapper that writes and reads markers
	 * @return the CAT session directory
	 * @throws NullPointerException if any argument is null
	 * @throws IOException          if {@code sessionId} is not a UUID in canonical form, or if the directory or marker
	 *                              cannot be created
	 */
	public SessionDirectory open(Path projectRoot, String sessionId, SessionHarness harness,
		Path configurationDirectory, Instant now, JsonMapper json) throws IOException
	{
		return SessionDirectorySecrets.getSessionDirectoryAccess().open(projectRoot, sessionId, harness,
			configurationDirectory, now, json, this);
	}

	@Override
	public int checkIgnore(Path directory, String path) throws IOException
	{
		requireThat(directory, "directory").isNotNull();
		requireThat(path, "path").isNotNull();
		prepare(directory);
		return checkIgnoreStatus;
	}

	@Override
	public List<String> isInsideWorkTree(Path directory) throws IOException
	{
		requireThat(directory, "directory").isNotNull();
		prepare(directory);
		return List.of(Boolean.toString(insideWorkTree));
	}

	/**
	 * Makes a directory a work tree unless these questions leave projects as they are or it already holds the
	 * {@code .git/HEAD} of one.
	 *
	 * @param directory the project
	 * @throws IOException if the work tree cannot be created
	 */
	private void prepare(Path directory) throws IOException
	{
		synchronized (MONITOR)
		{
			if (!preparesWorkTree || Files.exists(directory.resolve(".git/HEAD")))
				return;
			if (ignoring)
				GitWorkTrees.ignoring(directory);
			else
				GitWorkTrees.notIgnoring(directory);
		}
	}
}
