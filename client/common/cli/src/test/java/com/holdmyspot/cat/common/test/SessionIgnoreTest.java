package com.holdmyspot.cat.common.test;

import static com.holdmyspot.cat.common.test.SessionHarnesses.HARNESS_A;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.SessionDirectory;
import com.holdmyspot.cat.common.SessionIgnore;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.GitProjects;
import com.holdmyspot.cat.common.fakes.test.GitWorkTrees;
import com.holdmyspot.cat.common.git.Git;
import com.holdmyspot.cat.common.secrets.SessionIgnoreSecrets;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests that creating a project's first CAT session directory keeps the CAT session directories out of Git through
 * the project's {@code .gitignore}.
 * <p>
 * {@link #addsLinesInRealWorkTree()} asks real Git about a work tree that {@code git init} creates. Each other test
 * that opens a session directory gets the answers of {@link GitProjects}, which {@link #gitProcessAnswersAsGit()} shows
 * to be those of the questions that run Git, by running the {@link GitQueriesContract} on both. The decision from Git's
 * exit status and output is tested by calling
 * {@link SessionIgnore#decide(Integer, java.util.function.BooleanSupplier)} with the statuses that Git gives.
 */
public final class SessionIgnoreTest
{
	private static final String COMMENT = "# Per-session files of the CAT plugin for Claude Code and Codex " +
		"(https://github.com/holdmyspot-com/cat)";
	private static final String LINES = COMMENT + "\n/.cat/sessions/\n";
	private static final Instant CREATED = Instant.parse("2026-11-01T12:00:00Z");

	/**
	 * Creates a test instance.
	 */
	public SessionIgnoreTest()
	{
	}

	/**
	 * Verifies, end to end through real Git, that the first session of a Git work tree that does not ignore the CAT
	 * session directories adds CAT's lines to the project's {@code .gitignore}, which Git then lists as changed but
	 * unstaged, and tells the user.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void addsLinesInRealWorkTree() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = GitWorkTrees.create(cleanup.add(Files.createTempDirectory("cat-ignore-")).resolve("project"));
			Files.writeString(project.resolve(".gitignore"), "build/", UTF_8);
			new Git(project).run("add", ".gitignore");
			new Git(project).run("commit", "--quiet", "-m", "ignore");

			SessionDirectory session = open(project);

			requireThat(Files.readString(project.resolve(".gitignore"), UTF_8), "gitignore").
				isEqualTo("build/\n" + LINES);
			requireThat(session.ignoreNotice(), "ignoreNotice").isPresent();
			requireThat(new Git(project).lines("status", "--porcelain"), "status").isEqualTo(List.of(" M .gitignore"));
		}
	}

	/**
	 * Verifies that the first session of a work tree whose Git does not ignore the CAT session directories appends the
	 * comment and the entry after ending the existing content with a line feed, and tells the user, while the agent's
	 * form of the notice tells the agent that the change is the user's to commit; a later session adds nothing and
	 * says nothing.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void addsLinesOnceWhenNotIgnored() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = Files.createDirectories(cleanup.add(Files.createTempDirectory("cat-ignore-")).
				resolve("project"));
			Files.writeString(project.resolve(".gitignore"), "build/", UTF_8);

			SessionDirectory first = open(project, GitProjects.NOT_IGNORING);
			SessionDirectory second = open(project, GitProjects.NOT_IGNORING);

			requireThat(Files.readString(project.resolve(".gitignore"), UTF_8), "gitignore").
				isEqualTo("build/\n" + LINES);
			SessionIgnore.IgnoreNotice notice = first.ignoreNotice().orElseThrow();
			requireThat(notice.forUser(), "forUser").contains("CAT added /.cat/sessions/ to " +
				project.resolve(".gitignore")).contains("Commit the change when convenient.");
			requireThat(notice.forAgent(), "forAgent").contains("CAT added /.cat/sessions/ to ").
				contains("this change is theirs to commit; do not stage or commit it.").
				doesNotContain("Commit the change when convenient.");
			requireThat(second.ignoreNotice(), "second.ignoreNotice").isEmpty();
		}
	}

	/**
	 * Verifies that reopening a session's existing CAT session directory leaves {@code .gitignore} alone and reports
	 * nothing, even though Git does not ignore the CAT session directories.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void reopeningSessionChangesNothing() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = Files.createDirectories(cleanup.add(Files.createTempDirectory("cat-ignore-")).
				resolve("project"));
			String sessionId = UUID.randomUUID().toString();
			GitProjects.IGNORING.open(project, sessionId, HARNESS_A, project.resolve("configuration"), CREATED,
				JsonMapper.builder().build());

			SessionDirectory reopened = GitProjects.NOT_IGNORING.open(project, sessionId, HARNESS_A,
				project.resolve("configuration"), CREATED, JsonMapper.builder().build());

			requireThat(Files.exists(project.resolve(".gitignore")), "gitignoreExists").isFalse();
			requireThat(reopened.ignoreNotice(), "ignoreNotice").isEmpty();
		}
	}

	/**
	 * Verifies that a work tree without a {@code .gitignore}, whose Git does not ignore the CAT session directories,
	 * receives one that holds only CAT's lines.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void createsGitignoreWhenAbsent() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = Files.createDirectories(cleanup.add(Files.createTempDirectory("cat-ignore-")).
				resolve("project"));

			SessionDirectory session = open(project, GitProjects.NOT_IGNORING);

			requireThat(Files.readString(project.resolve(".gitignore"), UTF_8), "gitignore").isEqualTo(LINES);
			requireThat(session.ignoreNotice(), "ignoreNotice").isPresent();
		}
	}

	/**
	 * Verifies that a project that Git ignores the CAT session directories in gets no {@code .gitignore} and no notice.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void leavesGitignoreWhenGitIgnores() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = Files.createDirectories(cleanup.add(Files.createTempDirectory("cat-ignore-")).
				resolve("project"));

			SessionDirectory session = open(project, GitProjects.IGNORING);

			requireThat(Files.exists(project.resolve(".gitignore")), "gitignoreExists").isFalse();
			requireThat(session.ignoreNotice(), "ignoreNotice").isEmpty();
		}
	}

	/**
	 * Verifies that a project that is the directory of a bare repository gets no {@code .gitignore} and no notice,
	 * because Git, which then fails the check and says that the project is not in a work tree, cannot say what it
	 * ignores outside a work tree.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void writesNothingInBareRepository() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = Files.createDirectories(cleanup.add(Files.createTempDirectory("cat-ignore-")).
				resolve("project"));

			SessionDirectory session = open(project, GitProjects.reporting(128, false));

			requireThat(Files.exists(project.resolve(".gitignore")), "gitignoreExists").isFalse();
			requireThat(session.ignoreNotice(), "ignoreNotice").isEmpty();
		}
	}

	/**
	 * Verifies that when Git fails to tell whether it ignores the CAT session directories in a work tree, here with exit
	 * status 128, as when {@code .cat} is a symbolic link, CAT leaves {@code .gitignore} alone and tells the user that
	 * it could not add the entry, naming Git's exit status.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void failingCheckInWorkTreeReports() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = Files.createDirectories(cleanup.add(Files.createTempDirectory("cat-ignore-")).
				resolve("project"));

			SessionDirectory session = open(project, GitProjects.reporting(128, true));

			requireThat(Files.exists(project.resolve(".gitignore")), "gitignoreExists").isFalse();
			SessionIgnore.IgnoreNotice notice = session.ignoreNotice().orElseThrow();
			requireThat(notice.forUser(), "forUser").
				contains("CAT could not add /.cat/sessions/ to " + project.resolve(".gitignore") + ": ").
				contains("git check-ignore exited with status 128");
			requireThat(notice.forAgent(), "forAgent").contains("do not change that file yourself.");
		}
	}

	/**
	 * Verifies what CAT does for each status that Git gives for the check of the CAT session directories and each
	 * answer to whether the project is in a work tree, including the cases that real Git does not produce on demand,
	 * such as Git failing to start.
	 */
	@Test
	public void decidesFromGitStatus()
	{
		requireThat(SessionIgnore.decide(0, () -> true), "ignored").isEqualTo(SessionIgnore.Action.NOTHING);
		requireThat(SessionIgnore.decide(1, () -> true), "notIgnored").isEqualTo(SessionIgnore.Action.ADD_ENTRY);
		requireThat(SessionIgnore.decide(null, () -> true), "gitCannotStart").isEqualTo(SessionIgnore.Action.NOTHING);
		requireThat(SessionIgnore.decide(128, () -> true), "failedInWorkTree").
			isEqualTo(SessionIgnore.Action.REPORT_UNKNOWN);
		requireThat(SessionIgnore.decide(128, () -> false), "failedOutsideWorkTree").
			isEqualTo(SessionIgnore.Action.NOTHING);
		requireThat(SessionIgnore.decide(2, () -> true), "otherStatusInWorkTree").
			isEqualTo(SessionIgnore.Action.REPORT_UNKNOWN);
	}

	/**
	 * Verifies that a {@code .gitignore} that already holds CAT's entry gets no second copy and no notice, even when
	 * Git does not ignore the CAT session directories, as when a later line of the file negates the entry.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void existingEntryIsNotRepeated() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = Files.createDirectories(cleanup.add(Files.createTempDirectory("cat-ignore-")).
				resolve("project"));
			String content = "/.cat/sessions/\n!/.cat/sessions/\n";
			Files.writeString(project.resolve(".gitignore"), content, UTF_8);

			SessionDirectory session = open(project, GitProjects.NOT_IGNORING);

			requireThat(Files.readString(project.resolve(".gitignore"), UTF_8), "gitignore").isEqualTo(content);
			requireThat(session.ignoreNotice(), "ignoreNotice").isEmpty();
		}
	}

	/**
	 * Verifies that a project on a file system where Git cannot run, here a ZIP file system, gets its session directory
	 * and counts as outside a Git work tree: no {@code .gitignore} and no notice, whatever Git would answer.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void otherFileSystemCountsAsOutsideGit() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			FileSystem isolated = FileSystems.newFileSystem(cleanup.add(Files.createTempDirectory("cat-ignore-")).
				resolve("project.zip"), Map.of("create", "true")))
		{
			Path project = isolated.getPath("/");

			SessionDirectory session = open(project, GitProjects.NOT_IGNORING);

			requireThat(session.temp(), "temp").isDirectory();
			requireThat(Files.exists(project.resolve(".gitignore")), "gitignoreExists").isFalse();
			requireThat(session.ignoreNotice(), "ignoreNotice").isEmpty();
		}
	}

	/**
	 * Verifies that sessions that start at once in a work tree whose Git does not ignore the CAT session directories
	 * leave one copy of CAT's lines, and that exactly one of them tells the user.
	 *
	 * @throws IOException if the fixture cannot be created
	 * @throws InterruptedException if the test is interrupted
	 * @throws ExecutionException if a session fails to open its directory
	 */
	@Test
	public void concurrentFirstSessionsLeaveOneEntry() throws IOException, InterruptedException, ExecutionException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			ExecutorService executor = Executors.newFixedThreadPool(8))
		{
			Path project = Files.createDirectories(cleanup.add(Files.createTempDirectory("cat-ignore-")).
				resolve("project"));
			List<Callable<SessionDirectory>> sessions = new ArrayList<>();
			for (int index = 0; index < 8; ++index)
				sessions.add(() -> open(project, GitProjects.NOT_IGNORING));

			int notices = 0;
			for (Future<SessionDirectory> session : executor.invokeAll(sessions))
			{
				if (session.get().ignoreNotice().isPresent())
					++notices;
			}

			requireThat(Files.readString(project.resolve(".gitignore"), UTF_8), "gitignore").isEqualTo(LINES);
			requireThat(notices, "notices").isEqualTo(1);
		}
	}

	/**
	 * Verifies that a {@code .gitignore} that cannot be written, here a directory, in a work tree whose Git does not
	 * ignore the CAT session directories, leaves the session directory in place and tells the user that CAT could not
	 * add the entry and why.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void failingWriteReportsAndContinues() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = Files.createDirectories(cleanup.add(Files.createTempDirectory("cat-ignore-")).
				resolve("project"));
			Files.createDirectories(project.resolve(".gitignore"));

			SessionDirectory session = open(project, GitProjects.NOT_IGNORING);

			requireThat(session.temp(), "temp").isDirectory();
			requireThat(session.ignoreNotice().orElseThrow().forUser(), "notice").
				contains("CAT could not add /.cat/sessions/ to " + project.resolve(".gitignore") + ": ").
				contains("Exception: ");
		}
	}

	/**
	 * Verifies that the questions of {@link SessionIgnore#GIT_PROCESS} run {@code git check-ignore} and
	 * {@code git rev-parse} in the project and report Git's exit status and output, for a project in each situation of
	 * the {@link GitQueriesContract}. Each Git process is replaced by one that has already exited with Git's answer to
	 * the command that the question runs, so no Git starts; any other command fails the test.
	 *
	 * @throws IOException if the project directory cannot be created
	 */
	@Test
	public void gitProcessAnswersAsGit() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-ignore-")).toAbsolutePath();

			new GitQueriesContract(SessionIgnoreTest::gitAnswering).answersAsGit(project);
		}
	}

	/**
	 * Verifies which {@code .gitignore} contents are an earlier content followed by CAT's lines.
	 */
	@Test
	public void recognizesAppendedLines()
	{
		requireThat(SessionIgnore.isAppended("", LINES), "fromEmpty").isTrue();
		requireThat(SessionIgnore.isAppended("a", "a\n" + LINES), "withoutFinalLineFeed").isTrue();
		requireThat(SessionIgnore.isAppended("a\r\n", "a\r\n" + LINES.replace("\n", "\r\n")), "crlf").isTrue();
		requireThat(SessionIgnore.isAppended("a\n", "b\n" + LINES), "otherChange").isFalse();
		requireThat(SessionIgnore.isAppended("a\n", "a\n" + LINES + "b\n"), "extraLine").isFalse();
		requireThat(SessionIgnore.isAppended("a\n", "a\n/.cat/sessions/\n"), "withoutComment").isFalse();
	}

	/**
	 * Returns the questions of {@link SessionIgnore#GIT_PROCESS} with each Git process replaced by one that has already
	 * exited with Git's answer in a project's situation.
	 *
	 * @param project   the project, which Git must run in
	 * @param situation the project's situation in Git
	 * @return the questions
	 */
	private static SessionIgnore.GitQueries gitAnswering(Path project, GitQueriesContract.Situation situation)
	{
		return SessionIgnoreSecrets.getSessionIgnoreAccess().gitProcess(builder ->
		{
			List<String> command = builder.command();
			if (!project.toFile().equals(builder.directory()))
				throw new AssertionError("Git runs in " + builder.directory() + " instead of " + project);
			if (command.equals(List.of("git", "check-ignore", "--quiet", "--no-index", GitQueriesContract.PROBE)))
				return new ExitedProcess(builder, situation.checkIgnoreStatus(), "", "");
			if (command.equals(List.of("git", "rev-parse", "--is-inside-work-tree")))
				return new ExitedProcess(builder, 0, situation.insideWorkTree() + "\n", "");
			throw new AssertionError("Unexpected Git command: " + command);
		});
	}

	/**
	 * Opens the CAT session directory of a new session, asking real Git about the project.
	 *
	 * @param project the project
	 * @return the session directory
	 * @throws IOException if the directory cannot be created
	 */
	private static SessionDirectory open(Path project) throws IOException
	{
		return SessionDirectory.open(project, UUID.randomUUID().toString(), HARNESS_A, project.resolve("configuration"),
			CREATED, JsonMapper.builder().build());
	}

	/**
	 * Opens the CAT session directory of a new session, with the answers that the given questions give about the
	 * project.
	 *
	 * @param project the project
	 * @param git     the questions that answer for Git
	 * @return the session directory
	 * @throws IOException if the directory cannot be created
	 */
	private static SessionDirectory open(Path project, GitProjects git) throws IOException
	{
		return git.open(project, UUID.randomUUID().toString(), HARNESS_A, project.resolve("configuration"), CREATED,
			JsonMapper.builder().build());
	}
}
