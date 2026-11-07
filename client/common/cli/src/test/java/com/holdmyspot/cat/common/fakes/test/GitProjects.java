package com.holdmyspot.cat.common.fakes.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.SessionDirectory;
import com.holdmyspot.cat.common.SessionHarness;
import com.holdmyspot.cat.common.SessionIgnore;
import com.holdmyspot.cat.common.secrets.SessionDirectorySecrets;
import java.io.IOException;
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
 * {@code git rev-parse --is-inside-work-tree} give in each situation of {@code GitQueriesContract}, which
 * {@code GitProjectsContractTest} runs on these answers and {@code SessionIgnoreTest} on the questions of
 * {@link SessionIgnore#GIT_PROCESS}. The project's files are left as they are: a test whose project must be a work tree
 * on disk creates it with {@link GitWorkTrees}.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 */
public final class GitProjects implements SessionIgnore.GitQueries
{
	/**
	 * Projects whose Git already ignores the CAT session directories, where opening a session directory leaves
	 * {@code .gitignore} alone and reports nothing.
	 */
	public static final GitProjects IGNORING = reporting(0, true);
	/**
	 * Projects that are Git work trees that do not ignore the CAT session directories.
	 */
	public static final GitProjects NOT_IGNORING = reporting(1, true);

	private final int checkIgnoreStatus;
	private final boolean insideWorkTree;

	/**
	 * Creates the questions.
	 *
	 * @param checkIgnoreStatus the exit status that the check of the CAT session directories gives
	 * @param insideWorkTree    {@code true} if the project is inside a work tree
	 */
	private GitProjects(int checkIgnoreStatus, boolean insideWorkTree)
	{
		this.checkIgnoreStatus = checkIgnoreStatus;
		this.insideWorkTree = insideWorkTree;
	}

	/**
	 * Creates questions that give the same answers for every project.
	 *
	 * @param checkIgnoreStatus the exit status that the check of the CAT session directories gives
	 * @param insideWorkTree    {@code true} if the project is inside a work tree
	 * @return the questions
	 */
	public static GitProjects reporting(int checkIgnoreStatus, boolean insideWorkTree)
	{
		return new GitProjects(checkIgnoreStatus, insideWorkTree);
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
	public int checkIgnore(Path directory, String path)
	{
		requireThat(directory, "directory").isNotNull();
		requireThat(path, "path").isNotNull();
		return checkIgnoreStatus;
	}

	@Override
	public List<String> isInsideWorkTree(Path directory)
	{
		requireThat(directory, "directory").isNotNull();
		return List.of(Boolean.toString(insideWorkTree));
	}
}
