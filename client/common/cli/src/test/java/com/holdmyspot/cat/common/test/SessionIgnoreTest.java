package com.holdmyspot.cat.common.test;

import static com.holdmyspot.cat.common.test.SessionHarnesses.HARNESS_A;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.SessionDirectory;
import com.holdmyspot.cat.common.SessionIgnore;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.GitWorkTrees;
import com.holdmyspot.cat.common.git.Git;
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
	 * Verifies that the first session of a Git work tree that does not ignore the CAT session directories appends the
	 * comment and the entry once, after ending the existing content with a line feed, leaves the change unstaged, and
	 * tells the user, while the agent's form of the notice tells the agent that the change is the user's to commit; a
	 * later session adds nothing and says nothing.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void addsLinesOnceInWorkTree() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = GitWorkTrees.create(cleanup.add(Files.createTempDirectory("cat-ignore-")).resolve("project"));
			Files.writeString(project.resolve(".gitignore"), "build/", UTF_8);
			new Git(project).run("add", ".gitignore");
			new Git(project).run("commit", "--quiet", "-m", "ignore");

			SessionDirectory first = open(project);
			SessionDirectory second = open(project);

			requireThat(Files.readString(project.resolve(".gitignore"), UTF_8), "gitignore").
				isEqualTo("build/\n" + LINES);
			SessionIgnore.IgnoreNotice notice = first.ignoreNotice().orElseThrow();
			requireThat(notice.forUser(), "forUser").contains("CAT added /.cat/sessions/ to ").
				contains("Commit the change when convenient.");
			requireThat(notice.forAgent(), "forAgent").contains("CAT added /.cat/sessions/ to ").
				contains("this change is theirs to commit; do not stage or commit it.").
				doesNotContain("Commit the change when convenient.");
			requireThat(second.ignoreNotice(), "second.ignoreNotice").isEmpty();
			requireThat(new Git(project).lines("status", "--porcelain"), "status").isEqualTo(List.of(" M .gitignore"));
		}
	}

	/**
	 * Verifies that a Git work tree without a {@code .gitignore} receives one that holds only CAT's lines.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void createsGitignoreWhenAbsent() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = GitWorkTrees.create(cleanup.add(Files.createTempDirectory("cat-ignore-")).resolve("project"));

			SessionDirectory session = open(project);

			requireThat(Files.readString(project.resolve(".gitignore"), UTF_8), "gitignore").isEqualTo(LINES);
			requireThat(session.ignoreNotice(), "ignoreNotice").isPresent();
		}
	}

	/**
	 * Verifies that a work tree that already ignores the CAT session directories, here through the repository's local
	 * exclude file, gets no {@code .gitignore} and no notice.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void leavesGitignoreWhenAlreadyIgnored() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = GitWorkTrees.create(cleanup.add(Files.createTempDirectory("cat-ignore-")).resolve("project"));
			Files.writeString(project.resolve(".git/info/exclude"), "/.cat/\n", UTF_8);

			SessionDirectory session = open(project);

			requireThat(Files.exists(project.resolve(".gitignore")), "gitignoreExists").isFalse();
			requireThat(session.ignoreNotice(), "ignoreNotice").isEmpty();
		}
	}

	/**
	 * Verifies that a directory outside a Git work tree, here a bare repository, gets no {@code .gitignore} and no
	 * notice.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void writesNothingOutsideWorkTree() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = Files.createDirectories(cleanup.add(Files.createTempDirectory("cat-ignore-")).
				resolve("bare"));
			new Git(project).run("init", "--quiet", "--bare");

			SessionDirectory session = open(project);

			requireThat(Files.exists(project.resolve(".gitignore")), "gitignoreExists").isFalse();
			requireThat(session.ignoreNotice(), "ignoreNotice").isEmpty();
		}
	}

	/**
	 * Verifies that a project on a file system where Git cannot run, here a ZIP file system, gets its session directory
	 * and counts as outside a Git work tree: no {@code .gitignore} and no notice.
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

			SessionDirectory session = open(project);

			requireThat(session.temp(), "temp").isDirectory();
			requireThat(Files.exists(project.resolve(".gitignore")), "gitignoreExists").isFalse();
			requireThat(session.ignoreNotice(), "ignoreNotice").isEmpty();
		}
	}

	/**
	 * Verifies that sessions that start at once in a work tree that does not ignore the CAT session directories leave
	 * one copy of CAT's lines, and that exactly one of them tells the user.
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
			Path project = GitWorkTrees.create(cleanup.add(Files.createTempDirectory("cat-ignore-")).resolve("project"));
			List<Callable<SessionDirectory>> sessions = new ArrayList<>();
			for (int index = 0; index < 8; ++index)
				sessions.add(() -> open(project));

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
	 * Verifies that a {@code .gitignore} that cannot be written, here a directory, leaves the session directory in
	 * place and tells the user that CAT could not add the entry and why.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void failingWriteReportsAndContinues() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = GitWorkTrees.create(cleanup.add(Files.createTempDirectory("cat-ignore-")).resolve("project"));
			Files.createDirectories(project.resolve(".gitignore"));

			SessionDirectory session = open(project);

			requireThat(session.temp(), "temp").isDirectory();
			requireThat(session.ignoreNotice().orElseThrow().forUser(), "notice").
				contains("CAT could not add /.cat/sessions/ to " + project.resolve(".gitignore") + ": ").
				contains("Exception: ");
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
	 * Opens the CAT session directory of a new session.
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
}
