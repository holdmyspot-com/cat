package com.holdmyspot.cat.common.runner.test;

import com.holdmyspot.cat.common.FileLocks;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.Yielding;
import com.holdmyspot.cat.common.git.Git;
import com.holdmyspot.cat.common.optimizer.workspace.GitWorktreeRegistrar;
import com.holdmyspot.cat.common.optimizer.workspace.WorktreeRefusedException;
import com.holdmyspot.cat.common.optimizer.workspace.WorktreeRegistrar;
import com.holdmyspot.cat.common.runner.HarnessEvaluationResult;
import com.holdmyspot.cat.common.runner.HarnessProcessLiveness;
import com.holdmyspot.cat.common.runner.HarnessProcessResult;
import com.holdmyspot.cat.common.runner.HarnessRunnerBridge;
import com.holdmyspot.cat.common.runner.HarnessRunnerOptions;
import com.holdmyspot.cat.common.runner.HarnessTemporaryRoot;
import com.holdmyspot.cat.common.runner.InvalidArgumentsException;
import com.holdmyspot.cat.common.runner.LiveNestedSession;
import com.holdmyspot.cat.common.runner.NestedSession;
import com.holdmyspot.cat.common.runner.NestedSessionManager;
import com.holdmyspot.cat.common.runner.NestedSessionReceipt;
import com.holdmyspot.cat.common.runner.ProcessHandleLiveness;
import com.holdmyspot.cat.common.runner.RequestLimits;
import com.holdmyspot.cat.common.runner.RetainedNestedSessionClient;
import com.holdmyspot.cat.common.runner.RetainedTrialClaim;
import com.holdmyspot.cat.common.runner.RetainedTrialPrefix;
import com.holdmyspot.cat.common.runner.RetainedTrialRequest;
import com.holdmyspot.cat.common.runner.RetainedTrialResponse;
import com.holdmyspot.cat.common.runner.SessionRequestAccess;
import com.holdmyspot.cat.common.runner.SessionRequestBroker;
import com.holdmyspot.cat.common.runner.SessionRequestCli;
import com.holdmyspot.cat.common.runner.SessionRequestForwarder;
import com.holdmyspot.cat.common.runner.SessionSlots;
import com.holdmyspot.cat.common.runner.WorktreeRequest;
import com.holdmyspot.cat.common.runner.WorktreeRequestClient;
import com.holdmyspot.cat.common.runner.WorktreeResponse;
import com.holdmyspot.cat.common.runner.fakes.test.FakeHarnessCli;
import com.holdmyspot.cat.common.runner.fakes.test.ScriptedHarness.Turn;
import com.holdmyspot.cat.common.runner.fakes.test.ScriptedProcess;
import com.holdmyspot.cat.common.runner.fakes.test.TestHarnessRunnerScope;
import com.holdmyspot.cat.common.runner.launcher.RunnerArguments;
import com.holdmyspot.cat.common.runner.policy.ExtraPermissions;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedReader;
import java.io.PipedWriter;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.apache.commons.compress.archivers.tar.TarUtils;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static org.testng.Assert.expectThrows;

/**
 * Tests request validation, child runner options, retained responses, and broker lifecycle with in-process children.
 */
public final class SessionRequestBrokerTest
{
	/**
	 * The harness session that an interrupted requested nested session records.
	 */
	private static final String RESUMED_SESSION = "5f0c2a8e-7b1d-4e3a-9c6f-2d8b1a4e7c90";
	/**
	 * The harness session that a requested nested session records.
	 */
	private static final String CHILD_SESSION = "0a4e7c3b-9d2f-4b1a-8e6c-5f3d2a1b0c9e";
	/**
	 * The file that binds a slot to the archive that first took it.
	 */
	private static final String ORIGINAL_REQUEST_FILE = "launch-request.json";
	/**
	 * Creates the test.
	 */
	public SessionRequestBrokerTest()
	{
	}

	/**
	 * Supplies runner exit statuses and the broker classifications they must produce.
	 *
	 * @return exit status and response status pairs
	 */
	@DataProvider
	public Object[][] outcomes()
	{
		return new Object[][]{{0, "completed"}, {1, "failed"}, {75, "capacity"}};
	}

	/**
	 * Verifies input snapshots, prompt ordering, capacity bounds, transcript copies, and exit classifications.
	 *
	 * @param exit the modeled runner exit status
	 * @param expected the required response classification
	 * @throws IOException if fixture or broker I/O fails
	 */
	@Test(dataProvider = "outcomes", timeOut = 30_000)
	public void retainsChildEvidence(int exit, String expected) throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		// Requirement: R-I9
		try (Fixture fixture = new Fixture())
		{
			AtomicReference<FakeHarnessCli> child = new AtomicReference<>();
			AtomicReference<List<String>> arguments = new AtomicReference<>();
			try (SessionRequestBroker broker = new SessionRequestBroker(fixture.nestedSession, fixture.manager,
				fixture.options, (harness, options, directory) ->
				{
					arguments.set(options);
					requireThat(harness, "harness").isEqualTo("claude");
					Path project = Path.of(option(options, "--project"));
					requireThat(Files.readString(project.resolve("marker")), "snapshot").isEqualTo("original");
					Path plugin = Path.of(option(options, "--plugin"));
					requireThat(Files.readString(plugin.resolve("plugin.txt")), "plugin").isEqualTo("plugin bytes");
					Files.writeString(fixture.project.resolve("marker"), "changed");
					requireThat(Files.readString(project.resolve("marker")), "snapshot").isEqualTo("original");
					if (exit != 75)
					{
						NestedSessionManager manager = new NestedSessionManager(fileLocks, Path.of(option(options, "--results")),
							Clock.systemUTC(), Yielding.fileReplacer());
						NestedSession nestedSession = manager.create("claude");
						manager.recordHarnessSession(nestedSession, CHILD_SESSION);
						Files.writeString(Files.createDirectories(nestedSession.sessionTranscripts()).resolve("native.jsonl"),
							"native");
						Path transcript = Files.writeString(Path.of(option(options, "--output")), "bridge");
						if (exit == 0)
							NestedSessionReceipt.write(fixture.json, Yielding.fileReplacer(), Path.of(option(options, "--receipt")),
							nestedSession, transcript, List.of());
					}
					FakeHarnessCli process = new FakeHarnessCli(exitOnCompleteRecord(fixture.json, child), exit);
					child.set(process);
					return process;
				}))
			{
				fixture.request("first");
				broker.scan();
				JsonNode response = fixture.await("first");
				requireThat(response.path("status").asString(), "status").isEqualTo(expected);
				requireThat(option(arguments.get(), "--capacity-wait"), "capacityWait").isEqualTo("600");
				requireThat(option(arguments.get(), "--model"), "model").isEqualTo("haiku");
				requireThat(option(arguments.get(), "--effort"), "effort").isEqualTo("low");
				requireThat(arguments.get(), "arguments").doesNotContain("--session-requests").
					contains(fixture.options.resultsFolder().toString());
				requireThat(option(arguments.get(), "--deny-read"), "deniedRead").isEqualTo(fixture.denied.toString());
				requireThat(child.get().requests().size(), "records").isEqualTo(3);
				requireThat(fixture.json.readTree(child.get().requests().getFirst()).path("prompt").asString(), "prompt").
					isEqualTo("test the plugin");
				requireThat(fixture.json.readTree(child.get().requests().get(1)).path("prompt").asString(), "secondPrompt").
					isEqualTo("then verify");
				if (exit != 75)
				{
					requireThat(Files.readString(fixture.responses.resolve("first/session-transcripts/native.jsonl")), "native").
						isEqualTo("native");
					requireThat(response.path("session_id").asString(), "sessionId").isEqualTo(CHILD_SESSION);
				}
				fixture.request("second");
				broker.scan();
				requireThat(fixture.await("second").path("reason").asString(), "limit").
					isEqualTo(SessionRequestAccess.limitReason(1));
			}
		}
	}

	/**
	 * Supplies the request depths of a requesting nested session and the depth that each requested session must receive,
	 * or {@code -1} when the requested session must not request sessions.
	 *
	 * @return the nested session's depth and the requested session's depth
	 */
	@DataProvider
	public Object[][] requestDepths()
	{
		return new Object[][]{{0, -1}, {1, 0}, {3, 2}};
	}

	/**
	 * Verifies that a requested session may request sessions in turn, as many as the requesting nested session, only
	 * while the nested session's request depth leaves a level below it, and that each level lowers the depth by one.
	 *
	 * @param depth    the requesting nested session's request depth
	 * @param expected the requested session's request depth, or {@code -1} if it must request none
	 * @throws IOException if fixture or broker I/O fails
	 */
	@Test(dataProvider = "requestDepths", timeOut = 30_000)
	public void passesRequestDepthDown(int depth, int expected) throws IOException
	{
		// Requirement: R-I11
		try (Fixture fixture = new Fixture("--session-requests", "1", "--request-depth", String.valueOf(depth)))
		{
			AtomicReference<List<String>> arguments = new AtomicReference<>();
			AtomicReference<FakeHarnessCli> child = new AtomicReference<>();
			try (SessionRequestBroker broker = new SessionRequestBroker(fixture.nestedSession, fixture.manager,
				fixture.options, (_, options, _) ->
				{
					arguments.set(options);
					FakeHarnessCli process = new FakeHarnessCli(exitOnCompleteRecord(fixture.json, child), 1);
					child.set(process);
					return process;
				}))
			{
				fixture.request("first");
				broker.scan();
				fixture.await("first");
			}
			if (expected < 0)
				requireThat(arguments.get(), "arguments").doesNotContain("--session-requests");
			else
				requireThat(option(arguments.get(), "--session-requests"), "sessionRequests").isEqualTo("1");
			if (expected <= 0)
				requireThat(arguments.get(), "arguments").doesNotContain("--request-depth");
			else
				requireThat(option(arguments.get(), "--request-depth"), "requestDepth").
					isEqualTo(String.valueOf(expected));
		}
	}

	/**
	 * Verifies that the runner states the nested session's limits in the responses directory when its broker starts and
	 * again whenever it accepts a request, so that a command can tell before it changes anything whether the nested
	 * session allows a further session, and that a reader of the file gets the reason with which the runner would refuse
	 * one.
	 *
	 * @throws IOException if fixture or broker I/O fails
	 */
	@Test(timeOut = 30_000)
	public void statesLimits() throws IOException
	{
		try (Fixture fixture = new Fixture("--session-requests", "1", "--request-depth", "2"))
		{
			AtomicReference<FakeHarnessCli> child = new AtomicReference<>();
			Path file = fixture.responses.resolve(SessionRequestAccess.LIMITS_FILE);
			try (SessionRequestBroker broker = new SessionRequestBroker(fixture.nestedSession, fixture.manager,
				fixture.options, (_, _, _) ->
				{
					FakeHarnessCli process = new FakeHarnessCli(exitOnCompleteRecord(fixture.json, child), 1);
					child.set(process);
					return process;
				}))
			{
				broker.start(_ -> () ->
				{
				});
				requireThat(fixture.json.readTree(file.toFile()), "started").isEqualTo(fixture.json.readTree(
					"{\"format_version\":1,\"requests_allowed\":1,\"requests_used\":0,\"depth_remaining\":2}"));
				requireThat(SessionRequestAccess.furtherRequestRefusal(fixture.requests), "before").isEmpty();

				fixture.request("first");
				broker.scan();
				fixture.await("first");
				requireThat(fixture.json.readTree(file.toFile()), "accepted").isEqualTo(fixture.json.readTree(
					"{\"format_version\":1,\"requests_allowed\":1,\"requests_used\":1,\"depth_remaining\":2}"));
				requireThat(SessionRequestAccess.furtherRequestRefusal(fixture.requests), "spent").
					isEqualTo(Optional.of(SessionRequestAccess.limitReason(1)));
			}
		}
	}

	/**
	 * Verifies that a nested session whose options allow no session request states so in its limits, and that a reader of
	 * the file gets the reason that names the caller's options that would allow the sessions.
	 *
	 * @throws IOException if fixture or broker I/O fails
	 */
	@Test(timeOut = 30_000)
	public void statesNoRequests() throws IOException
	{
		try (Fixture fixture = new Fixture("--session-requests", "0");
			SessionRequestBroker broker = new SessionRequestBroker(fixture.nestedSession, fixture.manager, fixture.options,
				(_, _, _) ->
			{
				throw new AssertionError("A nested session that allows no request starts no session");
			}))
		{
			broker.start(_ -> () ->
			{
			});
			requireThat(SessionRequestAccess.furtherRequestRefusal(fixture.requests), "refusal").
				isEqualTo(Optional.of(SessionRequestAccess.NO_REQUESTS_REASON));
		}
	}

	/**
	 * Verifies that a request installs CAT in the requested session's home only when it asks for it, so that the
	 * requested session has CAT's rules and launchers, such as the runner launchers through which it requests sessions in
	 * turn.
	 *
	 * @throws IOException if fixture or broker I/O fails
	 */
	@Test(timeOut = 30_000)
	public void installsCatWhenRequested() throws IOException
	{
		try (Fixture fixture = new Fixture("--session-requests", "2"))
		{
			List<List<String>> arguments = new CopyOnWriteArrayList<>();
			AtomicReference<FakeHarnessCli> child = new AtomicReference<>();
			try (SessionRequestBroker broker = new SessionRequestBroker(fixture.nestedSession, fixture.manager,
				fixture.options, (_, options, _) ->
				{
					arguments.add(options);
					FakeHarnessCli process = new FakeHarnessCli(exitOnCompleteRecord(fixture.json, child), 1);
					child.set(process);
					return process;
				}))
			{
				fixture.request("plain");
				broker.scan();
				fixture.await("plain");
				fixture.writeRequest("installed", fixture.document().put("install_cat", true));
				broker.scan();
				fixture.await("installed");
			}
			requireThat(arguments.get(0), "plain").doesNotContain("--install-cat");
			requireThat(arguments.get(1), "installed").contains("--install-cat");
		}
	}

	/**
	 * Verifies that a request's extra permissions reach the requested session's runner as a file of the same
	 * permissions, and that a request without them gives the runner no such option.
	 *
	 * @throws IOException if fixture or broker I/O fails
	 */
	@Test(timeOut = 30_000)
	public void givesChildTheRequestedExtraPermissions() throws IOException
	{
		try (Fixture fixture = new Fixture("--session-requests", "2"))
		{
			ExtraPermissions extra = new ExtraPermissions(List.of(fixture.root.resolve("registry cache")),
				Set.of("registry.example"));
			List<List<String>> arguments = new CopyOnWriteArrayList<>();
			List<ExtraPermissions> given = new CopyOnWriteArrayList<>();
			AtomicReference<FakeHarnessCli> child = new AtomicReference<>();
			try (SessionRequestBroker broker = new SessionRequestBroker(fixture.nestedSession, fixture.manager,
				fixture.options, (_, options, _) ->
				{
					arguments.add(options);
					if (options.contains(ExtraPermissions.OPTION))
						given.add(ExtraPermissions.read(fixture.json, Path.of(option(options, ExtraPermissions.OPTION))));
					FakeHarnessCli process = new FakeHarnessCli(exitOnCompleteRecord(fixture.json, child), 1);
					child.set(process);
					return process;
				}))
			{
				fixture.request("plain");
				broker.scan();
				fixture.await("plain");
				fixture.writeRequest("extra", fixture.document().set("extra_permissions", extra.toDocument(fixture.json)));
				broker.scan();
				fixture.await("extra");
			}
			requireThat(arguments.get(0), "plain").doesNotContain(ExtraPermissions.OPTION);
			requireThat(given, "given").isEqualTo(List.of(extra));
		}
	}

	/**
	 * Verifies that a request whose extra permissions are not valid is refused and starts no session.
	 *
	 * @throws IOException if fixture or broker I/O fails
	 */
	@Test(timeOut = 30_000)
	public void refusesInvalidExtraPermissions() throws IOException
	{
		try (Fixture fixture = new Fixture();
			SessionRequestBroker broker = new SessionRequestBroker(fixture.nestedSession, fixture.manager, fixture.options,
				(_, _, _) ->
			{
				throw new AssertionError("Invalid extra permissions cannot start a child");
			}))
		{
			ObjectNode extra = fixture.json.createObjectNode().put("format_version", 1);
			extra.putArray("writes").add("relative/cache");
			fixture.writeRequest("bad", fixture.document().set("extra_permissions", extra));
			broker.scan();
			JsonNode response = fixture.await("bad");
			requireThat(response.path("status").asString(), "status").isEqualTo("refused");
			requireThat(response.path("reason").asString(), "reason").contains(Path.of("relative/cache").toString());
		}
	}

	/**
	 * Verifies that a requested session that its runner refuses to launch, because the requesting session's sandbox
	 * lacks permissions that the session needs, is answered as refused with the runner's reason, which names them.
	 *
	 * @throws IOException if fixture or broker I/O fails
	 */
	@Test(timeOut = 30_000)
	public void answersRefusedWhenRunnerRefusesLaunch() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			String reason = "The session needs host registry.example, which the parent's sandbox does not allow";
			AtomicReference<FakeHarnessCli> child = new AtomicReference<>();
			try (SessionRequestBroker broker = new SessionRequestBroker(fixture.nestedSession, fixture.manager,
				fixture.options, (_, _, directory) ->
				{
					Files.writeString(directory.resolve("runner.stderr"), reason + System.lineSeparator());
					FakeHarnessCli process = new FakeHarnessCli(exitOnCompleteRecord(fixture.json, child),
						HarnessRunnerOptions.REFUSED_LAUNCH_STATUS);
					child.set(process);
					return process;
				}))
			{
				fixture.request("refused");
				broker.scan();
				JsonNode response = fixture.await("refused");
				requireThat(response.path("status").asString(), "status").isEqualTo("refused");
				requireThat(response.path("reason").asString(), "reason").isEqualTo(reason);
			}
		}
	}

	/**
	 * Verifies that, on Windows, where a request archive is opened exclusively so that no writer can change it while
	 * it is read, a request whose archive another handle still holds open, as a program that scans a file that was just
	 * written does, gets no response and no response directory on that scan, does not use up the nested session's
	 * allowance of sessions, and is served by a scan after the handle is closed. On other platforms an open handle does
	 * not prevent the copy, so the request is served by the first scan.
	 *
	 * @throws IOException if fixture or broker I/O fails
	 */
	@Test(timeOut = 30_000)
	public void servesRequestWhoseArchiveWasBusy() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			AtomicInteger started = new AtomicInteger();
			AtomicReference<FakeHarnessCli> child = new AtomicReference<>();
			try (SessionRequestBroker broker = new SessionRequestBroker(fixture.nestedSession, fixture.manager,
				fixture.options, (_, _, _) ->
				{
					started.incrementAndGet();
					FakeHarnessCli process = new FakeHarnessCli(exitOnCompleteRecord(fixture.json, child), 75);
					child.set(process);
					return process;
				}))
			{
				fixture.request("busy");
				Path archive = fixture.requests.resolve("busy.tar");
				try (FileChannel _ = FileChannel.open(archive, StandardOpenOption.READ, StandardOpenOption.WRITE))
				{
					broker.scan();
					if (Platform.current().isWindows())
					{
						requireThat(Files.exists(fixture.responses.resolve("busy")), "responseDirectoryExists").isFalse();
						requireThat(started.get(), "startedWhileBusy").isEqualTo(0);
					}
				}
				broker.scan();
				requireThat(fixture.await("busy").path("status").asString(), "status").isEqualTo("capacity");
				requireThat(started.get(), "started").isEqualTo(1);
			}
		}
	}

	/**
	 * Supplies invalid request mutations, including unsafe archives and invalid manifest fields.
	 *
	 * @return invalid input categories
	 */
	@DataProvider
	public Object[][] invalidRequests()
	{
		return new Object[][]{{"outside"}, {"absolute"}, {"parent"}, {"drive"}, {"backslash"},
			{"link_escape"}, {"link_absolute"}, {"link_beyond_project"}, {"link_back_out"}, {"link_broken"},
			{"link_through"}, {"link_in_plugin"}, {"copy_in_link_targets"}, {"copy_writable_entry"}, {"copy_link"},
			{"copy_after_entry"}, {"copy_inside_copy"}, {"hardlink_entry"}, {"special"}, {"duplicate"},
			{"directory_duplicate"},
			{"too_many"}, {"large_entry"}, {"pax_chain"}, {"pax_size"}, {"long_chain"}, {"long_size"},
			{"manifest_size"}, {"prompt_size"}, {"total_size"}, {"checksum"}, {"sparse"},
			{"request_link"}, {"request_hardlink"}, {"oversized"},
			{"unknown_field"}, {"wrong_type"}, {"bad_version"}, {"unsupported_harness"}, {"malformed"},
			{"empty"}, {"trailing"}, {"harness_path"}, {"prompt_outside"}, {"plugin_outside"}};
	}

	/**
	 * Verifies that hostile inputs produce refusals and never reach the child starter. Among them are symbolic links
	 * that the extracted project cannot hold: one whose target leaves the project, by its text or through another link,
	 * or does not exist, one that an entry's path passes through, and one outside the project. Among them too are
	 * read-only copies of link targets that the extracted project cannot hold: an entry where the copies go, a writable
	 * entry or a link in a copy, a copy that comes after an entry inside it, and a copy inside another.
	 * <p>
	 * The entry-count, cumulative-size and whole-archive-size cases cross the bounds that {@link #limits(String)}
	 * gives the broker, which are small enough for a few small entries to cross; the other cases use the bounds of
	 * every request.
	 *
	 * @param kind the invalid input category
	 * @throws IOException if fixture or broker I/O fails
	 */
	@Test(dataProvider = "invalidRequests")
	public void refusesHostileInputs(String kind) throws IOException
	{
		// Requirement: R-I9
		try (Fixture fixture = new Fixture();
			SessionRequestBroker broker = new SessionRequestBroker(fixture.nestedSession, fixture.manager, fixture.options,
				(_, _, _) ->
			{
				throw new AssertionError("Invalid inputs cannot start a child");
			}, limits(kind)))
		{
			ObjectNode request = fixture.request("bad");
			switch (kind)
			{
				case "outside" -> request.put("project", fixture.root.toString());
				case "unsupported_harness" -> request.put("harness", "unknown");
				case "harness_path" -> request.put("harness", "../../outside");
				case "prompt_outside" -> request.withArray("prompts").removeAll().add("../outside");
				case "plugin_outside" -> request.withArray("plugins").add("../outside");
				case "unknown_field" -> request.put("unexpected", true);
				case "wrong_type" -> request.put("session_transcripts", "true");
				case "bad_version" -> request.put("format_version", 1);
				default ->
				{
				}
			}
			fixture.writeHostile(request, kind);
			Files.writeString(fixture.requests.resolve(".temporary.tar"), "broken");
			broker.scan();
			JsonNode response = fixture.await("bad");
			requireThat(response.path("status").asString(), "status").isEqualTo("refused");
			if (List.of("too_many", "large_entry", "long_size", "manifest_size", "prompt_size", "total_size").
				contains(kind))
				requireThat(response.path("reason").asString(), "sizeRefusal").contains("bounds");
			if (kind.startsWith("link_"))
				requireThat(response.path("reason").asString(), "linkRefusal").contains("symbolic link");
			if (kind.startsWith("copy_"))
			{
				String expected = switch (kind)
				{
					case "copy_in_link_targets" -> "lies in link-targets";
					case "copy_writable_entry" -> "is writable but lies in a read-only copy";
					case "copy_link" -> "is a symbolic link in a copy";
					case "copy_after_entry" -> "which another entry lies in";
					default -> "comes before the read-only copy";
				};
				requireThat(response.path("reason").asString(), "copyRefusal").contains(expected);
			}
			requireThat(Files.exists(fixture.responses.resolve(".temporary")), "temporaryResponse").isFalse();
		}
	}

	/**
	 * Verifies that the requested session's project holds each symbolic link that the request's project carries, with
	 * the same target, written before the file that it leads to.
	 *
	 * @throws IOException if fixture or broker I/O fails
	 */
	@Test(timeOut = 30_000)
	public void servesProjectLinks() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			List<Path> targets = new CopyOnWriteArrayList<>();
			List<String> contents = new CopyOnWriteArrayList<>();
			AtomicReference<FakeHarnessCli> child = new AtomicReference<>();
			try (SessionRequestBroker broker = new SessionRequestBroker(fixture.nestedSession, fixture.manager,
				fixture.options, (_, options, _) ->
				{
					Path link = Path.of(options.get(options.indexOf("--project") + 1)).resolve("docs/marker");
					targets.add(Files.readSymbolicLink(link));
					contents.add(Files.readString(link));
					FakeHarnessCli process = new FakeHarnessCli(exitOnCompleteRecord(fixture.json, child), 0);
					child.set(process);
					return process;
				}))
			{
				fixture.writeRequest("linked", fixture.document(), Map.of("project/docs/marker", "../marker"));
				broker.scan();
				fixture.await("linked");
			}
			requireThat(targets, "targets").isEqualTo(List.of(Path.of("../marker")));
			requireThat(contents, "contents").isEqualTo(List.of("original"));
		}
	}

	/**
	 * Supplies fields that only a session-level request may set, each with a value of the type that it takes there.
	 *
	 * @return the field name and its JSON value
	 */
	@DataProvider
	public Object[][] sessionOnlyFields()
	{
		return new Object[][]{{"agent", "\"agent-a\""}, {"turns", "\"turns\""}, {"capacity", "\"held\""}};
	}

	/**
	 * Verifies that the runner refuses a request made inside its nested session that sets a field that only a
	 * session-level request may set, naming the field, and starts no session for it.
	 *
	 * @param field the session-only field
	 * @param value its JSON value
	 * @throws IOException if fixture or broker I/O fails
	 */
	@Test(dataProvider = "sessionOnlyFields")
	public void refusesSessionOnlyField(String field, String value) throws IOException
	{
		try (Fixture fixture = new Fixture();
			SessionRequestBroker broker = new SessionRequestBroker(fixture.nestedSession, fixture.manager, fixture.options,
				(_, _, _) ->
			{
				throw new AssertionError("A request with a session-only field cannot start a session");
			}))
		{
			ObjectNode request = fixture.document();
			request.set(field, fixture.json.readTree(value));
			fixture.writeRequest("session-only", request);
			broker.scan();
			JsonNode response = fixture.await("session-only");
			requireThat(response.path("status").asString(), "status").isEqualTo("refused");
			requireThat(response.path("reason").asString(), "reason").contains(field);
		}
	}

	/**
	 * Returns request manifests that lack a model, an effort or both, each with the start of the refusal.
	 *
	 * @return the fields to remove and the refusal's expected start
	 */
	@DataProvider
	public Object[][] incompleteProfiles()
	{
		return new Object[][]{{List.of("model"), "Missing --model."}, {List.of("effort"), "Missing --effort."},
			{List.of("model", "effort"), "Missing --model and --effort."}};
	}

	/**
	 * Verifies that the runner refuses a session request without a model or an effort, with a reason that names the
	 * missing option and where to list the supported values, and starts no session for it.
	 *
	 * @param missing  the manifest fields to remove
	 * @param expected the start of the refusal's reason
	 * @throws IOException if fixture or broker I/O fails
	 */
	@Test(dataProvider = "incompleteProfiles")
	public void refusesRequestWithoutProfile(List<String> missing, String expected) throws IOException
	{
		// Requirement: R-I9
		try (Fixture fixture = new Fixture();
			SessionRequestBroker broker = new SessionRequestBroker(fixture.nestedSession, fixture.manager, fixture.options,
				(_, _, _) ->
			{
				throw new AssertionError("A request without a profile cannot start a session");
			}))
		{
			ObjectNode request = fixture.document();
			request.remove(missing);
			fixture.writeRequest("unprofiled", request);
			broker.scan();
			JsonNode response = fixture.await("unprofiled");
			requireThat(response.path("status").asString(), "status").isEqualTo("refused");
			requireThat(response.path("reason").asString(), "reason").startsWith(expected).
				contains("`claude --help`");
		}
	}

	/**
	 * Returns the bounds that a broker applies to a hostile input.
	 *
	 * @param kind the invalid input category
	 * @return 10 entries for {@code too_many}, 1,000 content bytes for {@code total_size}, 100,000 archive bytes for
	 *   {@code oversized}, and the bounds of every request otherwise
	 */
	private static RequestLimits limits(String kind)
	{
		RequestLimits limits = RequestLimits.IN_NESTED_SESSION;
		return switch (kind)
		{
			case "too_many" -> new RequestLimits(10, limits.entryBytes(), limits.totalBytes(), limits.archiveBytes());
			case "total_size" -> new RequestLimits(limits.entries(), limits.entryBytes(), 1000, limits.archiveBytes());
			case "oversized" -> new RequestLimits(limits.entries(), limits.entryBytes(), limits.totalBytes(), 100_000);
			default -> limits;
		};
	}

	/**
	 * Verifies closing a broker terminates its child and answers undiscovered requests as stopped.
	 *
	 * @throws IOException if fixture or broker I/O fails
	 * @throws InterruptedException if the child-start observation is interrupted
	 */
	@Test(timeOut = 30_000)
	public void stopsChildrenAndPending() throws IOException, InterruptedException
	{
		// Requirement: R-I9
		try (Fixture fixture = new Fixture())
		{
			CountDownLatch started = new CountDownLatch(1);
			FakeHarnessCli process = new FakeHarnessCli(_ ->
					{
					});
			try (SessionRequestBroker broker = new SessionRequestBroker(fixture.nestedSession, fixture.manager,
				fixture.options, (_, _, _) ->
				{
					started.countDown();
					return process;
				}))
			{
				fixture.request("running");
				broker.scan();
				requireThat(started.await(10, TimeUnit.SECONDS), "started").isTrue();
				fixture.request("pending");
			}
			requireThat(process.isAlive(), "childAlive").isFalse();
			requireThat(fixture.await("running").path("status").asString(), "running").isEqualTo("stopped");
			requireThat(fixture.await("pending").path("status").asString(), "pending").isEqualTo("stopped");
		}
	}

	/**
	 * Verifies discovery shutdown failures still terminate children.
	 *
	 * @throws IOException if fixture I/O fails
	 * @throws InterruptedException if child observation is interrupted
	 */
	@Test(timeOut = 30_000)
	public void stopsDespiteMonitorFailure() throws IOException, InterruptedException
	{
		// Requirement: R-I9
		try (Fixture fixture = new Fixture(); FakeHarnessCli child = new FakeHarnessCli(_ ->
		{
		}))
		{
			CountDownLatch started = new CountDownLatch(1);
			try (SessionRequestBroker broker = new SessionRequestBroker(fixture.nestedSession, fixture.manager,
				fixture.options, (_, _, _) ->
				{
					started.countDown();
					return child;
				}))
			{
				broker.start(_ -> () ->
				{
					throw new IOException("Monitor failed");
				});
				fixture.request("running");
				broker.scan();
				requireThat(started.await(10, TimeUnit.SECONDS), "started").isTrue();
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").isEqualTo("Monitor failed");
			}
			requireThat(child.isAlive(), "childAlive").isFalse();
			requireThat(fixture.await("running").path("status").asString(), "status").isEqualTo("stopped");
		}
	}

	/**
	 * Supplies the parent bridge's terminal and interruption paths.
	 *
	 * @return the bridge lifecycle paths
	 */
	@DataProvider
	public Object[][] lifecycles()
	{
		return new Object[][]{{"complete"}, {"failure"}, {"interrupt"}};
	}

	/**
	 * Verifies the bridge owns request discovery and stops children on completion, failure, and turn interruption.
	 *
	 * @param mode the parent bridge lifecycle
	 * @throws IOException if fixture or bridge I/O fails
	 */
	@Test(dataProvider = "lifecycles", timeOut = 30_000)
	public void bridgeStopsRequestedSessions(String mode) throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		// Requirement: R-I9
		try (Fixture fixture = new Fixture();
			TestHarnessRunnerScope scope = new TestHarnessRunnerScope();
			FakeHarnessCli child = new FakeHarnessCli(_ ->
			{
			}))
		{
			CountDownLatch started = new CountDownLatch(1);
			// The parent's session requests a child and waits until the broker starts it, then ends its turn the way
			// the mode says
			Turn parentTurn = switch (mode)
			{
				case "interrupt" -> Turn.blocksUntilInterrupted();
				case "failure" -> Turn.rejected();
				default -> Turn.replies("done");
			};
			ScriptedProcess parent = new ScriptedProcess(List.of(parentTurn.withCheckpoint(_ ->
			{
				fixture.request("child");
				scope.heartbeatTicker().tick();
				awaitStarted(started);
			})), new ArrayList<>());
			String turn = "{\"type\":\"turn\",\"prompt\":\"test\"}\n";
			String rest = "{\"type\":\"complete\"}\n";
			if (mode.equals("interrupt"))
				rest = "{\"type\":\"interrupt\"}\n" + rest;
			try (parent; Reader input = new StringReader(turn + rest)
			{
				private boolean submitted;

				@Override
				public int read(char[] target, int offset, int length) throws IOException
				{
					if (submitted)
					{
						awaitStarted(started);
						return super.read(target, offset, length);
					}
					submitted = true;
					return super.read(target, offset, Math.min(length, turn.length()));
				}
			})
			{
				boolean failed = false;
				try
				{
					HarnessRunnerBridge.serve(() -> new LiveNestedSession(fixture.nestedSession, parent, scope),
						new NestedSessionManager(fileLocks, fixture.root.resolve("results"),
							Clock.systemUTC(), Yielding.fileReplacer()),
						fixture.options, fixture.json, Yielding.fileReplacer(), input, new StringWriter(), (_, _, _) ->
						{
							started.countDown();
							return child;
						});
				}
				catch (IOException failure)
				{
					requireThat(failure.getMessage(), "failure").isEqualTo("The harness rejected the turn");
					failed = true;
				}
				requireThat(failed, "failed").isEqualTo(mode.equals("failure"));
			}
			requireThat(child.isAlive(), "childAlive").isFalse();
			requireThat(fixture.await("child").path("status").asString(), "status").isEqualTo("stopped");
		}
	}

	/**
	 * Verifies that a runner launch made inside a session whose nested session may request no session reaches the
	 * session's runner, which refuses it without starting a requested session, so that the launch exits with status 2 and
	 * a reason that names the caller's options that would allow the session, instead of starting a harness inside the
	 * session.
	 *
	 * @throws IOException if fixture or bridge I/O fails
	 */
	@Test(timeOut = 30_000)
	public void refusesLaunchWithoutRequests() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		// Requirement: R-I9, R-I11
		try (Fixture fixture = new Fixture(new String[0]);
			TestHarnessRunnerScope scope = new TestHarnessRunnerScope())
		{
			SessionRequestAccess.allowing(fixture.nestedSession, fixture.root, 0, Platform.current(),
				SessionRequestAccess.Shell.POSIX);
			List<String> started = new CopyOnWriteArrayList<>();
			AtomicReference<Integer> status = new AtomicReference<>();
			StringWriter errors = new StringWriter();
			AtomicInteger checks = new AtomicInteger();
			// The session's command runs the claude-runner launcher, which forwards its launch while the session's
			// runner checks for requests on each heartbeat
			ScriptedProcess parent = new ScriptedProcess(List.of(Turn.replies("done").withCheckpoint(_ ->
			{
				status.set(SessionRequestForwarder.forward("claude", new String[]{"--project",
					fixture.project.toString(), "--model", "haiku", "--effort", "low"},
					new StringReader("{\"type\":\"turn\",\"prompt\":\"nested\"}\n{\"type\":\"complete\"}\n"),
					fixture.requests, () ->
					{
						if (checks.incrementAndGet() > 100)
							throw new IOException("The runner did not answer the forwarded launch");
						scope.heartbeatTicker().tick();
					}, OutputStream.nullOutputStream(), new PrintWriter(errors, true)));
			})), new ArrayList<>());
			try (parent; Reader input = new StringReader("{\"type\":\"turn\",\"prompt\":\"test\"}\n" +
				"{\"type\":\"complete\"}\n"))
			{
				HarnessRunnerBridge.serve(() -> new LiveNestedSession(fixture.nestedSession, parent, scope),
					new NestedSessionManager(fileLocks, fixture.root.resolve("results"), Clock.systemUTC(),
						Yielding.fileReplacer()),
					fixture.options, fixture.json, Yielding.fileReplacer(), input, new StringWriter(),
					(harness, _, _) ->
					{
						started.add(harness);
						throw new IOException("No requested session may start");
					});
			}
			requireThat(status.get(), "status").isEqualTo(HarnessRunnerOptions.REFUSED_LAUNCH_STATUS);
			requireThat(errors.toString(), "errors").contains("refused: this nested session allows no further sessions").
				contains("--session-requests").contains("--request-depth").doesNotContain("--no-daemon");
			requireThat(started, "started").isEmpty();
		}
	}

	/**
	 * Verifies that a session inside a nested session holds a conversation with the session that it requests through
	 * the nested session's runner: each turn is sent once the previous turn has ended, an interrupt stops the turn in
	 * progress, a line that is no message is refused without reaching the requested session, and {@code end} completes
	 * the session.
	 *
	 * @throws IOException if fixture, broker or launch I/O fails
	 */
	@Test(timeOut = 30_000)
	public void conversationThroughBroker() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		try (Fixture fixture = new Fixture())
		{
			AtomicReference<FakeHarnessCli> child = new AtomicReference<>();
			AtomicReference<List<String>> runnerOptions = new AtomicReference<>();
			Path folder = fixture.root.resolve("conversation");
			// The asking session sends a malformed line with its first turn, its second turn and an interrupt once
			// the first turn has ended, its third turn once the second has ended, and ends the conversation after
			// the third
			List<Message> messages = List.of(new Message(0, "{\"turn\": \"first\"}"), new Message(0, "oops"),
				new Message(1, "{\"turn\": \"second\"}"), new Message(1, "{\"interrupt\": true}"),
				new Message(2, "{\"turn\": \"third\"}"), new Message(3, "{\"end\": true}"));
			AtomicInteger sent = new AtomicInteger();
			AtomicInteger checks = new AtomicInteger();
			int status;
			try (SessionRequestBroker broker = new SessionRequestBroker(fixture.nestedSession, fixture.manager,
				fixture.options, (_, options, _) ->
				{
					runnerOptions.set(options);
					FakeHarnessCli process = conversingRunner(fixture, fileLocks, options, child);
					child.set(process);
					return process;
				}))
			{
				status = SessionRequestForwarder.forward("claude", new String[]{"--project", fixture.project.toString(),
						"--model", "haiku", "--effort", "low", "--conversation", folder.toString()}, new StringReader(""),
					fixture.requests, () ->
					{
						if (checks.incrementAndGet() > 1_000_000)
							throw new IOException("The conversation did not end");
						ask(folder, messages, sent);
						broker.scan();
						Thread.yield();
					}, OutputStream.nullOutputStream(), new PrintWriter(new StringWriter(), true));
			}
			requireThat(status, "status").isEqualTo(0);
			// The requested session's runner keeps the conversation open after a turn fails
			requireThat(runnerOptions.get(), "runnerOptions").contains("--continue-after-failed-turn");
			requireThat(fromChild(fixture.json, folder), "fromChild").isEqualTo(List.of("refused oops",
				"turn_completed reply 1", "{\"turn_end\":1,\"status\":\"completed\"}", "interrupted",
				"turn_completed reply 2", "{\"turn_end\":2,\"status\":\"interrupted\"}", "turn_completed reply 3",
				"{\"turn_end\":3,\"status\":\"completed\"}", "completed", "{\"ended\":\"completed\"}"));
			requireThat(child.get().requests(), "records").isEqualTo(List.of("{\"type\":\"turn\",\"prompt\":\"first\"}",
				"{\"type\":\"turn\",\"prompt\":\"second\"}", "{\"type\":\"interrupt\"}",
				"{\"type\":\"turn\",\"prompt\":\"third\"}", "{\"type\":\"complete\"}"));
			try (Stream<Path> left = Files.list(fixture.requests))
			{
				requireThat(left.map(file -> file.getFileName().toString()).filter(name -> !name.endsWith(".tar")).
					toList(), "leftInRequests").isEmpty();
			}
		}
	}

	/**
	 * Verifies that {@code request-session --conversation} holds a conversation with the session that it requests and
	 * prints the response's path once the conversation ends.
	 *
	 * @throws IOException if fixture, broker or request I/O fails
	 */
	@Test(timeOut = 30_000)
	public void requestSessionConverses() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		try (Fixture fixture = new Fixture())
		{
			AtomicReference<FakeHarnessCli> child = new AtomicReference<>();
			Path folder = fixture.root.resolve("conversation");
			List<Message> messages = List.of(new Message(0, "{\"turn\": \"first\"}"),
				new Message(1, "{\"turn\": \"third\"}"), new Message(2, "{\"end\": true}"));
			AtomicInteger sent = new AtomicInteger();
			AtomicInteger checks = new AtomicInteger();
			StringWriter printed = new StringWriter();
			int status;
			try (SessionRequestBroker broker = new SessionRequestBroker(fixture.nestedSession, fixture.manager,
				fixture.options, (_, options, _) ->
				{
					FakeHarnessCli process = conversingRunner(fixture, fileLocks, options, child);
					child.set(process);
					return process;
				}))
			{
				status = SessionRequestCli.execute(new String[]{"--harness", "claude", "--project",
					fixture.project.toString(), "--model", "haiku", "--effort", "low", "--conversation",
					folder.toString()}, fixture.requests, Clock.systemUTC(), () ->
				{
					if (checks.incrementAndGet() > 1_000_000)
						throw new IOException("The conversation did not end");
					ask(folder, messages, sent);
					broker.scan();
					Thread.yield();
				}, new PrintWriter(printed, true));
			}
			requireThat(status, "status").isEqualTo(0);
			requireThat(fromChild(fixture.json, folder), "fromChild").isEqualTo(List.of("turn_completed reply 1",
				"{\"turn_end\":1,\"status\":\"completed\"}", "turn_completed reply 2",
				"{\"turn_end\":2,\"status\":\"completed\"}", "completed", "{\"ended\":\"completed\"}"));
			Path response = Path.of(printed.toString().strip());
			requireThat(fixture.json.readTree(response.toFile()).path("status").asString(), "response").
				isEqualTo("completed");
		}
	}

	/**
	 * Verifies that the nested session's runner stops a requested session that converses with a launcher once the
	 * launcher ends, however it ends, and answers the request as stopped, and that the launcher's last line in the
	 * conversation says that the request failed for it.
	 *
	 * @throws IOException if fixture, broker or launch I/O fails
	 */
	@Test(timeOut = 30_000)
	public void conversationStopsWithLauncher() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		try (Fixture fixture = new Fixture())
		{
			AtomicReference<FakeHarnessCli> child = new AtomicReference<>();
			Path folder = Files.createDirectory(fixture.root.resolve("conversation"));
			Files.writeString(folder.resolve("to-child.jsonl"), "{\"turn\": \"hold\"}\n");
			AtomicInteger checks = new AtomicInteger();
			try (SessionRequestBroker broker = new SessionRequestBroker(fixture.nestedSession, fixture.manager,
				fixture.options, (_, options, _) ->
				{
					FakeHarnessCli process = conversingRunner(fixture, fileLocks, options, child);
					child.set(process);
					return process;
				}))
			{
				String[] launch = {"--project", fixture.project.toString(), "--model", "haiku", "--effort", "low",
					"--conversation", folder.toString()};
				// The launcher's own session stops it while the requested session's turn runs
				IOException stopped = expectThrows(IOException.class, () -> SessionRequestForwarder.forward("claude",
					launch, new StringReader(""), fixture.requests, () ->
					{
						if (checks.incrementAndGet() > 1_000_000)
							throw new IOException("The requested session never received its turn");
						broker.scan();
						FakeHarnessCli process = child.get();
						if (process != null && !process.requests().isEmpty())
							throw new IOException("The launcher was stopped");
						Thread.yield();
					}, OutputStream.nullOutputStream(), new PrintWriter(new StringWriter(), true)));
				requireThat(stopped.getMessage(), "stopped").isEqualTo("The launcher was stopped");
				broker.scan();
				requireThat(child.get().isAlive(), "childAlive").isFalse();
				String id;
				try (Stream<Path> answered = Files.list(fixture.responses))
				{
					id = answered.filter(Files::isDirectory).map(file -> file.getFileName().toString()).findFirst().
						orElseThrow();
				}
				JsonNode response = fixture.await(id);
				requireThat(response.path("status").asString(), "status").isEqualTo("stopped");
				requireThat(response.path("reason").asString(), "reason").
					isEqualTo("The launcher that requested the session ended");
				requireThat(child.get().requests(), "records").
					isEqualTo(List.of("{\"type\":\"turn\",\"prompt\":\"hold\"}"));
				// The launcher could not follow the request to its response
				requireThat(fromChild(fixture.json, folder), "fromChild").
					isEqualTo(List.of("{\"ended\":\"failed\"}"));
			}
		}
	}

	/**
	 * Verifies that the nested session's runner stops a requested session whose records a launcher sent all at once with
	 * {@code --input} once the launcher ends before the session does, and answers the request as stopped.
	 *
	 * @throws IOException if fixture, broker or launch I/O fails
	 */
	@Test(timeOut = 30_000)
	public void packedLaunchStopsWithLauncher() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			AtomicReference<FakeHarnessCli> child = new AtomicReference<>();
			Path records = Files.writeString(fixture.root.resolve("records.jsonl"),
				"{\"type\":\"turn\",\"prompt\":\"hold\"}\n{\"type\":\"complete\"}\n");
			AtomicInteger checks = new AtomicInteger();
			try (SessionRequestBroker broker = new SessionRequestBroker(fixture.nestedSession, fixture.manager,
				fixture.options, (_, _, _) ->
				{
					// The requested session works on its turn until it is stopped
					FakeHarnessCli process = new FakeHarnessCli(_ ->
					{
					});
					child.set(process);
					return process;
				}))
			{
				String[] launch = {"--project", fixture.project.toString(), "--model", "haiku", "--effort", "low",
					"--input", records.toString()};
				// The launcher's own session stops it while the requested session's turn runs
				IOException stopped = expectThrows(IOException.class, () -> SessionRequestForwarder.forward("claude",
					launch, new StringReader(""), fixture.requests, () ->
					{
						if (checks.incrementAndGet() > 1_000_000)
							throw new IOException("The requested session never received its records");
						broker.scan();
						FakeHarnessCli process = child.get();
						if (process != null && process.requests().size() == 2)
							throw new IOException("The launcher was stopped");
						Thread.yield();
					}, OutputStream.nullOutputStream(), new PrintWriter(new StringWriter(), true)));
				requireThat(stopped.getMessage(), "stopped").isEqualTo("The launcher was stopped");
				broker.scan();
				requireThat(child.get().isAlive(), "childAlive").isFalse();
				String id;
				try (Stream<Path> answered = Files.list(fixture.responses))
				{
					id = answered.filter(Files::isDirectory).map(file -> file.getFileName().toString()).findFirst().
						orElseThrow();
				}
				JsonNode response = fixture.await(id);
				requireThat(response.path("status").asString(), "status").isEqualTo("stopped");
				requireThat(response.path("reason").asString(), "reason").
					isEqualTo("The launcher that requested the session ended");
			}
		}
	}

	/**
	 * Verifies that a requested session whose launcher ended after its harness reported its session keeps its slot,
	 * which no other session takes, and that a launch that resumes the harness session continues it in that slot,
	 * without a project of its own and without using any of the requesting nested session's allowance. The slot
	 * records the original request before starting either runner and is emptied once the resumed session ends.
	 *
	 * @throws IOException if fixture, broker or launch I/O fails
	 */
	@Test(timeOut = 30_000)
	public void interruptedLaunchResumesInItsSlot() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		try (Fixture fixture = new Fixture("--session-requests", "1"))
		{
			Path held = Files.writeString(fixture.root.resolve("held.jsonl"),
				"{\"type\":\"turn\",\"prompt\":\"hold\"}\n{\"type\":\"complete\"}\n");
			Path again = Files.writeString(fixture.root.resolve("again.jsonl"),
				"{\"type\":\"turn\",\"prompt\":\"again\"}\n{\"type\":\"complete\"}\n");
			AtomicReference<FakeHarnessCli> child = new AtomicReference<>();
			List<List<String>> starts = new CopyOnWriteArrayList<>();
			List<String> requestIds = new CopyOnWriteArrayList<>();
			List<JsonNode> bindingsAtStart = new CopyOnWriteArrayList<>();
			AtomicInteger checks = new AtomicInteger();
			try (SessionRequestBroker broker = new SessionRequestBroker(fixture.nestedSession, fixture.manager,
				fixture.options, (_, options, response) ->
				{
					starts.add(List.copyOf(options));
					requestIds.add(response.getFileName().toString());
					Path binding = Path.of(option(options, "--results")).resolve(ORIGINAL_REQUEST_FILE);
					if (Files.exists(binding))
						bindingsAtStart.add(fixture.json.readTree(binding.toFile()));
					else
						bindingsAtStart.add(fixture.json.createObjectNode());
					FakeHarnessCli process = resumableRunner(fixture, fileLocks, options, child);
					child.set(process);
					return process;
				}))
			{
				String[] first = {"--project", fixture.project.toString(), "--model", "haiku", "--effort", "low",
					"--input", held.toString()};
				expectThrows(IOException.class, () -> SessionRequestForwarder.forward("claude", first,
					new StringReader(""), fixture.requests, () ->
					{
						if (checks.incrementAndGet() > 1_000_000)
							throw new IOException("The requested session never received its records");
						broker.scan();
						FakeHarnessCli process = child.get();
						if (process != null && process.requests().size() == 2)
							throw new IOException("The launcher was stopped");
						Thread.yield();
					}, OutputStream.nullOutputStream(), new PrintWriter(new StringWriter(), true)));
				broker.scan();
				Path slot = Path.of(option(starts.getFirst(), "--results"));
				Path manifest = slot.resolve("claude").resolve(option(starts.getFirst(), "--slot-id")).
					resolve("session.json");
				// The slot's nested session is removed only after the stopped runner has ended
				for (int check = 0; check < 1_000_000 && child.get().isAlive(); ++check)
					Thread.yield();
				requireThat(Files.readString(manifest), "retained").contains("\"state\": \"running\"").
					contains(RESUMED_SESSION);
				String originalRequest = requestIds.getFirst();
				requireOriginalRequest(bindingsAtStart.getFirst(), originalRequest);
				Path binding = slot.resolve(ORIGINAL_REQUEST_FILE);
				requireOriginalRequest(fixture.json.readTree(binding.toFile()), originalRequest);

				String[] resume = {RunnerArguments.RESUME_OPTION, RESUMED_SESSION, "--model", "haiku", "--effort",
					"low", "--input", again.toString()};
				StringWriter errors = new StringWriter();
				int status = SessionRequestForwarder.forward("claude", resume, new StringReader(""), fixture.requests,
					() ->
					{
						if (checks.incrementAndGet() > 2_000_000)
							throw new IOException("The resumed session never ended");
						broker.scan();
						Thread.yield();
					}, OutputStream.nullOutputStream(), new PrintWriter(errors, true));

				requireThat(status, "status").withContext(errors, "errors").isEqualTo(0);
				requireThat(fixture.json.readTree(fixture.responses.resolve(SessionRequestAccess.LIMITS_FILE).toFile()).
					path("requests_used").intValue(), "requestsUsed").isEqualTo(1);
				List<String> resumed = starts.get(1);
				requireThat(option(resumed, "--results"), "resultsFolder").isEqualTo(slot.toString());
				requireThat(option(resumed, RunnerArguments.RESUME_SLOT_OPTION), "resumeSlot").isEqualTo(RESUMED_SESSION);
				requireThat(resumed, "resumed").doesNotContain("--project");
				requireThat(requestIds.get(1), "resumeRequestId").isNotEqualTo(originalRequest);
				requireOriginalRequest(bindingsAtStart.get(1), originalRequest);
				// The slot is emptied once the resumed session's response is published
				for (int check = 0; check < 1_000_000 && Files.exists(slot); ++check)
					Thread.yield();
				requireThat(Files.exists(slot), "slotRetained").isFalse();
			}
		}
	}

	/**
	 * Verifies that a launch that resumes an interrupted session continues it when the scan that takes the resume is
	 * also the first to see that the interrupted launch's launcher ended. The broker stops the interrupted launch before
	 * it starts the resume, so the resume waits for the slot instead of being refused as if another launch held it.
	 *
	 * @throws IOException if fixture, broker or launch I/O fails
	 */
	@Test(timeOut = 30_000)
	public void resumeTakenWithItsLauncherEndContinues() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		try (Fixture fixture = new Fixture("--session-requests", "1"))
		{
			Path held = Files.writeString(fixture.root.resolve("held.jsonl"),
				"{\"type\":\"turn\",\"prompt\":\"hold\"}\n{\"type\":\"complete\"}\n");
			Path again = Files.writeString(fixture.root.resolve("again.jsonl"),
				"{\"type\":\"turn\",\"prompt\":\"again\"}\n{\"type\":\"complete\"}\n");
			AtomicReference<FakeHarnessCli> child = new AtomicReference<>();
			AtomicInteger checks = new AtomicInteger();
			// The requests that the broker had taken when it stopped the interrupted launch's session
			List<String> takenBeforeStop = new CopyOnWriteArrayList<>();
			try (SessionRequestBroker broker = new SessionRequestBroker(fixture.nestedSession, fixture.manager,
				fixture.options, (_, options, directory) ->
				{
					FakeHarnessCli process = resumableRunner(fixture, fileLocks, options, child);
					if (!options.contains(RunnerArguments.RESUME_SLOT_OPTION))
						process.whenExited(() -> takenBeforeStop.addAll(otherRequests(fixture, directory)));
					child.set(process);
					return process;
				}))
			{
				String[] first = {"--project", fixture.project.toString(), "--model", "haiku", "--effort", "low",
					"--input", held.toString()};
				expectThrows(IOException.class, () -> SessionRequestForwarder.forward("claude", first,
					new StringReader(""), fixture.requests, () ->
					{
						if (checks.incrementAndGet() > 1_000_000)
							throw new IOException("The requested session never received its records");
						broker.scan();
						FakeHarnessCli process = child.get();
						if (process != null && process.requests().size() == 2)
							throw new IOException("The launcher was stopped");
						Thread.yield();
					}, OutputStream.nullOutputStream(), new PrintWriter(new StringWriter(), true)));

				// No scan runs between the launcher's end and the resume's request, so one scan sees both
				String[] resume = {RunnerArguments.RESUME_OPTION, RESUMED_SESSION, "--model", "haiku", "--effort",
					"low", "--input", again.toString()};
				StringWriter errors = new StringWriter();
				int status = SessionRequestForwarder.forward("claude", resume, new StringReader(""), fixture.requests,
					() ->
					{
						if (checks.incrementAndGet() > 2_000_000)
							throw new IOException("The resumed session never ended");
						broker.scan();
						Thread.yield();
					}, OutputStream.nullOutputStream(), new PrintWriter(errors, true));

				requireThat(takenBeforeStop, "takenBeforeStop").isEmpty();
				requireThat(status, "status").withContext(errors, "errors").isEqualTo(0);
			}
		}
	}

	/**
	 * Returns the requests that a broker has taken, other than one.
	 *
	 * @param fixture   the requesting nested session
	 * @param directory the response directory of the request to leave out
	 * @return the names of the response directories of the other requests
	 * @throws UncheckedIOException if the responses cannot be listed
	 */
	private static List<String> otherRequests(Fixture fixture, Path directory)
	{
		try (Stream<Path> answered = Files.list(fixture.responses))
		{
			return answered.filter(entry -> Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)).
				filter(entry -> !entry.getFileName().toString().startsWith(".")).
				filter(entry -> !entry.equals(directory)).
				map(entry -> entry.getFileName().toString()).toList();
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * Verifies that a launch whose harness cannot continue the session that it resumes fails with status 1 and the
	 * status {@code unresumable}, and that the session's slot stays retained for a later launch.
	 *
	 * @throws IOException if fixture, broker or launch I/O fails
	 */
	@Test(timeOut = 30_000)
	public void unresumableSessionStaysRetained() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		try (Fixture fixture = new Fixture("--session-requests", "1"))
		{
			Path held = Files.writeString(fixture.root.resolve("held.jsonl"),
				"{\"type\":\"turn\",\"prompt\":\"hold\"}\n{\"type\":\"complete\"}\n");
			Path again = Files.writeString(fixture.root.resolve("again.jsonl"),
				"{\"type\":\"turn\",\"prompt\":\"again\"}\n{\"type\":\"complete\"}\n");
			AtomicReference<FakeHarnessCli> child = new AtomicReference<>();
			List<List<String>> starts = new CopyOnWriteArrayList<>();
			AtomicInteger checks = new AtomicInteger();
			try (SessionRequestBroker broker = new SessionRequestBroker(fixture.nestedSession, fixture.manager,
				fixture.options, (_, options, _) ->
				{
					starts.add(List.copyOf(options));
					FakeHarnessCli process = resumableRunner(fixture, fileLocks, options, child, false);
					child.set(process);
					return process;
				}))
			{
				String[] first = {"--project", fixture.project.toString(), "--model", "haiku", "--effort", "low",
					"--input", held.toString()};
				expectThrows(IOException.class, () -> SessionRequestForwarder.forward("claude", first,
					new StringReader(""), fixture.requests, () ->
					{
						if (checks.incrementAndGet() > 1_000_000)
							throw new IOException("The requested session never received its records");
						broker.scan();
						FakeHarnessCli process = child.get();
						if (process != null && process.requests().size() == 2)
							throw new IOException("The launcher was stopped");
						Thread.yield();
					}, OutputStream.nullOutputStream(), new PrintWriter(new StringWriter(), true)));
				broker.scan();
				for (int check = 0; check < 1_000_000 && child.get().isAlive(); ++check)
					Thread.yield();

				StringWriter errors = new StringWriter();
				int status = SessionRequestForwarder.forward("claude", new String[]{RunnerArguments.RESUME_OPTION,
					RESUMED_SESSION, "--model", "haiku", "--effort", "low", "--input", again.toString()},
					new StringReader(""), fixture.requests, () ->
					{
						if (checks.incrementAndGet() > 2_000_000)
							throw new IOException("The resumed session never ended");
						broker.scan();
						Thread.yield();
					}, OutputStream.nullOutputStream(), new PrintWriter(errors, true));

				requireThat(status, "status").withContext(errors, "errors").isEqualTo(1);
				requireThat(errors.toString(), "errors").contains("unresumable");
				Path manifest = Path.of(option(starts.getFirst(), "--results")).resolve("claude").
					resolve(option(starts.getFirst(), "--slot-id")).resolve("session.json");
				// The slot is given back once the resumed session's response is published
				for (int check = 0; check < 1_000_000 && child.get().isAlive(); ++check)
					Thread.yield();
				requireThat(Files.readString(manifest), "retained").contains("\"state\": \"running\"").
					contains(RESUMED_SESSION);
			}
		}
	}

	/**
	 * Verifies that a discard request empties the retained slot whose nested session records the named harness session,
	 * without a slot or permit of its own, and that a discard request that names a session that no retained nested
	 * session records changes nothing.
	 *
	 * @throws IOException if fixture, broker or request I/O fails
	 */
	@Test(timeOut = 30_000)
	public void discardsRetainedSession() throws IOException
	{
		try (Fixture fixture = new Fixture("--session-requests", "0"))
		{
			Path slot = SessionRequestAccess.requestedSlotsDirectory(fixture.nestedSession).resolve("d1");
			NestedSessionManager manager = new NestedSessionManager(new FileLocks(), slot, Clock.systemUTC(),
				Yielding.fileReplacer()).withFixedSlotId("s-1");
			NestedSession retained = manager.create("claude");
			manager.recordHarnessSession(retained, RESUMED_SESSION);
			try (SessionRequestBroker broker = new SessionRequestBroker(fixture.nestedSession, fixture.manager,
				fixture.options, (_, _, _) ->
				{
					throw new AssertionError("A discard request started a session");
				}))
			{
				RetainedNestedSessionClient client = new RetainedNestedSessionClient(
					() -> Optional.of(new NestedSessionChannel(fixture.requests)), fixture.json, broker::scan);

				requireThat(client.discard(RESUMED_SESSION), "discarded").isTrue();
				requireThat(Files.exists(retained.directory()), "sessionRetained").isFalse();
				requireThat(client.discard(RESUMED_SESSION), "discardedAgain").isFalse();
			}
		}
	}

	/**
	 * Verifies that recovery settles an original queued launch whose sender ended, without admitting it or spending
	 * the sole available session request. A later scan still cannot start the original.
	 *
	 * @throws IOException if the broker or fixture cannot read or write a request
	 */
	@Test(timeOut = 30_000)
	public void retainedTrialFencesDeadQueuedLaunch() throws IOException
	{
		try (Fixture fixture = new Fixture();
		     SessionRequestBroker broker = new SessionRequestBroker(fixture.nestedSession, fixture.manager, fixture.options,
			     (_, _, _) ->
			     {
				     throw new AssertionError("Recovery admitted a launch whose sender had ended");
			     }))
		{
			UUID original = UUID.randomUUID();
			ObjectNode launch = fixture.document().put("input", "stream");
			launch.remove("prompts");
			fixture.writeRequest(original.toString(), launch);
			RetainedTrialRequest recovery = RetainedTrialRequest.status(original);
			String id = recovery.publish(fixture.json, fixture.requests);

			broker.scan();
			requireThat(fixture.manager.requestsUsed(fixture.nestedSession), "requestsUsed").isEqualTo(0);
			requireThat(retainedTrialStatus(fixture, id, recovery), "recovery").
				isEqualTo(RetainedTrialResponse.Status.ABSENT);

			broker.scan();
			requireThat(fixture.manager.requestsUsed(fixture.nestedSession), "requestsUsedAfterAnotherScan").isEqualTo(0);
		}
	}

	/**
	 * Verifies that an original launch published after its absence was confirmed stays fenced across broker restart.
	 *
	 * @throws IOException if the broker or fixture cannot read or write a request
	 */
	@Test(timeOut = 30_000)
	public void retainedTrialFenceSurvivesRestart() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			UUID original = UUID.randomUUID();
			try (SessionRequestBroker broker = new SessionRequestBroker(fixture.nestedSession, fixture.manager,
				fixture.options, (_, _, _) ->
				{
					throw new AssertionError("Recovery started a session");
				}))
			{
				RetainedTrialRequest recovery = RetainedTrialRequest.status(original);
				String id = recovery.publish(fixture.json, fixture.requests);
				broker.scan();
				requireThat(retainedTrialStatus(fixture, id, recovery), "recovery").
					isEqualTo(RetainedTrialResponse.Status.ABSENT);
				TemporaryDirectories.deleteRecursively(fixture.responses.resolve(id));
				Files.deleteIfExists(fixture.requests.resolve(id + ".tar"));
			}

			Path lock = fixture.requests.resolve(original + ".lock");
			try (SessionRequestBroker broker = new SessionRequestBroker(fixture.nestedSession, fixture.manager,
				fixture.options, (_, _, _) ->
				{
					throw new AssertionError("The restarted broker admitted a fenced launch");
				});
			     FileChannel channel = FileChannel.open(lock, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
			     FileLock _ = channel.lock())
			{
				ObjectNode launch = fixture.document().put("input", "stream");
				launch.remove("prompts");
				fixture.writeRequest(original.toString(), launch);
				broker.scan();
				requireThat(fixture.await(original.toString()).path("status").asString(), "launchState").
					isEqualTo("stopped");
				requireThat(fixture.manager.requestsUsed(fixture.nestedSession), "requestsUsed").isEqualTo(0);
			}
		}
	}

	/**
	 * Verifies that recovery reports a live sender as busy while its original launch can still publish an archive.
	 *
	 * @throws IOException if the broker or fixture cannot read or write a request
	 */
	@Test(timeOut = 30_000)
	public void retainedTrialWaitsForLiveSender() throws IOException
	{
		try (Fixture fixture = new Fixture();
		     SessionRequestBroker broker = new SessionRequestBroker(fixture.nestedSession, fixture.manager, fixture.options,
			     (_, _, _) ->
			     {
				     throw new AssertionError("A retained trial status request started a session");
			     }))
		{
			UUID original = UUID.randomUUID();
			RetainedTrialRequest recovery = RetainedTrialRequest.status(original);
			Path lock = fixture.requests.resolve(original + ".lock");
			try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
			     FileLock _ = channel.lock())
			{
				String id = recovery.publish(fixture.json, fixture.requests);
				broker.scan();
				requireThat(retainedTrialStatus(fixture, id, recovery), "liveSender").
					isEqualTo(RetainedTrialResponse.Status.BUSY);
			}
			String id = recovery.publish(fixture.json, fixture.requests);
			broker.scan();
			requireThat(retainedTrialStatus(fixture, id, recovery), "endedSender").
				isEqualTo(RetainedTrialResponse.Status.ABSENT);
		}
	}

	/**
	 * Verifies that a guarded resume replays the retained turn and continues the original harness session with only
	 * the remaining prompt.
	 *
	 * @throws IOException if the fixture or guarded launch fails
	 * @throws InterruptedException if the fixture cannot reserve its retained slot
	 */
	@Test(timeOut = 30_000)
	public void guardedResumeReplaysAndContinues() throws IOException, InterruptedException
	{
		try (Fixture fixture = new Fixture())
		{
			UUID original = UUID.randomUUID();
			SessionSlots.Slot slot = retainedTrialWithPrefix(fixture, original);
			List<String> prompts = new CopyOnWriteArrayList<>();
			AtomicReference<Exception> runnerFailure = new AtomicReference<>();
			try (SessionRequestBroker broker = new SessionRequestBroker(fixture.nestedSession, fixture.manager,
				fixture.options, (harness, options, _) ->
				{
					Path resultsFolder = Path.of(option(options, "--results"));
					requireThat(resultsFolder, "resumedSlot").isEqualTo(slot.root());
					requireOriginalRequest(fixture.json.readTree(resultsFolder.resolve(ORIGINAL_REQUEST_FILE).toFile()),
						original.toString());
					return startGuardedBridge(fixture, harness, options, prompts, runnerFailure);
				}))
			{
				RetainedNestedSessionClient retained = new RetainedNestedSessionClient(
					() -> Optional.of(new NestedSessionChannel(fixture.requests)), fixture.json, broker::scan);
				RetainedTrialResponse status = retained.request(RetainedTrialRequest.status(original));
				requireThat(status.status(), "status").isEqualTo(RetainedTrialResponse.Status.READY);
				RetainedTrialClaim claim = new RetainedTrialClaim(original, status.identity().orElseThrow(),
					status.completedTurns().orElseThrow());
				requireThat(claim.completedTurns(), "completedTurns").isEqualTo(1);

				Path results = fixture.root.resolve("resumed-results");
				StringWriter output = new StringWriter();
				SessionRequestForwarder.serveRetainedTrial(SessionRequestForwarder.Route.RUN, claim, "test command",
					"claude", fixture.root, fixture.project,
					List.of("--resume", RESUMED_SESSION, "--results", results.toString(), "--model", "haiku",
						"--effort", "low", "--session-transcripts"),
					new StringReader("{\"type\":\"turn\",\"prompt\":\"remaining\"}\n{\"type\":\"complete\"}\n"),
					output, fixture.requests, () ->
					{
						if (runnerFailure.get() != null)
							throw new IOException("The resumed bridge failed", runnerFailure.get());
						broker.scan();
						Thread.yield();
					});
				requireThat(prompts, "harnessPrompts").isEqualTo(List.of("remaining"));
				List<JsonNode> records = output.toString().lines().map(fixture.json::readTree).toList();
				requireThat(records.getFirst().path("session_id").asString(), "resumedSession").isEqualTo(RESUMED_SESSION);
				List<String> replies = records.stream().filter(record -> record.path("type").asString().
					equals("turn_completed")).map(record -> record.path("assistant_response").asString()).toList();
				requireThat(replies, "turnReplies").isEqualTo(List.of("retained reply", "resumed reply"));
				requireThat(Files.readString(results.resolve("claude").resolve(RESUMED_SESSION).resolve("project/marker")),
					"resumedProject").isEqualTo("retained project");
			}
		}
	}

	/**
	 * Writes the one-turn evidence left by an ended runner, then gives up the slot.
	 *
	 * @param fixture the requesting session that owns the slots
	 * @param original the request that originally took this slot
	 * @return the retained slot
	 * @throws IOException if the retained evidence cannot be written
	 * @throws InterruptedException if the fixture cannot reserve its slot
	 */
	private static SessionSlots.Slot retainedTrialWithPrefix(Fixture fixture, UUID original)
		throws IOException, InterruptedException
	{
		Path base = SessionRequestAccess.requestedSlotsDirectory(fixture.nestedSession);
		SessionSlots slots = new SessionSlots(base, 1, new ProcessHandleLiveness(), fixture.fileLocks);
		SessionSlots.Slot slot = slots.acquire(false);
		NestedSessionManager manager = new NestedSessionManager(fixture.fileLocks, slot.root(), Clock.systemUTC(),
			Yielding.fileReplacer()).withFixedSlotId(slot.slotId());
		NestedSession session = manager.create("claude");
		manager.recordHarnessSession(session, RESUMED_SESSION);
		manager.writeTurnOutput(session, 1, new HarnessProcessResult(0, "retained event\n", ""));
		Path directory = session.directory();
		Files.writeString(directory.resolve("project/marker"), "retained project");
		Files.createDirectory(directory.resolve("initial-project"));
		Files.writeString(directory.resolve("artifacts/turn-1.reply.json"), """
			{"type":"turn_completed","exit_code":0,"error":"","assistant_response":"retained reply",
			 "assistant_messages":["retained reply"]}
			""");
		RetainedTrialPrefix.capture(directory, 1, Optional.empty());
		Files.writeString(directory.resolve("turn-checkpoint.json"),
			"{\"format_version\":1,\"turn_number\":1,\"state\":\"ready\"}");

		// Keep the PID but give the old runner a different start identity to represent an ended process.
		Path manifestFile = directory.resolve("session.json");
		String rawManifest = Files.readString(manifestFile);
		JsonNode manifest = fixture.json.readTree(rawManifest);
		String currentStart = manifest.path("owner_started_at").asString();
		String oldStart = currentStart + "0";
		Files.writeString(manifestFile, rawManifest.replace("\"owner_started_at\": \"" + currentStart + "\"",
			"\"owner_started_at\": \"" + oldStart + "\""));
		long pid = manifest.path("owner_pid").asLong();
		Files.writeString(slot.root().resolve("startup-permit.json"), fixture.json.createObjectNode().
			put("format_version", 1).put("generation", UUID.randomUUID().toString()).put("state", "claimed").
			put("manual_resume", false).put("broker_pid", pid).put("broker_started_at", oldStart).
			put("runner_pid", pid).put("runner_started_at", oldStart).toString());
		Files.writeString(slot.root().resolve(ORIGINAL_REQUEST_FILE), fixture.json.createObjectNode().
			put("format_version", 1).put("request_id", original.toString()).toString());
		slots.retain(slot);
		return slot;
	}

	/**
	 * Connects the broker's child process boundary to a production bridge with a scripted native harness.
	 *
	 * @param fixture the requesting session
	 * @param harness the name selected by the broker
	 * @param options the broker's child options
	 * @param prompts receives the native harness's prompts
	 * @param failure receives any exception on the bridge's thread
	 * @return the transport for the bridge's standard input and exit
	 * @throws IOException if the child transport cannot be opened
	 */
	private static FakeHarnessCli startGuardedBridge(Fixture fixture, String harness, List<String> options,
		List<String> prompts, AtomicReference<Exception> failure) throws IOException
	{
		List<String> arguments = new ArrayList<>(List.of("--artifact", fixture.root.toString()));
		arguments.addAll(options);
		String[] args = arguments.toArray(String[]::new);
		HarnessRunnerOptions parsed = HarnessRunnerOptions.parse(args, harness, HarnessTemporaryRoot.of(fixture.root));
		PipedReader input = new PipedReader();
		PipedWriter messages = new PipedWriter(input);
		FakeHarnessCli transport = new FakeHarnessCli(line ->
		{
			try
			{
				messages.write(line + "\n");
				messages.flush();
			}
			catch (IOException e)
			{
				throw new UncheckedIOException(e);
			}
		});
		transport.whenExited(() ->
		{
			try
			{
				messages.close();
			}
			catch (IOException e)
			{
				failure.compareAndSet(null, e);
			}
		});
		Thread.startVirtualThread(() ->
		{
			AtomicReference<ScriptedProcess> script = new AtomicReference<>();
			Turn turn = Turn.replies("resumed reply").
				withCheckpoint(_ -> script.get().reportHarnessSession(RESUMED_SESSION));
			try (input; messages; ScriptedProcess nativeHarness = new ScriptedProcess(List.of(turn), prompts))
			{
				script.set(nativeHarness);
				NestedSessionManager manager = NestedSessionManager.openResultsFolder(fixture.fileLocks,
					parsed.resultsFolder(), Clock.systemUTC(), Yielding.fileReplacer(), parsed.slotId(),
					HarnessRunnerOptions.startupPermit(args).orElseThrow()).withFixedSlotId(parsed.slotId());
				HarnessRunnerBridge.serve(() -> new LiveNestedSession(manager.resume(harness, parsed.resumeSlot()),
					nativeHarness, new TestHarnessRunnerScope()), manager, parsed, fixture.json, Yielding.fileReplacer(),
					input, new StringWriter());
			}
			catch (IOException | RuntimeException e)
			{
				failure.set(e);
			}
			finally
			{
				transport.exit();
			}
		});
		return transport;
	}

	/**
	 * Verifies that the original launch remains busy after cancellation while its accepted starter can still return a
	 * process and needs to finish releasing the slot.
	 *
	 * @throws IOException if the broker or fixture cannot read or write a request
	 */
	@Test(timeOut = 30_000)
	public void retainedTrialWaitsForDelayedStarter() throws IOException
	{
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		try (Fixture fixture = new Fixture();
		     SessionRequestBroker broker = new SessionRequestBroker(fixture.nestedSession, fixture.manager, fixture.options,
			     (_, _, _) ->
			     {
				     entered.countDown();
				     awaitStarted(release);
				     return new FakeHarnessCli(_ -> {});
			     }))
		{
			try
			{
				UUID original = UUID.randomUUID();
				Path lock = fixture.requests.resolve(original + ".lock");
				try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
				     FileLock _ = channel.lock())
				{
					ObjectNode launch = fixture.document().put("input", "stream");
					launch.remove("prompts");
					fixture.writeRequest(original.toString(), launch);
					broker.scan();
					awaitStarted(entered);
				}
				RetainedTrialRequest recovery = RetainedTrialRequest.discard(original, Optional.empty());
				String id = recovery.publish(fixture.json, fixture.requests);
				broker.scan();
				requireThat(fixture.await(original.toString()).path("status").asString(), "launchState").
					isEqualTo("stopped");
				requireThat(retainedTrialStatus(fixture, id, recovery), "delayedStarter").
					isEqualTo(RetainedTrialResponse.Status.BUSY);
			}
			finally
			{
				release.countDown();
			}
		}
	}

	/**
	 * Reads the recovery status written by the broker for the request envelope.
	 *
	 * @param fixture the requesting nested session
	 * @param id       the envelope identifier
	 * @param request  the request that the answer must identify
	 * @return the answer's classified status
	 * @throws IOException if the answer cannot be read or does not identify the request
	 */
	private static RetainedTrialResponse.Status retainedTrialStatus(Fixture fixture, String id,
		RetainedTrialRequest request) throws IOException
	{
		fixture.await(id);
		return RetainedTrialResponse.read(fixture.json, fixture.responses.resolve(id), request).orElseThrow().status();
	}

	/**
	 * Verifies that a broker of a nested session that resumes its harness session answers {@code ended} each request that
	 * the nested session made before its interruption and that has no response, and leaves each answered request as it
	 * was.
	 *
	 * @throws IOException if fixture or broker I/O fails
	 */
	@Test(timeOut = 30_000)
	public void resumedSessionEndsUnansweredRequests() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Path unanswered = Files.createDirectories(fixture.responses.resolve("a1"));
			Path worktree = Files.createDirectories(fixture.responses.resolve(WorktreeRequest.PREFIX + "b2"));
			Path answered = Files.createDirectories(fixture.responses.resolve("c3"));
			Files.writeString(answered.resolve("response.json"), "{\"status\":\"completed\"}");
			try (SessionRequestBroker broker = new SessionRequestBroker(fixture.nestedSession, fixture.manager,
				fixture.options, (_, _, _) ->
				{
					throw new AssertionError("No session was requested");
				}))
			{
				broker.endUnanswered();
			}
			JsonNode launch = fixture.json.readTree(Files.readString(unanswered.resolve("response.json")));
			requireThat(launch.path("status").asString(), "status").isEqualTo("failed");
			requireThat(launch.path("reason").asString(), "reason").isEqualTo("ended");
			requireThat(WorktreeResponse.read(fixture.json, worktree).orElseThrow().reason(), "worktreeReason").
				isEqualTo("ended");
			requireThat(Files.readString(answered.resolve("response.json")), "answered").
				isEqualTo("{\"status\":\"completed\"}");
		}
	}

	/**
	 * Verifies that a nested session that resumes its harness session continues the count of the requests that the nested
	 * session accepted before its interruption, so that a resumed nested session that has already made all the requests
	 * that its options allow is refused the next one.
	 *
	 * @throws IOException if fixture or broker I/O fails
	 */
	@Test(timeOut = 30_000)
	public void resumedSessionKeepsItsSpentAllowance() throws IOException
	{
		try (Fixture fixture = new Fixture("--session-requests", "1"))
		{
			AtomicReference<FakeHarnessCli> child = new AtomicReference<>();
			try (SessionRequestBroker broker = new SessionRequestBroker(fixture.nestedSession, fixture.manager,
				fixture.options, (_, _, _) ->
				{
					FakeHarnessCli process = new FakeHarnessCli(exitOnCompleteRecord(fixture.json, child), 1);
					child.set(process);
					return process;
				}))
			{
				broker.start(_ -> () ->
				{
				});
				fixture.request("first");
				broker.scan();
				fixture.await("first");
			}
			// The runner that resumes the nested session is a process of its own, with a manager of its own
			NestedSessionManager resuming = new NestedSessionManager(new FileLocks(), fixture.manager.root(),
				Clock.systemUTC(), Yielding.fileReplacer());
			try (SessionRequestBroker broker = new SessionRequestBroker(fixture.nestedSession, resuming, fixture.options,
				(_, _, _) ->
				{
					throw new AssertionError("A resumed nested session that spent its allowance started a session");
				}))
			{
				broker.endUnanswered();
				broker.start(_ -> () ->
				{
				});
				requireThat(SessionRequestAccess.furtherRequestRefusal(fixture.requests), "stated").
					isEqualTo(Optional.of(SessionRequestAccess.limitReason(1)));

				fixture.request("second");
				broker.scan();

				requireThat(fixture.await("second").path("reason").asString(), "reason").
					isEqualTo(SessionRequestAccess.limitReason(1));
			}
		}
	}

	/**
	 * Verifies that a launch that resumes a harness session that no retained nested session records is refused with
	 * status 2 and a reason that names the session, and that one that names an option that the nested session fixed at
	 * its start is refused before it makes a request.
	 *
	 * @throws IOException if fixture, broker or launch I/O fails
	 */
	@Test(timeOut = 30_000)
	public void refusesResumeThatCannotContinue() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Path again = Files.writeString(fixture.root.resolve("again.jsonl"),
				"{\"type\":\"turn\",\"prompt\":\"again\"}\n{\"type\":\"complete\"}\n");
			AtomicInteger checks = new AtomicInteger();
			try (SessionRequestBroker broker = new SessionRequestBroker(fixture.nestedSession, fixture.manager,
				fixture.options, (_, _, _) ->
				{
					throw new AssertionError("The refused request started a session");
				}))
			{
				StringWriter errors = new StringWriter();
				int status = SessionRequestForwarder.forward("claude", new String[]{RunnerArguments.RESUME_OPTION,
					RESUMED_SESSION, "--model", "haiku", "--effort", "low", "--input", again.toString()},
					new StringReader(""), fixture.requests, () ->
					{
						if (checks.incrementAndGet() > 1_000_000)
							throw new IOException("The request was not answered");
						broker.scan();
						Thread.yield();
					}, OutputStream.nullOutputStream(), new PrintWriter(errors, true));
				requireThat(status, "status").isEqualTo(HarnessRunnerOptions.REFUSED_LAUNCH_STATUS);
				requireThat(errors.toString(), "errors").contains(RESUMED_SESSION);

				InvalidArgumentsException fixed = expectThrows(InvalidArgumentsException.class,
					() -> SessionRequestForwarder.forward("claude", new String[]{RunnerArguments.RESUME_OPTION,
						RESUMED_SESSION, "--model", "haiku", "--effort", "low", "--deny-read", "/secret", "--input",
						again.toString()}, new StringReader(""), fixture.requests, () ->
					{
						throw new IOException("The refused launch made a request");
					}, OutputStream.nullOutputStream(), new PrintWriter(new StringWriter(), true)));
				requireThat(fixed.getMessage(), "fixed").contains("--deny-read");
			}
		}
	}

	/**
	 * Refuses a guarded resume if it does not identify a current retained trial checkpoint. In particular, the
	 * broker may never fall back to a public resume based only on the harness session ID.
	 *
	 * @throws IOException if the fixture, broker or launch I/O fails
	 */
	@Test
	public void refusesMissingGuardedTrialCheckpoint() throws IOException
	{
		try (Fixture fixture = new Fixture();
			SessionRequestBroker broker = new SessionRequestBroker(fixture.nestedSession, fixture.manager,
				fixture.options, (_, _, _) ->
				{
					throw new AssertionError("A missing guarded checkpoint cannot start a session");
				}))
		{
			String id = UUID.randomUUID().toString();
			Path lock = fixture.requests.resolve(id + ".lock");
			try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
			     FileLock _ = channel.lock())
			{
				fixture.writeRequest(id, guardedResume(fixture, UUID.randomUUID()));
				broker.scan();
				JsonNode response = fixture.await(id);
				requireThat(response.path("status").asString(), "status").isEqualTo("refused");
				requireThat(response.path("reason").asString(), "reason").contains("retained trial checkpoint");
			}
		}
	}

	/**
	 * Verifies that a guarded resume with its own envelope cannot enter admission after its sender ended and
	 * recovery settled the original launch.
	 *
	 * @throws IOException if the fixture or broker cannot read or write a request
	 */
	@Test(timeOut = 30_000)
	public void stopsDeadGuardedResumeBeforeAdmission() throws IOException
	{
		try (Fixture fixture = new Fixture();
			SessionRequestBroker broker = new SessionRequestBroker(fixture.nestedSession, fixture.manager,
				fixture.options, (_, _, _) ->
				{
					throw new AssertionError("A guarded resume with a dead sender cannot start a session");
				}))
		{
			UUID original = UUID.randomUUID();
			RetainedTrialRequest recovery = RetainedTrialRequest.status(original);
			String recoveryId = recovery.publish(fixture.json, fixture.requests);
			broker.scan();
			requireThat(retainedTrialStatus(fixture, recoveryId, recovery), "recovery").
				isEqualTo(RetainedTrialResponse.Status.ABSENT);

			String id = UUID.randomUUID().toString();
			Files.createFile(fixture.requests.resolve(id + ".lock"));
			fixture.writeRequest(id, guardedResume(fixture, original));
			broker.scan();
			JsonNode response = fixture.await(id);
			requireThat(response.path("status").asString(), "status").isEqualTo("stopped");
			requireThat(response.path("reason").asString(), "reason").contains("ended before admission");
		}
	}

	/**
	 * Builds a guarded resume archive manifest for a trial checkpoint.
	 *
	 * @param fixture the requesting session
	 * @param original the original launch's request ID
	 * @return the guarded resume manifest
	 */
	private static ObjectNode guardedResume(Fixture fixture, UUID original)
	{
		ObjectNode request = fixture.json.createObjectNode().put("format_version", 3).put("harness", "claude").
			put("resume", RESUMED_SESSION).put("model", "haiku").put("effort", "low").put("input", "stream").
			put("turn_copy", true);
		request.putObject("retained_trial_claim").put("request_id", original.toString()).
			put("slot_generation", UUID.randomUUID().toString()).put("completed_turns", 1);
		return request;
	}

	/**
	 * Verifies that {@code request-session --conversation} exits with status 2 when the nested session allows no
	 * requested session, and that the conversation's last line says that the request was refused.
	 *
	 * @throws IOException if fixture, broker or request I/O fails
	 */
	@Test(timeOut = 30_000)
	public void refusedConversationEnds() throws IOException
	{
		try (Fixture fixture = new Fixture("--session-requests", "0"))
		{
			Path folder = fixture.root.resolve("conversation");
			AtomicInteger checks = new AtomicInteger();
			StringWriter printed = new StringWriter();
			int status;
			try (SessionRequestBroker broker = new SessionRequestBroker(fixture.nestedSession, fixture.manager,
				fixture.options, (_, _, _) ->
				{
					throw new AssertionError("The refused request started a session");
				}))
			{
				status = SessionRequestCli.execute(new String[]{"--harness", "claude", "--project",
					fixture.project.toString(), "--model", "haiku", "--effort", "low", "--conversation",
					folder.toString()}, fixture.requests, Clock.systemUTC(), () ->
				{
					if (checks.incrementAndGet() > 1_000_000)
						throw new IOException("The request was not answered");
					broker.scan();
					Thread.yield();
				}, new PrintWriter(printed, true));
			}
			requireThat(status, "status").isEqualTo(2);
			requireThat(fromChild(fixture.json, folder), "fromChild").isEqualTo(List.of("{\"ended\":\"refused\"}"));
		}
	}

	/**
	 * Verifies that a session whose nested session's caller granted no session requests, which finds how to request one
	 * in CAT's rules, sees in its nested session's limits that the nested session allows none, and that a request that it
	 * makes with {@code request-session} anyway exits with status 2, starts no session, and is answered with a reason
	 * that names the options with which the nested session's caller allows sessions.
	 *
	 * @throws IOException if fixture, broker or request I/O fails
	 */
	@Test(timeOut = 30_000)
	public void requestSessionRefusedWithoutGrant() throws IOException
	{
		// Requirement: R-I9, R-I11
		try (Fixture fixture = new Fixture(new String[0]))
		{
			AtomicInteger checks = new AtomicInteger();
			StringWriter printed = new StringWriter();
			int status;
			try (SessionRequestBroker broker = new SessionRequestBroker(fixture.nestedSession, fixture.manager,
				fixture.options, (_, _, _) ->
				{
					throw new AssertionError("The refused request started a session");
				}))
			{
				broker.start(_ -> () ->
				{
				});
				requireThat(fixture.json.readTree(fixture.responses.resolve(SessionRequestAccess.LIMITS_FILE).toFile()).
					path("requests_allowed").asInt(-1), "requestsAllowed").isEqualTo(0);

				status = SessionRequestCli.execute(new String[]{"--harness", "claude", "--project",
					fixture.project.toString(), "--prompt", fixture.project.resolve("prompt").toString(), "--model",
					"haiku", "--effort", "low"}, fixture.requests, Clock.systemUTC(), () ->
				{
					if (checks.incrementAndGet() > 1_000_000)
						throw new IOException("The request was not answered");
					broker.scan();
					Thread.yield();
				}, new PrintWriter(printed, true));
			}
			requireThat(status, "status").isEqualTo(HarnessRunnerOptions.REFUSED_LAUNCH_STATUS);
			JsonNode response = fixture.json.readTree(Path.of(printed.toString().strip()).toFile());
			requireThat(response.path("status").asString(), "responseStatus").isEqualTo("refused");
			requireThat(response.path("reason").asString(), "reason").
				isEqualTo(SessionRequestAccess.NO_REQUESTS_REASON).contains("--session-requests").
				contains("--request-depth");
		}
	}

	/**
	 * Verifies that two sessions that run one after the other in the one slot of a nested session build their nested
	 * session at the same paths, which are in the slot of the requesting nested session's directory, so that the paths
	 * that the harness puts into its instructions repeat from one session to the next.
	 *
	 * @throws IOException if fixture or broker I/O fails
	 */
	@Test(timeOut = 30_000)
	public void sessionsOfOneSlotShareSessionPaths() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		try (Fixture fixture = new Fixture("--session-requests", "2", "--max-concurrency", "1"))
		{
			List<SlotNestedSession> nestedSessions = new CopyOnWriteArrayList<>();
			try (SessionRequestBroker broker = new SessionRequestBroker(fixture.nestedSession, fixture.manager,
				fixture.options, (_, options, _) -> inSlot(fixture, fileLocks, options, nestedSessions, 0)))
			{
				fixture.request("first");
				broker.scan();
				fixture.await("first");
				fixture.request("second");
				broker.scan();
				fixture.await("second");
			}
			requireThat(nestedSessions, "nestedSessions").size().isEqualTo(2);
			SlotNestedSession first = nestedSessions.getFirst();
			SlotNestedSession second = nestedSessions.getLast();
			requireThat(second.resultsFolder(), "resultsFolder").isEqualTo(first.resultsFolder());
			requireThat(second.nestedSession().id(), "slotId").isEqualTo(first.nestedSession().id());
			requireThat(second.nestedSession().directory(), "directory").isEqualTo(first.nestedSession().directory());
			requireThat(second.nestedSession().directory().resolve("home"), "home").
				isEqualTo(first.nestedSession().directory().resolve("home"));
			requireThat(second.nestedSession().directory().resolve("project"), "project").
				isEqualTo(first.nestedSession().directory().resolve("project"));
			requireThat(first.resultsFolder(),
				"resultsFolder").isEqualTo(SessionRequestAccess.requestedSlotsDirectory(fixture.nestedSession).resolve("d1"));
		}
	}

	/**
	 * Verifies recovery that wins the slot after a broker starts a child prevents that delayed child from creating a
	 * nested session in the recovered slot.
	 *
	 * @throws IOException if fixture or broker I/O fails
	 * @throws InterruptedException if observing the child is interrupted
	 */
	@Test(timeOut = 30_000)
	public void recoveredSlotRefusesDelayedRunner() throws IOException, InterruptedException
	{
		// Requirement: R-I9
		try (Fixture fixture = new Fixture())
		{
			CountDownLatch childStarting = new CountDownLatch(1);
			CountDownLatch allowChild = new CountDownLatch(1);
			CountDownLatch childPrepared = new CountDownLatch(1);
			CountDownLatch allowReturn = new CountDownLatch(1);
			AtomicReference<Path> resultsFolder = new AtomicReference<>();
			AtomicReference<IOException> startupFailure = new AtomicReference<>();
			try (SessionRequestBroker broker = new SessionRequestBroker(fixture.nestedSession, fixture.manager,
				fixture.options, (_, options, _) ->
				{
					resultsFolder.set(Path.of(option(options, "--results")));
					childStarting.countDown();
					try
					{
						allowChild.await();
					}
					catch (InterruptedException e)
					{
						Thread.currentThread().interrupt();
						throw new IOException("Child start was interrupted", e);
					}
					FakeHarnessCli child;
					try
					{
						String slotId = option(options, "--slot-id");
						NestedSessionManager manager = NestedSessionManager.openResultsFolder(fixture.fileLocks,
							resultsFolder.get(), Clock.systemUTC(), Yielding.fileReplacer(), slotId,
							option(options, RunnerArguments.STARTUP_PERMIT_OPTION)).withFixedSlotId(slotId);
						try
						{
							manager.create("claude");
						}
						finally
						{
							manager.closeStartupLease();
						}
						child = new FakeHarnessCli(_ ->
						{
						});
					}
					catch (IOException e)
					{
						startupFailure.set(e);
						AtomicReference<FakeHarnessCli> refusedChild = new AtomicReference<>();
						child = new FakeHarnessCli(exitOnCompleteRecord(fixture.json, refusedChild),
							HarnessRunnerOptions.REFUSED_LAUNCH_STATUS);
						refusedChild.set(child);
					}
					childPrepared.countDown();
					try
					{
						allowReturn.await();
					}
					catch (InterruptedException e)
					{
						Thread.currentThread().interrupt();
						throw new IOException("Child return was interrupted", e);
					}
					return child;
				}))
			{
				fixture.request("delayed");
				broker.scan();
				requireThat(childStarting.await(10, TimeUnit.SECONDS), "childStarting").isTrue();
				Path slotBase = SessionRequestAccess.requestedSlotsDirectory(fixture.nestedSession);
				SessionSlots recovery = new SessionSlots(slotBase, 1, new HarnessProcessLiveness()
				{
					@Override
					public boolean isAlive(long pid, String start)
					{
						return false;
					}

					@Override
					public boolean terminate(long pid, String start)
					{
						return false;
					}
				});
				SessionSlots.Slot recovered = recovery.acquire(false);
				recovery.release(recovered);
				Path replacementRoot = slotBase.resolve("d1");
				Files.createDirectories(replacementRoot);
				ObjectNode replacementBinding = fixture.json.createObjectNode().put("format_version", 1).
					put("request_id", "replacement");
				Files.writeString(replacementRoot.resolve(ORIGINAL_REQUEST_FILE),
					fixture.json.writeValueAsString(replacementBinding));
				allowChild.countDown();
				requireThat(childPrepared.await(10, TimeUnit.SECONDS), "childPrepared").isTrue();
				Path manifest = resultsFolder.get().resolve("claude").resolve("s-1").resolve("session.json");
				try
				{
					requireThat(Files.exists(manifest), "delayedChildManifest").isFalse();
						requireThat(startupFailure.get(), "startupFailure").isNotNull();
				}
				finally
				{
					allowReturn.countDown();
				}
				fixture.await("delayed");
			}
		}
	}

	/**
	 * Verifies that a session that takes a slot finds nothing of the previous session's files, neither in its nested
	 * session's home and project nor beside them.
	 *
	 * @throws IOException if fixture or broker I/O fails
	 */
	@Test(timeOut = 30_000)
	public void sessionFindsNothingOfPreviousSession() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		try (Fixture fixture = new Fixture("--session-requests", "2", "--max-concurrency", "1"))
		{
			List<SlotNestedSession> nestedSessions = new CopyOnWriteArrayList<>();
			try (SessionRequestBroker broker = new SessionRequestBroker(fixture.nestedSession, fixture.manager,
				fixture.options, (_, options, _) -> inSlot(fixture, fileLocks, options, nestedSessions, 0)))
			{
				fixture.request("first");
				broker.scan();
				fixture.await("first");
				fixture.request("second");
				broker.scan();
				fixture.await("second");
			}
			requireThat(nestedSessions, "nestedSessions").size().isEqualTo(2);
			SlotNestedSession second = nestedSessions.getLast();
			requireThat(second.entriesAtStart().stream().sorted().toList(), "entriesAtStart").
				isEqualTo(List.of(ORIGINAL_REQUEST_FILE, "startup-permit.json"));
			requireThat(second.originalRequestId(), "originalRequestId").isEqualTo("second");
		}
	}

	/**
	 * Verifies kept receipts remain readable after a second request reuses their slot and the broker closes.
	 *
	 * @throws IOException if fixture, broker or retained evidence I/O fails
	 */
	@Test(timeOut = 30_000)
	public void keptReceiptSurvivesSlotReuse() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		try (Fixture fixture = new Fixture("--session-requests", "2", "--max-concurrency", "1"))
		{
			List<SlotNestedSession> nestedSessions = new CopyOnWriteArrayList<>();
			Iterator<String> markers = List.of("first", "second").iterator();
			JsonNode firstResponse;
			JsonNode secondResponse;
			try (SessionRequestBroker broker = new SessionRequestBroker(fixture.nestedSession, fixture.manager,
				fixture.options, (_, options, _) -> inSlotWithReceipt(fixture, fileLocks, options, nestedSessions,
					markers.next())))
			{
				fixture.writeRequest("first", fixture.document());
				broker.scan();
				firstResponse = fixture.await("first");
				fixture.writeRequest("second", fixture.document());
				broker.scan();
				secondResponse = fixture.await("second");
			}

			Path firstReceiptPath = Path.of(firstResponse.path("receipt").asString());
			Path secondReceiptPath = Path.of(secondResponse.path("receipt").asString());
			Path firstSession = Path.of(firstResponse.path("results_copy").asString());
			Path secondSession = Path.of(secondResponse.path("results_copy").asString());
			requireThat(Files.isDirectory(firstSession.resolve("project")), "firstCopiedProject").isTrue();
			requireThat(Files.isRegularFile(firstSession.resolve("artifacts/bridge-transcript.jsonl")),
				"firstCopiedRawResult").isTrue();
			requireThat(Files.isDirectory(secondSession.resolve("project")), "secondCopiedProject").isTrue();
			requireThat(Files.isRegularFile(secondSession.resolve("artifacts/bridge-transcript.jsonl")),
				"secondCopiedRawResult").isTrue();
			HarnessEvaluationResult firstReceipt = NestedSessionReceipt.read(fixture.json, firstReceiptPath,
				firstSession.resolve("project"));
			HarnessEvaluationResult secondReceipt = NestedSessionReceipt.read(fixture.json, secondReceiptPath,
				secondSession.resolve("project"));
			requireThat(firstReceipt.retainedProject(), "firstRetainedProject").isEqualTo(firstSession.resolve("project"));
			requireThat(secondReceipt.retainedProject(), "secondRetainedProject").isEqualTo(secondSession.resolve("project"));
			requireThat(Files.readString(firstReceipt.retainedProject().resolve("session.txt")), "firstProjectMarker").
				isEqualTo("first");
			requireThat(Files.readString(secondReceipt.retainedProject().resolve("session.txt")), "secondProjectMarker").
				isEqualTo("second");
			requireThat(firstReceipt.sessionTranscripts(), "firstNativeTranscripts").size().isEqualTo(1);
			requireThat(secondReceipt.sessionTranscripts(), "secondNativeTranscripts").size().isEqualTo(1);
			requireThat(Files.readString(firstReceipt.sessionTranscripts().getFirst()), "firstNativeTranscript").
				isEqualTo("first native bytes");
			requireThat(Files.readString(secondReceipt.sessionTranscripts().getFirst()), "secondNativeTranscript").
				isEqualTo("second native bytes");
			requireThat(Files.readString(firstReceipt.rawResult()), "firstRawResult").isEqualTo("first response bytes");
			requireThat(Files.readString(secondReceipt.rawResult()), "secondRawResult").isEqualTo("second response bytes");
			requireThat(Files.readString(Path.of(firstResponse.path("transcript").asString())), "firstPublishedTranscript").
				isEqualTo(Files.readString(firstReceipt.rawResult()));
			requireThat(Files.readString(Path.of(secondResponse.path("transcript").asString())), "secondPublishedTranscript").
				isEqualTo(Files.readString(secondReceipt.rawResult()));
			requireThat(Files.exists(firstSession.resolve("home")), "firstHome").isFalse();
			requireThat(Files.exists(secondSession.resolve("home")), "secondHome").isFalse();
			ObjectNode firstReceiptDocument = (ObjectNode) fixture.json.readTree(firstReceiptPath.toFile());
			ObjectNode secondReceiptDocument = (ObjectNode) fixture.json.readTree(secondReceiptPath.toFile());
			requireThat(firstReceiptDocument.path("requested_sessions").get(0).path("response").asString(),
				"firstRequestedResponse").isEqualTo("responses/nested/response.json");
			requireThat(secondReceiptDocument.path("requested_sessions").get(0).path("response").asString(),
				"secondRequestedResponse").isEqualTo("responses/nested/response.json");
			requireThat(fixture.json.readTree(firstSession.resolve("responses/nested/response.json").toFile()).
				path("id").asString(), "firstRequestedResponseId").isEqualTo("first");
			requireThat(fixture.json.readTree(secondSession.resolve("responses/nested/response.json").toFile()).
				path("id").asString(), "secondRequestedResponseId").isEqualTo("second");
		}
	}

	/**
	 * Verifies that a slot is not reused while a process that its previous session's nested session recorded remains
	 * alive: the nested session's runner asks the process to end, waits while it lives, and starts the next session once
	 * it has ended, in a slot that holds nothing of the previous session.
	 *
	 * @throws IOException          if fixture or broker I/O fails
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = 30_000)
	public void slotWaitsForPreviousSessionsProcesses() throws IOException, InterruptedException
	{
		FileLocks fileLocks = new FileLocks();
		try (Fixture fixture = new Fixture("--session-requests", "2", "--max-concurrency", "1"))
		{
			List<SlotNestedSession> nestedSessions = new CopyOnWriteArrayList<>();
			ScriptedLiveness liveness = new ScriptedLiveness();
			try (SessionRequestBroker broker = new SessionRequestBroker(fixture.nestedSession, fixture.manager,
				fixture.options,
				(_, options, _) -> inSlot(fixture, fileLocks, options, nestedSessions, 4242), RequestLimits.IN_NESTED_SESSION,
				liveness))
			{
				fixture.request("first");
				broker.scan();
				fixture.await("first");
				fixture.request("second");
				broker.scan();
				requireThat(liveness.awaitPolls(4), "polled").isTrue();
				requireThat(nestedSessions, "nestedSessionsWhileAlive").size().isEqualTo(1);
				requireThat(liveness.terminations(), "terminations").isGreaterThan(0);
				liveness.end();
				fixture.await("second");
			}
			requireThat(nestedSessions, "nestedSessions").size().isEqualTo(2);
			SlotNestedSession second = nestedSessions.getLast();
			requireThat(second.entriesAtStart().stream().sorted().toList(), "entriesAtStart").
				isEqualTo(List.of(ORIGINAL_REQUEST_FILE, "startup-permit.json"));
			requireThat(second.originalRequestId(), "originalRequestId").isEqualTo("second");
		}
	}

	/**
	 * Verifies that a development session and a held-out session take slots of their own: the held-out session runs
	 * in the held-out part while the development session holds the only development slot.
	 *
	 * @throws IOException          if fixture or broker I/O fails
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = 30_000)
	public void heldOutSessionTakesHeldOutSlot() throws IOException, InterruptedException
	{
		FileLocks fileLocks = new FileLocks();
		try (Fixture fixture = new Fixture("--session-requests", "2", "--max-concurrency", "1"))
		{
			List<SlotNestedSession> nestedSessions = new CopyOnWriteArrayList<>();
			CountDownLatch developmentStarted = new CountDownLatch(1);
			CountDownLatch release = new CountDownLatch(1);
			AtomicInteger calls = new AtomicInteger();
			try (SessionRequestBroker broker = new SessionRequestBroker(fixture.nestedSession, fixture.manager,
				fixture.options, (_, options, _) ->
				{
					FakeHarnessCli process = inSlot(fixture, fileLocks, options, nestedSessions, 0);
					if (calls.getAndIncrement() == 0)
					{
						developmentStarted.countDown();
						awaitStarted(release);
					}
					return process;
				}))
			{
				fixture.request("development");
				broker.scan();
				awaitStarted(developmentStarted);
				ObjectNode heldOut = fixture.document().put("held_out", true);
				fixture.writeRequest("held-out", heldOut);
				broker.scan();
				requireThat(fixture.await("held-out").path("status").asString(), "heldOutStatus").
					isEqualTo("completed");
				release.countDown();
				requireThat(fixture.await("development").path("status").asString(), "developmentStatus").
					isEqualTo("completed");
			}
			Path slots = SessionRequestAccess.requestedSlotsDirectory(fixture.nestedSession);
			requireThat(nestedSessions, "nestedSessions").size().isEqualTo(2);
			requireThat(nestedSessions.getFirst().resultsFolder(), "development").isEqualTo(slots.resolve("d1"));
			requireThat(nestedSessions.getLast().resultsFolder(), "heldOut").isEqualTo(slots.resolve("h1"));
		}
	}

	/**
	 * Verifies that the copy of a session's results copy, which the requester reads, survives the next session's use of
	 * the slot: each response keeps the files of its own session.
	 *
	 * @throws IOException if fixture or broker I/O fails
	 */
	@Test(timeOut = 30_000)
	public void resultsCopySurvivesSlotReuse() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		try (Fixture fixture = new Fixture("--session-requests", "2", "--max-concurrency", "1"))
		{
			List<SlotNestedSession> nestedSessions = new CopyOnWriteArrayList<>();
			try (SessionRequestBroker broker = new SessionRequestBroker(fixture.nestedSession, fixture.manager,
				fixture.options, (_, options, _) -> inSlot(fixture, fileLocks, options, nestedSessions, 0)))
			{
				for (String id : List.of("first", "second"))
				{
					fixture.writeRequest(id, fixture.document());
					broker.scan();
					fixture.await(id);
				}
				requireThat(nestedSessions, "nestedSessions").size().isEqualTo(2);
				// The second session has taken the slot that the first one left, and the first one's copy remains
				requireThat(Files.readString(fixture.responses.resolve("first/results-copy/result.txt")), "first").
					isEqualTo("1");
				requireThat(Files.readString(fixture.responses.resolve("second/results-copy/result.txt")), "second").
					isEqualTo("2");
			}
		}
	}

	/**
	 * Verifies that a nested session never has more sessions of one part running at once than its concurrency limit, and
	 * that it does run as many as the limit when requests wait.
	 *
	 * @throws IOException          if fixture or broker I/O fails
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = 30_000)
	public void runsNoMoreSessionsThanSlots() throws IOException, InterruptedException
	{
		FileLocks fileLocks = new FileLocks();
		try (Fixture fixture = new Fixture("--session-requests", "4", "--max-concurrency", "2"))
		{
			List<SlotNestedSession> nestedSessions = new CopyOnWriteArrayList<>();
			Set<Path> active = ConcurrentHashMap.newKeySet();
			AtomicInteger running = new AtomicInteger();
			AtomicInteger peak = new AtomicInteger();
			CountDownLatch full = new CountDownLatch(2);
			Semaphore gate = new Semaphore(0);
			try (SessionRequestBroker broker = new SessionRequestBroker(fixture.nestedSession, fixture.manager,
				fixture.options, (_, options, _) ->
				{
					Path resultsFolder = Path.of(option(options, "--results"));
					requireThat(active.add(resultsFolder), "slotFree").isTrue();
					peak.accumulateAndGet(running.incrementAndGet(), Math::max);
					full.countDown();
					try
					{
						if (!gate.tryAcquire(10, TimeUnit.SECONDS))
							throw new IOException("The test did not release the session");
					}
					catch (InterruptedException e)
					{
						Thread.currentThread().interrupt();
						throw new IOException("Interrupted while the session held its slot", e);
					}
					FakeHarnessCli process = inSlot(fixture, fileLocks, options, nestedSessions, 0);
					// The broker releases the slot after the session's exit, so the session must stop counting as
					// running before that exit is observed
					process.whenExited(() ->
					{
						running.decrementAndGet();
						active.remove(resultsFolder);
					});
					return process;
				}))
			{
				List<String> ids = List.of("a", "b", "c", "d");
				for (String id : ids)
					fixture.request(id);
				broker.scan();
				awaitStarted(full);
				gate.release(4);
				for (String id : ids)
					requireThat(fixture.await(id).path("status").asString(), id).isEqualTo("completed");
			}
			requireThat(peak.get(), "peak").isEqualTo(2);
			requireThat(nestedSessions, "nestedSessions").size().isEqualTo(4);
		}
	}

	/**
	 * Verifies that a request that waits for a free slot is answered when the broker closes, and that closing does
	 * not wait for a slot to be released.
	 *
	 * @throws IOException          if fixture or broker I/O fails
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = 30_000)
	public void closeAnswersRequestWaitingForSlot() throws IOException, InterruptedException
	{
		try (Fixture fixture = new Fixture("--session-requests", "2", "--max-concurrency", "1"))
		{
			CountDownLatch started = new CountDownLatch(1);
			AtomicInteger calls = new AtomicInteger();
			try (SessionRequestBroker broker = new SessionRequestBroker(fixture.nestedSession, fixture.manager,
				fixture.options, (_, _, _) ->
				{
					calls.incrementAndGet();
					started.countDown();
					return new FakeHarnessCli(_ ->
					{
					});
				}))
			{
				fixture.request("running");
				broker.scan();
				awaitStarted(started);
				fixture.request("waiting");
				broker.scan();
			}
			requireThat(fixture.await("waiting").path("status").asString(), "waiting").isEqualTo("stopped");
			requireThat(calls.get(), "sessionsStarted").isEqualTo(1);
		}
	}

	/**
	 * Verifies that a failure that the broker reports to the caller of {@code scan()} is not thrown again when the
	 * caller's try-with-resources statement closes the broker, as the request server's statement does, so that the
	 * caller receives that failure. The failure is the broker's failure to answer a request whose runner did not start:
	 * a directory in the place of the request's response file stands for whatever keeps the broker from writing it.
	 *
	 * @throws IOException if the broker reports the failed answer, as expected
	 */
	@Test(timeOut = 30_000, expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*response\\.json.*")
	public void closeDoesNotRepeatScanFailure() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			try (SessionRequestBroker broker = new SessionRequestBroker(fixture.nestedSession, fixture.manager,
				fixture.options, (_, _, directory) ->
				{
					Files.createDirectory(directory.resolve("response.json"));
					throw new IOException("The runner did not start");
				}))
			{
				fixture.request("first");
				// The broker answers the request on a thread of its own, so a later scan reports the failed answer
				long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
				while (System.nanoTime() < deadline)
				{
					broker.scan();
					Thread.yield();
				}
				throw new AssertionError("The broker did not report that it could not answer the request");
			}
		}
	}

	/**
	 * Verifies that the broker goes on serving after it cannot empty a slot that a session gives back: the first
	 * session's runner recorded that it was about to start a harness and recorded nothing more, so the slot cannot be
	 * emptied once that session ends. The next request, which waits for that slot, fails with the reason, the scans that
	 * follow report no failure, and once the nested session records that no harness started, a third request runs in the
	 * slot.
	 *
	 * @throws IOException if the fixture cannot be created or a request cannot be served
	 */
	@Test(timeOut = 30_000)
	public void servesAfterSlotCannotBeReleased() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		try (Fixture fixture = new Fixture("--session-requests", "3", "--max-concurrency", "1"))
		{
			List<SlotNestedSession> nestedSessions = new CopyOnWriteArrayList<>();
			try (SessionRequestBroker broker = new SessionRequestBroker(fixture.nestedSession, fixture.manager,
				fixture.options, (_, options, _) ->
				{
					if (nestedSessions.isEmpty())
						return inSlotWithUnknownLaunch(fixture, fileLocks, options, nestedSessions);
					return inSlot(fixture, fileLocks, options, nestedSessions, 0);
				}))
			{
				fixture.request("first");
				broker.scan();
				requireThat(fixture.await("first").path("status").asString(), "first").isEqualTo("completed");
				fixture.request("second");
				broker.scan();
				JsonNode refused = fixture.await("second");
				requireThat(refused.path("status").asString(), "second").isEqualTo("failed");
				requireThat(refused.path("reason").asString(), "reason").contains("Harness launch state is unknown");

				SlotNestedSession leftover = nestedSessions.getFirst();
				new NestedSessionManager(fileLocks, leftover.resultsFolder(), Clock.systemUTC(), Yielding.fileReplacer()).
					recordNoHarnessProcess(leftover.nestedSession());
				fixture.request("third");
				broker.scan();
				requireThat(fixture.await("third").path("status").asString(), "third").isEqualTo("completed");
			}
			requireThat(nestedSessions, "nestedSessions").size().isEqualTo(2);
		}
	}

	/**
	 * Verifies that the broker keeps every failure to answer a request, not only the last one: two requests whose
	 * runners cannot start each fail to receive their answer, and the next scan reports both failures. A directory in
	 * the place of each request's response file stands for whatever keeps the broker from writing the answer.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test(timeOut = 30_000)
	public void keepsEveryFailedAnswer() throws IOException
	{
		try (Fixture fixture = new Fixture("--session-requests", "2", "--max-concurrency", "2"))
		{
			AtomicInteger started = new AtomicInteger();
			try (SessionRequestBroker broker = new SessionRequestBroker(fixture.nestedSession, fixture.manager,
				fixture.options, (_, _, directory) ->
				{
					Files.createDirectory(directory.resolve("response.json"));
					started.incrementAndGet();
					throw new IOException("The runner did not start");
				}))
			{
				fixture.request("first");
				fixture.request("second");
				broker.scan();
				// Each request gives its slot back after the broker tried to answer it
				Path slots = SessionRequestAccess.requestedSlotsDirectory(fixture.nestedSession);
				long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
				while (started.get() < 2 || hasSlotDirectories(slots))
				{
					if (System.nanoTime() > deadline)
						throw new AssertionError("The broker did not try to answer both requests");
					Thread.yield();
				}
				List<String> failures = new ArrayList<>();
				try
				{
					broker.scan();
				}
				catch (IOException e)
				{
					failures.add(e.getMessage());
					for (Throwable suppressed : e.getSuppressed())
						failures.add(suppressed.getMessage());
				}
				requireThat(failures, "failures").size().isEqualTo(2);
				requireThat(String.join("\n", failures), "failures").
					contains(fixture.responses.resolve("first").toString()).
					contains(fixture.responses.resolve("second").toString());
			}
		}
	}

	/**
	 * Indicates whether the slots directory still contains a slot. Startup lock files stay beside the slots.
	 *
	 * @param slots the slots directory
	 * @return {@code true} if a slot directory remains
	 * @throws IOException if the directory cannot be listed
	 */
	private static boolean hasSlotDirectories(Path slots) throws IOException
	{
		if (!Files.isDirectory(slots, LinkOption.NOFOLLOW_LINKS))
			return false;
		try (Stream<Path> entries = Files.list(slots))
		{
			return entries.anyMatch(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS));
		}
	}

	/**
	 * Starts a double of a requested runner that builds its nested session in its slot and completes, as {@code inSlot}
	 * does, and leaves the nested session recording that the runner was about to start a harness, as a runner killed at
	 * that moment leaves it.
	 *
	 * @param fixture        the requesting nested session
	 * @param fileLocks      the file locks of the requested nested session
	 * @param options        the requested runner's options
	 * @param nestedSessions receives what the runner finds in its slot and builds there
	 * @return the runner
	 * @throws IOException if the nested session cannot be created or its launch state cannot be recorded
	 */
	private static FakeHarnessCli inSlotWithUnknownLaunch(Fixture fixture, FileLocks fileLocks, List<String> options,
		List<SlotNestedSession> nestedSessions) throws IOException
	{
		return inSlot(fixture, fileLocks, options, nestedSessions, NestedSessionManager::markHarnessStarting);
	}

	/**
	 * Starts a double of a requested runner that builds its nested session in the results folder and under the slot ID
	 * that the broker names, as the real runner does, and completes.
	 *
	 * @param fixture        the requesting nested session
	 * @param fileLocks      the file locks of the requested nested session
	 * @param options        the requested runner's options
	 * @param nestedSessions receives what the runner finds in its slot and builds there
	 * @param harnessPid     the identifier of the harness process that the nested session records, or {@code 0} to record
	 *                       none
	 * @return the runner
	 * @throws IOException if the nested session cannot be created or the runner refuses to reuse the slot
	 */
	private static FakeHarnessCli inSlot(Fixture fixture, FileLocks fileLocks, List<String> options,
		List<SlotNestedSession> nestedSessions, long harnessPid) throws IOException
	{
		return inSlot(fixture, fileLocks, options, nestedSessions, (manager, nestedSession) ->
		{
			if (harnessPid != 0)
			{
				manager.markHarnessStarting(nestedSession);
				manager.recordHarnessProcess(nestedSession, harnessPid, "0f1e2d3c4b5a69788796a5b4c3d2e1f0t5125");
			}
			else
			{
				// The session ends normally, so its harness reported its identifier and its nested session is complete
				manager.recordHarnessSession(nestedSession, UUID.randomUUID().toString());
				manager.markCompleted(nestedSession);
			}
		});
	}

	/**
	 * Starts a double of a requested runner that builds its nested session in the results folder and under the slot ID
	 * that the broker names, records what {@code state} records in the nested session, and completes.
	 *
	 * @param fixture        the requesting nested session
	 * @param fileLocks      the file locks of the requested nested session
	 * @param options        the requested runner's options
	 * @param nestedSessions receives what the runner finds in its slot and builds there
	 * @param state          records the nested session's state
	 * @return the runner
	 * @throws IOException if the nested session cannot be created or the runner refuses to reuse the slot
	 */
	private static FakeHarnessCli inSlot(Fixture fixture, FileLocks fileLocks, List<String> options,
		List<SlotNestedSession> nestedSessions, NestedSessionState state) throws IOException
	{
		Path resultsFolder = Path.of(option(options, "--results"));
		List<String> entries;
		try (Stream<Path> paths = Files.list(resultsFolder))
		{
			entries = paths.map(path -> path.getFileName().toString()).filter(name -> !name.startsWith(".")).toList();
		}
		String originalRequestId = fixture.json.readTree(resultsFolder.resolve(ORIGINAL_REQUEST_FILE).toFile()).
			path("request_id").stringValue("");
		NestedSessionManager manager = new NestedSessionManager(fileLocks, resultsFolder, Clock.systemUTC(),
			Yielding.fileReplacer()).withFixedSlotId(option(options, "--slot-id"));
		NestedSession nestedSession = manager.create("claude");
		state.record(manager, nestedSession);
		nestedSessions.add(new SlotNestedSession(resultsFolder, nestedSession, entries, originalRequestId));
		Files.writeString(nestedSession.directory().resolve("left-behind"), "left behind");
		Files.writeString(nestedSession.directory().resolve("result.txt"), String.valueOf(nestedSessions.size()));
		Path transcript = Files.writeString(Path.of(option(options, "--output")), "bridge");
		NestedSessionReceipt.write(fixture.json, Yielding.fileReplacer(), Path.of(option(options, "--receipt")),
			nestedSession, transcript, List.of());
		AtomicReference<FakeHarnessCli> processReference = new AtomicReference<>();
		FakeHarnessCli process = new FakeHarnessCli(exitOnCompleteRecord(fixture.json, processReference));
		processReference.set(process);
		return process;
	}

	/**
	 * Records the state of a requested runner's nested session.
	 */
	@FunctionalInterface
	private interface NestedSessionState
	{
		/**
		 * Records the nested session's state.
		 *
		 * @param manager       the nested session's manager
		 * @param nestedSession the nested session
		 * @throws IOException if the state cannot be recorded
		 */
		void record(NestedSessionManager manager, NestedSession nestedSession) throws IOException;
	}

	/**
	 * Adds distinct requested-session evidence to the current slot and writes its receipt for the slot-reuse test.
	 *
	 * @param fixture the requesting nested session
	 * @param fileLocks the requesting nested session's file locks
	 * @param options the requested runner's options
	 * @param nestedSessions receives the nested session built in its slot
	 * @param marker the request identity and retained project content
	 * @return the fake runner
	 * @throws IOException if the nested session's evidence cannot be written
	 */
	private static FakeHarnessCli inSlotWithReceipt(Fixture fixture, FileLocks fileLocks, List<String> options,
		List<SlotNestedSession> nestedSessions, String marker) throws IOException
	{
		FakeHarnessCli process = inSlot(fixture, fileLocks, options, nestedSessions, 0);
		NestedSession nestedSession = nestedSessions.getLast().nestedSession();
		Files.writeString(nestedSession.directory().resolve("project/session.txt"), marker);
		Path nativeTranscript = Files.writeString(Files.createDirectories(
			nestedSession.sessionTranscripts().resolve("subagents")).resolve("native.jsonl"), marker + " native bytes");
		Path nestedResponse = Files.createDirectories(SessionRequestAccess.responsesDirectory(nestedSession).
			resolve("nested")).resolve("response.json");
		ObjectNode nestedResponseDocument = fixture.json.createObjectNode().put("id", marker).
			put("harness", "harness").put("status", "completed").put("session_id", "child-" + marker);
		fixture.json.writeValue(nestedResponse.toFile(), nestedResponseDocument);
		Path rawResult = Path.of(option(options, "--output"));
		Files.writeString(rawResult, marker + " response bytes");
		NestedSessionReceipt.write(fixture.json, Yielding.fileReplacer(), Path.of(option(options, "--receipt")),
			nestedSession, rawResult, List.of(nativeTranscript));
		return process;
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
	 * What a session finds in its slot, and the nested session that it builds there.
	 *
	 * @param resultsFolder     the results folder that the session received
	 * @param nestedSession     the nested session that it built
	 * @param entriesAtStart    the names of the entries of the results folder when the session started
	 * @param originalRequestId the request that the slot's record names at startup
	 */
	private record SlotNestedSession(Path resultsFolder, NestedSession nestedSession, List<String> entriesAtStart,
		String originalRequestId)
	{
	}

	/**
	 * Tells which processes live, as the requesting nested session's runner would see them.
	 */
	private static final class ScriptedLiveness implements HarnessProcessLiveness
	{
		private final Semaphore polls = new Semaphore(0);
		private final AtomicInteger terminations = new AtomicInteger();
		private volatile boolean alive = true;

		/**
		 * Creates a liveness in which every recorded process lives.
		 */
		private ScriptedLiveness()
		{
		}

		/**
		 * Makes every recorded process end.
		 */
		private void end()
		{
			alive = false;
		}

		/**
		 * Waits until the runner has checked for a process the given number of times.
		 *
		 * @param count the number of checks
		 * @return {@code true} if the runner has checked that often
		 * @throws InterruptedException if the test is interrupted
		 */
		private boolean awaitPolls(int count) throws InterruptedException
		{
			return polls.tryAcquire(count, 10, TimeUnit.SECONDS);
		}

		/**
		 * Returns how many times the runner asked a process to end.
		 *
		 * @return the number of requests
		 */
		private int terminations()
		{
			return terminations.get();
		}

		@Override
		public boolean isAlive(long pid, String start)
		{
			polls.release();
			return alive;
		}

		@Override
		public boolean terminate(long pid, String start)
		{
			terminations.incrementAndGet();
			return false;
		}
	}

	/**
	 * Verifies that a slot binds to the original launch's archive.
	 *
	 * @param binding   the observed slot record
	 * @param requestId the original launch's request ID
	 */
	private static void requireOriginalRequest(JsonNode binding, String requestId)
	{
		requireThat(binding.path("format_version").asInt(-1), "bindingFormat").isEqualTo(1);
		requireThat(binding.path("request_id").stringValue(""), "boundRequest").isEqualTo(requestId);
		requireThat(binding.size(), "bindingFields").isEqualTo(2);
	}

	/**
	 * Returns a requested runner whose harness reports its session as soon as the nested session starts. A runner that
	 * starts a nested session works on its turns until it is stopped, and records no end. A runner that resumes the
	 * nested session continues it in its slot, and on {@code complete} records the nested session's end, writes its
	 * receipt and exits.
	 *
	 * @param fixture   the requesting nested session
	 * @param fileLocks the file locks of the requested nested session
	 * @param options   the requested runner's options
	 * @param child     receives the runner, so that it can exit
	 * @return the runner
	 * @throws IOException if the requested nested session cannot be created
	 */
	private static FakeHarnessCli resumableRunner(Fixture fixture, FileLocks fileLocks, List<String> options,
		AtomicReference<FakeHarnessCli> child) throws IOException
	{
		return resumableRunner(fixture, fileLocks, options, child, true);
	}

	/**
	 * Returns a requested runner whose harness reports its session as soon as the nested session starts. A runner that
	 * starts a nested session works on its turns until it is stopped, and records no end. A runner that resumes the
	 * nested session either continues it, and on {@code complete} records the nested session's end, writes its receipt
	 * and exits, or, when the harness cannot continue the session, exits with status 1 and leaves the nested session as
	 * it was.
	 *
	 * @param fixture   the requesting nested session
	 * @param fileLocks the file locks of the requested nested session
	 * @param options   the requested runner's options
	 * @param child     receives the runner, so that it can exit
	 * @param continues indicates whether the harness continues the session that a resumed nested session records
	 * @return the runner
	 * @throws IOException if the requested nested session cannot be created
	 */
	private static FakeHarnessCli resumableRunner(Fixture fixture, FileLocks fileLocks, List<String> options,
		AtomicReference<FakeHarnessCli> child, boolean continues) throws IOException
	{
		Path resultsFolder = Path.of(option(options, "--results"));
		String slotId = option(options, "--slot-id");
		NestedSessionManager manager = new NestedSessionManager(fileLocks, resultsFolder, Clock.systemUTC(),
			Yielding.fileReplacer()).withFixedSlotId(slotId);
		if (!options.contains(RunnerArguments.RESUME_SLOT_OPTION))
		{
			NestedSession nestedSession = manager.create("claude");
			manager.recordHarnessSession(nestedSession, RESUMED_SESSION);
			return new FakeHarnessCli(_ ->
			{
			});
		}
		if (!continues)
		{
			return new FakeHarnessCli(line ->
			{
				if (line.contains("\"complete\""))
					child.get().exit();
			}, 1);
		}
		NestedSession nestedSession = new NestedSession("claude", slotId, resultsFolder.resolve("claude").resolve(slotId));
		Path output = Path.of(option(options, "--output"));
		Files.writeString(output, "");
		return new FakeHarnessCli(line ->
		{
			try
			{
				if (fixture.json.readTree(line).path("type").asString().equals("complete"))
				{
					append(output, "{\"type\":\"completed\"}");
					manager.markCompleted(nestedSession);
					NestedSessionReceipt.write(fixture.json, Yielding.fileReplacer(), Path.of(option(options, "--receipt")),
						nestedSession, output, List.of());
					child.get().exit();
				}
			}
			catch (IOException e)
			{
				throw new UncheckedIOException(e);
			}
		});
	}

	/**
	 * Returns a requested runner that answers its turns with the runner's records: it replies to each turn except
	 * {@code hold} and {@code second}, which run until they are interrupted, reports an interrupt and then the
	 * interrupted turn's completion, and on {@code complete} writes its receipt and exits.
	 *
	 * @param fixture   the requesting nested session
	 * @param fileLocks the file locks of the requested nested session
	 * @param options   the requested runner's options
	 * @param child     receives the runner, so that it can exit
	 * @return the runner
	 * @throws IOException if the requested nested session cannot be created
	 */
	private static FakeHarnessCli conversingRunner(Fixture fixture, FileLocks fileLocks, List<String> options,
		AtomicReference<FakeHarnessCli> child) throws IOException
	{
		NestedSession nestedSession = new NestedSessionManager(fileLocks, Path.of(option(options, "--results")),
			Clock.systemUTC(), Yielding.fileReplacer()).create("claude");
		Path output = Path.of(option(options, "--output"));
		Files.writeString(output, "");
		AtomicInteger turns = new AtomicInteger();
		return new FakeHarnessCli(line ->
		{
			try
			{
				JsonNode record = fixture.json.readTree(line);
				switch (record.path("type").asString())
				{
					case "turn" ->
					{
						int turn = turns.incrementAndGet();
						String prompt = record.path("prompt").asString();
						if (!prompt.equals("hold") && !prompt.equals("second"))
							append(output, turnCompleted(fixture, turn));
					}
					case "interrupt" ->
					{
						append(output, "{\"type\":\"interrupted\"}");
						append(output, turnCompleted(fixture, turns.get()));
					}
					case "complete" ->
					{
						append(output, "{\"type\":\"completed\"}");
						NestedSessionReceipt.write(fixture.json, Yielding.fileReplacer(), Path.of(option(options, "--receipt")),
							nestedSession, output, List.of());
						child.get().exit();
					}
					default -> throw new IOException("Unexpected record: " + line);
				}
			}
			catch (IOException e)
			{
				throw new UncheckedIOException(e);
			}
		});
	}

	/**
	 * Returns the record with which a requested runner ends a turn.
	 *
	 * @param fixture the requesting nested session
	 * @param turn    the turn's number
	 * @return the record
	 */
	private static String turnCompleted(Fixture fixture, int turn)
	{
		ObjectNode record = fixture.json.createObjectNode().put("type", "turn_completed").
			put("exit_code", 0).put("error", "").put("assistant_response", "reply " + turn);
		record.putArray("assistant_messages").add("reply " + turn);
		return record.toString();
	}

	/**
	 * Appends a line to a file.
	 *
	 * @param file the file
	 * @param line the line, without its terminator
	 * @throws IOException if the file cannot be written
	 */
	private static void append(Path file, String line) throws IOException
	{
		Files.writeString(file, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
	}

	/**
	 * Plays the asking session: appends each message whose turn has come to the conversation, once.
	 *
	 * @param folder   the conversation's folder
	 * @param messages the messages in order
	 * @param sent     how many messages were appended
	 * @throws IOException if the conversation cannot be read or written
	 */
	private static void ask(Path folder, List<Message> messages, AtomicInteger sent) throws IOException
	{
		Path fromChild = folder.resolve("from-child.jsonl");
		long turnEnds = 0;
		if (Files.exists(fromChild))
		{
			turnEnds = Files.readAllLines(fromChild).stream().filter(line -> line.startsWith("{\"turn_end\"")).
				count();
		}
		while (sent.get() < messages.size() && messages.get(sent.get()).afterTurnEnds() <= turnEnds)
		{
			Files.createDirectories(folder);
			Files.writeString(folder.resolve("to-child.jsonl"), messages.get(sent.get()).line() + "\n",
				StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			sent.incrementAndGet();
		}
	}

	/**
	 * Summarizes the lines of a conversation's {@code from-child.jsonl}: a record's type, followed by the reply for a
	 * {@code turn_completed} record and the refused line for a refusal, or the line itself otherwise.
	 *
	 * @param json   the line mapper
	 * @param folder the conversation's folder
	 * @return the summaries in order
	 * @throws IOException if the file cannot be read
	 */
	private static List<String> fromChild(JsonMapper json, Path folder) throws IOException
	{
		List<String> summaries = new ArrayList<>();
		for (String line : Files.readAllLines(folder.resolve("from-child.jsonl")))
		{
			JsonNode record = json.readTree(line);
			String type = record.path("type").asString("");
			if (record.has("refused"))
				summaries.add("refused " + record.path("refused").asString());
			else if (type.equals("turn_completed"))
				summaries.add(type + " " + record.path("assistant_messages").path(0).asString());
			else if (!type.isEmpty())
				summaries.add(type);
			else
				summaries.add(line);
		}
		return summaries;
	}

	/**
	 * One message of an asking session.
	 *
	 * @param afterTurnEnds how many of the requested session's turns must have ended before it is sent
	 * @param line          the line
	 */
	private record Message(int afterTurnEnds, String line)
	{
	}

	/**
	 * Waits for a test-controlled lifecycle boundary.
	 *
	 * @param signal the boundary's signal
	 * @throws IOException if the boundary is not reached or waiting is interrupted
	 */
	private static void awaitStarted(CountDownLatch signal) throws IOException
	{
		try
		{
			if (!signal.await(10, TimeUnit.SECONDS))
				throw new IOException("Test lifecycle boundary was not reached");
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted awaiting test lifecycle boundary", e);
		}
	}

	/**
	 * Verifies that a nested session's runner carries out each worktree operation that a session inside the nested
	 * session requests for a campaign of the nested session's project, outside the session's sandbox: add, recreate,
	 * repair after the registration came to name another location of the project, and remove. The requests do not count
	 * against the nested session's allowance of sessions.
	 *
	 * @throws IOException if fixture or broker I/O fails
	 */
	@Test(timeOut = 30_000)
	public void servesWorktreeOperations() throws IOException
	{
		// Requirement: R-W17
		try (Fixture fixture = new Fixture();
			SessionRequestBroker broker = new SessionRequestBroker(fixture.nestedSession, fixture.manager, fixture.options,
				(_, _, _) ->
			{
				throw new AssertionError("A worktree request starts no session");
			}))
		{
			broker.start(_ -> () ->
			{
			});
			Git git = TestRepositories.initialize(fixture.project, fixture.root.resolve("no-excludes"));
			Path campaign = Files.createDirectories(fixture.project.resolve(".cat/sessions/s1/optimizer/c1"));
			Path worktree = campaign.resolve("worktree");
			WorktreeRegistrar client = fixture.worktreeClient(broker);

			client.add(fixture.project, campaign);
			requireThat(worktree.resolve("marker"), "added").isRegularFile();
			TemporaryDirectories.deleteRecursively(worktree);
			client.recreate(fixture.project, campaign);
			requireThat(worktree.resolve("marker"), "recreated").isRegularFile();
			requireThat(TestRepositories.worktreeCount(git), "count").isEqualTo(2L);

			Files.writeString(fixture.project.resolve(".git/worktrees/worktree/gitdir"),
				"/moved/project/.cat/sessions/s1/optimizer/c1/worktree/.git\n", StandardCharsets.UTF_8);
			requireThat(client.repair(fixture.project, campaign), "repaired").isTrue();
			requireThat(GitWorktreeRegistrar.isLinked(fixture.project, campaign), "linked").isTrue();

			client.remove(fixture.project, campaign);
			requireThat(Files.exists(worktree), "removed").isFalse();
			requireThat(TestRepositories.worktreeCount(git), "removedCount").isEqualTo(1L);
			requireThat(SessionRequestAccess.furtherRequestRefusal(fixture.requests), "refusal").isEmpty();
		}
	}

	/**
	 * Verifies that a nested session's runner refuses, having changed nothing, a worktree request whose campaign lies in
	 * more than one CAT session directory of the nested session's project, and any worktree request of a nested session
	 * that allows no session request.
	 *
	 * @throws IOException if fixture or broker I/O fails
	 */
	@Test(timeOut = 30_000)
	public void refusesWorktreeRequests() throws IOException
	{
		// Requirement: R-W17
		for (String allowed : List.of("1", "0"))
		{
			try (Fixture fixture = new Fixture("--session-requests", allowed);
				SessionRequestBroker broker = new SessionRequestBroker(fixture.nestedSession, fixture.manager, fixture.options,
					(_, _, _) ->
					{
						throw new AssertionError("A worktree request starts no session");
					}))
			{
				Git git = TestRepositories.initialize(fixture.project, fixture.root.resolve("no-excludes"));
				Path campaign = Files.createDirectories(fixture.project.resolve(".cat/sessions/s1/optimizer/c1"));
				if (allowed.equals("1"))
					Files.createDirectories(fixture.project.resolve(".cat/sessions/s2/optimizer/c1"));
				WorktreeRegistrar client = fixture.worktreeClient(broker);
				WorktreeRefusedException refusal = expectThrows(WorktreeRefusedException.class,
					() -> client.add(fixture.project, campaign));
				String expected = SessionRequestAccess.NO_REQUESTS_REASON;
				if (allowed.equals("1"))
					expected = "lies in more than one CAT session directory";
				requireThat(refusal.getMessage(), "message").contains(expected);
				requireThat(Files.exists(campaign.resolve("worktree")), "worktree").isFalse();
				requireThat(TestRepositories.worktreeCount(git), "count").isEqualTo(1L);
			}
		}
	}

	/**
	 * Finds one option in the child runner command.
	 *
	 * @param arguments the runner options
	 * @param name the option name
	 * @return the option value
	 */
	private static String option(List<String> arguments, String name)
	{
		return arguments.get(arguments.indexOf(name) + 1);
	}

	/**
	 * The channel of a session inside a nested session to the nested session's runner.
	 *
	 * @param requests the nested session's requests directory
	 */
	private record NestedSessionChannel(Path requests) implements WorktreeRequestClient.Channel
	{
		@Override
		public boolean isServed()
		{
			return true;
		}

		@Override
		public boolean removesResponses()
		{
			return false;
		}

		@Override
		public void close()
		{
		}
	}

	/**
	 * The registrar that a client uses in place, which a client that sends requests never calls.
	 */
	private static final class InPlaceRefused implements WorktreeRegistrar
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
	 * Owns one isolated requesting nested session and removes it after its broker and assertions finish.
	 */
	private static final class Fixture implements AutoCloseable
	{
		private final TemporaryDirectories cleanup = new TemporaryDirectories();
		private final JsonMapper json = JsonMapper.builder().build();
		private final Path root;
		private final FileLocks fileLocks;
		private final NestedSessionManager manager;
		private final NestedSession nestedSession;
		private final Path project;
		private final Path denied;
		private final Path requests;
		private final Path responses;
		private final HarnessRunnerOptions options;

		/**
		 * Creates a requesting nested session with capacity for one requested session.
		 *
		 * @throws IOException if setup fails
		 */
		private Fixture() throws IOException
		{
			this("--session-requests", "1");
		}

		/**
		 * Creates a requesting nested session whose runner options include the given options that govern session requests.
		 *
		 * @param requestOptions the runner's options that govern session requests, such as
		 *                       {@code --session-requests}
		 * @throws IOException if setup fails
		 */
		private Fixture(String... requestOptions) throws IOException
		{
			try (TestJvmScope jvm = new TestJvmScope())
			{
				root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "broker-"));
				fileLocks = jvm.fileLocks();
				manager = new NestedSessionManager(fileLocks, root.resolve("results"), Clock.systemUTC(),
					Yielding.fileReplacer());
				nestedSession = manager.create("claude");
				project = nestedSession.directory().resolve("project");
				denied = Files.createDirectory(root.resolve("denied"));
				Files.writeString(project.resolve("marker"), "original");
				Files.writeString(project.resolve("prompt"), "test the plugin");
				requests = Files.createDirectory(SessionRequestAccess.requestsDirectory(nestedSession));
				responses = Files.createDirectory(SessionRequestAccess.responsesDirectory(nestedSession));
				List<String> arguments = new ArrayList<>(List.of("--artifact", root.toString(), "--project",
					project.toString(), "--model", "model-a", "--effort", "effort-a", "--deny-read",
					denied.toString()));
				arguments.addAll(List.of(requestOptions));
				options = HarnessRunnerOptions.parse(arguments.toArray(String[]::new), "harness",
					HarnessTemporaryRoot.of(root));
			}
		}

		/**
		 * Writes a valid request.
		 *
		 * @param id the request id
		 * @return its mutable document for invalid-input tests
		 * @throws IOException if writing fails
		 */
		private ObjectNode request(String id) throws IOException
		{
			ObjectNode request = document();
			writeRequest(id, request);
			return request;
		}

		/**
		 * Returns a valid request manifest without writing it.
		 *
		 * @return the manifest, which names the entries that {@link #writeRequest(String, ObjectNode)} packs
		 */
		private ObjectNode document()
		{
			ObjectNode request = json.createObjectNode().put("format_version", 3).put("harness", "claude").
				put("project", "project").put("model", "haiku").put("effort", "low").put("session_transcripts", true);
			request.putArray("prompts").add("prompts/0").add("prompts/1");
			request.putArray("plugins").add("plugins/0");
			return request;
		}

		/**
		 * Writes a hostile archive or manifest for the broker's rejection boundary.
		 *
		 * @param request the manifest
		 * @param kind    the hostile variation
		 * @throws IOException if fixture writing fails
		 */
		private void writeHostile(ObjectNode request, String kind) throws IOException
		{
			Path file = requests.resolve("bad.tar");
			byte[] manifest = json.writeValueAsBytes(request);
			if (kind.equals("malformed"))
				manifest = "{".getBytes(StandardCharsets.UTF_8);
			if (kind.equals("empty"))
				manifest = new byte[0];
			if (kind.equals("trailing"))
				manifest = (json.writeValueAsString(request) + " {}").getBytes(StandardCharsets.UTF_8);
			try (OutputStream bytes = Files.newOutputStream(file);
				TarArchiveOutputStream archive = new TarArchiveOutputStream(bytes))
			{
				entry(archive, "request.json", manifest);
				if (kind.equals("pax_chain") || kind.equals("long_chain"))
				{
					for (int index = 0; index < 2; ++index)
					{
						byte type = 'x';
						if (kind.equals("long_chain"))
							type = 'L';
						archive.putArchiveEntry(new TarArchiveEntry("metadata" + index, type));
						archive.closeArchiveEntry();
					}
				}
				if (kind.equals("pax_size"))
				{
					TarArchiveEntry metadata = new TarArchiveEntry("project/metadata");
					metadata.addPaxHeader("comment", "x".repeat(65_537));
					archive.putArchiveEntry(metadata);
					archive.closeArchiveEntry();
				}
				entry(archive, "project/", new byte[0]);
				entry(archive, "project/marker", "original".getBytes(StandardCharsets.UTF_8));
				entry(archive, "prompts/0", "test".getBytes(StandardCharsets.UTF_8));
				entry(archive, "prompts/1", "next".getBytes(StandardCharsets.UTF_8));
				entry(archive, "plugins/0/", new byte[0]);
				switch (kind)
				{
					case "absolute" -> entry(archive, "/outside", new byte[0]);
					case "parent" -> entry(archive, "project/../outside", new byte[0]);
					case "drive" -> entry(archive, "C:/outside", new byte[0]);
					case "backslash" -> entry(archive, "project/outside", new byte[0]);
					case "duplicate" -> entry(archive, "project/marker", new byte[0]);
					case "directory_duplicate" -> entry(archive, "project/", new byte[0]);
					case "link_escape" -> link(archive, "project/link", "../../outside");
					case "link_absolute" -> link(archive, "project/link", "/outside");
					case "link_beyond_project" -> link(archive, "project/link", "../prompts/0");
					case "link_back_out" ->
					{
						// Read as text, the target stays inside the project, but the link that it passes through leads to
						// the project itself, so its parent is the directory that holds the project
						link(archive, "project/self", ".");
						link(archive, "project/link", "self/../prompts/0");
					}
					case "link_broken" -> link(archive, "project/link", "absent");
					case "link_through" ->
					{
						link(archive, "project/link", ".");
						entry(archive, "project/link/written", new byte[0]);
					}
					case "link_in_plugin" -> link(archive, "plugins/0/link", "../../project/marker");
					case "copy_in_link_targets" -> entry(archive, "link-targets/0", new byte[0]);
					case "copy_writable_entry" ->
					{
						readOnlyEntry(archive, "project/copy/", new byte[0]);
						entry(archive, "project/copy/written", new byte[0]);
					}
					case "copy_link" ->
					{
						readOnlyEntry(archive, "project/copy/", new byte[0]);
						link(archive, "project/copy/link", "../marker");
					}
					case "copy_after_entry" ->
					{
						entry(archive, "project/copy/written", new byte[0]);
						readOnlyEntry(archive, "project/copy/", new byte[0]);
					}
					case "copy_inside_copy" ->
					{
						readOnlyEntry(archive, "project/copy/inner", new byte[0]);
						readOnlyEntry(archive, "project/copy/", new byte[0]);
					}
					case "hardlink_entry", "special", "sparse" ->
					{
						byte type = '1';
						if (kind.equals("special"))
							type = '6';
						if (kind.equals("sparse"))
							type = 'S';
						TarArchiveEntry link = new TarArchiveEntry("project/link", type, true);
						link.setLinkName("marker");
						archive.putArchiveEntry(link);
						archive.closeArchiveEntry();
					}
					case "too_many" ->
					{
						for (int index = 0; index < limits(kind).entries(); ++index)
							entry(archive, "project/file" + index, new byte[0]);
					}
					default ->
					{
					}
				}
			}
			if (kind.equals("large_entry") || kind.equals("manifest_size") || kind.equals("prompt_size") ||
				kind.equals("long_size"))
			{
				TarArchiveEntry entry = new TarArchiveEntry("project/large");
				entry.setSize(67_108_865);
				if (kind.equals("manifest_size"))
				{
					entry.setName("request.json");
					entry.setSize(65_537);
				}
				if (kind.equals("prompt_size"))
				{
					entry.setName("prompts/0");
					entry.setSize(1_048_577);
				}
				if (kind.equals("long_size"))
				{
					entry = new TarArchiveEntry("long", (byte) 'L');
					entry.setSize(65_537);
				}
				byte[] header = new byte[512];
				entry.writeEntryHeader(header);
				Files.write(file, header);
			}
			if (kind.equals("backslash"))
				Files.write(file, withBackslash(Files.readAllBytes(file), "project/outside"));
			if (kind.equals("checksum"))
			{
				byte[] content = Files.readAllBytes(file);
				content[0] ^= 1;
				Files.write(file, content);
			}
			if (kind.equals("total_size"))
			{
				// Each entry is within the per-entry bound, but together they exceed the total
				try (OutputStream bytes = Files.newOutputStream(file);
					TarArchiveOutputStream archive = new TarArchiveOutputStream(bytes))
				{
					for (int index = 0; index < 2; ++index)
						entry(archive, "project/large" + index, new byte[600]);
				}
			}

			if (kind.equals("request_link") || kind.equals("request_hardlink"))
			{
				Path original = Files.move(file, root.resolve("original.tar"));
				if (kind.equals("request_link"))
					Files.createSymbolicLink(file, original);
				else
					Files.createLink(file, original);
			}
			if (kind.equals("oversized"))
			{
				try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE))
				{
					channel.position(limits(kind).archiveBytes());
					channel.write(ByteBuffer.wrap(new byte[]{0}));
				}
			}
		}

		/**
		 * Writes a plain archive with a supplied manifest and captured inputs.
		 *
		 * @param id      the request identifier
		 * @param request the manifest
		 * @throws IOException if the fixture cannot be written
		 */
		private void writeRequest(String id, ObjectNode request) throws IOException
		{
			writeRequest(id, request, Map.of());
		}

		/**
		 * Writes a plain archive with a supplied manifest, captured inputs and symbolic links in the project.
		 *
		 * @param id      the request identifier
		 * @param request the manifest
		 * @param links   the target of each link, by its entry name
		 * @throws IOException if the fixture cannot be written
		 */
		private void writeRequest(String id, ObjectNode request, Map<String, String> links) throws IOException
		{
			try (OutputStream bytes = Files.newOutputStream(requests.resolve(id + ".tar"));
				TarArchiveOutputStream archive = new TarArchiveOutputStream(bytes))
			{
				entry(archive, "request.json", json.writeValueAsBytes(request));
				for (Map.Entry<String, String> link : links.entrySet())
					link(archive, link.getKey(), link.getValue());
				entry(archive, "project/", new byte[0]);
				entry(archive, "project/marker", "original".getBytes(StandardCharsets.UTF_8));
				entry(archive, "prompts/0", "test the plugin".getBytes(StandardCharsets.UTF_8));
				entry(archive, "prompts/1", "then verify".getBytes(StandardCharsets.UTF_8));
				entry(archive, "plugins/0/", new byte[0]);
				entry(archive, "plugins/0/plugin.txt", "plugin bytes".getBytes(StandardCharsets.UTF_8));
			}
		}

		/**
		 * Adds a file or empty directory to a test archive.
		 *
		 * @param archive the fixture archive
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
		 * Adds a file or directory that lacks every write permission and carries the sticky bit to a test archive, as
		 * the entries of a copy of a project link's target do.
		 *
		 * @param archive the fixture archive
		 * @param name    the entry name, which ends with {@code /} for a directory
		 * @param content the file's content
		 * @throws IOException if writing fails
		 */
		private static void readOnlyEntry(TarArchiveOutputStream archive, String name, byte[] content)
			throws IOException
		{
			TarArchiveEntry entry = new TarArchiveEntry(name, true);
			if (name.endsWith("/"))
				entry.setMode(041555);
			else
				entry.setMode(0101444);
			entry.setSize(content.length);
			archive.putArchiveEntry(entry);
			archive.write(content);
			archive.closeArchiveEntry();
		}

		/**
		 * Adds a symbolic link to a test archive.
		 *
		 * @param archive the fixture archive
		 * @param name    the entry name
		 * @param target  the link's target
		 * @throws IOException if writing fails
		 */
		private static void link(TarArchiveOutputStream archive, String name, String target) throws IOException
		{
			TarArchiveEntry link = new TarArchiveEntry(name, TarConstants.LF_SYMLINK, true);
			link.setLinkName(target);
			archive.putArchiveEntry(link);
			archive.closeArchiveEntry();
		}

		/**
		 * Replaces the first slash in one entry's name with a backslash. Commons Compress replaces the platform
		 * separator in a name that it is given, so on Windows only the written header bytes can hold a backslash.
		 *
		 * @param archive the archive bytes
		 * @param name    the entry name, which must occupy a header's name field
		 * @return the archive bytes, with that header's checksum recomputed
		 */
		private static byte[] withBackslash(byte[] archive, String name)
		{
			byte[] expected = name.getBytes(StandardCharsets.US_ASCII);
			for (int offset = 0; offset < archive.length; offset += 512)
			{
				if (!Arrays.equals(archive, offset, offset + expected.length, expected, 0, expected.length) ||
					archive[offset + expected.length] != 0)
					continue;
				byte[] header = Arrays.copyOfRange(archive, offset, offset + 512);
				header[name.indexOf('/')] = '\\';
				Arrays.fill(header, TarConstants.CHKSUM_OFFSET, TarConstants.CHKSUM_OFFSET + TarConstants.CHKSUMLEN,
					(byte) ' ');
				TarUtils.formatCheckSumOctalBytes(TarUtils.computeCheckSum(header), header, TarConstants.CHKSUM_OFFSET,
					TarConstants.CHKSUMLEN);
				System.arraycopy(header, 0, archive, offset, 512);
				return archive;
			}
			throw new AssertionError("No header names " + name);
		}

		/**
		 * Returns a client that sends its worktree requests to the nested session's runner, scanning the nested session's
		 * requests while it waits for each answer.
		 *
		 * @param broker the nested session's broker
		 * @return the client
		 */
		private WorktreeRegistrar worktreeClient(SessionRequestBroker broker)
		{
			return new WorktreeRequestClient(() -> Optional.of(new NestedSessionChannel(requests)), new InPlaceRefused(),
				json, broker::scan);
		}

		/**
		 * Reads the response through the launcher's bounded wait operation.
		 *
		 * @param id the request id
		 * @return the response
		 * @throws IOException if waiting or reading fails
		 */
		private JsonNode await(String id) throws IOException
		{
			int status = SessionRequestCli.execute(new String[]{"--await", id, "--wait", "10"}, requests,
				Clock.systemUTC(), Thread::yield, new PrintWriter(new StringWriter()));
			requireThat(status, "waitStatus").isNotEqualTo(3);
			return json.readTree(responses.resolve(id + "/response.json").toFile());
		}

		@Override
		public void close() throws IOException
		{
			cleanup.close();
		}
	}
}
