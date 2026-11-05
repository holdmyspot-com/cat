package com.holdmyspot.cat.common.runner.launcher.client.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.ManualClock;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.optimizer.adapter.Bridge;
import com.holdmyspot.cat.common.runner.HarnessTemporaryRoot;
import com.holdmyspot.cat.common.runner.SessionLauncherChannel;
import com.holdmyspot.cat.common.runner.SessionLauncherChannel.Status;
import com.holdmyspot.cat.common.runner.launcher.client.GitIdentity;
import com.holdmyspot.cat.common.runner.launcher.client.LaunchRefusedException;
import com.holdmyspot.cat.common.runner.launcher.client.SessionLauncherClient;
import com.holdmyspot.cat.common.runner.launcher.client.test.FakeLauncher.FakeHolder;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Stream;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests that a runner launch inside a sandboxed session becomes a request to the session launcher of its harness
 * process, through {@link SessionLauncherClient}.
 */
public final class SessionLauncherClientTest
{
	/**
	 * The instant at which each test's clock starts.
	 */
	private static final Instant START = Instant.parse("2026-11-05T12:00:00Z");
	/**
	 * The product name of the harness that runs the tests' sessions.
	 */
	private static final String PRODUCT = "Claude Code";

	/**
	 * Creates the test.
	 */
	public SessionLauncherClientTest()
	{
	}

	/**
	 * One test's files, clock, launcher and client.
	 *
	 * @param root     the test's directory
	 * @param channel  the channel below the test's home directory
	 * @param clock    the client's clock
	 * @param launcher the fake launcher, which also starts the holders
	 * @param client   the client
	 * @param project  a project with one file
	 */
	private record Fixture(Path root, SessionLauncherChannel channel, ManualClock clock, FakeLauncher launcher,
		SessionLauncherClient client, Path project)
	{
		/**
		 * Creates the fixture.
		 *
		 * @param root the test's directory
		 * @return the fixture
		 * @throws IOException if the files cannot be written
		 */
		static Fixture create(Path root) throws IOException
		{
			SessionLauncherChannel channel = new SessionLauncherChannel(Files.createDirectory(root.resolve("home")));
			ManualClock clock = new ManualClock(START);
			FakeLauncher launcher = new FakeLauncher(channel, clock);
			GitIdentity git = new GitIdentity()
			{
				@Override
				public Optional<String> name(Path project)
				{
					return Optional.of("Ada");
				}

				@Override
				public Optional<String> email(Path project)
				{
					return Optional.of("ada@example.com");
				}
			};
			SessionLauncherClient client = new SessionLauncherClient(channel, PRODUCT, launcher, git, clock,
				launcher::step);
			Path project = Files.createDirectory(root.resolve("project"));
			Files.writeString(project.resolve("README.md"), "project");
			return new Fixture(root, channel, clock, launcher, client, project);
		}

		/**
		 * Writes a records file with one turn and {@code complete}.
		 *
		 * @return the file
		 * @throws IOException if it cannot be written
		 */
		Path records() throws IOException
		{
			return Files.writeString(root.resolve("turns.jsonl"), "{\"type\":\"turn\",\"prompt\":\"task\"}\n" +
				"{\"type\":\"complete\"}\n");
		}

		/**
		 * Runs a launch.
		 *
		 * @param errors receives the reason when the launch does not complete
		 * @param args   the launch's options
		 * @return the exit status
		 * @throws IOException if the launch fails
		 */
		int launch(StringWriter errors, String... args) throws IOException
		{
			return client.launch("claude", args, root, HarnessTemporaryRoot.of(root.resolve("session-temp")),
				new StringReader(""), new ByteArrayOutputStream(), new PrintWriter(errors, true));
		}

		/**
		 * Lists the inbox's entries.
		 *
		 * @return their names
		 * @throws IOException if the inbox cannot be listed
		 */
		List<String> inbox() throws IOException
		{
			if (!Files.isDirectory(channel.inbox()))
				return List.of();
			try (Stream<Path> entries = Files.list(channel.inbox()))
			{
				return entries.map(path -> path.getFileName().toString()).toList();
			}
		}
	}

	/**
	 * Verifies that with no launcher registered, the launch exits at once with status 2 and the no-launcher message,
	 * publishing nothing and starting no holder.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void exitsAtOnceWithoutLauncher() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = Fixture.create(cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(),
				"client-")));
			Files.createDirectories(fixture.channel().launchers());
			StringWriter errors = new StringWriter();

			int status = fixture.launch(errors, "--project", fixture.project().toString(), "--model", "m",
				"--effort", "e", "--input", fixture.records().toString());

			requireThat(status, "status").isEqualTo(2);
			requireThat(errors.toString(), "errors").contains(SessionLauncherChannel.noLauncherMessage(PRODUCT));
			requireThat(fixture.inbox(), "inbox").isEmpty();
			requireThat(fixture.launcher().holders, "holders").isEmpty();
			requireThat(fixture.clock().instant(), "clock").isEqualTo(START);
		}
	}

	/**
	 * Verifies that when the launchers' state folder cannot be read, the launch exits with status 2 and a message
	 * that names the folder.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void exitsWhenStateIsUnreadable() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = Fixture.create(cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(),
				"client-")));
			fixture.launcher().register();
			Path state = fixture.channel().stateRoot();
			Files.setPosixFilePermissions(state, PosixFilePermissions.fromString("---------"));
			StringWriter errors = new StringWriter();
			int status;
			try
			{
				status = fixture.launch(errors, "--project", fixture.project().toString(), "--model", "m",
					"--effort", "e", "--input", fixture.records().toString());
			}
			finally
			{
				Files.setPosixFilePermissions(state, PosixFilePermissions.fromString("rwx------"));
			}

			requireThat(status, "status").isEqualTo(2);
			requireThat(errors.toString(), "errors").contains(state.toString());
			requireThat(fixture.inbox(), "inbox").isEmpty();
		}
	}

	/**
	 * Verifies that a request that no launcher claims ends after the acceptance wait with status 2 and the
	 * no-launcher message, leaving no inbox file and ending its holder.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void exitsWhenRequestIsUnclaimed() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = Fixture.create(cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(),
				"client-")));
			fixture.launcher().register();
			fixture.launcher().accepts = false;
			StringWriter errors = new StringWriter();

			int status = fixture.launch(errors, "--project", fixture.project().toString(), "--model", "m",
				"--effort", "e", "--input", fixture.records().toString());

			requireThat(status, "status").isEqualTo(2);
			requireThat(errors.toString(), "errors").contains(SessionLauncherChannel.noLauncherMessage(PRODUCT));
			Duration waited = Duration.between(START, fixture.clock().instant());
			requireThat(waited, "waited").isGreaterThanOrEqualTo(SessionLauncherChannel.ACCEPTANCE_WAIT).
				isLessThan(SessionLauncherChannel.ACCEPTANCE_WAIT.plusSeconds(1));
			requireThat(fixture.inbox(), "inbox").isEmpty();
			requireThat(fixture.launcher().holders.size(), "holders").isEqualTo(1);
			requireThat(fixture.launcher().holders.getFirst().closed, "closed").isTrue();
		}
	}

	/**
	 * Verifies that a completed request writes the child's records to {@code --output} and its receipt to
	 * {@code --receipt}, copies the kept run into {@code --run-root} without links or the child's home, names the
	 * copy in the records and the receipt, and exits with status 0. The request carries the launch's choices and the
	 * project's Git identity, under a holder that names its digest.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void completedRequestCopiesResults() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = Fixture.create(cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(),
				"client-")).toRealPath());
			fixture.launcher().register();
			Path output = fixture.root().resolve("out/transcript.jsonl");
			Path receipt = fixture.root().resolve("out/receipt.json");
			Path runRoot = fixture.root().resolve("runs");
			StringWriter errors = new StringWriter();

			int status = fixture.launch(errors, "--project", fixture.project().toString(), "--model", "m",
				"--effort", "e", "--input", fixture.records().toString(), "--output", output.toString(), "--receipt",
				receipt.toString(), "--keep-run", "--run-root", runRoot.toString());

			requireThat(errors.toString(), "errors").isEmpty();
			requireThat(status, "status").isEqualTo(0);
			Path run = runRoot.resolve("claude").resolve(FakeLauncher.RUN_ID);
			requireThat(Files.readString(output, UTF_8), "output").
				contains("\"type\":\"started\"").
				contains("reply 1").
				contains(run.resolve("artifacts/turn.events").toString()).
				contains("\"type\":\"completed\"");
			requireThat(Files.readString(receipt, UTF_8), "receipt").contains(run.resolve("project").toString());
			requireThat(Files.readString(run.resolve("artifacts/result.txt"), UTF_8), "result").isEqualTo("result");
			requireThat(Files.exists(run.resolve("run.json")), "runManifest").isTrue();
			requireThat(Files.exists(run.resolve("home")), "home").isFalse();
			requireThat(Files.exists(run.resolve("link"), java.nio.file.LinkOption.NOFOLLOW_LINKS), "link").isFalse();
			FakeLauncher launcher = fixture.launcher();
			requireThat(launcher.request.model(), "model").isEqualTo(Optional.of("m"));
			requireThat(launcher.request.keepRun(), "keepRun").isTrue();
			requireThat(launcher.request.capacityHeld(), "capacityHeld").isFalse();
			requireThat(launcher.request.gitName(), "gitName").isEqualTo(Optional.of("Ada"));
			requireThat(launcher.consumed, "consumed").isEqualTo(List.of("{\"type\":\"turn\",\"prompt\":\"task\"}",
				"{\"type\":\"complete\"}"));
			requireThat(launcher.requestHolder().closed, "requestHolderClosed").isTrue();
		}
	}

	/**
	 * The one launch that the copy-failure tests share: a request whose kept run cannot be copied in full, because
	 * the sender already has a file where the copy of the child's {@code artifacts/result.txt} belongs. The existing
	 * file stands for a protected file that the sender's sandbox refuses to write, and copying onto it fails.
	 *
	 * @param fixture the test's fixture
	 * @param answer  how the launcher answers the request
	 * @param errors  receives why the launch did not complete
	 * @param output  the file that receives the child's records
	 * @param runRoot the run root that receives the kept run
	 * @return the launch's exit status
	 * @throws IOException if the fixture cannot be written
	 */
	private static int launchWithBlockedCopy(Fixture fixture, Status answer, StringWriter errors, Path output,
		Path runRoot) throws IOException
	{
		fixture.launcher().register();
		fixture.launcher().status = answer;
		Path blocked = blockedFile(runRoot);
		Files.createDirectories(blocked.getParent());
		Files.writeString(blocked, "protected");
		return fixture.launch(errors, "--project", fixture.project().toString(), "--model", "m", "--effort", "e",
			"--input", fixture.records().toString(), "--output", output.toString(), "--keep-run", "--run-root",
			runRoot.toString());
	}

	/**
	 * Returns the file of the sender's copy of the kept run that the copy-failure tests block.
	 *
	 * @param runRoot the run root that receives the kept run
	 * @return the file
	 */
	private static Path blockedFile(Path runRoot)
	{
		return runRoot.resolve("claude").resolve(FakeLauncher.RUN_ID).resolve("artifacts/result.txt");
	}

	/**
	 * Verifies that when the sender cannot write one file of a completed session's kept run, the child's records and
	 * its receipt still reach the caller, and the launch then reports that the kept run is incomplete, naming the
	 * file and the kept run, and exits with status 1.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void completedRequestReportsFailedCopy() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = Fixture.create(cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(),
				"client-")).toRealPath());
			Path output = fixture.root().resolve("out/transcript.jsonl");
			Path runRoot = fixture.root().resolve("runs");
			StringWriter errors = new StringWriter();

			int status = launchWithBlockedCopy(fixture, Status.COMPLETED, errors, output, runRoot);

			requireThat(status, "status").isEqualTo(1);
			requireThat(Files.readString(output, UTF_8), "output").
				contains("reply 1").
				contains("\"type\":\"completed\"");
			requireThat(errors.toString(), "errors").
				contains(blockedFile(runRoot).toString()).
				contains("The kept run at " + runRoot.resolve("claude").resolve(FakeLauncher.RUN_ID) +
					" is incomplete");
		}
	}

	/**
	 * Verifies that when the sender cannot write one file of a refused session's kept run, the launch exits with
	 * status 2 and prints the refusal's message before the message that the kept run is incomplete.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void refusalComesBeforeFailedCopyMessage() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = Fixture.create(cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(),
				"client-")).toRealPath());
			Path output = fixture.root().resolve("out/transcript.jsonl");
			Path runRoot = fixture.root().resolve("runs");
			StringWriter errors = new StringWriter();

			int status = launchWithBlockedCopy(fixture, Status.REFUSED, errors, output, runRoot);

			requireThat(status, "status").isEqualTo(2);
			String message = errors.toString();
			int refusal = message.indexOf("the child's refused reason");
			int incomplete = message.indexOf("is incomplete");
			requireThat(refusal, "refusal").isNotNegative();
			requireThat(incomplete, "incomplete").isGreaterThan(refusal);
			requireThat(message, "errors").contains(blockedFile(runRoot).toString());
		}
	}

	/**
	 * Returns each status that does not complete, with the exit status that the launch returns for it.
	 *
	 * @return the status and the exit status
	 */
	@DataProvider
	public Object[][] incomplete()
	{
		return new Object[][]{{Status.CAPACITY, 75}, {Status.REFUSED, 2}, {Status.FAILED, 1}};
	}

	/**
	 * Verifies that a request that does not complete exits with its status's exit status and prints the launcher's
	 * reason.
	 *
	 * @param answer     how the launcher answers
	 * @param exitStatus the expected exit status
	 * @throws IOException if the fixture cannot be written
	 */
	@Test(dataProvider = "incomplete")
	public void incompleteRequestReportsReason(Status answer, int exitStatus) throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = Fixture.create(cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(),
				"client-")));
			fixture.launcher().register();
			fixture.launcher().status = answer;
			StringWriter errors = new StringWriter();

			int status = fixture.launch(errors, "--project", fixture.project().toString(), "--model", "m",
				"--effort", "e", "--input", fixture.records().toString());

			requireThat(status, "status").isEqualTo(exitStatus);
			requireThat(errors.toString(), "errors").contains("the child's " + answer.value() + " reason");
		}
	}

	/**
	 * Verifies that when the accepting launcher's heartbeat stops, the launch gives up after the heartbeat timeout
	 * with status 1 and the stopped-launcher message, ending its holder.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void staleHeartbeatEndsRequest() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories();
			FeedReader input = new FeedReader())
		{
			Fixture fixture = Fixture.create(cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(),
				"client-")));
			fixture.launcher().register();
			// The launcher accepts the request, then stops before it answers
			fixture.launcher().onStep = launcher ->
			{
				if (launcher.id != null)
					launcher.alive = false;
			};
			input.send("{\"type\":\"turn\",\"prompt\":\"task\"}");
			StringWriter errors = new StringWriter();

			int status = fixture.client().launch("claude", new String[]{"--project", fixture.project().toString(),
					"--model", "m", "--effort", "e"}, fixture.root(), HarnessTemporaryRoot.of(fixture.root()), input,
				new ByteArrayOutputStream(), new PrintWriter(errors, true));

			requireThat(status, "status").isEqualTo(1);
			requireThat(errors.toString(), "errors").contains(SessionLauncherChannel.stoppedLauncherMessage());
			requireThat(Duration.between(START, fixture.clock().instant()), "waited").
				isGreaterThan(SessionLauncherChannel.HEARTBEAT_TIMEOUT);
			requireThat(fixture.launcher().requestHolder().closed, "closed").isTrue();
		}
	}

	/**
	 * Verifies that a launch that names {@code --artifact} is refused with status 2 before anything is published.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void refusesArtifact() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = Fixture.create(cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(),
				"client-")));
			fixture.launcher().register();
			StringWriter errors = new StringWriter();

			int status = fixture.launch(errors, "--project", fixture.project().toString(), "--model", "m",
				"--effort", "e", "--artifact", fixture.root().toString());

			requireThat(status, "status").isEqualTo(2);
			requireThat(errors.toString(), "errors").contains("--artifact");
			requireThat(fixture.inbox(), "inbox").isEmpty();
			requireThat(fixture.launcher().holders, "holders").isEmpty();
		}
	}

	/**
	 * Verifies that native Windows, which has no session launcher, and a launch that a session launcher started run
	 * in place, while any other launch becomes a request.
	 */
	@Test
	public void servesOutsideWindowsAndLauncherChildren()
	{
		String[] launch = {"--model", "m", "--effort", "e"};
		requireThat(SessionLauncherClient.serves(Platform.LINUX_X64, launch), "linux").isTrue();
		requireThat(SessionLauncherClient.serves(Platform.MACOS_ARM64, launch), "macos").isTrue();
		requireThat(SessionLauncherClient.serves(Platform.WINDOWS_X64, launch), "windows").isFalse();
		requireThat(SessionLauncherClient.serves(Platform.LINUX_X64, new String[]{"--model", "m", "--effort", "e",
			SessionLauncherClient.POLICY_BOUND_OPTION, "policy.json"}), "launcherChild").isFalse();
	}

	/**
	 * A writer that answers each reply that reaches the optimizer: a follow-up turn after the first reply, and the end of
	 * the session after the second.
	 */
	private static final class ReplyWriter extends Writer
	{
		/**
		 * The input that receives the answers.
		 */
		private final FeedReader input;
		/**
		 * The replies received, in order.
		 */
		final List<String> replies = new ArrayList<>();

		/**
		 * Creates a writer.
		 *
		 * @param input the input that receives the answers
		 */
		ReplyWriter(FeedReader input)
		{
			this.input = input;
		}

		@Override
		public void write(char[] buffer, int offset, int length)
		{
			String text = new String(buffer, offset, length);
			if (!text.contains("turn_completed"))
				return;
			replies.add(text);
			if (replies.size() == 1)
				input.send("{\"type\":\"turn\",\"prompt\":\"follow-up\"}");
			else
			{
				input.send("{\"type\":\"complete\"}");
				input.end();
			}
		}

		@Override
		public void flush()
		{
		}

		@Override
		public void close()
		{
		}
	}

	/**
	 * A reader whose lines a test supplies while the reader is read, as the optimizer supplies its turns.
	 */
	private static final class FeedReader extends Reader
	{
		/**
		 * The supplied text, one entry per line, with an empty entry at the end.
		 */
		private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
		/**
		 * The rest of the line being read.
		 */
		private String current = "";
		/**
		 * Indicates whether the input ended.
		 */
		private boolean ended;

		/**
		 * Supplies a line.
		 *
		 * @param line the line, without its terminator
		 */
		void send(String line)
		{
			lines.add(line + "\n");
		}

		/**
		 * Ends the input.
		 */
		void end()
		{
			lines.add("");
		}

		@Override
		public int read(char[] buffer, int offset, int length) throws IOException
		{
			if (ended)
				return -1;
			try
			{
				while (current.isEmpty())
				{
					current = lines.take();
					if (current.isEmpty())
					{
						ended = true;
						return -1;
					}
				}
			}
			catch (InterruptedException _)
			{
				Thread.currentThread().interrupt();
				throw new InterruptedIOException("Interrupted while waiting for the next line");
			}
			int count = Math.min(length, current.length());
			current.getChars(0, count, buffer, offset);
			current = current.substring(count);
			return count;
		}

		@Override
		public void close()
		{
		}
	}

	/**
	 * Verifies that the optimizer's bridge streams its turns one at a time, each under its own holder that ends once
	 * the launcher consumed the turn, sends its follow-up turn only after the reply to the one before, holds the
	 * child's capacity slot itself, and carries the candidate CAT artifact as the first plugin to install as CAT.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void bridgeStreamsTurnsAfterReplies() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories();
			FeedReader input = new FeedReader();
			ReplyWriter output = new ReplyWriter(input))
		{
			Fixture fixture = Fixture.create(cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(),
				"client-")).toRealPath());
			fixture.launcher().register();
			Path candidate = Files.createDirectories(fixture.root().resolve("candidate/.claude-plugin")).getParent();
			Files.writeString(candidate.resolve(".claude-plugin/plugin.json"), "{}");
			Path own = Files.createDirectory(fixture.root().resolve("own"));
			Path runRoot = fixture.root().resolve("runs");
			List<String> repliesAtConsume = new ArrayList<>();
			fixture.launcher().onConsume = _ -> repliesAtConsume.add(String.valueOf(output.replies.size()));
			input.send("{\"type\":\"turn\",\"prompt\":\"first\"}");
			Bridge bridge = fixture.client().bridge("claude", own);

			bridge.serve(List.of("--artifact", candidate.toString(), "--project", fixture.project().toString(),
				"--run-root", runRoot.toString(), "--keep-run", "--session-transcripts", "--model", "m", "--effort", "e",
				"--install-cat"), input, output);

			FakeLauncher launcher = fixture.launcher();
			requireThat(launcher.consumed, "consumed").isEqualTo(List.of("{\"type\":\"turn\",\"prompt\":\"first\"}",
				"{\"type\":\"turn\",\"prompt\":\"follow-up\"}", "{\"type\":\"complete\"}"));
			// The follow-up turn reached the launcher only after the first reply reached the optimizer
			requireThat(repliesAtConsume, "repliesAtConsume").isEqualTo(List.of("0", "1", "2"));
			List<FakeHolder> turnHolders = launcher.holders.stream().
				filter(holder -> !holder.arguments.get(4).equals("0")).toList();
			requireThat(turnHolders.stream().map(holder -> holder.arguments.get(4)).toList(), "turnHolders").
				isEqualTo(List.of("1", "2", "3"));
			requireThat(turnHolders.stream().allMatch(holder -> holder.closed), "turnHoldersClosed").isTrue();
			requireThat(launcher.request.capacityHeld(), "capacityHeld").isTrue();
			requireThat(launcher.request.installCat(), "installCat").isTrue();
			requireThat(launcher.request.plugins().size(), "plugins").isEqualTo(1);
			requireThat(Files.exists(launcher.request.plugins().getFirst().resolve(".claude-plugin/plugin.json")),
				"candidatePlugin").isTrue();
			requireThat(Files.isDirectory(runRoot.resolve("claude").resolve(FakeLauncher.RUN_ID).resolve("artifacts")),
				"artifacts").isTrue();
		}
	}

	/**
	 * Verifies that an interrupt of the thread that serves the optimizer's bridge ends the request's holder, which
	 * makes the launcher stop the child.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void bridgeInterruptEndsHolder() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories();
			FeedReader input = new FeedReader())
		{
			Fixture fixture = Fixture.create(cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(),
				"client-")));
			fixture.launcher().register();
			// The optimizer interrupts the session once the launcher has consumed its first turn
			fixture.launcher().onStep = launcher ->
			{
				if (!launcher.consumed.isEmpty())
					Thread.currentThread().interrupt();
			};
			input.send("{\"type\":\"turn\",\"prompt\":\"first\"}");
			Bridge bridge = fixture.client().bridge("claude", fixture.root());
			boolean interrupted;
			try
			{
				bridge.serve(List.of("--project", fixture.project().toString(), "--run-root",
					fixture.root().resolve("runs").toString(), "--keep-run", "--model", "m", "--effort", "e"), input,
					new StringWriter());
				interrupted = false;
			}
			catch (InterruptedIOException _)
			{
				interrupted = Thread.interrupted();
			}

			requireThat(interrupted, "interrupted").isTrue();
			requireThat(fixture.launcher().requestHolder().closed, "closed").isTrue();
			requireThat(fixture.launcher().holders.stream().allMatch(holder -> holder.closed), "allClosed").isTrue();
		}
	}

	/**
	 * Verifies that the optimizer's bridge signals a refusal when no launcher serves the session, so that the optimizer
	 * does not retry a session that is refused again until the user acts, and that the refusal carries the launcher's
	 * message.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void bridgeWithoutLauncherIsRefused() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = Fixture.create(cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(),
				"client-")));
			Files.createDirectories(fixture.channel().launchers());

			String refusal = bridgeFailure(fixture, LaunchRefusedException.class);

			requireThat(refusal, "refusal").contains(SessionLauncherChannel.noLauncherMessage(PRODUCT));
			requireThat(fixture.inbox(), "inbox").isEmpty();
			requireThat(fixture.client().refusal(), "noSpendRefusal").
				isEqualTo(Optional.of(SessionLauncherChannel.noLauncherMessage(PRODUCT)));
		}
	}

	/**
	 * Verifies that the optimizer's bridge signals a refusal when the launcher refuses the session, carrying the
	 * launcher's reason, while the no-spend check finds the launcher that serves the session.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void bridgeRefusedByLauncherIsRefused() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = Fixture.create(cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(),
				"client-")));
			fixture.launcher().register();
			fixture.launcher().status = Status.REFUSED;

			String refusal = bridgeFailure(fixture, LaunchRefusedException.class);

			requireThat(refusal, "refusal").contains("the child's " + Status.REFUSED.value() + " reason");
			requireThat(fixture.client().refusal(), "noSpendRefusal").isEmpty();
		}
	}

	/**
	 * Returns each status that ends a session for a reason that a later attempt may not meet.
	 *
	 * @return the status
	 */
	@DataProvider
	public Object[][] retryable()
	{
		return new Object[][]{{Status.CAPACITY}, {Status.FAILED}};
	}

	/**
	 * Verifies that a session that the child fails, or that finds no capacity, is not signalled as a refusal, so the
	 * optimizer handles it as before.
	 *
	 * @param answer how the launcher answers
	 * @throws IOException if the fixture cannot be written
	 */
	@Test(dataProvider = "retryable")
	public void bridgeFailureIsNotRefusal(Status answer) throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = Fixture.create(cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(),
				"client-")));
			fixture.launcher().register();
			fixture.launcher().status = answer;

			String failure = bridgeFailure(fixture, IOException.class);

			requireThat(failure, "failure").contains("the child's " + answer.value() + " reason");
		}
	}

	/**
	 * Serves one turn through the fixture's bridge and returns the message of the exception that ends it.
	 *
	 * @param fixture  the fixture
	 * @param expected the exact type of exception that the bridge must throw
	 * @return the exception's message
	 * @throws IOException if the fixture cannot be written
	 */
	private static String bridgeFailure(Fixture fixture, Class<? extends IOException> expected) throws IOException
	{
		Bridge bridge = fixture.client().bridge("claude", fixture.root());
		try (FeedReader input = new FeedReader())
		{
			input.send("{\"type\":\"turn\",\"prompt\":\"first\"}");
			input.send("{\"type\":\"complete\"}");
			bridge.serve(List.of("--project", fixture.project().toString(), "--run-root",
				fixture.root().resolve("runs").toString(), "--keep-run", "--model", "m", "--effort", "e"), input,
				new StringWriter());
		}
		catch (IOException e)
		{
			requireThat(e.getClass().getName(), "exception").isEqualTo(expected.getName());
			return e.getMessage();
		}
		throw new AssertionError("The bridge completed");
	}
}
