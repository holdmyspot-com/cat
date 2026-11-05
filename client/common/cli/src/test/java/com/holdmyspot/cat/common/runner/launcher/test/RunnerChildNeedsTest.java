package com.holdmyspot.cat.common.runner.launcher.test;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.RequestedSessionStarter;
import com.holdmyspot.cat.common.runner.SessionLauncherChannel;
import com.holdmyspot.cat.common.runner.launcher.RunnerChildNeeds;
import com.holdmyspot.cat.common.runner.policy.ChildNeeds;
import com.holdmyspot.cat.common.runner.policy.ChildNeedsReport;
import com.holdmyspot.cat.common.runner.policy.HostAccess;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Tests that a session launcher learns a child's needs from the runner of the child's harness, and refuses a child
 * whose isolated home would copy a login that the requesting session may not read.
 */
public final class RunnerChildNeedsTest
{
	private final JsonMapper json = JsonMapper.builder().build();

	/**
	 * Creates the test.
	 */
	public RunnerChildNeedsTest()
	{
	}

	/**
	 * Verifies that a child needs its run folder, the request's denied reads, and the writes and hosts that the runner
	 * of the child's harness reports, which the launcher asks it for in the request's directory.
	 *
	 * @throws IOException   if the fixture cannot be created
	 * @throws PolicyRefusal if the child is refused
	 */
	@Test
	public void needsWhatRunnerReports() throws IOException, PolicyRefusal
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path request = cleanup.add(Files.createTempDirectory("cat-request-")).toRealPath();
			Path runFolder = request.resolve(SessionLauncherChannel.RUN_DIRECTORY);
			Path cache = request.resolveSibling("cache");
			Path denied = request.resolveSibling("denied");
			List<List<String>> started = new ArrayList<>();
			RequestedSessionStarter starter = (harness, arguments, directory) ->
			{
				started.add(List.of(harness, directory.toString(), String.join(" ", arguments)));
				new ChildNeedsReport(List.of(cache), Set.of("model.example"), List.of()).write(json,
					Path.of(arguments.get(1)));
				return new ExitedProcess(0);
			};

			ChildNeeds needs = new RunnerChildNeeds(starter).needs("codex", runFolder, List.of(denied),
				ParentPolicy.UNSANDBOXED);

			requireThat(needs, "needs").isEqualTo(new ChildNeeds(runFolder, List.of(cache), List.of(denied),
				Set.of("model.example")));
			requireThat(started, "started").isEqualTo(List.of(List.of("codex", request.toString(),
				ChildNeedsReport.OPTION + " " + request.resolve(ChildNeedsReport.FILE_NAME))));
		}
	}

	/**
	 * Verifies that a child whose isolated home would copy a login file that the requesting session's sandbox denies
	 * it reading is refused with the file's name, since the session could read the copy in the child's run folder.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void refusesLoginThatRequesterMayNotRead() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-request-")).toRealPath();
			Path request = Files.createDirectory(root.resolve("request"));
			Path home = Files.createDirectory(root.resolve("home"));
			Path login = Files.writeString(home.resolve("auth.json"), "{}");
			RequestedSessionStarter starter = (_, arguments, _) ->
			{
				new ChildNeedsReport(List.of(), Set.of("model.example"), List.of(login)).write(json,
					Path.of(arguments.get(1)));
				return new ExitedProcess(0);
			};
			ParentPolicy bound = new ParentPolicy(true, List.of(root), List.of(), List.of(home), List.of(),
				HostAccess.listed(Set.of("model.example")), false, List.of());
			try
			{
				new RunnerChildNeeds(starter).needs("codex",
					request.resolve(SessionLauncherChannel.RUN_DIRECTORY), List.of(), bound);
				throw new AssertionError("The child was not refused");
			}
			catch (PolicyRefusal e)
			{
				requireThat(e.reason(), "reason").contains(login.toString());
			}
		}
	}

	/**
	 * Verifies that a runner that cannot report its harness's needs fails the request with the runner's own error.
	 *
	 * @throws IOException   if the fixture cannot be created
	 * @throws PolicyRefusal if the child is refused
	 */
	@Test
	public void failsWithRunnerError() throws IOException, PolicyRefusal
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path request = cleanup.add(Files.createTempDirectory("cat-request-")).toRealPath();
			RequestedSessionStarter starter = (_, _, directory) ->
			{
				Files.writeString(directory.resolve(SessionLauncherChannel.RUNNER_STDERR_FILE),
					"Codex has no CAT plugin of this release\n");
				return new ExitedProcess(1);
			};
			try
			{
				new RunnerChildNeeds(starter).needs("codex", request.resolve(SessionLauncherChannel.RUN_DIRECTORY),
					List.of(), ParentPolicy.UNSANDBOXED);
				throw new AssertionError("The needs were read");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").contains("Codex has no CAT plugin of this release");
			}
		}
	}

	/**
	 * A runner process that has already exited.
	 */
	private static final class ExitedProcess extends Process
	{
		private final int status;

		/**
		 * Creates a process.
		 *
		 * @param status the exit status
		 */
		ExitedProcess(int status)
		{
			this.status = status;
		}

		@Override
		public OutputStream getOutputStream()
		{
			return new ByteArrayOutputStream();
		}

		@Override
		public InputStream getInputStream()
		{
			return new ByteArrayInputStream(new byte[0]);
		}

		@Override
		public InputStream getErrorStream()
		{
			return new ByteArrayInputStream(new byte[0]);
		}

		@Override
		public int waitFor()
		{
			return status;
		}

		@Override
		public boolean waitFor(long timeout, TimeUnit unit)
		{
			return true;
		}

		@Override
		public int exitValue()
		{
			return status;
		}

		@Override
		public boolean isAlive()
		{
			return false;
		}

		@Override
		public void destroy()
		{
		}
	}
}
