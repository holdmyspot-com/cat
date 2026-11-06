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
 * Asks real Git whether a project ignores the CAT session directories, after making the project the kind of Git work
 * tree that a test needs: a test does not depend on whether the build's temporary directory sits inside a Git
 * repository or which ignore files the user's Git configuration reads.
 * <p>
 * Before the first question about a directory that holds no work tree's {@code .git/HEAD}, it writes the files that
 * make the directory a work tree ({@link GitWorkTrees#ignoring(Path)} or {@link GitWorkTrees#notIgnoring(Path)}). A
 * directory that already holds a work tree is left as it is. Git itself answers every question, in about 3.5 ms.
 */
public final class GitProjects implements SessionIgnore.GitQueries
{
	/**
	 * Projects whose Git already ignores the CAT session directories, where opening a session directory leaves
	 * {@code .gitignore} alone and reports nothing.
	 */
	public static final GitProjects IGNORING = new GitProjects(true);
	/**
	 * Projects that are Git work trees that do not ignore the CAT session directories.
	 */
	public static final GitProjects NOT_IGNORING = new GitProjects(false);
	/**
	 * Serializes the creation of work trees, so that two threads that ask about one directory do not both create it.
	 */
	private static final Object MONITOR = new Object();

	private final boolean ignoring;

	/**
	 * Creates the questions.
	 *
	 * @param ignoring {@code true} if a project that is not yet a work tree becomes one that ignores the CAT session
	 *   directories
	 */
	private GitProjects(boolean ignoring)
	{
		this.ignoring = ignoring;
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
	 * Opens a session's CAT session directory, deciding about the project's {@code .gitignore} from what real Git says
	 * about the project.
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
		return SessionIgnore.GIT_PROCESS.checkIgnore(directory, path);
	}

	@Override
	public List<String> isInsideWorkTree(Path directory) throws IOException
	{
		requireThat(directory, "directory").isNotNull();
		prepare(directory);
		return SessionIgnore.GIT_PROCESS.isInsideWorkTree(directory);
	}

	/**
	 * Makes a directory a work tree unless it already holds the {@code .git/HEAD} of one.
	 *
	 * @param directory the project
	 * @throws IOException if the work tree cannot be created
	 */
	private void prepare(Path directory) throws IOException
	{
		synchronized (MONITOR)
		{
			if (Files.exists(directory.resolve(".git/HEAD")))
				return;
			if (ignoring)
				GitWorkTrees.ignoring(directory);
			else
				GitWorkTrees.notIgnoring(directory);
		}
	}
}
