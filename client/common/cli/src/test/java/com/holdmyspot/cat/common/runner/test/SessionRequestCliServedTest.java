package com.holdmyspot.cat.common.runner.test;

import static com.holdmyspot.cat.common.test.SessionHarnesses.HARNESS_A;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.ToolScope;
import com.holdmyspot.cat.common.fakes.test.GitProjects;
import com.holdmyspot.cat.common.fakes.test.ManualClock;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.fakes.test.Yielding;
import com.holdmyspot.cat.common.runner.HarnessRunnerOptions;
import com.holdmyspot.cat.common.runner.RequestServer;
import com.holdmyspot.cat.common.runner.SessionRequestCli;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.testng.annotations.Test;

/**
 * Tests the requests that {@link SessionRequestCli} writes outside a run, for the session's {@link RequestServer}.
 */
public final class SessionRequestCliServedTest
{
	/**
	 * The ID of the session that runs the command.
	 */
	private static final String SESSION_ID = "3b9d7c2e-5f1a-4e8b-a0c6-d4e2f1a3b5c7";

	/**
	 * Creates the test.
	 */
	public SessionRequestCliServedTest()
	{
	}

	/**
	 * Verifies that outside a run a request that no request server serves is refused with status 2, naming the
	 * command that starts the server, before the command writes a request or creates the requests directory.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void refusesWithoutServer() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = project(jvm, cleanup);
			Path plugin = Files.createDirectory(project.resolveSibling("plugin"));
			Path prompt = Files.writeString(project.resolveSibling("prompt"), "prompt bytes");
			StringWriter output = new StringWriter();
			StringWriter errors = new StringWriter();

			int status = SessionRequestCli.executeServed(submission(project, prompt), plugin, scopes(project),
				new ManualClock(Instant.EPOCH), () ->
				{
					throw new AssertionError("Nothing waits");
				}, new PrintWriter(output, true), new PrintWriter(errors, true));

			requireThat(status, "status").isEqualTo(HarnessRunnerOptions.REFUSED_LAUNCH_STATUS);
			requireThat(errors.toString(), "errors").contains("No request server serves this session").
				contains(plugin.resolve("bin/request-server").toString());
			requireThat(output.toString(), "output").isEmpty();
			requireThat(Files.exists(sessionDirectory(project).resolve(RequestServer.REQUESTS)), "requestsExist").
				isFalse();
		}
	}

	/**
	 * Verifies that outside a run a request goes to the requests directory of the request server that serves the
	 * session, and that a request that outlasts its wait names the absolute path of the plugin's
	 * {@code request-session} to wait for it again, since outside a run no variable names that command.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void submitsToServer() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = project(jvm, cleanup);
			Path plugin = Files.createDirectory(project.resolveSibling("plugin"));
			Path prompt = Files.writeString(project.resolveSibling("prompt"), "prompt bytes");
			Path session = Files.createDirectories(sessionDirectory(project));
			Path requests = Files.createDirectory(session.resolve(RequestServer.REQUESTS));
			Files.createDirectory(session.resolve("responses"));
			ManualClock time = new ManualClock(Instant.EPOCH);
			StringWriter output = new StringWriter();
			int status;
			try (FileChannel channel = FileChannel.open(session.resolve(RequestServer.LOCK_FILE),
				StandardOpenOption.CREATE, StandardOpenOption.WRITE);
				FileLock _ = channel.lock())
			{
				status = SessionRequestCli.executeServed(submission(project, prompt), plugin, scopes(project), time,
					() -> time.advance(Duration.ofSeconds(300)), new PrintWriter(output, true),
					new PrintWriter(new StringWriter(), true));
			}

			requireThat(status, "status").isEqualTo(3);
			String id;
			try (Stream<Path> files = Files.list(requests))
			{
				id = files.findFirst().orElseThrow().getFileName().toString().replace(".tar", "");
			}
			requireThat(output.toString(), "output").contains("\"" + plugin.resolve("bin/request-session") +
				"\" --await " + id);
		}
	}

	/**
	 * Returns the options of a request for a session.
	 *
	 * @param project the session's project
	 * @param prompt  the session's prompt
	 * @return the options
	 */
	private static String[] submission(Path project, Path prompt)
	{
		return new String[]{"--harness", "harness-a", "--project", project.toString(), "--prompt", prompt.toString(),
			"--model", "model-a", "--effort", "effort-a"};
	}

	/**
	 * Creates the project of the session that runs the command.
	 *
	 * @param jvm     the test's JVM scope
	 * @param cleanup removes the project
	 * @return the project
	 * @throws IOException if the project cannot be created
	 */
	private static Path project(TestJvmScope jvm, TemporaryDirectories cleanup) throws IOException
	{
		// The command refuses a link anywhere in an input's path, and macOS reaches its temporary directory through
		// /var, a link to /private/var.
		Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "served-request-")).toRealPath();
		Files.createDirectories(root.resolve("home"));
		Path project = Files.createDirectories(root.resolve("project/.cat")).getParent();
		Files.writeString(project.resolve("marker"), "project bytes");
		return project;
	}

	/**
	 * Returns the CAT session directory of the session that runs the command.
	 *
	 * @param project the session's project
	 * @return the directory
	 */
	private static Path sessionDirectory(Path project)
	{
		return project.resolve(".cat/sessions").resolve(SESSION_ID);
	}

	/**
	 * Returns how the command opens the tool scope of the session that runs it.
	 *
	 * @param project the session's project
	 * @return the scopes
	 */
	private static RequestServer.SessionScope scopes(Path project)
	{
		Path home = project.resolveSibling("home");
		Map<String, String> environment = Map.of(HARNESS_A.sessionVariable(), SESSION_ID, "HOME", home.toString(),
			"USERPROFILE", home.toString());
		return () -> ToolScope.forSession(project, environment, List.of(HARNESS_A), Yielding.waiting(),
			GitProjects.IGNORING);
	}
}
