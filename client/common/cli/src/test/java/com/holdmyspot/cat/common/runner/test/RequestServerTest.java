package com.holdmyspot.cat.common.runner.test;

import static com.holdmyspot.cat.common.test.SessionHarnesses.HARNESS_A;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.FileLocks;
import com.holdmyspot.cat.common.Pause;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.ToolScope;
import com.holdmyspot.cat.common.fakes.test.GitProjects;
import com.holdmyspot.cat.common.fakes.test.Yielding;
import com.holdmyspot.cat.common.git.Git;
import com.holdmyspot.cat.common.optimizer.execution.CheckProgram;
import com.holdmyspot.cat.common.optimizer.execution.CheckProgramStartFailure;
import com.holdmyspot.cat.common.optimizer.execution.contract.test.CheckProgramFixtures;
import com.holdmyspot.cat.common.optimizer.proposal.HookReport;
import com.holdmyspot.cat.common.optimizer.proposal.HookReport.HookDifference;
import com.holdmyspot.cat.common.optimizer.workspace.GitWorktreeRegistrar;
import com.holdmyspot.cat.common.optimizer.workspace.WorktreeRefusedException;
import com.holdmyspot.cat.common.optimizer.workspace.WorktreeRegistrar;
import com.holdmyspot.cat.common.release.fakes.test.FakeRelease;
import com.holdmyspot.cat.common.runner.HarnessCapacity;
import com.holdmyspot.cat.common.runner.HarnessRunnerOptions;
import com.holdmyspot.cat.common.runner.HookComparisonRequest;
import com.holdmyspot.cat.common.runner.HookComparisonResult;
import com.holdmyspot.cat.common.runner.NestedSession;
import com.holdmyspot.cat.common.runner.NestedSessionManager;
import com.holdmyspot.cat.common.runner.NestedSessionReceipt;
import com.holdmyspot.cat.common.runner.CheckProgramClient;
import com.holdmyspot.cat.common.runner.RequestServer;
import com.holdmyspot.cat.common.runner.RequestedRunner;
import com.holdmyspot.cat.common.runner.RuntimeBinding;
import com.holdmyspot.cat.common.runner.SessionRequestForwarder;
import com.holdmyspot.cat.common.runner.WorktreeRequest;
import com.holdmyspot.cat.common.runner.WorktreeRequestClient;
import com.holdmyspot.cat.common.runner.fakes.test.FakeHarnessCli;
import com.holdmyspot.cat.common.runner.launcher.ProcessIdentity;
import com.holdmyspot.cat.common.runner.launcher.RunnerArguments;
import com.holdmyspot.cat.common.runner.launcher.test.FakeParentPolicySource;
import com.holdmyspot.cat.common.runner.launcher.test.FakeProcessTable;
import com.holdmyspot.cat.common.runner.login.CodexLoginService;
import com.holdmyspot.cat.common.runner.policy.ChildNeedsReport;
import com.holdmyspot.cat.common.runner.policy.ChildPolicy;
import com.holdmyspot.cat.common.runner.policy.HostAccess;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.PolicyBoundFile;
import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import com.holdmyspot.cat.common.runner.policy.SessionRefusedException;
import com.holdmyspot.cat.common.test.ExitedProcess;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests the request server that a harness session's agent starts outside the session's sandbox.
 */
public final class RequestServerTest
{
	private static final String SESSION_ID = "0f8fad5b-d9cb-469f-a165-70867728950e";
	private static final String MODEL_HOST = "model.example";
	private static final Duration WAIT = Duration.ofSeconds(20);

	/**
	 * Verifies that the server refuses any argument, because its sandbox exception matches only its bare path.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void refusesArguments() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			StringWriter errors = new StringWriter();
			int status = RequestServer.serve(new String[]{"--project", "elsewhere"}, fixture.host(),
				new PrintWriter(errors, true));
			requireThat(status, "status").isEqualTo(HarnessRunnerOptions.REFUSED_LAUNCH_STATUS);
			requireThat(errors.toString(), "errors").contains("takes no arguments").
				contains(RequestServer.program(fixture.plugin).toString());
		}
	}

	/**
	 * Verifies that the launcher of the request server is {@code bin/request-server.cmd} on Windows, which runs
	 * batch launchers only, and {@code bin/request-server} elsewhere.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void namesLauncherByPlatform() throws IOException
	{
		// Requirement: R-I36
		try (Fixture fixture = new Fixture())
		{
			requireThat(RequestServer.program(fixture.plugin, Platform.WINDOWS_X64), "windows").
				isEqualTo(fixture.plugin.resolve("bin").resolve("request-server.cmd"));
			requireThat(RequestServer.program(fixture.plugin, Platform.WINDOWS_ARM64), "windowsArm").
				isEqualTo(fixture.plugin.resolve("bin").resolve("request-server.cmd"));
			requireThat(RequestServer.program(fixture.plugin, Platform.LINUX_X64), "linux").
				isEqualTo(fixture.plugin.resolve("bin").resolve("request-server"));
			requireThat(RequestServer.program(fixture.plugin, Platform.MACOS_ARM64), "macos").
				isEqualTo(fixture.plugin.resolve("bin").resolve("request-server"));
		}
	}

	/**
	 * Verifies that the server's refusal of an argument names the launcher that its platform runs.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void argumentRefusalNamesLauncher() throws IOException
	{
		// Requirement: R-I36
		try (Fixture fixture = new Fixture())
		{
			fixture.platform = Platform.WINDOWS_X64;
			StringWriter errors = new StringWriter();

			int status = RequestServer.serve(new String[]{"--project", "elsewhere"}, fixture.host(),
				new PrintWriter(errors, true));

			requireThat(status, "status").isEqualTo(HarnessRunnerOptions.REFUSED_LAUNCH_STATUS);
			requireThat(errors.toString(), "errors").
				contains(fixture.plugin.resolve("bin").resolve("request-server.cmd").toString());
		}
	}

	/**
	 * Verifies that the reason that a client gives when no request server serves its session, and the reason that a
	 * command of the optimization worktrees gives, name the launcher that their platform runs, whether the client is
	 * in the installed plugin or in another copy.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void missingServerReasonsNameLauncher() throws IOException
	{
		// Requirement: R-I36
		try (Fixture fixture = new Fixture())
		{
			Path other = Files.createDirectories(fixture.root.resolve("other-plugin"));
			String windows = fixture.plugin.resolve("bin").resolve("request-server.cmd").toString();
			String posix = fixture.plugin.resolve("bin").resolve("request-server").toString();

			for (Path client : List.of(fixture.plugin, other))
			{
				List<String> reasons = List.of(
					RequestServer.missingServer(client, fixture.installed, Platform.WINDOWS_X64),
					RequestServer.missingWorktreeServer(client, fixture.installed, Platform.WINDOWS_X64));
				for (String reason : reasons)
					requireThat(reason, "windowsReason").contains(windows);
				reasons = List.of(
					RequestServer.missingServer(client, fixture.installed, Platform.LINUX_X64),
					RequestServer.missingWorktreeServer(client, fixture.installed, Platform.LINUX_X64));
				for (String reason : reasons)
				{
					requireThat(reason, "posixReason").contains(posix + ". ").doesNotContain(".cmd");
				}
			}
		}
	}

	/**
	 * Verifies that the server refuses on a platform that runs no sandboxed session, such as native Windows.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void refusesPlatform() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.hostRefusal = Optional.of("CAT does not start sessions on native Windows");
			requireThat(fixture.refusal(), "refusal").isEqualTo("CAT does not start sessions on native Windows\n");
		}
	}

	/**
	 * Verifies that a server that the other harness's session runs names the plugin to use.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void refusesOtherHarness() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.running = Set.of("codex");
			requireThat(fixture.refusal(), "refusal").contains("Run the request server of CAT's Codex plugin instead");
		}
	}

	/**
	 * Verifies that a server that no harness session runs is refused, because it has no session to serve.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void refusesWithoutHarness() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.running = Set.of();
			requireThat(fixture.refusal(), "refusal").contains("no harness session runs it");
		}
	}

	/**
	 * Verifies that a server that runs inside the session's sandbox, where it cannot write its plugin's folder,
	 * refuses with the cause that the plugin gives.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void refusesInsideSandbox() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			TemporaryDirectories.deleteRecursively(fixture.plugin);
			requireThat(fixture.refusal(), "refusal").isEqualTo("the session runs the server inside its sandbox\n");
		}
	}

	/**
	 * Verifies that a server refuses a session that its sources do not identify.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void refusesUnidentifiedSession() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.sessionRefusal = Optional.of(new PolicyRefusal("no transcript has the session's identifier",
				"Start a new session"));
			requireThat(fixture.refusal(), "refusal").contains("no transcript has the session's identifier");
		}
	}

	/**
	 * Verifies that a server refuses a session whose commands could write its transcript, from which the server
	 * takes the session's project folder.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void refusesWritableTranscript() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.policy.allow(fixture.policy(List.of(fixture.project, fixture.transcript.getParent())));
			requireThat(fixture.refusal(), "refusal").contains(fixture.transcript.toString());
		}
	}

	/**
	 * Verifies that a server refuses a session whose commands could write the plugin that holds it, from which the
	 * server's own code and the runners that it starts are read.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void refusesWritablePlugin() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.policy.allow(fixture.policy(List.of(fixture.project, fixture.plugin)));
			requireThat(fixture.refusal(), "refusal").contains(fixture.plugin.toString());
		}
	}

	/**
	 * Verifies that a server whose plugin folder is writable and is not the installed plugin's refuses with a reason
	 * that says to run the installed plugin's launcher, not to remove from the sandbox's writable paths a folder that
	 * may lie inside the project.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void refusesWritableCopyNotInstalled() throws IOException
	{
		// Requirement: R-I11
		try (Fixture fixture = new Fixture())
		{
			Path installed = fixture.root.resolve("installed");
			fixture.installed = List.of(installed);
			fixture.policy.allow(fixture.policy(List.of(fixture.project, fixture.plugin)));

			String refusal = fixture.refusal();

			requireThat(refusal, "refusal").contains("the session's sandbox lets its commands write " +
					fixture.plugin).contains("Run the launcher of the installed plugin, " + installed).
				doesNotContain("Remove " + fixture.plugin);
		}
	}

	/**
	 * Verifies that a server whose plugin folder is writable, when no plugin is installed, refuses with a reason that
	 * says to install CAT.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void refusesWritableCopyWithNoInstall() throws IOException
	{
		// Requirement: R-I11
		try (Fixture fixture = new Fixture())
		{
			fixture.installed = List.of();
			fixture.policy.allow(fixture.policy(List.of(fixture.project, fixture.plugin)));

			String refusal = fixture.refusal();

			requireThat(refusal, "refusal").contains("Install CAT").doesNotContain("Remove " + fixture.plugin);
		}
	}

	/**
	 * Verifies that a writable transcript, and a writable installed plugin, keep the reason that names the path to
	 * remove from the sandbox's writable paths, even when the server's own plugin is not the installed one.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void keepsRemovalAdvice() throws IOException
	{
		// Requirement: R-I11
		try (Fixture fixture = new Fixture())
		{
			fixture.policy.allow(fixture.policy(List.of(fixture.project, fixture.transcript.getParent())));
			requireThat(fixture.refusal(), "transcriptRefusal").contains("Remove " + fixture.transcript +
				" from the paths that the session's sandbox lets its commands write");
		}
		try (Fixture fixture = new Fixture())
		{
			fixture.policy.allow(fixture.policy(List.of(fixture.project, fixture.plugin)));
			requireThat(fixture.refusal(), "installedRefusal").contains("Remove " + fixture.plugin +
				" from the paths that the session's sandbox lets its commands write");
		}
	}

	/**
	 * Verifies that a server refuses a session whose policy it cannot read.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void refusesUnreadablePolicy() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.policy.failReading(new IOException("the settings are malformed"));
			requireThat(fixture.refusal(), "refusal").contains("sandbox policy").contains("the settings are malformed");
		}
	}

	/**
	 * Verifies that a second server for a session that a server already serves ends without serving it.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void refusesSecondServer() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Path directory = fixture.sessionDirectory();
			Files.createDirectories(directory);
			try (FileChannel channel = FileChannel.open(directory.resolve(RequestServer.LOCK_FILE),
				StandardOpenOption.CREATE, StandardOpenOption.WRITE);
			     FileLock _ = channel.lock())
			{
				fixture.pause = () ->
				{
				};
				requireThat(fixture.refusal(), "refusal").contains("Another request server already serves");
			}
		}
	}

	/**
	 * Verifies that the server serves a launch that a client packs into the session's requests: the requested session
	 * starts in a nested session of its own, bounded by the intersection of the session's policy and what the new session
	 * needs, and the server removes that run once it has answered, leaving the client the response.
	 *
	 * @throws IOException          if the fixture cannot be created or a request cannot be served
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = 60_000)
	public void servesWithinBound() throws IOException, InterruptedException
	{
		try (Fixture fixture = new Fixture())
		{
			AtomicReference<List<String>> started = new AtomicReference<>();
			AtomicReference<List<String>> copied = new AtomicReference<>();
			AtomicReference<ChildPolicy> bounds = new AtomicReference<>();
			AtomicReference<FakeHarnessCli> child = new AtomicReference<>();
			fixture.child = arguments ->
			{
				started.set(arguments);
				bounds.set(PolicyBoundFile.read(fixture.json, Path.of(option(arguments, PolicyBoundFile.OPTION))));
				Path copy = Path.of(option(arguments, "--project"));
				try (Stream<Path> paths = Files.walk(copy))
				{
					copied.set(paths.map(path -> copy.relativize(path).toString().replace('\\', '/')).sorted().
						toList());
				}
				Files.writeString(Path.of(option(arguments, "--output")), "{\"type\":\"turn_completed\"}\n");
				Files.writeString(Path.of(option(arguments, "--receipt")), "{}");
				FakeHarnessCli process = new FakeHarnessCli(exitOnCompleteRecord(fixture.json, child));
				child.set(process);
				return process;
			};
			try (Fixture.Running server = fixture.start())
			{
				ByteArrayOutputStream output = new ByteArrayOutputStream();
				StringWriter errors = new StringWriter();
				int status = SessionRequestForwarder.forward("claude", new String[]{"--project",
						fixture.project.toString(), "--model", "haiku", "--effort", "low"},
					new StringReader("{\"type\":\"turn\",\"prompt\":\"hello\"}\n{\"type\":\"complete\"}\n"),
					server.requests(), server.pause(), output, new PrintWriter(errors, true));
				requireThat(status, "status").withContext(errors, "errors").isEqualTo(0);

				List<String> arguments = started.get();
				Path resultsFolder = Path.of(option(arguments, "--results"));
				requireThat(resultsFolder.startsWith(fixture.sessionDirectory()), "inSessionDirectory").isTrue();
				ChildPolicy bound = bounds.get();
				requireThat(bound.writeRoots(), "writeRoots").isEqualTo(List.of(resultsFolder));
				requireThat(bound.hosts(), "hosts").isEqualTo(Set.of(MODEL_HOST));
				requireThat(awaitAbsent(resultsFolder), "resultsFolderRemains").isFalse();
				requireThat(copied.get(), "copied").contains("README.md").contains(".cat/sessions/" + SESSION_ID).
					doesNotContain(".cat/sessions/" + SESSION_ID + "/" + RequestServer.REQUESTS).
					doesNotContain(".cat/sessions/" + SESSION_ID + "/responses").
					doesNotContain(".cat/sessions/" + SESSION_ID + "/" + RequestServer.LOCK_FILE);
				fixture.processes.end(fixture.harness);
				requireThat(server.end(), "exit").isEqualTo(0);
			}
		}
	}

	/**
	 * Verifies that the server starts one login service for its session, gives each runner that it starts the
	 * service's address and key and no request or response file holds them, gives the service the policy of the
	 * session's main thread even when a thread of the session runs the server, and closes the service when it ends.
	 *
	 * @throws IOException          if the fixture cannot be created or a request cannot be served
	 * @throws InterruptedException if the test is interrupted
	 */
	// The server closes the service that it started
	@SuppressWarnings("PMD.CloseResource")
	@Test(timeOut = 60_000)
	public void givesLoginServiceToRunners() throws IOException, InterruptedException
	{
		try (Fixture fixture = new Fixture())
		{
			Path threadFolder = Files.createDirectories(fixture.project.resolve("thread"));
			fixture.askingThread = Optional.of("thread-1");
			fixture.policy.allowThread("thread-1", fixture.policy(List.of(threadFolder)));
			AtomicReference<FakeHarnessCli> child = new AtomicReference<>();
			fixture.child = arguments ->
			{
				Files.writeString(Path.of(option(arguments, "--receipt")), "{}");
				FakeHarnessCli process = new FakeHarnessCli(exitOnCompleteRecord(fixture.json, child));
				child.set(process);
				return process;
			};
			CodexLoginService service;
			try (Fixture.Running server = fixture.start())
			{
				JsonNode response = server.request(fixture.manifest());
				requireThat(response.path("status").asString(), "status").withContext(response, "response").
					isEqualTo("completed");

				requireThat(fixture.loginServices.size(), "loginServices").isEqualTo(1);
				service = fixture.loginServices.getFirst();
				requireThat(fixture.runnerVariables, "runnerVariables").isNotEmpty();
				for (Map<String, String> variables : fixture.runnerVariables)
					requireThat(variables, "variables").isEqualTo(service.runnerVariables());
				requireThat(Files.exists(service.socket()), "socketExists").isTrue();
				requireThat(fixture.topLevels.getFirst().read().writeRoots(), "topLevelWriteRoots").
					isEqualTo(List.of(fixture.project));
				fixture.processes.end(fixture.harness);
				requireThat(server.end(), "exit").isEqualTo(0);
			}
			// The server's files are read once it has ended: while it runs, it replaces some through temporary files
			// and locks others, which Windows then refuses to let another handle read
			List<String> secrets = List.copyOf(service.runnerVariables().values());
			List<Path> files;
			try (Stream<Path> paths = Files.walk(fixture.sessionDirectory()))
			{
				files = paths.filter(Files::isRegularFile).toList();
			}
			for (Path file : files)
			{
				String content = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
				for (String secret : secrets)
					requireThat(content.contains(secret), "holdsServiceVariable").withContext(file, "file").isFalse();
			}
			requireThat(Files.exists(service.socket()), "socketRemains").isFalse();
		}
	}

	/**
	 * Verifies that a launch sent to a server that has ended fails within the test's time limit instead of waiting
	 * for an answer that never comes, so that a server that stops early fails its test rather than hanging the build.
	 *
	 * @throws IOException          if the fixture cannot be created
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = 60_000)
	public void failsLaunchToEndedServer() throws IOException, InterruptedException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.child = _ ->
			{
				throw new AssertionError("The ended server started a session");
			};
			try (Fixture.Running server = fixture.start())
			{
				fixture.processes.end(fixture.harness);
				requireThat(server.end(), "exit").isEqualTo(0);
				IOException failure = null;
				try
				{
					SessionRequestForwarder.forward("claude", new String[]{"--project", fixture.project.toString(),
							"--model", "haiku", "--effort", "low"},
						new StringReader("{\"type\":\"turn\",\"prompt\":\"hello\"}\n{\"type\":\"complete\"}\n"),
						server.requests(), server.pause(), OutputStream.nullOutputStream(),
						new PrintWriter(new StringWriter(), true));
				}
				catch (IOException e)
				{
					failure = e;
				}
				requireThat(failure, "failure").isNotNull();
				requireThat(failure.getMessage(), "message").contains("The server ended");
			}
		}
	}

	/**
	 * Verifies that a launch that resumes a harness session that another session of the project interrupted is refused
	 * with status 2 and a reason that names the session that can resume it.
	 *
	 * @throws IOException          if the fixture cannot be created or a request cannot be served
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = 60_000)
	public void refusesResumeOfAnotherSession() throws IOException, InterruptedException
	{
		try (Fixture fixture = new Fixture())
		{
			String other = "6a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d";
			String harnessSession = "5f0c2a8e-7b1d-4e3a-9c6f-2d8b1a4e7c90";
			Path slot = fixture.project.resolve(".cat/sessions").resolve(other).resolve("slots").resolve("d1");
			NestedSessionManager manager = new NestedSessionManager(new FileLocks(), slot, Clock.systemUTC(),
				Yielding.fileReplacer()).withFixedSlotId("s-1");
			manager.recordHarnessSession(manager.create("claude"), harnessSession);
			fixture.child = _ ->
			{
				throw new AssertionError("The refused launch started a session");
			};
			try (Fixture.Running server = fixture.start())
			{
				StringWriter errors = new StringWriter();
				int status = SessionRequestForwarder.forward("claude", new String[]{RunnerArguments.RESUME_OPTION,
						harnessSession, "--model", "haiku", "--effort", "low"},
					new StringReader("{\"type\":\"turn\",\"prompt\":\"again\"}\n{\"type\":\"complete\"}\n"),
					server.requests(), server.pause(), OutputStream.nullOutputStream(), new PrintWriter(errors, true));
				requireThat(status, "status").withContext(errors, "errors").
					isEqualTo(HarnessRunnerOptions.REFUSED_LAUNCH_STATUS);
				requireThat(errors.toString(), "errors").contains(other);
				fixture.processes.end(fixture.harness);
				requireThat(server.end(), "exit").isEqualTo(0);
			}
		}
	}

	/**
	 * Verifies that a server that a thread of the session runs bounds each request by that thread's policy, not the
	 * session's: a thread that may not reach the model's host has its request refused, naming the entry to add.
	 *
	 * @throws IOException          if the fixture cannot be created or a request cannot be served
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = 60_000)
	public void boundsByAskingThread() throws IOException, InterruptedException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.askingThread = Optional.of("thread-1");
			fixture.policy.allowThread("thread-1", new ParentPolicy(true, List.of(fixture.project), List.of(),
				List.of(), List.of(), HostAccess.listed(Set.of()), false, List.of()));
			fixture.child = _ ->
			{
				throw new AssertionError("The server started a session that the thread's policy refuses");
			};
			try (Fixture.Running server = fixture.start())
			{
				JsonNode response = server.request(fixture.manifest());
				requireThat(response.path("status").asString(), "status").withContext(response, "response").
					isEqualTo("refused");
				requireThat(response.path("reason").asString(), "reason").contains(MODEL_HOST);
			}
		}
	}


	/**
	 * Verifies that a writable directory in the installed peer prevents the broker from starting its runner.
	 *
	 * @throws IOException if the fixture cannot be created or a request cannot be served
	 */
	@Test(timeOut = 60_000)
	public void refusesWritablePeerBeforeProbe() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Path peer = Files.createDirectory(fixture.root.resolve("peer"));
			Path writable = Files.createDirectories(peer.resolve("bin/runtime/modules"));
			fixture.peer = Optional.of(peer);
			fixture.policy.allow(fixture.policy(List.of(fixture.project, writable)));
			fixture.child = _ -> new ExitedProcess(new ProcessBuilder(), 1, "", "");
			try (Fixture.Running server = fixture.startForRequest())
			{
				JsonNode response = server.request(fixture.manifest().put("harness", "codex"));
				requireThat(fixture.needsCalls.get(), "needsCalls").withContext(response, "response").isEqualTo(0);
				requireThat(response.path("status").asString(), "status").isEqualTo("refused");
				requireThat(response.path("reason").asString(), "reason").contains(writable.toString());
			}
		}
	}


	/**
	 * Verifies that a grant in the startup policy prevents execution of a peer's bound runtime, even after the
	 * owner removes the grant from its current policy.
	 *
	 * @throws IOException if the fixture cannot be created or a request cannot be served
	 */
	@Test(timeOut = 60_000)
	public void refusesRuntimeWritableInOwnerSnapshot() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Path runtime = fixture.installPeer();
			Path writable = runtime.resolve("bin");
			fixture.policy.allow(fixture.policy(List.of(fixture.project, writable)));
			fixture.child = _ -> new ExitedProcess(new ProcessBuilder(), 1, "", "");
			try (Fixture.Running server = fixture.startForRequest())
			{
				fixture.policy.allow(fixture.policy(List.of(fixture.project)));
				JsonNode response = server.request(fixture.manifest().put("harness", "codex"));
				requireThat(fixture.needsCalls.get(), "needsCalls").withContext(response, "response").isEqualTo(0);
				requireThat(response.path("status").asString(), "status").isEqualTo("refused");
				requireThat(response.path("reason").asString(), "reason").contains(writable.toString());
			}
		}
	}

	/**
	 * Verifies that the broker starts a trusted peer with one selection for the probe and the launch.
	 *
	 * @throws IOException if the fixture cannot be created or a request cannot be served
	 * @throws InterruptedException if the test is interrupted while the server ends
	 */
	@Test(timeOut = 60_000)
	public void reusesTrustedPeerForProbeAndLaunch() throws IOException, InterruptedException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.installPeer();
			AtomicInteger launches = new AtomicInteger();
			fixture.child = _ ->
			{
				launches.incrementAndGet();
				FakeHarnessCli process = new FakeHarnessCli(_ ->
				{
				}, 1);
				process.exit();
				return process;
			};
			try (Fixture.Running server = fixture.startForRequest())
			{
				JsonNode response = server.request(fixture.manifest().put("harness", "codex"));
				requireThat(fixture.needsCalls.get(), "needsCalls").withContext(response, "response").isEqualTo(1);
				requireThat(fixture.peerSelections.get(), "peerSelections").isEqualTo(1);
				requireThat(launches.get(), "launches").isEqualTo(1);
				fixture.processes.end(fixture.harness);
				requireThat(server.end(), "exit").isEqualTo(0);
			}
		}
	}

	/**
	 * Verifies that a launch with {@code --conversation} holds a conversation through the server: the client sends
	 * the asking session's turn once the server has started the session, writes the session's reply and the turn's
	 * end to the conversation, and ends the session when the asking session ends the conversation.
	 *
	 * @throws IOException          if the fixture cannot be created or a request cannot be served
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = 60_000)
	public void conversesThroughServer() throws IOException, InterruptedException
	{
		try (Fixture fixture = new Fixture())
		{
			AtomicReference<FakeHarnessCli> child = new AtomicReference<>();
			fixture.child = arguments ->
			{
				Path output = Path.of(option(arguments, "--output"));
				Path receipt = Path.of(option(arguments, "--receipt"));
				Files.writeString(output, "");
				FakeHarnessCli process = new FakeHarnessCli(line ->
				{
					try
					{
						String type = fixture.json.readTree(line).path("type").asString();
						if (type.equals("turn"))
						{
							Files.writeString(output, "{\"type\":\"turn_completed\",\"assistant_messages\":[\"reply\"]}\n",
								StandardOpenOption.APPEND);
						}
						else if (type.equals("complete"))
						{
							Files.writeString(output, "{\"type\":\"completed\"}\n", StandardOpenOption.APPEND);
							Files.writeString(receipt, "{}");
							child.get().exit();
						}
					}
					catch (IOException e)
					{
						throw new UncheckedIOException(e);
					}
				});
				child.set(process);
				return process;
			};
			Path folder = fixture.root.resolve("conversation");
			Path toChild = folder.resolve("to-child.jsonl");
			Path fromChild = folder.resolve("from-child.jsonl");
			AtomicInteger sent = new AtomicInteger();
			try (Fixture.Running server = fixture.start())
			{
				StringWriter errors = new StringWriter();
				Pause bounded = server.pause();
				int status = SessionRequestForwarder.forward(SessionRequestForwarder.Route.SERVER, "claude",
					new String[]{"--project", fixture.project.toString(), "--model", "haiku", "--effort", "low",
						"--conversation", folder.toString()}, new StringReader(""), server.requests(),
					fixture.root.resolve("results"), () ->
					{
						// The asking session sends its turn at once, and ends the conversation once the turn has ended
						if (sent.get() == 0)
						{
							Files.writeString(toChild, "{\"turn\": \"hello\"}\n", StandardOpenOption.CREATE,
								StandardOpenOption.APPEND);
							sent.incrementAndGet();
						}
						else if (sent.get() == 1 && Files.readString(fromChild).contains("\"turn_end\""))
						{
							Files.writeString(toChild, "{\"end\": true}\n", StandardOpenOption.APPEND);
							sent.incrementAndGet();
						}
						bounded.pause();
					}, OutputStream.nullOutputStream(), new PrintWriter(errors, true));

				requireThat(status, "status").withContext(errors, "errors").isEqualTo(0);
				List<String> lines = Files.readAllLines(fromChild);
				requireThat(lines, "fromChild").contains("{\"turn_end\":1,\"status\":\"completed\"}");
				requireThat(lines.getLast(), "lastLine").isEqualTo("{\"ended\":\"completed\"}");
				requireThat(child.get().requests(), "records").isEqualTo(List.of(
					"{\"type\":\"turn\",\"prompt\":\"hello\"}", "{\"type\":\"complete\"}"));
			}
		}
	}

	/**
	 * Verifies that a conversation of a launch that keeps its nested session ends only once the nested session is copied
	 * into the launch's results folder: the asking session that reads the {@code ended} line finds the results copy,
	 * including its last file, already in place.
	 *
	 * @throws IOException          if the fixture cannot be created or a request cannot be served
	 * @throws InterruptedException if the test is interrupted
	 * @throws ExecutionException   if the watcher of the conversation fails
	 * @throws TimeoutException     if the conversation does not end
	 */
	@Test(timeOut = 60_000)
	public void conversationEndsAfterResultsAreCopied() throws IOException, InterruptedException,
		ExecutionException, TimeoutException
	{
		try (Fixture fixture = new Fixture())
		{
			List<ServedNestedSession> served = new CopyOnWriteArrayList<>();
			fixture.child = slotChild(fixture, served);
			Path folder = fixture.root.resolve("conversation");
			Path toChild = folder.resolve("to-child.jsonl");
			Path fromChild = folder.resolve("from-child.jsonl");
			Path results = fixture.root.resolve("results");
			String lastFile = "artifacts/many/file-399";
			AtomicBoolean endSent = new AtomicBoolean();
			try (Fixture.Running server = fixture.start())
			{
				// The asking session reads the results folder as soon as it reads the line that ends the conversation
				Future<Boolean> keptWhenEnded = fixture.executor.submit(() ->
				{
					while (!(Files.isRegularFile(fromChild) && Files.readString(fromChild).contains("\"ended\"")))
					{
						if (Thread.currentThread().isInterrupted())
							throw new InterruptedException("Observation of the conversation end was cancelled");
						Thread.yield();
					}
					Path claude = results.resolve("claude");
					if (!Files.isDirectory(claude))
						return false;
					try (Stream<Path> kept = Files.list(claude))
					{
						return kept.anyMatch(copy -> Files.isRegularFile(copy.resolve("session.json")) &&
							Files.isRegularFile(copy.resolve(lastFile)));
					}
				});
				try
				{
					StringWriter errors = new StringWriter();
					Pause bounded = server.pause();
					int status = SessionRequestForwarder.forward(SessionRequestForwarder.Route.SERVER, "claude",
						new String[]{"--project", fixture.project.toString(), "--results", results.toString(),
							"--model", "haiku", "--effort", "low", "--conversation", folder.toString()},
						new StringReader(""), server.requests(), results, () ->
						{
							// Once the session runs, its nested session holds many files, so that copying it takes a while
							if (!served.isEmpty() && !endSent.get())
							{
								Path many = Files.createDirectories(served.getFirst().nestedSession().directory().
									resolve("artifacts/many"));
								for (int i = 0; i < 400; ++i)
									Files.writeString(many.resolve("file-" + i), "content");
								Files.writeString(toChild, "{\"end\": true}\n", StandardOpenOption.CREATE,
									StandardOpenOption.APPEND);
								endSent.set(true);
							}
							bounded.pause();
						}, OutputStream.nullOutputStream(), new PrintWriter(errors, true));

					requireThat(status, "status").withContext(errors, "errors").isEqualTo(0);
					requireThat(keptWhenEnded.get(WAIT.toSeconds(), TimeUnit.SECONDS), "sessionKeptInPlaceWhenEnded").
						isTrue();
				}
				finally
				{
					keptWhenEnded.cancel(true);
				}
			}
		}
	}

	/**
	 * Verifies that a CAT command that starts sessions, such as the prompt optimizer, has its session served by the
	 * server outside a nested session: the command receives the session's records, finds the session's results copy in
	 * its own results folder, and removes the response once it has copied the nested session, while the server keeps
	 * neither the request nor a nested session of its own, so the request is served once.
	 *
	 * @throws IOException          if the fixture cannot be created or a request cannot be served
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = 60_000)
	public void servesCommandSessionAndCopiesResults() throws IOException, InterruptedException
	{
		try (Fixture fixture = new Fixture())
		{
			List<ServedNestedSession> served = new CopyOnWriteArrayList<>();
			fixture.child = slotChild(fixture, served);
			Path resultsFolder = fixture.root.resolve("optimizer-nested-sessions");
			try (Fixture.Running server = fixture.start())
			{
				String slotId = serve(fixture, server, resultsFolder);
				Path kept = resultsFolder.resolve("claude").resolve(slotId);
				requireThat(Files.readString(kept.resolve("result.txt")), "result").isEqualTo("1");
				try (Stream<Path> responses = Files.list(fixture.sessionDirectory().resolve("responses")))
				{
					requireThat(responses.toList(), "responses").isEmpty();
				}
				try (Stream<Path> requests = Files.list(server.requests()))
				{
					requireThat(requests.filter(path -> path.toString().endsWith(".tar")).toList(),
						"requests").isEmpty();
				}
				requireThat(awaitAbsent(served.getFirst().resultsFolder()), "servedResultsFolderRemains").isFalse();
			}
		}
	}

	/**
	 * Verifies that sessions that reuse a server slot build their nested sessions at the same paths, so that the paths
	 * that the harness puts into its instructions repeat when that slot is reused.
	 *
	 * @throws IOException          if the fixture cannot be created or a request cannot be served
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = 60_000)
	public void servedSessionsShareSessionPaths() throws IOException, InterruptedException
	{
		try (Fixture fixture = new Fixture())
		{
			List<ServedNestedSession> served = new CopyOnWriteArrayList<>();
			fixture.child = slotChild(fixture, served);
			Path resultsFolder = fixture.root.resolve("optimizer-nested-sessions");
			ServedNestedSession first;
			try (Fixture.Running server = fixture.start())
			{
				first = serveUntilSlotReuse(fixture, server, resultsFolder, served).earlier();
			}
			ServedNestedSession second = served.getLast();
			requireThat(second.resultsFolder(), "resultsFolder").isEqualTo(first.resultsFolder());
			requireThat(second.nestedSession().directory(), "directory").isEqualTo(first.nestedSession().directory());
			requireThat(second.nestedSession().directory().resolve("home"), "home").
				isEqualTo(first.nestedSession().directory().resolve("home"));
			requireThat(second.nestedSession().directory().resolve("project"), "project").
				isEqualTo(first.nestedSession().directory().resolve("project"));
			requireThat(first.resultsFolder().getParent(), "slots").
				isEqualTo(fixture.sessionDirectory().resolve("slots"));
		}
	}

	/**
	 * Verifies that a session that the server serves finds nothing of the previous session's files in its slot.
	 *
	 * @throws IOException          if the fixture cannot be created or a request cannot be served
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = 60_000)
	public void servedSessionFindsNoPriorSlotFiles() throws IOException, InterruptedException
	{
		try (Fixture fixture = new Fixture())
		{
			List<ServedNestedSession> served = new CopyOnWriteArrayList<>();
			fixture.child = slotChild(fixture, served);
			Path resultsFolder = fixture.root.resolve("optimizer-nested-sessions");
			try (Fixture.Running server = fixture.start())
			{
				serveUntilSlotReuse(fixture, server, resultsFolder, served);
			}
			requireThat(served.getLast().entriesAtStart().stream().sorted().toList(), "entriesAtStart").
				isEqualTo(List.of("launch-request.json", "startup-permit.json"));
		}
	}

	/**
	 * Verifies that a request that names the held-out part runs in a slot of the held-out part, and one that does not
	 * runs in a slot of the development part.
	 *
	 * @throws IOException          if the fixture cannot be created or a request cannot be served
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = 60_000)
	public void servedHeldOutSessionTakesHeldOutSlot() throws IOException, InterruptedException
	{
		try (Fixture fixture = new Fixture())
		{
			List<ServedNestedSession> served = new CopyOnWriteArrayList<>();
			fixture.child = slotChild(fixture, served);
			Path resultsFolder = fixture.root.resolve("optimizer-nested-sessions");
			try (Fixture.Running server = fixture.start())
			{
				serve(fixture, server, resultsFolder, "--held-out");
				serve(fixture, server, resultsFolder);
			}
			Path slots = fixture.sessionDirectory().resolve("slots");
			requireThat(served, "served").size().isEqualTo(2);
			requireThat(served.getFirst().resultsFolder(), "heldOut").isEqualTo(slots.resolve("h1"));
			requireThat(served.getLast().resultsFolder(), "development").isEqualTo(slots.resolve("d1"));
		}
	}

	/**
	 * Verifies that a served judge request extracts both evidence trees at the paths that its prompt and runner use.
	 *
	 * @throws IOException          if the fixture cannot be created or a request cannot be served
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = 60_000)
	public void servedJudgeReceivesEvidenceTrees() throws IOException, InterruptedException
	{
		try (Fixture fixture = new Fixture())
		{
			Path probe = Files.createDirectories(fixture.project.resolve("evidence-probe"));
			Path judge = Files.createDirectory(probe.resolve("judge"));
			Path effects = Files.createDirectory(probe.resolve("effects"));
			Path artifacts = Files.createDirectory(probe.resolve("artifacts"));
			String shipping = "shipping-slip-3164\n";
			String transcript = "{\"type\":\"message\",\"text\":\"synthetic evidence\"}\n";
			Files.writeString(effects.resolve("shipping.txt"), shipping);
			Files.writeString(artifacts.resolve("transcript.jsonl"), transcript);
			Files.writeString(probe.resolve("unrelated-note.txt"), "private\n");
			Path ownArtifact = Files.createDirectory(fixture.root.resolve("artifact"));
			Child child = slotChild(fixture, new CopyOnWriteArrayList<>());
			UUID id = UUID.randomUUID();
			try (Fixture.Running server = fixture.start())
			{
				Path offeredEffects = SessionRequestForwarder.offeredEvidence(server.requests(), id, 0);
				Path offeredArtifacts = SessionRequestForwarder.offeredEvidence(server.requests(), id, 1);
				fixture.child = arguments ->
				{
					int first = arguments.indexOf("--read-only-evidence");
					int second = arguments.lastIndexOf("--read-only-evidence");
					requireThat(first, "firstEvidenceOption").isNotNegative();
					requireThat(second, "secondEvidenceOption").isGreaterThan(first);
					requireThat(Path.of(arguments.get(first + 1)), "runnerEffectsDirectory").isEqualTo(offeredEffects);
					requireThat(Path.of(arguments.get(second + 1)), "runnerArtifactsDirectory").isEqualTo(offeredArtifacts);
					requireThat(Files.readString(offeredEffects.resolve("shipping.txt")), "stagedEffect").isEqualTo(shipping);
					requireThat(Files.readString(offeredArtifacts.resolve("transcript.jsonl")), "stagedTranscript").
						isEqualTo(transcript);
					Path evidence = offeredEffects.getParent();
					try (Stream<Path> roots = Files.list(evidence))
					{
						requireThat(roots.sorted().toList(), "stagedEvidenceDirectories").
							isEqualTo(List.of(offeredEffects, offeredArtifacts));
					}
					return child.start(arguments);
				};
				List<String> arguments = List.of("--project", judge.toString(), "--results",
					fixture.root.resolve("optimizer-nested-sessions").toString(), "--model", "haiku", "--effort", "low",
					"--held-out", "--deny-read", fixture.project.toString(), "--read-only-evidence", effects.toString(),
					"--read-only-evidence", artifacts.toString());
				String prompt = "Read " + offeredEffects.resolve("shipping.txt") + " and " +
					offeredArtifacts.resolve("transcript.jsonl");
				String turn = fixture.json.createObjectNode().put("type", "turn").put("prompt", prompt).toString();
				SessionRequestForwarder.serve(SessionRequestForwarder.Route.SERVER, id, "The prompt optimizer", "claude",
					ownArtifact, arguments, new StringReader(turn + "\n{\"type\":\"complete\"}\n"), new StringWriter(),
					server.requests(), server.pause());
			}
		}
	}

	/**
	 * Verifies that the server ends without a failure when its session ends after a command received its answer and
	 * removed the request's response folder, as the prompt optimizer's command does: a requester that has gone needs no
	 * answer.
	 *
	 * @throws IOException          if the fixture cannot be created or a request cannot be served
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = 60_000)
	public void endsAfterRequesterRemovedResponse() throws IOException, InterruptedException
	{
		try (Fixture fixture = new Fixture())
		{
			List<ServedNestedSession> served = new CopyOnWriteArrayList<>();
			fixture.child = slotChild(fixture, served);
			try (Fixture.Running server = fixture.start())
			{
				serve(fixture, server, fixture.root.resolve("optimizer-nested-sessions"));
				try (Stream<Path> responses = Files.list(fixture.sessionDirectory().resolve("responses")))
				{
					requireThat(responses.toList(), "responses").isEmpty();
				}
				fixture.processes.end(fixture.harness);
				requireThat(server.end(), "exit").isEqualTo(0);
			}
		}
	}

	/**
	 * Verifies that a request whose slot the server refuses to empty fails with the reason, and that the server goes
	 * on serving: an earlier session's nested session, whose runner recorded that it was about to start a harness and
	 * recorded nothing more, holds the only development slot that the request can take, so the request is answered {@code
	 * failed}; once that run records that no harness started, the next request runs in the slot.
	 *
	 * @throws IOException if the fixture cannot be created or a request cannot be served
	 */
	@Test(timeOut = 60_000)
	public void servesAfterSlotCannotBeEmptied() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Path slot = Files.createDirectories(fixture.sessionDirectory().resolve("slots/d1"));
			NestedSessionManager earlier = new NestedSessionManager(new FileLocks(), slot, Clock.systemUTC(),
				Yielding.fileReplacer()).withFixedSlotId("s-1");
			NestedSession leftover = earlier.create("claude");
			earlier.markHarnessStarting(leftover);
			List<ServedNestedSession> served = new CopyOnWriteArrayList<>();
			Path resultsFolder = fixture.root.resolve("optimizer-nested-sessions");
			try (Fixture.Running server = fixture.start())
			{
				JsonNode refused = server.request(fixture.manifest());
				requireThat(refused.path("status").asString(), "status").isEqualTo("failed");
				requireThat(refused.path("reason").asString(), "reason").contains("Harness launch state is unknown");

				earlier.recordNoHarnessProcess(leftover);
				fixture.child = slotChild(fixture, served);
				serve(fixture, server, resultsFolder);
				requireThat(served, "served").size().isEqualTo(1);
				requireThat(served.getFirst().resultsFolder(), "resultsFolder").isEqualTo(slot);
			}
		}
	}

	/**
	 * Verifies that the results copy that the command receives survives the next session's use of the slot: each
	 * session's results copy is in the command's results folder under an identifier of its own, with the files of its own
	 * session.
	 *
	 * @throws IOException          if the fixture cannot be created or a request cannot be served
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = 60_000)
	public void servedResultsCopiesSurviveSlotReuse() throws IOException, InterruptedException
	{
		try (Fixture fixture = new Fixture())
		{
			List<ServedNestedSession> served = new CopyOnWriteArrayList<>();
			fixture.child = slotChild(fixture, served);
			Path resultsFolder = fixture.root.resolve("optimizer-nested-sessions");
			try (Fixture.Running server = fixture.start())
			{
				SlotReuse reuse = serveUntilSlotReuse(fixture, server, resultsFolder, served);
				ServedNestedSession first = reuse.earlier();
				String firstId = reuse.earlierId();
				String secondId = reuse.currentId();
				requireThat(secondId, "secondSessionId").isNotEqualTo(firstId);
				requireThat(Files.readString(resultsFolder.resolve("claude").resolve(firstId).resolve("result.txt")),
					"first").isEqualTo(String.valueOf(served.indexOf(first) + 1));
				requireThat(Files.readString(resultsFolder.resolve("claude").resolve(secondId).resolve("result.txt")),
					"second").isEqualTo(String.valueOf(served.size()));
				requireThat(Files.readString(resultsFolder.resolve("claude").resolve(firstId).resolve("session.json")),
					"manifest").contains("\"id\": \"" + firstId + "\"");
			}
		}
	}

	/**
	 * Serves sessions until one reuses an earlier session's slot and returns that earlier session.
	 *
	 * @param fixture       the session that runs the server
	 * @param server        the running server
	 * @param resultsFolder the command's results folder
	 * @param served        initially empty list that the child populates with the served sessions
	 * @return the earlier session and both retained slot IDs for the reused slot
	 * @throws IOException if a session does not complete
	 */
	private static SlotReuse serveUntilSlotReuse(Fixture fixture, Fixture.Running server, Path resultsFolder,
		List<ServedNestedSession> served) throws IOException
	{
		// A response can arrive before cleanup releases its slot. One more session than the fixed pool's size
		// must reuse a slot, without requiring the immediately preceding session's cleanup to have finished.
		List<String> retainedIds = new ArrayList<>();
		for (int request = 0; request <= HarnessCapacity.MAXIMUM_CONCURRENCY; ++request)
		{
			retainedIds.add(serve(fixture, server, resultsFolder));
			ServedNestedSession current = served.getLast();
			for (int previous = 0; previous < served.size() - 1; ++previous)
			{
				ServedNestedSession earlier = served.get(previous);
				if (earlier.resultsFolder().equals(current.resultsFolder()))
					return new SlotReuse(earlier, retainedIds.get(previous), retainedIds.getLast());
			}
		}
		throw new AssertionError("The fixed server slot pool did not reuse a slot");
	}

	/**
	 * Returns a request handler that ends the captured runner when it receives a complete record.
	 *
	 * @param json reads the runner's input record
	 * @param process receives the fake runner before the handler is called
	 * @return the request handler
	 */
	private static Consumer<String> exitOnCompleteRecord(JsonMapper json, AtomicReference<FakeHarnessCli> process)
	{
		return line ->
		{
			if (json.readTree(line).path("type").asString().equals("complete"))
				process.get().exit();
		};
	}

	/**
	 * Serves one session of a command through the server, as the prompt optimizer does.
	 *
	 * @param fixture       the session that runs the server
	 * @param server        the running server
	 * @param resultsFolder the command's results folder
	 * @param extra         further options of the launch
	 * @return the identifier that the session's harness reported, as the command's records name it
	 * @throws IOException if the session does not complete
	 */
	private static String serve(Fixture fixture, Fixture.Running server, Path resultsFolder, String... extra)
		throws IOException
	{
		Path ownArtifact = Files.createDirectories(fixture.root.resolve("artifact"));
		StringWriter output = new StringWriter();
		List<String> arguments = new ArrayList<>(List.of("--project", fixture.project.toString(), "--results",
			resultsFolder.toString(), "--model", "haiku", "--effort", "low"));
		arguments.addAll(List.of(extra));
		SessionRequestForwarder.serve(SessionRequestForwarder.Route.SERVER, "The prompt optimizer", "claude",
			ownArtifact, arguments,
			new StringReader("{\"type\":\"turn\",\"prompt\":\"hello\"}\n{\"type\":\"complete\"}\n"), output,
			server.requests(), server.pause());
		return output.toString().lines().map(fixture.json::readTree).
			filter(record -> record.path("type").asString().equals("session_id")).findFirst().orElseThrow().
			path("session_id").asString();
	}

	/**
	 * Returns a double of a requested runner that builds its nested session in the results folder and under the slot ID
	 * that the server names, as the real runner does, and completes the session when its records end.
	 *
	 * @param fixture the session that runs the server
	 * @param served  receives what each session finds in its slot and builds there
	 * @return the double
	 */
	private static Child slotChild(Fixture fixture, List<ServedNestedSession> served)
	{
		FileLocks fileLocks = new FileLocks();
		return arguments ->
		{
			Path resultsFolder = Path.of(option(arguments, "--results"));
			List<String> entries;
			try (Stream<Path> paths = Files.list(resultsFolder))
			{
				// The server prepares the session's policy in the slot, in entries whose names start with a dot
				entries = paths.map(path -> path.getFileName().toString()).filter(name -> !name.startsWith(".")).
					toList();
			}
			NestedSessionManager manager = new NestedSessionManager(fileLocks, resultsFolder, Clock.systemUTC(),
				Yielding.fileReplacer()).withFixedSlotId(option(arguments, "--slot-id"));
			NestedSession nestedSession = manager.create("claude");
			String sessionId = UUID.randomUUID().toString();
			manager.recordHarnessSession(nestedSession, sessionId);
			served.add(new ServedNestedSession(resultsFolder, nestedSession, entries));
			Files.writeString(nestedSession.directory().resolve("result.txt"), String.valueOf(served.size()));
			Files.writeString(nestedSession.directory().resolve("left-behind"), "left behind");
			Path output = Path.of(option(arguments, "--output"));
			Files.writeString(output, fixture.json.createObjectNode().put("type", "started") + "\n" +
				fixture.json.createObjectNode().put("type", "session_id").put("session_id", sessionId) + "\n");
			AtomicReference<FakeHarnessCli> process = new AtomicReference<>();
			process.set(new FakeHarnessCli(line ->
			{
				try
				{
					if (fixture.json.readTree(line).path("type").asString().equals("complete"))
					{
						Files.writeString(output, fixture.json.createObjectNode().put("type", "completed") + "\n",
							StandardOpenOption.APPEND);
						manager.markCompleted(nestedSession);
						NestedSessionReceipt.write(fixture.json, Yielding.fileReplacer(),
							Path.of(option(arguments, "--receipt")), nestedSession, output, List.of());
						process.get().exit();
					}
				}
				catch (IOException e)
				{
					throw new UncheckedIOException(e);
				}
			}));
			return process.get();
		};
	}

	/**
	 * What a served session finds in its slot, and the nested session that it builds there.
	 *
	 * @param resultsFolder  the results folder that the session received
	 * @param nestedSession  the nested session that it built
	 * @param entriesAtStart the names of the entries of the results folder when the session started
	 */
	private record ServedNestedSession(Path resultsFolder, NestedSession nestedSession, List<String> entriesAtStart)
	{
	}

	/**
	 * Identifies two kept sessions that used the same reusable slot.
	 *
	 * @param earlier   earlier session whose slot was reused
	 * @param earlierId identifier of its retained copy
	 * @param currentId identifier of the latest retained copy
	 */
	private record SlotReuse(ServedNestedSession earlier, String earlierId, String currentId)
	{
	}

	/**
	 * Verifies that the server applies a request's own session requests and depth to the session that it starts, so
	 * that the top-level session's requests have no caller limit.
	 *
	 * @throws IOException          if the fixture cannot be created or a request cannot be served
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = 60_000)
	public void appliesRequestedSessionRequests() throws IOException, InterruptedException
	{
		try (Fixture fixture = new Fixture())
		{
			AtomicReference<List<String>> started = new AtomicReference<>();
			AtomicReference<FakeHarnessCli> child = new AtomicReference<>();
			fixture.child = arguments ->
			{
				started.set(arguments);
				Files.writeString(Path.of(option(arguments, "--receipt")), "{}");
				FakeHarnessCli process = new FakeHarnessCli(exitOnCompleteRecord(fixture.json, child));
				child.set(process);
				return process;
			};
			try (Fixture.Running server = fixture.start())
			{
				ObjectNode manifest = fixture.manifest().put("session_requests", 3).put("request_depth", 2).
					put("max_concurrency", 4);
				JsonNode response = server.request(manifest);
				requireThat(response.path("status").asString(), "status").withContext(response, "response").
					isEqualTo("completed");
				requireThat(option(started.get(), "--session-requests"), "sessionRequests").isEqualTo("3");
				requireThat(option(started.get(), "--request-depth"), "requestDepth").isEqualTo("2");
				requireThat(option(started.get(), "--max-concurrency"), "maxConcurrency").isEqualTo("4");
			}
		}
	}

	/**
	 * Verifies that the server passes the source home and the gateway login class of a launch's request to the runner
	 * that it asks for the child's needs and to the runner that starts the session, as the launch's own choice, which the
	 * runner checks against its bound.
	 *
	 * @throws IOException          if the fixture cannot be created or a request cannot be served
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = 60_000)
	public void passesSourceHomeToRunner() throws IOException, InterruptedException
	{
		try (Fixture fixture = new Fixture())
		{
			// Requirement: R-I14
			Path source = Files.createDirectories(fixture.root.resolve("source-home"));
			AtomicReference<List<String>> started = new AtomicReference<>();
			AtomicReference<FakeHarnessCli> child = new AtomicReference<>();
			fixture.child = arguments ->
			{
				started.set(arguments);
				Files.writeString(Path.of(option(arguments, "--receipt")), "{}");
				FakeHarnessCli process = new FakeHarnessCli(exitOnCompleteRecord(fixture.json, child));
				child.set(process);
				return process;
			};
			try (Fixture.Running server = fixture.start())
			{
				JsonNode response = server.request(fixture.manifest().put("home", source.toString()).
					put("gateway_login_class", "api_key"));

				requireThat(response.path("status").asString(), "status").withContext(response, "response").
					isEqualTo("completed");
				requireThat(option(started.get(), "--home"), "home").isEqualTo(source.toString());
				requireThat(option(started.get(), "--gateway-login-class"), "loginClass").isEqualTo("api_key");
				requireThat(started.get(), "arguments").doesNotContain("--inherited-source-home");
				List<String> needs = fixture.needsArguments.getLast();
				requireThat(option(needs, "--home"), "needsHome").isEqualTo(source.toString());
				requireThat(option(needs, "--gateway-login-class"), "needsLoginClass").isEqualTo("api_key");
			}
		}
	}

	/**
	 * Verifies that the server refuses a launch whose runner reads a source file that the requesting session's sandbox
	 * denies it reading, before it starts the session, naming the file and the denial: a session may use only a
	 * credential that it may read.
	 *
	 * @throws IOException          if the fixture cannot be created or a request cannot be served
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = 60_000)
	public void refusesSourceFileThatRequesterMayNotRead() throws IOException, InterruptedException
	{
		try (Fixture fixture = new Fixture())
		{
			// Requirement: R-I14
			Path source = Files.createDirectories(fixture.root.resolve("source-home/.claude")).getParent();
			Path settings = Files.writeString(source.resolve(".claude/settings.json"), "{}");
			fixture.needsLogins = List.of(settings);
			fixture.policy = new FakeParentPolicySource("Claude Code", new ParentPolicy(true, List.of(fixture.project),
				List.of(), List.of(source), List.of(), HostAccess.listed(Set.of(MODEL_HOST)), false, List.of()));
			fixture.child = _ ->
			{
				throw new AssertionError("The server started a session for a refused request");
			};
			try (Fixture.Running server = fixture.start())
			{
				JsonNode response = server.request(fixture.manifest().put("home", source.toString()));

				requireThat(response.path("status").asString(), "status").isEqualTo("refused");
				String reason = response.path("reason").asString();
				requireThat(reason, "reason").contains(settings.toString());
				// The denied folder's path begins the file's, so the denial is named only if it remains without the file's
				requireThat(reason.replace(settings.toString(), ""), "denial").contains(source.toString());
			}
		}
	}

	/**
	 * Verifies that the server refuses a request that carries a bound or another option that only CAT's own starters
	 * pass, without starting a session.
	 *
	 * @throws IOException          if the fixture cannot be created or a request cannot be served
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = 60_000)
	public void refusesBoundInRequest() throws IOException, InterruptedException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.child = _ ->
			{
				throw new AssertionError("The server started a session for a refused request");
			};
			try (Fixture.Running server = fixture.start())
			{
				JsonNode response = server.request(fixture.manifest().put("policy_bound", "bound.json"));
				requireThat(response.path("status").asString(), "status").isEqualTo("refused");
				requireThat(response.path("reason").asString(), "reason").contains("policy_bound");
			}
		}
	}

	/**
	 * Verifies that the server ends once the session's harness process ends, stops the session that it started with
	 * its process tree, and answers its request with the reason {@code ended}.
	 *
	 * @throws IOException          if the fixture cannot be created or a request cannot be served
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = 60_000)
	public void endsWithSession() throws IOException, InterruptedException
	{
		try (Fixture fixture = new Fixture())
		{
			AtomicReference<FakeHarnessCli> child = new AtomicReference<>();
			fixture.child = _ ->
			{
				FakeHarnessCli process = new FakeHarnessCli(_ ->
				{
				});
				child.set(process);
				fixture.processes.end(fixture.harness);
				return process;
			};
			try (Fixture.Running server = fixture.start())
			{
				JsonNode response = server.request(fixture.manifest());
				requireThat(response.path("status").asString(), "status").isEqualTo("failed");
				requireThat(response.path("reason").asString(), "reason").isEqualTo("ended");
				requireThat(server.end(), "exit").isEqualTo(0);
				requireThat(child.get().isAlive(), "childAlive").isFalse();
			}
		}
	}

	/**
	 * Verifies that the server carries out each worktree operation of a campaign of its session in the session's
	 * project, outside the session's sandbox: add, recreate after sandboxed code deleted the worktree, repair after the
	 * registration came to name another location of the project, and remove of an existing and of a missing worktree.
	 * The client carries out none of them itself, sends no request for links that are intact, and removes each
	 * response that it read.
	 *
	 * @throws IOException          if the fixture cannot be created or a request cannot be served
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = 60_000)
	public void servesWorktreeOperations() throws IOException, InterruptedException
	{
		// Requirement: R-W17
		try (Fixture fixture = new Fixture())
		{
			Git git = fixture.repository();
			fixture.policy.allow(fixture.protectingGit());
			Path campaign = Files.createDirectories(fixture.sessionDirectory().resolve("optimizer/c1"));
			Path worktree = campaign.resolve("worktree");
			try (Fixture.Running server = fixture.start())
			{
				WorktreeRegistrar client = server.client();

				client.add(fixture.project, campaign);
				requireThat(worktree.resolve("README.md"), "added").isRegularFile();
				requireThat(TestRepositories.worktreeCount(git), "addedCount").isEqualTo(2L);

				TemporaryDirectories.deleteRecursively(worktree);
				client.recreate(fixture.project, campaign);
				requireThat(worktree.resolve("README.md"), "recreated").isRegularFile();
				requireThat(TestRepositories.worktreeCount(git), "recreatedCount").isEqualTo(2L);

				requireThat(client.repair(fixture.project, campaign), "intact").isTrue();
				Path backlink = fixture.project.resolve(".git/worktrees/worktree/gitdir");
				Files.writeString(backlink, "/moved/project/.cat/sessions/" + SESSION_ID +
					"/optimizer/c1/worktree/.git\n", StandardCharsets.UTF_8);
				requireThat(GitWorktreeRegistrar.isLinked(fixture.project, campaign), "brokenLinked").isFalse();
				requireThat(client.repair(fixture.project, campaign), "repaired").isTrue();
				requireThat(GitWorktreeRegistrar.isLinked(fixture.project, campaign), "repairedLinked").isTrue();

				client.remove(fixture.project, campaign);
				requireThat(Files.exists(worktree, LinkOption.NOFOLLOW_LINKS), "removed").isFalse();
				requireThat(TestRepositories.worktreeCount(git), "removedCount").isEqualTo(1L);
				client.remove(fixture.project, campaign);
				requireThat(TestRepositories.worktreeCount(git), "missingCount").isEqualTo(1L);

				try (Stream<Path> responses = Files.list(fixture.sessionDirectory().resolve("responses")))
				{
					requireThat(responses.toList(), "responses").isEmpty();
				}
			}
		}
	}

	/**
	 * Verifies that the owner server dispatches archived inputs to its comparison service, returns its observations
	 * to the requester, and removes both copies after use.
	 *
	 * @throws IOException if the fixture cannot be prepared or the response cannot be read
	 * @throws InterruptedException if the server is interrupted
	 */
	@Test(timeOut = 60_000)
	public void servesHookComparison() throws IOException, InterruptedException
	{
		// Requirement: R-L7
		try (Fixture fixture = new Fixture())
		{
			HookComparisonRequest request = new HookComparisonRequest(fixture.project.resolve("comparison"), List.of());
			Files.writeString(Files.createDirectories(request.production()).resolve("guide"), "project guidance");
			Files.createDirectories(request.trial());
			HookComparisonResult expected = new HookComparisonResult(new HookReport(1,
				List.of(new HookDifference("startup hook", List.of("trial"), List.of("session")))), List.of(),
				List.of("docs@market PostToolUse `./log`"));
			RequestServer.ComparisonHost comparison = (owner, _, _) -> (captured, scratch) ->
			{
				requireThat(owner.project(), "authenticatedOwner").isEqualTo(fixture.project);
				requireThat(captured.production().startsWith(scratch), "privateCopy").isTrue();
				requireThat(Files.readString(captured.production().resolve("guide")), "production").
					isEqualTo("project guidance");
				requireThat(Files.notExists(captured.trial().resolve("guide")), "trial").isTrue();
				return expected;
			};
			try (Fixture.Running server = fixture.start(Optional.of(comparison)))
			{
				requireThat(server.compare(request), "result").isEqualTo(expected);
				try (Stream<Path> responses = Files.list(fixture.sessionDirectory().resolve("responses")))
				{
					requireThat(responses.toList(), "responses").isEmpty();
				}
			}
		}
	}

	/**
	 * Verifies that the server refuses, with the reason and having changed nothing, a worktree request for a campaign
	 * that is not its session's, one whose campaign directory or worktree is a link, an add over an existing worktree,
	 * and any request of a session whose sandbox lets its commands write the repository's configuration.
	 *
	 * @throws IOException          if the fixture cannot be created or a request cannot be served
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = 60_000)
	public void refusesWorktreeRequests() throws IOException, InterruptedException
	{
		// Requirement: R-W17
		try (Fixture fixture = new Fixture())
		{
			Git git = fixture.repository();
			fixture.policy.allow(fixture.protectingGit());
			Path optimizer = Files.createDirectories(fixture.sessionDirectory().resolve("optimizer"));
			Path other = Files.createDirectories(fixture.project.resolve(".cat/sessions/" +
				"1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed/optimizer/c2"));
			Path target = Files.createDirectories(fixture.root.resolve("elsewhere"));
			Path linked = Files.createSymbolicLink(optimizer.resolve("c3"), target);
			Path campaign = Files.createDirectories(optimizer.resolve("c4"));
			Files.createSymbolicLink(Files.createDirectories(optimizer.resolve("c5")).resolve("worktree"), target);
			try (Fixture.Running server = fixture.start())
			{
				WorktreeRegistrar client = server.client();
				for (Path refused : List.of(optimizer.resolve("unknown"), other, linked, optimizer.resolve("c5")))
				{
					String message = refusal(() -> client.remove(fixture.project, refused));
					requireThat(message, "message").contains("is not this session's");
				}
				client.add(fixture.project, campaign);
				String existing = refusal(() -> client.add(fixture.project, campaign));
				requireThat(existing, "existing").contains("already exists");
				requireThat(TestRepositories.worktreeCount(git), "count").isEqualTo(2L);
				try (Stream<Path> entries = Files.list(target))
				{
					requireThat(entries.toList(), "target").isEmpty();
				}
			}
		}
		try (Fixture fixture = new Fixture())
		{
			Git git = fixture.repository();
			Path campaign = Files.createDirectories(fixture.sessionDirectory().resolve("optimizer/c1"));
			try (Fixture.Running server = fixture.start())
			{
				String message = refusal(() -> server.client().add(fixture.project, campaign));
				requireThat(message, "message").contains("lets its commands write " +
					fixture.project.resolve(".git/config"));
				requireThat(Files.exists(campaign.resolve("worktree"), LinkOption.NOFOLLOW_LINKS), "worktree").isFalse();
				requireThat(TestRepositories.worktreeCount(git), "count").isEqualTo(1L);
			}
		}
	}

	/**
	 * Verifies that the server refuses a worktree request whose document holds another field, names another
	 * operation or an identifier that is not shaped like a campaign identifier, or whose archive holds another entry.
	 *
	 * @throws IOException          if the fixture cannot be created or a request cannot be served
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = 60_000)
	public void refusesMalformedWorktreeRequests() throws IOException, InterruptedException
	{
		// Requirement: R-W17
		try (Fixture fixture = new Fixture())
		{
			fixture.repository();
			fixture.policy.allow(fixture.protectingGit());
			Files.createDirectories(fixture.sessionDirectory().resolve("optimizer/c1"));
			try (Fixture.Running server = fixture.start())
			{
				ObjectNode extra = fixture.json.createObjectNode().put("operation", "add").put("campaign", "c1").
					put("path", "/elsewhere");
				ObjectNode operation = fixture.json.createObjectNode().put("operation", "prune").put("campaign", "c1");
				ObjectNode identifier = fixture.json.createObjectNode().put("operation", "add").
					put("campaign", "../c1");
				List<String> reasons = new ArrayList<>();
				for (ObjectNode manifest : List.of(extra, operation, identifier))
				{
					JsonNode response = server.worktreeRequest(manifest, false);
					requireThat(response.path("status").asString(), "status").isEqualTo("refused");
					reasons.add(response.path("reason").asString());
				}
				JsonNode response = server.worktreeRequest(fixture.json.createObjectNode().put("operation", "add").
					put("campaign", "c1"), true);
				requireThat(response.path("status").asString(), "entryStatus").isEqualTo("refused");
				requireThat(reasons.get(0), "extra").contains("may not choose path");
				requireThat(reasons.get(1), "operation").contains("not prune");
				requireThat(reasons.get(2), "identifier").contains("campaign identifier");
				requireThat(response.path("reason").asString(), "entry").contains("holds only request.json");
				requireThat(Files.exists(fixture.sessionDirectory().resolve("optimizer/c1/worktree")), "worktree").
					isFalse();
			}
		}
	}

	/**
	 * Runs a worktree operation that must be refused.
	 *
	 * @param operation the operation
	 * @return the refusal's message
	 * @throws IOException if the operation fails otherwise
	 */
	private static String refusal(WorktreeOperation operation) throws IOException
	{
		try
		{
			operation.run();
		}
		catch (WorktreeRefusedException e)
		{
			return e.getMessage();
		}
		throw new AssertionError("The worktree operation was not refused");
	}

	/**
	 * A worktree operation of a test.
	 */
	@FunctionalInterface
	private interface WorktreeOperation
	{
		/**
		 * Runs the operation.
		 *
		 * @throws IOException if the operation fails or is refused
		 */
		void run() throws IOException;
	}

	/**
	 * Verifies that the server runs a check program for a command that runs in the served session's sandbox, under the
	 * server's reading of the session's policy, so that a path that the session may not read stays unreadable to the
	 * program, and that it leaves nothing of the run in the session's responses.
	 *
	 * @throws IOException          if the fixture cannot be created or a request cannot be served
	 * @throws InterruptedException if the run is interrupted
	 */
	@Test(timeOut = 120_000)
	public void runsCheckProgramUnderSessionPolicy() throws IOException, InterruptedException
	{
		// Requirement: R-L21
		CheckProgramFixtures.requireBubblewrap();
		try (Fixture fixture = new Fixture())
		{
			Path secret = Files.createDirectory(fixture.root.resolve("secret"));
			Path secretFile = Files.writeString(secret.resolve("token.txt"), "do not read\n");
			fixture.policy.allow(new ParentPolicy(true, List.of(fixture.project), List.of(), List.of(secret),
				List.of(), HostAccess.listed(Set.of(MODEL_HOST)), false, List.of()));
			try (Fixture.Running server = fixture.start())
			{
				CheckProgramClient client = new CheckProgramClient(
					() -> Optional.of(new ServerChannel(server.requests(), server.exit())), JsonMapper.builder().build(),
					Thread::yield);

				CheckProgram.Result result = CheckProgramFixtures.runDeniedRead(client, JsonMapper.builder().build(),
					Files.createDirectory(fixture.root.resolve("run")), secretFile, List.of());

				requireThat(result.outcome(), "outcome").withContext(result.observed(), "observed").
					isEqualTo(CheckProgram.Outcome.PASS);
				requireThat(result.observed(), "observed").contains("service after").contains("secret readable: false");
				try (Stream<Path> responses = Files.list(fixture.sessionDirectory().resolve("responses")))
				{
					requireThat(responses.toList(), "responses").isEmpty();
				}
			}
		}
	}

	/**
	 * Verifies that a check program that cannot start reaches the command that asked as a start failure with its
	 * cause, not as a plain failure whose cause is only text, so that the guard pilot can stop at once. The program's
	 * sandbox cannot execute {@code env}, which the request holds out.
	 *
	 * @throws IOException          if the fixture cannot be created or a request cannot be served
	 * @throws InterruptedException if the run is interrupted
	 */
	@Test(timeOut = 120_000)
	public void keepsTheCauseOfAStartThatFailed() throws IOException, InterruptedException
	{
		// Requirement: R-L23
		CheckProgramFixtures.requireBubblewrap();
		try (Fixture fixture = new Fixture())
		{
			Path secret = Files.createDirectory(fixture.root.resolve("secret"));
			Path secretFile = Files.writeString(secret.resolve("token.txt"), "do not read\n");
			Path env = Path.of("/usr/bin/env").toRealPath();
			fixture.policy.allow(new ParentPolicy(true, List.of(fixture.project), List.of(), List.of(secret),
				List.of(), HostAccess.listed(Set.of(MODEL_HOST)), false, List.of()));
			try (Fixture.Running server = fixture.start())
			{
				CheckProgramClient client = new CheckProgramClient(
					() -> Optional.of(new ServerChannel(server.requests(), server.exit())), JsonMapper.builder().build(),
					Thread::yield);

				CheckProgramStartFailure failure = null;
				try
				{
					CheckProgramFixtures.runDeniedRead(client, JsonMapper.builder().build(),
						Files.createDirectory(fixture.root.resolve("run")), secretFile, List.of(env));
				}
				catch (CheckProgramStartFailure e)
				{
					failure = e;
				}

				requireThat(failure, "failure").isNotNull();
				requireThat(failure.reason(), "reason").isEqualTo(CheckProgramStartFailure.Reason.SANDBOX_CANNOT_START);
				requireThat(failure.isInfrastructure(), "isInfrastructure").isFalse();
				requireThat(failure.getMessage(), "message").contains("bwrap:").contains("/usr/bin/env");
			}
		}
	}

	/**
	 * Verifies that the server refuses to run a check program once the session's sandbox lets its commands write the
	 * installed plugin, as it refuses a launch, so that no program runs on a runtime that the session could change.
	 *
	 * @throws IOException          if the fixture cannot be created or a request cannot be served
	 * @throws InterruptedException if the run is interrupted
	 */
	@Test(timeOut = 60_000)
	public void refusesCheckProgramWhenPluginIsWritable() throws IOException, InterruptedException
	{
		// Requirement: R-L21
		try (Fixture fixture = new Fixture())
		{
			try (Fixture.Running server = fixture.start())
			{
				fixture.policy.allow(fixture.policy(List.of(fixture.project, fixture.plugin)));
				CheckProgramClient client = new CheckProgramClient(
					() -> Optional.of(new ServerChannel(server.requests(), server.exit())), JsonMapper.builder().build(),
					Thread::yield);

				SessionRefusedException refused = null;
				try
				{
					CheckProgramFixtures.runDeniedRead(client, JsonMapper.builder().build(),
						Files.createDirectory(fixture.root.resolve("run")), fixture.root.resolve("unused"), List.of());
				}
				catch (SessionRefusedException e)
				{
					refused = e;
				}

				requireThat(refused, "refused").isNotNull();
				requireThat(refused.getMessage(), "message").contains("lets its commands write " + fixture.plugin);
			}
		}
	}

	/**
	 * The channel of a client to a server that runs on a thread of the test.
	 *
	 * @param requests the server's requests directory
	 * @param exit     the server's exit status
	 */
	private record ServerChannel(Path requests, Future<Integer> exit) implements WorktreeRequestClient.Channel
	{
		@Override
		public boolean isServed()
		{
			return !exit.isDone();
		}

		@Override
		public boolean removesResponses()
		{
			return true;
		}

		@Override
		public void close()
		{
		}
	}

	/**
	 * The registrar that a client uses in place, which a client that sends requests never calls.
	 */
	private static final class NoInPlace implements WorktreeRegistrar
	{
		@Override
		public void add(Path project, Path campaign)
		{
			throw new AssertionError("The client ran add itself");
		}

		@Override
		public void recreate(Path project, Path campaign)
		{
			throw new AssertionError("The client ran recreate itself");
		}

		@Override
		public void remove(Path project, Path campaign)
		{
			throw new AssertionError("The client ran remove itself");
		}

		@Override
		public boolean repair(Path project, Path campaign)
		{
			throw new AssertionError("The client ran repair itself");
		}
	}

	/**
	 * Waits, for a bounded time, until the server removes a path once it has answered the request that the path
	 * belongs to.
	 *
	 * @param path the path
	 * @return {@code true} if the path still exists when the wait ends
	 */
	private static boolean awaitAbsent(Path path)
	{
		long deadline = System.nanoTime() + WAIT.toNanos();
		while (Files.exists(path) && System.nanoTime() < deadline)
			Thread.yield();
		return Files.exists(path);
	}

	/**
	 * Returns the value of an option.
	 *
	 * @param arguments the options
	 * @param name      the option's name
	 * @return its value
	 */
	private static String option(List<String> arguments, String name)
	{
		int index = arguments.indexOf(name);
		requireThat(index, "index").withContext(arguments, "arguments").isNotNegative();
		return arguments.get(index + 1);
	}

	/**
	 * Starts a requested session's runner in a test.
	 */
	@FunctionalInterface
	private interface Child
	{
		/**
		 * Starts the runner.
		 *
		 * @param arguments the runner's options
		 * @return the runner's process
		 * @throws IOException if the runner cannot start
		 */
		Process start(List<String> arguments) throws IOException;
	}

	/**
	 * A session that runs a request server, with its project, transcript, plugin and harness process.
	 */
	private static final class Fixture implements AutoCloseable
	{
		private final TemporaryDirectories cleanup = new TemporaryDirectories();
		private final JsonMapper json = JsonMapper.builder().build();
		/**
		 * Runs the server and the output observer on platform threads so their polling leaves the broker's virtual
		 * thread scheduler available to handle requests.
		 */
		private final ExecutorService executor = Executors.newThreadPerTaskExecutor(Thread.ofPlatform().factory());
		private final Path root;
		private final Path project;
		private final Path home;
		private final Path plugin;
		/**
		 * The folders of the plugin that the harness has installed.
		 */
		private List<Path> installed;
		private final Path transcript;
		private final FakeProcessTable processes = new FakeProcessTable();
		private final ProcessIdentity harness;
		private FakeParentPolicySource policy;
		private Optional<String> hostRefusal = Optional.empty();
		private Platform platform = Platform.current();
		private Set<String> running = Set.of("claude");
		private Optional<PolicyRefusal> sessionRefusal = Optional.empty();
		private Optional<String> askingThread = Optional.empty();
		private Pause pause = Thread::yield;
		private Optional<Path> peer = Optional.empty();
		private final AtomicInteger needsCalls = new AtomicInteger();
		/**
		 * The options of each runner that the server asks for a child's needs, in the order that it asks.
		 */
		private final List<List<String>> needsArguments = new CopyOnWriteArrayList<>();
		/**
		 * The files that the runner reports reading for the child's credential.
		 */
		private List<Path> needsLogins = List.of();
		private final AtomicInteger peerSelections = new AtomicInteger();
		/**
		 * The login services that the server started.
		 */
		private final List<CodexLoginService> loginServices = new CopyOnWriteArrayList<>();
		/**
		 * The readers of the top-level session's policy that the server gave its login services.
		 */
		private final List<CodexLoginService.TopLevelPolicy> topLevels = new CopyOnWriteArrayList<>();
		/**
		 * The login-service variables of each runner that the server selected, in the order that it selected them.
		 */
		private final List<Map<String, String>> runnerVariables = new CopyOnWriteArrayList<>();
		private Child child = _ ->
		{
			throw new AssertionError("No session was expected");
		};

		/**
		 * Creates the session.
		 *
		 * @throws IOException if its files cannot be created
		 */
		private Fixture() throws IOException
		{
			root = cleanup.add(Files.createTempDirectory("request-server-")).toRealPath();
			project = Files.createDirectories(root.resolve("project/.cat")).getParent();
			Files.writeString(project.resolve("README.md"), "project\n");
			home = Files.createDirectories(root.resolve("home"));
			plugin = Files.createDirectories(root.resolve("plugin"));
			installed = List.of(plugin);
			transcript = Files.writeString(Files.createDirectories(root.resolve("transcripts")).
				resolve(SESSION_ID + ".jsonl"), "{}\n");
			harness = processes.start(4242);
			policy = new FakeParentPolicySource("Claude Code", policy(List.of(project)));
		}

		/**
		 * Installs a peer with a separate bound runtime, both outside the owning session's write roots.
		 *
		 * @return the peer's runtime
		 * @throws IOException if the peer or runtime cannot be created
		 */
		private Path installPeer() throws IOException
		{
			Path artifact = root.resolve("peer");
			Path runtime = FakeRelease.runtime(root.resolve("peer-runtime"), Platform.current());
			RuntimeBinding.write(artifact, runtime);
			Files.createFile(artifact.resolve("bin/runner"));
			peer = Optional.of(artifact);
			return runtime;
		}

		/**
		 * Returns a sandboxed policy that may reach the model's host.
		 *
		 * @param writeRoots the folders that the session may write
		 * @return the policy
		 */
		private ParentPolicy policy(List<Path> writeRoots)
		{
			return new ParentPolicy(true, writeRoots, List.of(), List.of(), List.of(),
				HostAccess.listed(Set.of(MODEL_HOST)), false, List.of());
		}

		/**
		 * Makes the session's project a repository with one commit.
		 *
		 * @return the repository's Git
		 * @throws IOException if Git fails
		 */
		private Git repository() throws IOException
		{
			return TestRepositories.initialize(project, root.resolve("no-excludes"));
		}

		/**
		 * Returns a sandboxed policy that lets the session write its project but not the repository's configuration
		 * or hooks, as a harness's sandbox protects them.
		 *
		 * @return the policy
		 */
		private ParentPolicy protectingGit()
		{
			return new ParentPolicy(true, List.of(project), List.of(project.resolve(".git/config"),
				project.resolve(".git/hooks")), List.of(), List.of(), HostAccess.listed(Set.of(MODEL_HOST)), false,
				List.of());
		}

		/**
		 * Returns the session's CAT session directory.
		 *
		 * @return the directory
		 */
		private Path sessionDirectory()
		{
			return project.resolve(".cat/sessions").resolve(SESSION_ID);
		}

		/**
		 * Returns what the plugin gives the server.
		 *
		 * @return the host
		 */
		private RequestServer.Host host()
		{
			Map<String, String> environment = Map.of(HARNESS_A.sessionVariable(), SESSION_ID, "HOME", home.toString(),
				"USERPROFILE", home.toString());
			RequestedRunner.Start starter = (arguments, _) ->
			{
				if (arguments.getFirst().equals(ChildNeedsReport.OPTION))
				{
					needsCalls.incrementAndGet();
					needsArguments.add(List.copyOf(arguments));
					new ChildNeedsReport(List.of(), Set.of(MODEL_HOST), needsLogins).write(json, Path.of(arguments.get(1)));
					return new ExitedProcess(new ProcessBuilder(), 0, "", "");
				}
				return child.start(arguments);
			};
			return new RequestServer.Host("claude", running, hostRefusal, plugin, () -> installed,
				() -> "the session runs the server inside its sandbox", () ->
			{
				if (sessionRefusal.isPresent())
					throw sessionRefusal.get();
				return new RequestServer.Session(policy, transcript, project, askingThread, harness);
			}, directory -> ToolScope.forSession(directory, environment, List.of(HARNESS_A), Yielding.waiting(),
				GitProjects.IGNORING), (parent, topLevel) ->
			{
				topLevels.add(topLevel);
				CodexLoginService service = RequestServer.LoginServices.system(Map.of()).start(parent, topLevel);
				loginServices.add(service);
				return service;
			}, variables -> harness ->
				{
					runnerVariables.add(Map.copyOf(variables));
					Path artifact = plugin;
					if (harness.equals("codex") && peer.isPresent())
					{
						peerSelections.incrementAndGet();
						artifact = peer.get();
					}
					return new RequestedRunner.Selection(artifact, artifact.resolve("bin/runner"), starter);
				}, processes, pause, platform);
		}

		/**
		 * Runs the server, expecting it to refuse.
		 *
		 * @return what it wrote to its errors
		 * @throws IOException if the server fails
		 */
		private String refusal() throws IOException
		{
			StringWriter errors = new StringWriter();
			int status = RequestServer.serve(new String[0], host(), new PrintWriter(errors, true));
			requireThat(status, "status").withContext(errors, "errors").
				isEqualTo(HarnessRunnerOptions.REFUSED_LAUNCH_STATUS);
			return errors.toString().replace(System.lineSeparator(), "\n");
		}

		/**
		 * Returns a valid request manifest, which names the entries that {@link Running#request(ObjectNode)} packs.
		 *
		 * @return the manifest
		 */
		private ObjectNode manifest()
		{
			ObjectNode request = json.createObjectNode().put("format_version", 3).put("harness", "claude").
				put("project", "project").put("model", "haiku").put("effort", "low");
			request.putArray("prompts").add("prompts/0");
			request.putArray("plugins");
			return request;
		}

		/**
		 * Starts the server on a thread of its own, once its session's directories exist.
		 *
		 * @return the running server
		 * @throws IOException if the server does not serve in time
		 */
		private Running start() throws IOException
		{
			return start(Optional.empty());
		}

		/**
		 * Starts a server whose next scan can wait for the fixture to publish a request.
		 *
		 * @return the running server
		 * @throws IOException if the server does not serve in time
		 */
		private Running startForRequest() throws IOException
		{
			Semaphore requestReady = new Semaphore(0);
			pause = Pause.interruptible(requestReady::acquire);
			return start(Optional.empty(), requestReady::release);
		}

		/**
		 * Starts the server with the selected native comparison service.
		 *
		 * @param comparison the comparison service, if the fixture serves one
		 * @return the running server
		 * @throws IOException if the server does not serve in time
		 */
		private Running start(Optional<RequestServer.ComparisonHost> comparison) throws IOException
		{
			return start(comparison, () ->
			{
			});
		}

		/**
		 * Starts the server with the selected comparison service and scan signal.
		 *
		 * @param comparison the comparison service, if the fixture serves one
		 * @param wake wakes the server after publication or when the owner ends
		 * @return the running server
		 * @throws IOException if the server does not serve in time
		 */
		private Running start(Optional<RequestServer.ComparisonHost> comparison, Runnable wake) throws IOException
		{
			StringWriter errors = new StringWriter();
			Future<Integer> exit = executor.submit(() -> RequestServer.serve(new String[0], host(),
				new PrintWriter(errors, true), comparison));
			Path requests = sessionDirectory().resolve(RequestServer.REQUESTS);
			long deadline = System.nanoTime() + WAIT.toNanos();
			while (!Files.isDirectory(sessionDirectory().resolve("responses")))
			{
				if (exit.isDone())
					throw new AssertionError("The server ended: " + result(exit));
				if (System.nanoTime() > deadline)
				{
					exit.cancel(true);
					throw new AssertionError("The server created no request directories. " + errors);
				}
				Thread.yield();
			}
			return new Running(exit, requests, wake, errors);
		}

		/**
		 * Returns a finished server's exit status.
		 *
		 * @param exit the server's result
		 * @return its exit status, or its failure
		 */
		private static String result(Future<Integer> exit)
		{
			try
			{
				return String.valueOf(exit.get());
			}
			catch (InterruptedException | ExecutionException e)
			{
				return e.toString();
			}
		}

		@Override
		public void close() throws IOException
		{
			processes.end(harness);
			executor.close();
			cleanup.close();
		}

		/**
		 * A server that runs on a thread of the fixture.
		 *
		 * @param exit     the server's exit status
		 * @param requests the directory into which the session's commands write their requests
		 * @param wake wakes the server if it waits for the test to publish a request
		 * @param errors the server's diagnostics
		 */
		private record Running(Future<Integer> exit, Path requests, Runnable wake, StringWriter errors)
			implements AutoCloseable
		{
			/**
			 * Writes a request archive that holds a manifest, a project and one prompt, and waits for its response.
			 *
			 * @param manifest the manifest
			 * @return the response
			 * @throws IOException if the request cannot be written or the response does not arrive in time
			 */
			private JsonNode request(ObjectNode manifest) throws IOException
			{
				JsonMapper json = JsonMapper.builder().build();
				String id = "0b7c8f3e-1d2a-4c5b-9e6f-7a8b9c0d1e2f";
				Path staging = requests.resolveSibling("staging.tar");
				try (OutputStream bytes = Files.newOutputStream(staging);
				     TarArchiveOutputStream archive = new TarArchiveOutputStream(bytes))
				{
					entry(archive, "request.json", json.writeValueAsBytes(manifest));
					entry(archive, "project/", new byte[0]);
					entry(archive, "project/marker", "original".getBytes(StandardCharsets.UTF_8));
					entry(archive, "prompts/0", "hello".getBytes(StandardCharsets.UTF_8));
				}
				Path published = Files.move(staging, requests.resolve(id + ".tar"));
				wake.run();
				Path response = requests.resolveSibling("responses").resolve(id).resolve("response.json");
				long deadline = System.nanoTime() + WAIT.toNanos();
				while (!Files.isRegularFile(response))
				{
					if (exit.isDone())
						throw new AssertionError("The server ended before answering " + id + ": " + result(exit) + ". " + errors);
					if (System.nanoTime() > deadline)
					{
						throw new AssertionError("The server did not answer " + id + ". Request still in inbox: " +
							Files.exists(published) + ". Response folder created: " + Files.isDirectory(response.getParent()) +
							". " + errors);
					}
					Thread.yield();
				}
				return json.readTree(Files.readString(response));
			}

			/**
			 * Returns a pause for a command that waits for this server's answer. It fails the wait once the server has
			 * ended, which no answer follows, or once the answer has not arrived in time, so that a command whose server
			 * stopped fails its test instead of waiting for ever.
			 *
			 * @return the pause, whose time starts now
			 */
			private Pause pause()
			{
				long deadline = System.nanoTime() + WAIT.toNanos();
				return () ->
				{
					if (exit.isDone())
						throw new IOException("The server ended before answering: " + result(exit) + ". " + errors);
					if (System.nanoTime() > deadline)
						throw new IOException("The server did not answer within " + WAIT + ". " + errors);
					Thread.yield();
				};
			}

			/**
			 * Returns a client that sends its worktree requests to this server.
			 *
			 * @return the client
			 */
			private WorktreeRegistrar client()
			{
				return new WorktreeRequestClient(() -> Optional.of(new ServerChannel(requests, exit)), new NoInPlace(),
					JsonMapper.builder().build(), Thread::yield);
			}

			/**
			 * Sends a comparison through this fixture's owner channel.
			 *
			 * @param request the comparison to send
			 * @return the observed result
			 * @throws IOException if the broker does not return a result
			 */
			private HookComparisonResult compare(HookComparisonRequest request) throws IOException
			{
				try (ServerChannel channel = new ServerChannel(requests, exit))
				{
					return request.compare(JsonMapper.builder().build(), channel, Thread::yield);
				}
			}

			/**
			 * Writes a worktree request archive that holds a document, and waits for its response.
			 *
			 * @param manifest the document
			 * @param extra    {@code true} to add an entry besides the document
			 * @return the response
			 * @throws IOException if the request cannot be written or the response does not arrive in time
			 */
			private JsonNode worktreeRequest(ObjectNode manifest, boolean extra) throws IOException
			{
				JsonMapper json = JsonMapper.builder().build();
				String id = WorktreeRequest.PREFIX + UUID.randomUUID();
				Path staging = requests.resolveSibling("staging.tar");
				try (OutputStream bytes = Files.newOutputStream(staging);
				     TarArchiveOutputStream archive = new TarArchiveOutputStream(bytes))
				{
					entry(archive, "request.json", json.writeValueAsBytes(manifest));
					if (extra)
						entry(archive, "project/marker", "extra".getBytes(StandardCharsets.UTF_8));
				}
				Files.move(staging, requests.resolve(id + ".tar"));
				Path response = requests.resolveSibling("responses").resolve(id).resolve("response.json");
				long deadline = System.nanoTime() + WAIT.toNanos();
				while (!Files.isRegularFile(response))
				{
					if (System.nanoTime() > deadline)
						throw new AssertionError("The server did not answer " + id);
					Thread.onSpinWait();
				}
				return json.readTree(Files.readString(response));
			}

			/**
			 * Adds a file or empty directory to a test archive.
			 *
			 * @param archive the archive
			 * @param name    the entry name
			 * @param content its bytes
			 * @throws IOException if writing fails
			 */
			private static void entry(TarArchiveOutputStream archive, String name, byte[] content) throws IOException
			{
				TarArchiveEntry entry = new TarArchiveEntry(name, true);
				entry.setSize(content.length);
				archive.putArchiveEntry(entry);
				archive.write(content);
				archive.closeArchiveEntry();
			}

			/**
			 * Waits for the server to end, after its session's harness process has ended.
			 *
			 * @return the server's exit status
			 * @throws InterruptedException if the test is interrupted
			 */
			private int end() throws InterruptedException
			{
				wake.run();
				try
				{
					return exit.get(WAIT.toSeconds(), TimeUnit.SECONDS);
				}
				catch (ExecutionException | TimeoutException e)
				{
					throw new AssertionError(e);
				}
			}

			@Override
			public void close()
			{
				exit.cancel(true);
			}
		}
	}
}
