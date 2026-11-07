package com.holdmyspot.cat.common.runner.launcher.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static org.testng.Assert.expectThrows;

import com.holdmyspot.cat.common.FileLocks;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.ManualClock;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.runner.RequestArchive;
import com.holdmyspot.cat.common.runner.RequestLimits;
import com.holdmyspot.cat.common.runner.SessionLauncherChannel;
import com.holdmyspot.cat.common.runner.SessionLauncherChannel.Request;
import com.holdmyspot.cat.common.runner.SessionLauncherChannel.Response;
import com.holdmyspot.cat.common.runner.SessionLauncherChannel.Status;
import com.holdmyspot.cat.common.runner.launcher.AcceptedRequest;
import com.holdmyspot.cat.common.runner.launcher.ChildBound;
import com.holdmyspot.cat.common.runner.launcher.ChildNeedsSource;
import com.holdmyspot.cat.common.runner.launcher.ProcessIdentity;
import com.holdmyspot.cat.common.runner.launcher.SessionLauncher;
import com.holdmyspot.cat.common.runner.policy.ChildNeeds;
import com.holdmyspot.cat.common.runner.policy.ExtraPermissions;
import com.holdmyspot.cat.common.runner.policy.HostAccess;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import com.holdmyspot.cat.common.runner.policy.SessionRefusedException;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.testng.SkipException;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests the session launcher: which requests it serves, how it runs and stops their children, when it ends, and what
 * it cleans up. Its process table, clock and child runners are fakes.
 */
public final class SessionLauncherTest
{
	private static final String TURN = "{\"type\":\"turn\",\"prompt\":\"hello\"}";
	private static final String COMPLETE = "{\"type\":\"complete\"}";
	private static final String HARNESS_NAME = "Harness A";
	private static final String MODEL_HOST = "model.example";
	private final JsonMapper json = JsonMapper.builder().build();

	/**
	 * Creates the test.
	 */
	public SessionLauncherTest()
	{
	}

	/**
	 * Verifies that a request whose holder runs in the served harness's process tree is served: the launcher accepts
	 * it, starts the requested harness's runner bounded by the parent's policy, feeds it the request's records, and
	 * answers with the runner's transcript, receipt and kept run.
	 *
	 * @throws IOException          if fixture I/O fails
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test
	public void servesHolderInItsHarnessTree() throws IOException, InterruptedException
	{
		// Requirement: R-I11
		try (Fixture fixture = new Fixture();
			SessionLauncher launcher = fixture.launcher(fixture.harness))
		{
			requireThat(launcher.start(), "start").isTrue();
			String id = fixture.publish(fixture.request(false));
			fixture.hold(id, 0, fixture.requestFile(id), fixture.sandbox);

			requireThat(launcher.poll(), "poll").isTrue();
			Path directory = fixture.channel.requestDirectory(id);
			requireThat(directory.resolve(SessionLauncherChannel.ACCEPTED_FILE), "accepted").isRegularFile();
			requireThat(Files.exists(fixture.requestFile(id), LinkOption.NOFOLLOW_LINKS), "inboxFile").isFalse();
			FakeRunner runner = fixture.starter.awaitRunner();
			requireThat(runner.awaitRecords(2), "records").isEqualTo(List.of(TURN, COMPLETE));
			fixture.executor.awaitIdle();

			requireThat(runner.harness(), "harness").isEqualTo("claude");
			JsonNode bound = json.readTree(Files.readString(Path.of(runner.option("--policy-bound").orElseThrow())));
			requireThat(bound.path("write_roots").get(0).asString(), "writeRoot").
				isEqualTo(directory.resolve(SessionLauncherChannel.RUN_DIRECTORY).toString());
			requireThat(bound.path("hosts").toString(), "hosts").isEqualTo("[\"" + MODEL_HOST + "\"]");
			requireThat(runner.option(ExtraPermissions.OPTION), "extraPermissions").isEqualTo(Optional.empty());
			Response response = fixture.response(id);
			requireThat(response.status(), "status").isEqualTo(Status.COMPLETED);
			requireThat(response.exitStatus(), "exitStatus").isEqualTo(Optional.of(0));
			requireThat(Files.readAllLines(response.transcript().orElseThrow()), "transcript").
				isEqualTo(runner.output());
			requireThat(response.receipt().orElseThrow(), "receipt").isRegularFile();
			requireThat(response.childRunId(), "childRunId").isEqualTo(Optional.of(FakeRunner.RUN_ID));
			requireThat(response.run(), "run").isEqualTo(Optional.of(directory.
				resolve(SessionLauncherChannel.RUN_DIRECTORY).resolve("claude").resolve(FakeRunner.RUN_ID)));
		}
	}

	/**
	 * Verifies that the launcher's log records each launch that it accepts with the request's identifier, the
	 * requested harness and the time of the acceptance.
	 *
	 * @throws IOException          if fixture I/O fails
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test
	public void logsAcceptedLaunch() throws IOException, InterruptedException
	{
		try (Fixture fixture = new Fixture();
			SessionLauncher launcher = fixture.launcher(fixture.harness))
		{
			requireThat(launcher.start(), "start").isTrue();
			String id = fixture.publish(fixture.request(false));
			fixture.hold(id, 0, fixture.requestFile(id), fixture.sandbox);
			Instant accepted = fixture.clock.instant();

			requireThat(launcher.poll(), "poll").isTrue();
			fixture.starter.awaitRunner();
			fixture.executor.awaitIdle();

			List<String> log = Files.readAllLines(fixture.log(fixture.harness));
			List<String> acceptances = log.stream().filter(line -> line.contains("claude")).toList();
			requireThat(acceptances, "acceptances").size().isEqualTo(1);
			requireThat(acceptances.get(0), "line").contains(accepted.toString()).contains(id);
		}
	}

	/**
	 * Supplies the ways that a launcher answers a request.
	 *
	 * @return the case names
	 */
	@DataProvider
	public Object[][] answers()
	{
		return new Object[][]{{"completed"}, {"refused"}, {"stopped"}, {"refused with a multi-line reason"}};
	}

	/**
	 * Verifies that the launcher's log records each answer that it writes, on one line with the request's identifier,
	 * the status, the reason and the time, so that the answer stays observable after the sender and the request's
	 * folder are gone.
	 *
	 * @param how the case
	 * @throws IOException          if fixture I/O fails
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(dataProvider = "answers")
	public void logsEachAnswer(String how) throws IOException, InterruptedException
	{
		try (Fixture fixture = new Fixture();
			SessionLauncher launcher = fixture.launcher(fixture.harness))
		{
			requireThat(launcher.start(), "start").isTrue();
			String id = fixture.publish(fixture.request(how.equals("stopped")));
			ProcessIdentity holder = fixture.hold(id, 0, fixture.requestFile(id), fixture.sandbox);
			switch (how)
			{
				case "completed" ->
				{
					requireThat(launcher.poll(), "poll").isTrue();
					fixture.starter.awaitRunner().awaitRecords(2);
				}
				case "stopped" ->
				{
					requireThat(launcher.poll(), "acceptingPoll").isTrue();
					fixture.starter.awaitRunner();
					fixture.processes.end(holder);
					requireThat(launcher.poll(), "cancellingPoll").isTrue();
				}
				default ->
				{
					String reason = "the settings file cannot be parsed";
					if (how.equals("refused with a multi-line reason"))
						reason += ":\nline 3";
					fixture.policy.refuse(new PolicyRefusal(reason, "Fix the file, then start a new session"));
					requireThat(launcher.poll(), "poll").isTrue();
				}
			}
			fixture.executor.awaitIdle();

			Response response = fixture.response(id);
			List<String> log = Files.readAllLines(fixture.log(fixture.harness));
			List<String> answers = log.stream().
				filter(line -> line.contains(id) && line.contains(response.status().value())).toList();
			requireThat(answers, "answers").size().isEqualTo(1);
			requireThat(answers.get(0), "answer").contains(fixture.clock.instant().toString());
			String oneLine = response.reason().replaceAll("\\R", " ");
			if (!response.reason().isEmpty())
				requireThat(answers.get(0), "answer").contains(oneLine);
		}
	}

	/**
	 * Verifies that the launcher's log outlives the launcher: after the harness process ends and the launcher deletes
	 * its own directory and its request's directory, the log is still readable and holds the line that records the
	 * {@code stopped} answer to the request, which the requester that already ended could no longer read from the
	 * request's directory.
	 *
	 * @throws IOException          if fixture I/O fails
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test
	public void logOutlivesLauncher() throws IOException, InterruptedException
	{
		try (Fixture fixture = new Fixture();
			SessionLauncher launcher = fixture.launcher(fixture.harness))
		{
			requireThat(launcher.start(), "start").isTrue();
			String id = fixture.publish(fixture.request(true));
			ProcessIdentity holder = fixture.hold(id, 0, fixture.requestFile(id), fixture.sandbox);
			requireThat(launcher.poll(), "acceptingPoll").isTrue();
			fixture.starter.awaitRunner();

			fixture.processes.end(fixture.harness);
			fixture.processes.end(holder);
			requireThat(launcher.poll(), "endingPoll").isFalse();

			requireThat(Files.exists(launcher.directory()), "launcherDirectory").isFalse();
			requireThat(Files.exists(fixture.channel.requestDirectory(id)), "requestDirectory").isFalse();
			Path log = fixture.channel.logFile("harness-a", fixture.harness.pid(), fixture.harness.start());
			requireThat(log, "log").isRegularFile();
			requireThat(log.startsWith(fixture.channel.stateRoot()), "insideStateRoot").isTrue();
			List<String> answers = Files.readAllLines(log).stream().
				filter(line -> line.contains("Answered request " + id + ": stopped")).toList();
			requireThat(answers, "answers").size().isEqualTo(1);
		}
	}

	/**
	 * Verifies that a launcher's start deletes the logs of ended launchers that were last written more than the
	 * retention ago, so that logs do not accumulate, and keeps a log that was written within the retention, a log whose
	 * launcher still has its directory, and its own log.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void startDeletesOldLogsOfEndedLaunchers() throws IOException
	{
		try (Fixture fixture = new Fixture();
			SessionLauncher launcher = fixture.launcher(fixture.harness))
		{
			Duration old = SessionLauncherChannel.LOG_RETENTION.plusMinutes(1);
			Duration recent = SessionLauncherChannel.LOG_RETENTION.minusMinutes(1);
			ProcessIdentity endedOld = fixture.processes.start(901, Optional.empty(), List.of("harness-a"));
			ProcessIdentity endedRecent = fixture.processes.start(902, Optional.empty(), List.of("harness-a"));
			ProcessIdentity running = fixture.processes.start(903, Optional.empty(), List.of("harness-a"));
			Path oldLog = fixture.logAged(endedOld, old);
			Path recentLog = fixture.logAged(endedRecent, recent);
			Path runningLog = fixture.logAged(running, old);
			Files.createDirectories(fixture.channel.launcherDirectory("harness-a", running.pid(), running.start()));
			Path ownLog = fixture.logAged(fixture.harness, old);

			requireThat(launcher.start(), "start").isTrue();

			requireThat(Files.exists(oldLog), "oldLog").isFalse();
			requireThat(recentLog, "recentLog").isRegularFile();
			requireThat(runningLog, "runningLog").isRegularFile();
			requireThat(ownLog, "ownLog").isRegularFile();
		}
	}

	/**
	 * Supplies the requests that a launcher must not serve: one whose holder runs in another harness's process tree,
	 * one without a holder, and one whose holder names another file's digest.
	 *
	 * @return the case names
	 */
	@DataProvider
	public Object[][] unprovenRequests()
	{
		return new Object[][]{{"another harness's tree"}, {"no holder"}, {"digest mismatch"}};
	}

	/**
	 * Verifies that the launcher starts nothing for a request that its sender cannot prove came from the served
	 * harness's process tree with exactly the published file, and leaves the file for the launcher that it belongs to.
	 *
	 * @param proof the case
	 * @throws IOException if fixture I/O fails
	 */
	@Test(dataProvider = "unprovenRequests")
	public void startsNothingWithoutProof(String proof) throws IOException
	{
		// Requirement: R-I11
		try (Fixture fixture = new Fixture();
			SessionLauncher launcher = fixture.launcher(fixture.harness))
		{
			requireThat(launcher.start(), "start").isTrue();
			String id = fixture.publish(fixture.request(false));
			switch (proof)
			{
				case "another harness's tree" ->
				{
					ProcessIdentity other = fixture.processes.start(900, Optional.empty(), List.of("harness-b"));
					fixture.hold(id, 0, fixture.requestFile(id), other);
				}
				case "digest mismatch" ->
				{
					Path forged = Files.writeString(fixture.home.resolve("forged"), "other bytes");
					fixture.hold(id, 0, forged, fixture.sandbox);
				}
				default ->
				{
				}
			}

			requireThat(launcher.poll(), "poll").isTrue();
			requireThat(launcher.poll(), "secondPoll").isTrue();
			requireThat(fixture.starter.runners(), "runners").isEmpty();
			requireThat(Files.exists(fixture.channel.requestDirectory(id)), "requestDirectory").isFalse();
			requireThat(fixture.requestFile(id), "inboxFile").isRegularFile();
		}
	}

	/**
	 * Verifies that when one harness runs inside another, a request from the inner harness's tree is served by the
	 * inner harness's launcher alone, even though the holder also descends from the outer harness.
	 *
	 * @throws IOException          if fixture I/O fails
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test
	public void nearestHarnessClaims() throws IOException, InterruptedException
	{
		// Requirement: R-I11
		try (Fixture fixture = new Fixture())
		{
			ProcessIdentity inner = fixture.processes.start(300, Optional.of(fixture.sandbox), List.of("harness-a"));
			ProcessIdentity innerSandbox = fixture.processes.start(350, Optional.of(inner), List.of("sandbox"));
			try (SessionLauncher outerLauncher = fixture.launcher(fixture.harness);
				SessionLauncher innerLauncher = fixture.launcher(inner))
			{
				requireThat(outerLauncher.start(), "outerStart").isTrue();
				requireThat(innerLauncher.start(), "innerStart").isTrue();
				String id = fixture.publish(fixture.request(false));
				fixture.hold(id, 0, fixture.requestFile(id), innerSandbox);

				requireThat(outerLauncher.poll(), "outerPoll").isTrue();
				requireThat(Files.exists(fixture.channel.requestDirectory(id)), "claimedByOuter").isFalse();
				requireThat(innerLauncher.poll(), "innerPoll").isTrue();
				requireThat(fixture.channel.requestDirectory(id).resolve(SessionLauncherChannel.ACCEPTED_FILE),
					"claimedByInner").isRegularFile();
				fixture.starter.awaitRunner().awaitRecords(2);
				fixture.executor.awaitIdle();
				requireThat(fixture.response(id).status(), "status").isEqualTo(Status.COMPLETED);
			}
		}
	}

	/**
	 * Verifies that streamed turns reach the runner in turn order: a turn that arrives before its predecessor waits,
	 * and is fed right after it.
	 *
	 * @throws IOException          if fixture I/O fails
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test
	public void feedsStreamedTurnsInOrder() throws IOException, InterruptedException
	{
		try (Fixture fixture = new Fixture();
			SessionLauncher launcher = fixture.launcher(fixture.harness))
		{
			requireThat(launcher.start(), "start").isTrue();
			String id = fixture.publish(fixture.request(true));
			fixture.hold(id, 0, fixture.requestFile(id), fixture.sandbox);
			requireThat(launcher.poll(), "acceptingPoll").isTrue();
			FakeRunner runner = fixture.starter.awaitRunner();
			Path turns = fixture.channel.requestDirectory(id).resolve(SessionLauncherChannel.TURNS_FILE);

			Path second = fixture.publishTurn(id, 2, COMPLETE);
			fixture.hold(id, 2, second, fixture.sandbox);
			requireThat(launcher.poll(), "earlyPoll").isTrue();
			requireThat(Files.exists(turns), "consumedEarly").isFalse();
			requireThat(second, "waitingTurn").isRegularFile();
			Path first = fixture.publishTurn(id, 1, TURN);
			fixture.hold(id, 1, first, fixture.sandbox);
			requireThat(launcher.poll(), "orderedPoll").isTrue();

			requireThat(Files.readString(turns), "consumed").isEqualTo("1\n2\n");
			requireThat(runner.awaitRecords(2), "records").isEqualTo(List.of(TURN, COMPLETE));
			fixture.executor.awaitIdle();
			requireThat(fixture.response(id).status(), "status").isEqualTo(Status.COMPLETED);
		}
	}

	/**
	 * Verifies that a streamed turn without a holder is ignored: the launcher neither feeds nor deletes it.
	 *
	 * @throws IOException          if fixture I/O fails
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test
	public void ignoresTurnWithoutHolder() throws IOException, InterruptedException
	{
		try (Fixture fixture = new Fixture();
			SessionLauncher launcher = fixture.launcher(fixture.harness))
		{
			requireThat(launcher.start(), "start").isTrue();
			String id = fixture.publish(fixture.request(true));
			fixture.hold(id, 0, fixture.requestFile(id), fixture.sandbox);
			requireThat(launcher.poll(), "acceptingPoll").isTrue();
			FakeRunner runner = fixture.starter.awaitRunner();

			Path turn = fixture.publishTurn(id, 1, COMPLETE);
			requireThat(launcher.poll(), "poll").isTrue();

			requireThat(Files.exists(fixture.channel.requestDirectory(id).resolve(SessionLauncherChannel.TURNS_FILE)),
				"consumed").isFalse();
			requireThat(turn, "turn").isRegularFile();
			requireThat(runner.records(), "records").isEmpty();
		}
	}

	/**
	 * Verifies that when a request's holder ends before the request completes, the launcher stops the child's runner
	 * and its descendants, although the runner keeps running after its standard input closes, and answers
	 * {@code stopped}. Once answered and abandoned, the request's directory is deleted.
	 *
	 * @throws IOException          if fixture I/O fails
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test
	public void holderEndStopsChildTree() throws IOException, InterruptedException
	{
		try (Fixture fixture = new Fixture();
			SessionLauncher launcher = fixture.launcher(fixture.harness))
		{
			requireThat(launcher.start(), "start").isTrue();
			String id = fixture.publish(fixture.request(true));
			ProcessIdentity holder = fixture.hold(id, 0, fixture.requestFile(id), fixture.sandbox);
			requireThat(launcher.poll(), "acceptingPoll").isTrue();
			FakeRunner runner = fixture.starter.awaitRunner();

			fixture.processes.end(holder);
			requireThat(launcher.poll(), "cancellingPoll").isTrue();
			fixture.executor.awaitIdle();

			requireThat(runner.treeStopped(), "treeStopped").isTrue();
			requireThat(fixture.response(id).status(), "status").isEqualTo(Status.STOPPED);
			requireThat(launcher.poll(), "cleaningPoll").isTrue();
			requireThat(Files.exists(fixture.channel.requestDirectory(id)), "requestDirectory").isFalse();
		}
	}

	/**
	 * Supplies the events that end a launcher.
	 *
	 * @return the event names
	 */
	@DataProvider
	public Object[][] launcherEnds()
	{
		return new Object[][]{{"harness exits"}, {"pid reused"}, {"stop file"}};
	}

	/**
	 * Verifies that the launcher ends when its harness process ends, when another process reuses the harness's
	 * identifier, or when its stop file appears, and that it then stops its children's process trees and deletes the
	 * directories of the requests whose holders have ended, the inbox files of its requests, and its own directory.
	 *
	 * @param event the event that ends the launcher
	 * @throws IOException          if fixture I/O fails
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(dataProvider = "launcherEnds")
	public void endsAndCleansUp(String event) throws IOException, InterruptedException
	{
		// Requirement: R-I11
		try (Fixture fixture = new Fixture();
			SessionLauncher launcher = fixture.launcher(fixture.harness))
		{
			requireThat(launcher.start(), "start").isTrue();
			String id = fixture.publish(fixture.request(true));
			ProcessIdentity holder = fixture.hold(id, 0, fixture.requestFile(id), fixture.sandbox);
			requireThat(launcher.poll(), "acceptingPoll").isTrue();
			FakeRunner runner = fixture.starter.awaitRunner();
			Path waitingTurn = fixture.publishTurn(id, 2, COMPLETE);
			fixture.hold(id, 2, waitingTurn, fixture.sandbox);
			requireThat(launcher.poll(), "waitingPoll").isTrue();

			switch (event)
			{
				case "harness exits" -> fixture.processes.end(fixture.harness);
				case "pid reused" -> fixture.processes.reuse(fixture.harness);
				default -> Files.writeString(launcher.directory().resolve(SessionLauncherChannel.STOP_FILE), "");
			}
			fixture.processes.end(holder);
			requireThat(launcher.poll(), "endingPoll").isFalse();

			requireThat(runner.treeStopped(), "treeStopped").isTrue();
			requireThat(Files.exists(fixture.channel.requestDirectory(id)), "requestDirectory").isFalse();
			requireThat(Files.exists(waitingTurn, LinkOption.NOFOLLOW_LINKS), "waitingTurn").isFalse();
			requireThat(Files.exists(launcher.directory()), "launcherDirectory").isFalse();
		}
	}

	/**
	 * Verifies that a launcher that ends while the process that holds a request still lives keeps the request's
	 * directory, with the response that says the launcher stopped the session because the requesting session ended,
	 * so that the sender reads why its session stopped; and that it deletes its own directory.
	 *
	 * @throws IOException          if fixture I/O fails
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test
	public void endKeepsResponseForLiveHolder() throws IOException, InterruptedException
	{
		try (Fixture fixture = new Fixture();
			SessionLauncher launcher = fixture.launcher(fixture.harness))
		{
			requireThat(launcher.start(), "start").isTrue();
			String id = fixture.publish(fixture.request(true));
			fixture.hold(id, 0, fixture.requestFile(id), fixture.sandbox);
			requireThat(launcher.poll(), "acceptingPoll").isTrue();
			FakeRunner runner = fixture.starter.awaitRunner();

			fixture.processes.end(fixture.harness);
			requireThat(launcher.poll(), "endingPoll").isFalse();

			requireThat(runner.treeStopped(), "treeStopped").isTrue();
			Response response = fixture.response(id);
			requireThat(response.status(), "status").isEqualTo(Status.STOPPED);
			requireThat(response.reason(), "reason").contains("requesting session ended");
			requireThat(Files.exists(launcher.directory()), "launcherDirectory").isFalse();
		}
	}

	/**
	 * Verifies that a serving launcher waits between polls through the pause that it is given, and returns once its
	 * harness process ends.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void servesUntilHarnessEnds() throws IOException
	{
		try (Fixture fixture = new Fixture();
			SessionLauncher launcher = fixture.launcher(fixture.harness))
		{
			requireThat(launcher.start(), "start").isTrue();
			AtomicInteger pauses = new AtomicInteger();

			launcher.serve(() ->
			{
				if (pauses.incrementAndGet() == 3)
					fixture.processes.end(fixture.harness);
			});

			requireThat(pauses.get(), "pauses").isEqualTo(3);
			requireThat(Files.exists(launcher.directory()), "launcherDirectory").isFalse();
		}
	}

	/**
	 * Verifies that a second launcher for the same harness process exits at start without touching anything, and
	 * that closing it leaves the first launcher serving.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void secondLauncherExits() throws IOException
	{
		try (Fixture fixture = new Fixture();
			SessionLauncher first = fixture.launcher(fixture.harness))
		{
			requireThat(first.start(), "firstStart").isTrue();
			Path crashed = fixture.crashedLauncher();

			try (SessionLauncher second = fixture.launcher(fixture.harness))
			{
				requireThat(second.start(), "secondStart").isFalse();
			}

			requireThat(crashed, "crashedLauncher").isDirectory();
			requireThat(first.directory().resolve(SessionLauncherChannel.HEARTBEAT_FILE), "heartbeat").isRegularFile();
			requireThat(first.poll(), "firstPoll").isTrue();
		}
	}

	/**
	 * Verifies the start-up sweep: it deletes the directory of a launcher whose harness has ended and whose lock is
	 * free, the requests that such a launcher accepted, and inbox files older than a minute that no live holder names,
	 * and keeps the rest.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void sweepsAbandonedState() throws IOException
	{
		try (Fixture fixture = new Fixture();
			SessionLauncher launcher = fixture.launcher(fixture.harness))
		{
			Path crashed = fixture.crashedLauncher();
			ProcessIdentity otherHarness = fixture.processes.start(900, Optional.empty(), List.of("harness-b"));
			Path other = Files.createDirectories(fixture.channel.launcherDirectory("harness-b", otherHarness.pid(),
				otherHarness.start()));
			String crashedRequest = fixture.acceptedBy(crashed);
			String otherRequest = fixture.acceptedBy(other);
			Path oldOrphan = fixture.inboxFile(SessionLauncherChannel.requestFileName(SessionLauncherChannel.newId()),
				Duration.ofSeconds(61));
			String heldId = SessionLauncherChannel.newId();
			Path oldHeld = fixture.inboxFile(SessionLauncherChannel.turnFileName(heldId, 1), Duration.ofSeconds(61));
			fixture.hold(heldId, 1, oldHeld, otherHarness);
			Path young = fixture.inboxFile(SessionLauncherChannel.requestFileName(SessionLauncherChannel.newId()),
				Duration.ofSeconds(10));
			Path oldTemporary = fixture.inboxFile(SessionLauncherChannel.TEMPORARY_PREFIX + "abandoned",
				Duration.ofSeconds(61));

			requireThat(launcher.start(), "start").isTrue();

			requireThat(Files.exists(crashed), "crashedLauncher").isFalse();
			requireThat(Files.exists(fixture.channel.requestDirectory(crashedRequest)), "crashedRequest").isFalse();
			requireThat(other, "liveHarnessLauncher").isDirectory();
			requireThat(fixture.channel.requestDirectory(otherRequest), "liveHarnessRequest").isDirectory();
			requireThat(Files.exists(oldOrphan), "oldOrphan").isFalse();
			requireThat(Files.exists(oldTemporary), "oldTemporary").isFalse();
			requireThat(oldHeld, "oldHeld").isRegularFile();
			requireThat(young, "young").isRegularFile();
		}
	}

	/**
	 * Verifies that an inbox entry that is a symbolic link is deleted without being read, even when a holder in the
	 * served tree names the digest of the link's target.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void deletesInboxLinksUnread() throws IOException
	{
		try (Fixture fixture = new Fixture();
			SessionLauncher launcher = fixture.launcher(fixture.harness))
		{
			requireThat(launcher.start(), "start").isTrue();
			String linkedId = SessionLauncherChannel.newId();
			Path target = fixture.home.resolve("target.tar");
			RequestArchive.packSession(json, target, fixture.request(false), fixture.nullDevice, RequestLimits.UNBOUNDED);
			byte[] targetBytes = Files.readAllBytes(target);
			Path link = Files.createSymbolicLink(fixture.requestFile(linkedId), target);
			fixture.hold(linkedId, 0, target, fixture.sandbox);

			requireThat(launcher.poll(), "poll").isTrue();

			requireThat(Files.exists(link, LinkOption.NOFOLLOW_LINKS), "link").isFalse();
			requireThat(Files.readAllBytes(target), "target").isEqualTo(targetBytes);
			requireThat(fixture.starter.runners(), "runners").isEmpty();
			requireThat(Files.exists(fixture.channel.requestDirectory(linkedId)), "requestDirectory").isFalse();
		}
	}

	/**
	 * Verifies that an inbox entry that is a folder rather than a regular file is deleted with its contents without
	 * being read.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void deletesInboxFoldersUnread() throws IOException
	{
		if (Platform.current().isWindows())
		{
			throw new SkipException("Native Windows opens no folder without following links, so the launcher " +
				"leaves an inbox folder, and CAT starts no session launcher there");
		}
		try (Fixture fixture = new Fixture();
			SessionLauncher launcher = fixture.launcher(fixture.harness))
		{
			requireThat(launcher.start(), "start").isTrue();
			String directoryId = SessionLauncherChannel.newId();
			Path directory = Files.createDirectory(fixture.requestFile(directoryId));
			Files.writeString(directory.resolve("inner"), "inner bytes");

			requireThat(launcher.poll(), "poll").isTrue();

			requireThat(Files.exists(directory, LinkOption.NOFOLLOW_LINKS), "directory").isFalse();
			requireThat(fixture.starter.runners(), "runners").isEmpty();
		}
	}

	/**
	 * Verifies that serving a request from acceptance to clean-up writes nothing below the inbox: every entry that
	 * other senders left there keeps its content and modification time, and the only change is the deletion of the
	 * served request's file.
	 *
	 * @throws IOException          if fixture I/O fails
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test
	public void neverWritesBelowInbox() throws IOException, InterruptedException
	{
		try (Fixture fixture = new Fixture();
			SessionLauncher launcher = fixture.launcher(fixture.harness))
		{
			fixture.inboxFile("notes.txt", Duration.ofSeconds(5));
			fixture.inboxFile(SessionLauncherChannel.TEMPORARY_PREFIX + "partial", Duration.ofSeconds(1));
			ProcessIdentity otherHarness = fixture.processes.start(900, Optional.empty(), List.of("harness-b"));
			String foreignId = fixture.publish(fixture.request(false));
			fixture.hold(foreignId, 0, fixture.requestFile(foreignId), otherHarness);
			requireThat(launcher.start(), "start").isTrue();
			Map<String, String> before = fixture.inboxState();

			String id = fixture.publish(fixture.request(false));
			ProcessIdentity holder = fixture.hold(id, 0, fixture.requestFile(id), fixture.sandbox);
			requireThat(launcher.poll(), "acceptingPoll").isTrue();
			fixture.starter.awaitRunner().awaitRecords(2);
			fixture.executor.awaitIdle();
			fixture.processes.end(holder);
			requireThat(launcher.poll(), "cleaningPoll").isTrue();

			requireThat(fixture.inboxState(), "inbox").isEqualTo(before);
			requireThat(Files.exists(fixture.channel.requestDirectory(id)), "requestDirectory").isFalse();
		}
	}

	/**
	 * Supplies the ways in which the parent's policy cannot be used: it cannot be read when the launcher starts, it
	 * cannot be read for the request, or it lets the parent write the launcher's state.
	 *
	 * @return the case names
	 */
	@DataProvider
	public Object[][] unusablePolicies()
	{
		return new Object[][]{{"at start"}, {"at request"}, {"state writable"}};
	}

	/**
	 * Verifies that a request is answered {@code refused}, with the reason and its fix, and starts nothing, when the
	 * parent's policy cannot be used.
	 *
	 * @param when the case
	 * @throws IOException          if fixture I/O fails
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(dataProvider = "unusablePolicies")
	public void refusesUnusablePolicy(String when) throws IOException, InterruptedException
	{
		// Requirement: R-I11
		try (Fixture fixture = new Fixture();
			SessionLauncher launcher = fixture.launcher(fixture.harness))
		{
			PolicyRefusal refusal = new PolicyRefusal("the settings file /p/settings.json cannot be parsed",
				"Fix the file, then start a new session");
			if (when.equals("at start"))
				fixture.policy.refuse(refusal);
			requireThat(launcher.start(), "start").isTrue();
			if (when.equals("at request"))
				fixture.policy.refuse(refusal);
			else if (when.equals("state writable"))
				fixture.policy.allow(fixture.policyWriting(fixture.home));
			String id = fixture.publish(fixture.request(false));
			fixture.hold(id, 0, fixture.requestFile(id), fixture.sandbox);

			requireThat(launcher.poll(), "poll").isTrue();
			fixture.executor.awaitIdle();

			Response response = fixture.response(id);
			requireThat(response.status(), "status").isEqualTo(Status.REFUSED);
			if (when.equals("state writable"))
			{
				requireThat(response.reason(), "reason").startsWith("CAT cannot start the session: it cannot read " +
					"this " + HARNESS_NAME + " session's sandbox policy safely").
					contains(fixture.channel.stateRoot().toString());
			}
			else
				requireThat(response.reason(), "reason").isEqualTo(refusal.messageFor(HARNESS_NAME));
			requireThat(fixture.starter.runners(), "runners").isEmpty();
		}
	}

	/**
	 * Verifies that a request's extra permissions reach the child's runner as a file of the same permissions, which the
	 * runner joins to the needs that it derives itself.
	 *
	 * @throws IOException          if fixture I/O fails
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test
	public void givesRunnerExtraPermissions() throws IOException, InterruptedException
	{
		// Requirement: R-I11
		try (Fixture fixture = new Fixture();
			SessionLauncher launcher = fixture.launcher(fixture.harness))
		{
			Path work = fixture.home.resolve("work");
			Path cache = Files.createDirectory(fixture.home.resolve("registry cache"));
			fixture.policy.allow(new ParentPolicy(true, List.of(work, cache), List.of(), List.of(), List.of(),
				HostAccess.listed(Set.of(MODEL_HOST, "registry.example")), false, List.of()));
			ExtraPermissions extra = new ExtraPermissions(List.of(cache), Set.of("registry.example"));
			requireThat(launcher.start(), "start").isTrue();
			String id = fixture.publish(fixture.request(false, extra));
			fixture.hold(id, 0, fixture.requestFile(id), fixture.sandbox);

			requireThat(launcher.poll(), "poll").isTrue();
			FakeRunner runner = fixture.starter.awaitRunner();
			runner.awaitRecords(2);
			fixture.executor.awaitIdle();

			requireThat(ExtraPermissions.read(json, Path.of(runner.option(ExtraPermissions.OPTION).orElseThrow())),
				"given").isEqualTo(extra);
			requireThat(fixture.response(id).status(), "status").isEqualTo(Status.COMPLETED);
		}
	}

	/**
	 * Verifies that the launcher answers a request with the bound that {@link ChildBound} gives for the parent's policy
	 * when the launcher started and the parent's policy when the request arrives: a request whose extra permissions
	 * ask for a write and a host that only the first allows, and a write and a host that only the second allows, is
	 * refused naming all four, and starts nothing.
	 *
	 * @throws IOException          if fixture I/O fails
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test
	public void boundsChildByStartAndRequestPolicies() throws IOException, InterruptedException
	{
		// Requirement: R-I11
		try (Fixture fixture = new Fixture();
			SessionLauncher launcher = fixture.launcher(fixture.harness))
		{
			Path work = fixture.home.resolve("work");
			Path startCache = Files.createDirectory(fixture.home.resolve("start cache"));
			Path requestCache = Files.createDirectory(fixture.home.resolve("request cache"));
			ParentPolicy atStart = new ParentPolicy(true, List.of(work, startCache), List.of(), List.of(), List.of(),
				HostAccess.listed(Set.of(MODEL_HOST, "start.example")), false, List.of());
			ParentPolicy atRequest = new ParentPolicy(true, List.of(work, requestCache), List.of(), List.of(),
				List.of(), HostAccess.listed(Set.of(MODEL_HOST, "request.example")), false, List.of());
			ExtraPermissions extra = new ExtraPermissions(List.of(startCache, requestCache),
				Set.of("start.example", "request.example"));
			fixture.policy.allow(atStart);
			requireThat(launcher.start(), "start").isTrue();
			fixture.policy.allow(atRequest);
			String id = fixture.publish(fixture.request(false, extra));
			fixture.hold(id, 0, fixture.requestFile(id), fixture.sandbox);

			requireThat(launcher.poll(), "poll").isTrue();
			fixture.executor.awaitIdle();

			FakeParentPolicySource replay = new FakeParentPolicySource(HARNESS_NAME, atStart);
			ChildBound snapshot = new ChildBound(replay, fixture.needs()).snapshot(fixture.channel.stateRoot());
			replay.allow(atRequest);
			Path runRoot = fixture.channel.requestDirectory(id).resolve(SessionLauncherChannel.RUN_DIRECTORY);
			SessionRefusedException expected = expectThrows(SessionRefusedException.class,
				() -> snapshot.bound("claude", runRoot, List.of(), extra, Optional.empty()));
			Response response = fixture.response(id);
			requireThat(response.status(), "status").isEqualTo(Status.REFUSED);
			requireThat(response.reason(), "reason").isEqualTo(expected.getMessage());
			requireThat(fixture.starter.runners(), "runners").isEmpty();
		}
	}

	/**
	 * Verifies that the launcher bounds a child by the sandbox policy of the thread that sends the request: a thread
	 * whose policy does not allow a write that the session's main thread allows has a request for that write refused,
	 * and nothing starts.
	 *
	 * @throws IOException          if fixture I/O fails
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test
	public void boundsChildByAskingThreadPolicy() throws IOException, InterruptedException
	{
		// Requirement: R-I11
		try (Fixture fixture = new Fixture();
			SessionLauncher launcher = fixture.launcher(fixture.harness))
		{
			Path work = fixture.home.resolve("work");
			Path cache = Files.createDirectory(fixture.home.resolve("registry cache"));
			fixture.policy.allow(new ParentPolicy(true, List.of(work, cache), List.of(), List.of(), List.of(),
				HostAccess.listed(Set.of(MODEL_HOST)), false, List.of()));
			fixture.policy.allowThread("thread-a", new ParentPolicy(true, List.of(work), List.of(), List.of(),
				List.of(), HostAccess.listed(Set.of(MODEL_HOST)), false, List.of()));
			ExtraPermissions extra = new ExtraPermissions(List.of(cache), Set.of());
			requireThat(launcher.start(), "start").isTrue();
			String id = fixture.publish(fixture.request(false, extra, Optional.of("thread-a")));
			fixture.hold(id, 0, fixture.requestFile(id), fixture.sandbox);

			requireThat(launcher.poll(), "poll").isTrue();
			fixture.executor.awaitIdle();

			Response response = fixture.response(id);
			requireThat(response.status(), "status").isEqualTo(Status.REFUSED);
			requireThat(response.reason(), "reason").contains(cache.toString());
			requireThat(fixture.starter.runners(), "runners").isEmpty();
		}
	}

	/**
	 * Supplies runner exit statuses and the request status that each one gives.
	 *
	 * @return the exit status and the request status
	 */
	@DataProvider
	public Object[][] exitStatuses()
	{
		return new Object[][]{{0, Status.COMPLETED}, {75, Status.CAPACITY}, {2, Status.REFUSED}, {1, Status.FAILED}};
	}

	/**
	 * Verifies that the runner's exit status decides how the request ended.
	 *
	 * @param exitStatus the runner's exit status
	 * @param expected   the request's status
	 * @throws IOException          if fixture I/O fails
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(dataProvider = "exitStatuses")
	public void answersRunnerExitStatus(int exitStatus, Status expected) throws IOException, InterruptedException
	{
		try (Fixture fixture = new Fixture();
			SessionLauncher launcher = fixture.launcher(fixture.harness))
		{
			fixture.starter.exitWith(exitStatus);
			requireThat(launcher.start(), "start").isTrue();
			String id = fixture.publish(fixture.request(false));
			fixture.hold(id, 0, fixture.requestFile(id), fixture.sandbox);

			requireThat(launcher.poll(), "poll").isTrue();
			fixture.starter.awaitRunner().awaitRecords(2);
			fixture.executor.awaitIdle();

			Response response = fixture.response(id);
			requireThat(response.status(), "status").isEqualTo(expected);
			requireThat(response.exitStatus(), "exitStatus").isEqualTo(Optional.of(exitStatus));
		}
	}

	/**
	 * A user's home directory with the inbox, a sender's files, a harness process with a sandbox process below it,
	 * and fakes for the processes, the clock and the child runners.
	 */
	private final class Fixture implements AutoCloseable
	{
		private final TemporaryDirectories cleanup = new TemporaryDirectories();
		private final Path home;
		private final SessionLauncherChannel channel;
		private final FakeProcessTable processes = new FakeProcessTable();
		private final ProcessIdentity harness = processes.start(100, Optional.empty(), List.of("harness-a"));
		private final ProcessIdentity sandbox = processes.start(150, Optional.of(harness), List.of("sandbox"));
		private final FakeStarter starter = new FakeStarter();
		private final ManualClock clock = new ManualClock(Instant.now());
		private final FileLocks fileLocks = new FileLocks();
		private final TrackedExecutor executor = new TrackedExecutor();
		private final Path project;
		private final Path turns;
		private final Path nullDevice;
		private final FakeParentPolicySource policy;
		private long nextPid = 1000;

		/**
		 * Creates the fixture.
		 *
		 * @throws IOException if setup fails
		 */
		private Fixture() throws IOException
		{
			try (TestJvmScope jvm = new TestJvmScope())
			{
				home = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "launcher-")).toRealPath();
			}
			channel = new SessionLauncherChannel(home);
			Files.createDirectories(channel.inbox());
			Path work = Files.createDirectory(home.resolve("work"));
			project = Files.createDirectory(work.resolve("project"));
			Files.writeString(project.resolve("marker"), "project bytes");
			turns = Files.writeString(work.resolve("turns.jsonl"), TURN + "\n" + COMPLETE + "\n");
			nullDevice = Files.writeString(home.resolve("null-device"), "");
			policy = new FakeParentPolicySource(HARNESS_NAME, policyWriting(work));
		}

		/**
		 * Returns a sandboxed policy that lets the parent write one folder and reach the model host.
		 *
		 * @param writeRoot the folder
		 * @return the policy
		 */
		private ParentPolicy policyWriting(Path writeRoot)
		{
			return new ParentPolicy(true, List.of(writeRoot), List.of(), List.of(), List.of(),
				HostAccess.listed(Set.of(MODEL_HOST)), false, List.of());
		}

		/**
		 * Creates a launcher for a harness process.
		 *
		 * @param harnessProcess the harness process
		 * @return the launcher
		 */
		private SessionLauncher launcher(ProcessIdentity harnessProcess)
		{
			return new SessionLauncher(channel, "harness-a", harnessProcess, processes, policy, needs(), starter,
				fileLocks, clock, executor);
		}

		/**
		 * Returns what the requested child's runner reports that the child needs: its run folder, the request's
		 * denied reads and the model's host.
		 *
		 * @return the needs
		 */
		private ChildNeedsSource needs()
		{
			return (_, runFolder, deniedReads, _) -> new ChildNeeds(runFolder, List.of(), deniedReads, Set.of(MODEL_HOST));
		}

		/**
		 * Returns a request for a Claude child.
		 *
		 * @param streamed {@code true} to stream the records as turn files, {@code false} to send them in the request
		 * @return the request, naming the sender's files
		 */
		private Request request(boolean streamed)
		{
			return request(streamed, ExtraPermissions.NONE);
		}

		/**
		 * Returns a request for a Claude child that asks for extra permissions.
		 *
		 * @param streamed {@code true} to stream the records as turn files, {@code false} to send them in the request
		 * @param extra    the writes and hosts that the child needs beyond its harness's
		 * @return the request, naming the sender's files
		 */
		private Request request(boolean streamed, ExtraPermissions extra)
		{
			return request(streamed, extra, Optional.empty());
		}

		/**
		 * Returns a request for a Claude child that asks for extra permissions from a thread of the sender's session.
		 *
		 * @param streamed     {@code true} to stream the records as turn files, {@code false} to send them in the
		 *                     request
		 * @param extra        the writes and hosts that the child needs beyond its harness's
		 * @param askingThread the identifier of the thread that sends the request, or empty if the sender gives none
		 * @return the request, naming the sender's files
		 */
		private Request request(boolean streamed, ExtraPermissions extra, Optional<String> askingThread)
		{
			Optional<Path> records = Optional.of(turns);
			if (streamed)
				records = Optional.empty();
			return new Request("claude", Optional.of("model-a"), Optional.of("effort-a"), Optional.empty(), records,
				project, List.of(), Optional.empty(), List.of(), false, false, true, 0, 0, Optional.empty(),
				Optional.empty(), false, Optional.empty(), Optional.empty(), List.of(), extra, Optional.empty(),
				Optional.empty(), askingThread);
		}

		/**
		 * Publishes a request in the inbox, as a sender does.
		 *
		 * @param request the request
		 * @return its identifier
		 * @throws IOException if the request cannot be published
		 */
		private String publish(Request request) throws IOException
		{
			String id = SessionLauncherChannel.newId();
			RequestArchive.packSession(json, requestFile(id), request, nullDevice, RequestLimits.UNBOUNDED);
			return id;
		}

		/**
		 * Returns the inbox file of a request.
		 *
		 * @param id the request's identifier
		 * @return the file
		 */
		private Path requestFile(String id)
		{
			return channel.inbox().resolve(SessionLauncherChannel.requestFileName(id));
		}

		/**
		 * Publishes a streamed turn in the inbox, as a sender does.
		 *
		 * @param id     the request's identifier
		 * @param turn   the turn number
		 * @param record the turn's runner record
		 * @return the turn's file
		 * @throws IOException if the turn cannot be published
		 */
		private Path publishTurn(String id, int turn, String record) throws IOException
		{
			Path partial = Files.writeString(channel.inbox().resolve(SessionLauncherChannel.TEMPORARY_PREFIX + id +
				"-" + turn), record + "\n");
			return Files.move(partial, channel.inbox().resolve(SessionLauncherChannel.turnFileName(id, turn)),
				StandardCopyOption.ATOMIC_MOVE);
		}

		/**
		 * Starts a holder process, as a sender does before it publishes a message.
		 *
		 * @param id      the request's identifier
		 * @param message the message number
		 * @param file    the file whose digest the holder names
		 * @param parent  the holder's parent
		 * @return the holder
		 * @throws IOException if the file cannot be read
		 */
		private ProcessIdentity hold(String id, int message, Path file, ProcessIdentity parent) throws IOException
		{
			nextPid += 1;
			return processes.start(nextPid, Optional.of(parent), SessionLauncherChannel.holderArguments(id, message,
				SessionLauncherChannel.digest(file)));
		}

		/**
		 * Creates the directory of a launcher that crashed: its harness process has ended and its lock is free.
		 *
		 * @return the directory
		 * @throws IOException if it cannot be created
		 */
		private Path crashedLauncher() throws IOException
		{
			Path directory = Files.createDirectories(channel.launcherDirectory("harness-a", 777,
				Instant.parse("2026-11-05T11:00:00Z")));
			Files.writeString(directory.resolve(SessionLauncherChannel.LOCK_FILE), "");
			Files.writeString(directory.resolve(SessionLauncherChannel.HEARTBEAT_FILE), "");
			return directory;
		}

		/**
		 * Returns the log of the launcher that serves a harness process.
		 *
		 * @param harnessProcess the harness process
		 * @return the log's path
		 */
		private Path log(ProcessIdentity harnessProcess)
		{
			return channel.logFile("harness-a", harnessProcess.pid(), harnessProcess.start());
		}

		/**
		 * Creates the log of a launcher that was last written some time ago.
		 *
		 * @param harnessProcess the harness process that the launcher served
		 * @param age            how long ago
		 * @return the log
		 * @throws IOException if it cannot be created
		 */
		private Path logAged(ProcessIdentity harnessProcess, Duration age) throws IOException
		{
			Path file = log(harnessProcess);
			Files.createDirectories(file.getParent());
			Files.writeString(file, "log bytes\n");
			Files.setLastModifiedTime(file, FileTime.from(clock.instant().minus(age)));
			return file;
		}

		/**
		 * Creates the directory of a request that a launcher accepted.
		 *
		 * @param launcher the launcher's directory
		 * @return the request's identifier
		 * @throws IOException if it cannot be created
		 */
		private String acceptedBy(Path launcher) throws IOException
		{
			String id = SessionLauncherChannel.newId();
			Path directory = Files.createDirectories(channel.requestDirectory(id));
			new AcceptedRequest(launcher.getFileName().toString(), clock.instant()).write(json,
				directory.resolve(SessionLauncherChannel.ACCEPTED_FILE));
			return id;
		}

		/**
		 * Creates an inbox file that was last modified some time ago.
		 *
		 * @param name the file's name
		 * @param age  how long ago
		 * @return the file
		 * @throws IOException if it cannot be created
		 */
		private Path inboxFile(String name, Duration age) throws IOException
		{
			Path file = Files.writeString(channel.inbox().resolve(name), name + " bytes");
			Files.setLastModifiedTime(file, FileTime.from(clock.instant().minus(age)));
			return file;
		}

		/**
		 * Returns each inbox entry with its content and modification time.
		 *
		 * @return the entries by name
		 * @throws IOException if the inbox cannot be read
		 */
		private Map<String, String> inboxState() throws IOException
		{
			Map<String, String> state = new TreeMap<>();
			try (DirectoryStream<Path> entries = Files.newDirectoryStream(channel.inbox()))
			{
				for (Path entry : entries)
				{
					state.put(entry.getFileName().toString(), Arrays.toString(Files.readAllBytes(entry)) + " " +
						Files.getLastModifiedTime(entry, LinkOption.NOFOLLOW_LINKS));
				}
			}
			return state;
		}

		/**
		 * Reads a request's response.
		 *
		 * @param id the request's identifier
		 * @return the response
		 * @throws IOException if it cannot be read
		 */
		private Response response(String id) throws IOException
		{
			return Response.read(json, channel.requestDirectory(id).resolve(SessionLauncherChannel.RESPONSE_FILE));
		}

		@Override
		public void close() throws IOException
		{
			try (cleanup)
			{
				executor.awaitIdle();
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
				throw new IOException(e);
			}
		}
	}
}
