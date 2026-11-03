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
 * Stands in for the Git binary that {@link SessionIgnore} asks whether a project ignores the CAT session directories,
 * so that a test opens a {@link SessionDirectory} without starting Git.
 * <p>
 * Each answer is one that real Git gives, as recorded from Git 2.43.0: {@code check-ignore --quiet --no-index} exits
 * with status 0 when Git ignores the path, 1 when it does not, and 128 in a bare repository or outside any repository;
 * {@code rev-parse --is-inside-work-tree} prints {@code true} in a work tree, prints {@code false} in a bare repository
 * and exits with status 128 outside any repository. {@code SessionIgnoreTest} observes the work-tree, already-ignored
 * and bare-repository answers through real Git.
 *
 * @param checkIgnoreStatus the exit status of {@code git check-ignore}, or null if Git cannot start
 * @param workTreeAnswer    the output lines of {@code git rev-parse --is-inside-work-tree}, or null if it fails
 */
public record GitAnswers(Integer checkIgnoreStatus, List<String> workTreeAnswer) implements SessionIgnore.GitQueries
{
	/**
	 * The answers in a project whose Git already ignores the CAT session directories, where opening a session
	 * directory leaves {@code .gitignore} alone and reports nothing.
	 */
	public static final GitAnswers IGNORED = new GitAnswers(0, List.of("true"));
	/**
	 * The answers in a Git work tree that does not ignore the CAT session directories.
	 */
	public static final GitAnswers NOT_IGNORED = new GitAnswers(1, List.of("true"));

	/**
	 * Creates the answers.
	 *
	 * @param checkIgnoreStatus the exit status of {@code git check-ignore}, or null if Git cannot start
	 * @param workTreeAnswer    the output lines of {@code git rev-parse --is-inside-work-tree}, or null if it fails
	 */
	public GitAnswers
	{
		if (workTreeAnswer != null)
			workTreeAnswer = List.copyOf(workTreeAnswer);
	}

	/**
	 * Opens a session's CAT session directory in a project whose Git already ignores the CAT session directories,
	 * without starting Git.
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
		return IGNORED.open(projectRoot, sessionId, harness, configurationDirectory, now, json);
	}

	/**
	 * Opens a session's CAT session directory, deciding about the project's {@code .gitignore} from these answers.
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
		if (checkIgnoreStatus == null)
			throw new IOException("Cannot run program \"git\": error=2, No such file or directory");
		return checkIgnoreStatus;
	}

	@Override
	public List<String> isInsideWorkTree(Path directory) throws IOException
	{
		requireThat(directory, "directory").isNotNull();
		if (workTreeAnswer == null)
		{
			throw new IOException("Git command failed: git rev-parse --is-inside-work-tree: fatal: not a git " +
				"repository (or any of the parent directories): .git");
		}
		return workTreeAnswer;
	}
}
