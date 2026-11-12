package com.holdmyspot.cat.common.runner.test;

import com.holdmyspot.cat.common.FileLocks;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.Yielding;
import com.holdmyspot.cat.common.runner.HarnessProcessLiveness;
import com.holdmyspot.cat.common.runner.HarnessRun;
import com.holdmyspot.cat.common.runner.HarnessRunManager;
import com.holdmyspot.cat.common.runner.HarnessRunReceipt;
import com.holdmyspot.cat.common.runner.HarnessRunnerOptions;
import com.holdmyspot.cat.common.runner.HarnessTemporaryRoot;
import com.holdmyspot.cat.common.runner.RequestLimits;
import com.holdmyspot.cat.common.runner.SessionRequestAccess;
import com.holdmyspot.cat.common.runner.SessionRequestBroker;
import com.holdmyspot.cat.common.runner.SessionRequestCli;
import com.holdmyspot.cat.common.runner.SessionRequestForwarder;
import com.holdmyspot.cat.common.runner.fakes.test.FakeHarnessCli;
import com.holdmyspot.cat.common.runner.policy.ExtraPermissions;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.StringReader;
import java.io.Reader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.nio.file.StandardOpenOption;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.apache.commons.compress.archivers.tar.TarUtils;
import com.holdmyspot.cat.common.runner.HarnessRunSession;
import com.holdmyspot.cat.common.runner.HarnessRunnerBridge;
import com.holdmyspot.cat.common.runner.fakes.test.ScriptedHarness.Turn;
import com.holdmyspot.cat.common.runner.fakes.test.ScriptedProcess;
import com.holdmyspot.cat.common.runner.fakes.test.TestHarnessRunnerScope;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
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
			try (SessionRequestBroker broker = new SessionRequestBroker(fixture.run, fixture.options,
				(harness, options, directory) ->
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
						HarnessRun run = new HarnessRunManager(fileLocks, Path.of(option(options, "--run-root")),
							Clock.systemUTC(),
							Yielding.fileReplacer()).create("claude");
						Files.writeString(Files.createDirectories(run.sessionTranscripts()).resolve("native.jsonl"), "native");
						Path transcript = Files.writeString(Path.of(option(options, "--output")), "bridge");
						if (exit == 0)
							HarnessRunReceipt.write(fixture.json, Yielding.fileReplacer(), Path.of(option(options, "--receipt")),
							run, transcript, List.of());
					}
					FakeHarnessCli process = new FakeHarnessCli(_ ->
					{
					}, exit);
					process.closeOutputThenExitWhenAwaited();
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
					contains(fixture.options.runRoot().toString());
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
					requireThat(response.path("child_run_id").asString(), "childRunId").isNotBlank();
				}
				fixture.request("second");
				broker.scan();
				requireThat(fixture.await("second").path("reason").asString(), "limit").
					isEqualTo(SessionRequestAccess.limitReason(1));
			}
		}
	}

	/**
	 * Supplies the request depths of a requesting run and the depth that each requested session must receive, or
	 * {@code -1} when the requested session must not request sessions.
	 *
	 * @return the run's depth and the requested session's depth
	 */
	@DataProvider
	public Object[][] requestDepths()
	{
		return new Object[][]{{0, -1}, {1, 0}, {3, 2}};
	}

	/**
	 * Verifies that a requested session may request sessions in turn, as many as the requesting run, only while the
	 * run's request depth leaves a level below it, and that each level lowers the depth by one.
	 *
	 * @param depth    the requesting run's request depth
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
			try (SessionRequestBroker broker = new SessionRequestBroker(fixture.run, fixture.options,
				(_, options, _) ->
				{
					arguments.set(options);
					FakeHarnessCli process = new FakeHarnessCli(_ ->
					{
					}, 1);
					process.closeOutputThenExitWhenAwaited();
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
	 * Verifies that the runner states the run's limits in the responses directory when its broker starts and again
	 * whenever it accepts a request, so that a command can tell before it changes anything whether the run allows a
	 * further session, and that a reader of the file gets the reason with which the runner would refuse one.
	 *
	 * @throws IOException if fixture or broker I/O fails
	 */
	@Test(timeOut = 30_000)
	public void statesLimits() throws IOException
	{
		try (Fixture fixture = new Fixture("--session-requests", "1", "--request-depth", "2"))
		{
			Path file = fixture.responses.resolve(SessionRequestAccess.LIMITS_FILE);
			try (SessionRequestBroker broker = new SessionRequestBroker(fixture.run, fixture.options,
				(_, _, _) ->
				{
					FakeHarnessCli process = new FakeHarnessCli(_ ->
					{
					}, 1);
					process.closeOutputThenExitWhenAwaited();
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
	 * Verifies that a run whose options allow no session request states so in its limits, and that a reader of the
	 * file gets the reason that names the caller's options that would allow the sessions.
	 *
	 * @throws IOException if fixture or broker I/O fails
	 */
	@Test(timeOut = 30_000)
	public void statesNoRequests() throws IOException
	{
		try (Fixture fixture = new Fixture("--session-requests", "0");
			SessionRequestBroker broker = new SessionRequestBroker(fixture.run, fixture.options, (_, _, _) ->
			{
				throw new AssertionError("A run that allows no request starts no session");
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
			try (SessionRequestBroker broker = new SessionRequestBroker(fixture.run, fixture.options,
				(_, options, _) ->
				{
					arguments.add(options);
					FakeHarnessCli process = new FakeHarnessCli(_ ->
					{
					}, 1);
					process.closeOutputThenExitWhenAwaited();
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
			try (SessionRequestBroker broker = new SessionRequestBroker(fixture.run, fixture.options,
				(_, options, _) ->
				{
					arguments.add(options);
					if (options.contains(ExtraPermissions.OPTION))
						given.add(ExtraPermissions.read(fixture.json, Path.of(option(options, ExtraPermissions.OPTION))));
					FakeHarnessCli process = new FakeHarnessCli(_ ->
					{
					}, 1);
					process.closeOutputThenExitWhenAwaited();
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
			SessionRequestBroker broker = new SessionRequestBroker(fixture.run, fixture.options, (_, _, _) ->
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
			try (SessionRequestBroker broker = new SessionRequestBroker(fixture.run, fixture.options,
				(_, _, directory) ->
				{
					Files.writeString(directory.resolve("runner.stderr"), reason + System.lineSeparator());
					FakeHarnessCli process = new FakeHarnessCli(_ ->
					{
					}, HarnessRunnerOptions.REFUSED_LAUNCH_STATUS);
					process.closeOutputThenExitWhenAwaited();
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
	 * written does, gets no response and no response directory on that scan, does not use up the run's allowance of
	 * sessions, and is served by a scan after the handle is closed. On other platforms an open handle does not prevent
	 * the copy, so the request is served by the first scan.
	 *
	 * @throws IOException if fixture or broker I/O fails
	 */
	@Test(timeOut = 30_000)
	public void servesRequestWhoseArchiveWasBusy() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			AtomicInteger started = new AtomicInteger();
			try (SessionRequestBroker broker = new SessionRequestBroker(fixture.run, fixture.options,
				(_, _, _) ->
				{
					started.incrementAndGet();
					FakeHarnessCli process = new FakeHarnessCli(_ ->
					{
					}, 75);
					process.closeOutputThenExitWhenAwaited();
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
			SessionRequestBroker broker = new SessionRequestBroker(fixture.run, fixture.options, (_, _, _) ->
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
			try (SessionRequestBroker broker = new SessionRequestBroker(fixture.run, fixture.options,
				(_, options, _) ->
				{
					Path link = Path.of(options.get(options.indexOf("--project") + 1)).resolve("docs/marker");
					targets.add(Files.readSymbolicLink(link));
					contents.add(Files.readString(link));
					FakeHarnessCli process = new FakeHarnessCli(_ ->
					{
					}, 0);
					process.closeOutputThenExitWhenAwaited();
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
	 * Verifies that the runner refuses a request made inside its run that sets a field that only a session-level
	 * request may set, naming the field, and starts no session for it.
	 *
	 * @param field the session-only field
	 * @param value its JSON value
	 * @throws IOException if fixture or broker I/O fails
	 */
	@Test(dataProvider = "sessionOnlyFields")
	public void refusesSessionOnlyField(String field, String value) throws IOException
	{
		try (Fixture fixture = new Fixture();
			SessionRequestBroker broker = new SessionRequestBroker(fixture.run, fixture.options, (_, _, _) ->
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
			SessionRequestBroker broker = new SessionRequestBroker(fixture.run, fixture.options, (_, _, _) ->
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
		RequestLimits limits = RequestLimits.IN_RUN;
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
			try (SessionRequestBroker broker = new SessionRequestBroker(fixture.run, fixture.options, (_, _, _) ->
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
			try (SessionRequestBroker broker = new SessionRequestBroker(fixture.run, fixture.options, (_, _, _) ->
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
					HarnessRunnerBridge.serve(() -> new HarnessRunSession(fixture.run, parent, scope),
						new HarnessRunManager(fileLocks, fixture.root.resolve("runs"),
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
	 * Verifies that a runner launch made inside a session whose run may request no session reaches the session's
	 * runner, which refuses it without starting a requested session, so that the launch exits with status 2 and a
	 * reason that names the caller's options that would allow the session, instead of starting a harness inside the
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
			SessionRequestAccess.allowing(fixture.run, fixture.root, 0, Platform.current(),
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
				HarnessRunnerBridge.serve(() -> new HarnessRunSession(fixture.run, parent, scope),
					new HarnessRunManager(fileLocks, fixture.root.resolve("runs"), Clock.systemUTC(),
						Yielding.fileReplacer()),
					fixture.options, fixture.json, Yielding.fileReplacer(), input, new StringWriter(),
					(harness, _, _) ->
					{
						started.add(harness);
						throw new IOException("No requested session may start");
					});
			}
			requireThat(status.get(), "status").isEqualTo(HarnessRunnerOptions.REFUSED_LAUNCH_STATUS);
			requireThat(errors.toString(), "errors").contains("refused: this run allows no further sessions").
				contains("--session-requests").contains("--request-depth").doesNotContain("--no-daemon");
			requireThat(started, "started").isEmpty();
		}
	}

	/**
	 * Verifies that a session inside a run holds a conversation with the session that it requests through the run's
	 * runner: each turn is sent once the previous turn has ended, an interrupt stops the turn in progress, a line that
	 * is no message is refused without reaching the requested session, and {@code end} completes the session.
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
			try (SessionRequestBroker broker = new SessionRequestBroker(fixture.run, fixture.options,
				(_, options, _) ->
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
			try (SessionRequestBroker broker = new SessionRequestBroker(fixture.run, fixture.options,
				(_, options, _) ->
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
	 * Verifies that the run's runner stops a requested session that converses with a launcher once the launcher
	 * ends, however it ends, and answers the request as stopped, and that the launcher's last line in the
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
			try (SessionRequestBroker broker = new SessionRequestBroker(fixture.run, fixture.options,
				(_, options, _) ->
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
	 * Verifies that {@code request-session --conversation} exits with status 2 when the run allows no requested
	 * session, and that the conversation's last line says that the request was refused.
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
			try (SessionRequestBroker broker = new SessionRequestBroker(fixture.run, fixture.options,
				(_, _, _) ->
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
	 * Verifies that a session whose run's caller granted no session requests, which finds how to request one in CAT's
	 * rules, sees in its run's limits that the run allows none, and that a request that it makes with
	 * {@code request-session} anyway exits with status 2, starts no session, and is answered with a reason that names
	 * the options with which the run's caller allows sessions.
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
			try (SessionRequestBroker broker = new SessionRequestBroker(fixture.run, fixture.options,
				(_, _, _) ->
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
	 * Verifies that two sessions that run one after the other in the one slot of a run build their run at the same
	 * paths, which are in the slot of the requesting run's directory, so that the paths that the harness puts into its
	 * instructions repeat from one session to the next.
	 *
	 * @throws IOException if fixture or broker I/O fails
	 */
	@Test(timeOut = 30_000)
	public void sessionsOfOneSlotShareRunPaths() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		try (Fixture fixture = new Fixture("--session-requests", "2", "--max-concurrency", "1"))
		{
			List<SlotRun> runs = new CopyOnWriteArrayList<>();
			try (SessionRequestBroker broker = new SessionRequestBroker(fixture.run, fixture.options,
				(_, options, _) -> inSlot(fixture, fileLocks, options, runs, 0)))
			{
				fixture.request("first");
				broker.scan();
				fixture.await("first");
				fixture.request("second");
				broker.scan();
				fixture.await("second");
			}
			requireThat(runs, "runs").size().isEqualTo(2);
			SlotRun first = runs.getFirst();
			SlotRun second = runs.getLast();
			requireThat(second.runRoot(), "runRoot").isEqualTo(first.runRoot());
			requireThat(second.run().id(), "runId").isEqualTo(first.run().id());
			requireThat(second.run().directory(), "directory").isEqualTo(first.run().directory());
			requireThat(second.run().directory().resolve("home"), "home").
				isEqualTo(first.run().directory().resolve("home"));
			requireThat(second.run().directory().resolve("project"), "project").
				isEqualTo(first.run().directory().resolve("project"));
			requireThat(first.runRoot(), "runRoot").isEqualTo(SessionRequestAccess.requestedRunsDirectory(fixture.run).
				resolve("d1"));
		}
	}

	/**
	 * Verifies that a session that takes a slot finds nothing of the previous session's files, neither in its run's
	 * home and project nor beside them.
	 *
	 * @throws IOException if fixture or broker I/O fails
	 */
	@Test(timeOut = 30_000)
	public void sessionFindsNothingOfPreviousSession() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		try (Fixture fixture = new Fixture("--session-requests", "2", "--max-concurrency", "1"))
		{
			List<SlotRun> runs = new CopyOnWriteArrayList<>();
			try (SessionRequestBroker broker = new SessionRequestBroker(fixture.run, fixture.options,
				(_, options, _) -> inSlot(fixture, fileLocks, options, runs, 0)))
			{
				fixture.request("first");
				broker.scan();
				fixture.await("first");
				fixture.request("second");
				broker.scan();
				fixture.await("second");
			}
			requireThat(runs, "runs").size().isEqualTo(2);
			requireThat(runs.getLast().entriesAtStart(), "entriesAtStart").isEmpty();
		}
	}

	/**
	 * Verifies that a slot is not reused while a process that its previous session's run recorded remains alive: the
	 * run's runner asks the process to end, waits while it lives, and starts the next session once it has ended, in a
	 * slot that holds nothing of the previous session.
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
			List<SlotRun> runs = new CopyOnWriteArrayList<>();
			ScriptedLiveness liveness = new ScriptedLiveness();
			try (SessionRequestBroker broker = new SessionRequestBroker(fixture.run, fixture.options,
				(_, options, _) -> inSlot(fixture, fileLocks, options, runs, 4242), RequestLimits.IN_RUN, liveness))
			{
				fixture.request("first");
				broker.scan();
				fixture.await("first");
				fixture.request("second");
				broker.scan();
				requireThat(liveness.awaitPolls(4), "polled").isTrue();
				requireThat(runs, "runsWhileAlive").size().isEqualTo(1);
				requireThat(liveness.terminations(), "terminations").isGreaterThan(0);
				liveness.end();
				fixture.await("second");
			}
			requireThat(runs, "runs").size().isEqualTo(2);
			requireThat(runs.getLast().entriesAtStart(), "entriesAtStart").isEmpty();
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
			List<SlotRun> runs = new CopyOnWriteArrayList<>();
			CountDownLatch developmentStarted = new CountDownLatch(1);
			CountDownLatch release = new CountDownLatch(1);
			AtomicInteger calls = new AtomicInteger();
			try (SessionRequestBroker broker = new SessionRequestBroker(fixture.run, fixture.options,
				(_, options, _) ->
				{
					FakeHarnessCli process = inSlot(fixture, fileLocks, options, runs, 0);
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
			Path slots = SessionRequestAccess.requestedRunsDirectory(fixture.run);
			requireThat(runs, "runs").size().isEqualTo(2);
			requireThat(runs.getFirst().runRoot(), "development").isEqualTo(slots.resolve("d1"));
			requireThat(runs.getLast().runRoot(), "heldOut").isEqualTo(slots.resolve("h1"));
		}
	}

	/**
	 * Verifies that the copy of a session's kept run, which the requester reads, survives the next session's use of
	 * the slot: each response keeps the files of its own session.
	 *
	 * @throws IOException if fixture or broker I/O fails
	 */
	@Test(timeOut = 30_000)
	public void keptRunSurvivesSlotReuse() throws IOException
	{
		FileLocks fileLocks = new FileLocks();
		try (Fixture fixture = new Fixture("--session-requests", "2", "--max-concurrency", "1"))
		{
			List<SlotRun> runs = new CopyOnWriteArrayList<>();
			try (SessionRequestBroker broker = new SessionRequestBroker(fixture.run, fixture.options,
				(_, options, _) -> inSlot(fixture, fileLocks, options, runs, 0)))
			{
				for (String id : List.of("first", "second"))
				{
					fixture.writeRequest(id, fixture.document().put("keep_run", true));
					broker.scan();
					fixture.await(id);
				}
				requireThat(runs, "runs").size().isEqualTo(2);
				// The second session has taken the slot that the first one left, and the first one's copy remains
				requireThat(Files.readString(fixture.responses.resolve("first/run/result.txt")), "first").
					isEqualTo("1");
				requireThat(Files.readString(fixture.responses.resolve("second/run/result.txt")), "second").
					isEqualTo("2");
			}
		}
	}

	/**
	 * Verifies that a run never has more sessions of one part running at once than its concurrency limit, and that
	 * it does run as many as the limit when requests wait.
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
			List<SlotRun> runs = new CopyOnWriteArrayList<>();
			Set<Path> active = ConcurrentHashMap.newKeySet();
			AtomicInteger running = new AtomicInteger();
			AtomicInteger peak = new AtomicInteger();
			CountDownLatch full = new CountDownLatch(2);
			Semaphore gate = new Semaphore(0);
			try (SessionRequestBroker broker = new SessionRequestBroker(fixture.run, fixture.options,
				(_, options, _) ->
				{
					Path runRoot = Path.of(option(options, "--run-root"));
					requireThat(active.add(runRoot), "slotFree").isTrue();
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
					FakeHarnessCli process = inSlot(fixture, fileLocks, options, runs, 0);
					// The broker releases the slot after the session's exit, so the session must stop counting as
					// running before that exit is observed
					process.whenExited(() ->
					{
						running.decrementAndGet();
						active.remove(runRoot);
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
			requireThat(runs, "runs").size().isEqualTo(4);
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
			try (SessionRequestBroker broker = new SessionRequestBroker(fixture.run, fixture.options, (_, _, _) ->
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
	 * Starts a double of a requested runner that builds its run in the run root and under the run identifier that the
	 * broker names, as the real runner does, and completes.
	 *
	 * @param fixture    the requesting run
	 * @param fileLocks  the file locks of the requested run
	 * @param options    the requested runner's options
	 * @param runs       receives what the runner finds in its slot and builds there
	 * @param harnessPid the identifier of the harness process that the run records, or {@code 0} to record none
	 * @return the runner
	 * @throws IOException if the run cannot be created or the runner refuses to reuse the slot
	 */
	private static FakeHarnessCli inSlot(Fixture fixture, FileLocks fileLocks, List<String> options,
		List<SlotRun> runs, long harnessPid) throws IOException
	{
		Path runRoot = Path.of(option(options, "--run-root"));
		List<String> entries;
		try (Stream<Path> paths = Files.list(runRoot))
		{
			entries = paths.map(path -> path.getFileName().toString()).filter(name -> !name.startsWith(".")).toList();
		}
		HarnessRunManager manager = new HarnessRunManager(fileLocks, runRoot, Clock.systemUTC(),
			Yielding.fileReplacer()).withFixedRunId(option(options, "--run-id"));
		HarnessRun run = manager.create("claude");
		if (harnessPid != 0)
			manager.recordHarnessProcess(run, harnessPid, "abc");
		runs.add(new SlotRun(runRoot, run, entries));
		Files.writeString(run.directory().resolve("left-behind"), "left behind");
		Files.writeString(run.directory().resolve("result.txt"), String.valueOf(runs.size()));
		Path transcript = Files.writeString(Path.of(option(options, "--output")), "bridge");
		HarnessRunReceipt.write(fixture.json, Yielding.fileReplacer(), Path.of(option(options, "--receipt")), run,
			transcript, List.of());
		FakeHarnessCli process = new FakeHarnessCli(_ ->
		{
		});
		process.closeOutputThenExitWhenAwaited();
		return process;
	}

	/**
	 * What a session finds in its slot, and the run that it builds there.
	 *
	 * @param runRoot       the run root that the session received
	 * @param run           the run that it built
	 * @param entriesAtStart the names of the entries of the run root when the session started
	 */
	private record SlotRun(Path runRoot, HarnessRun run, List<String> entriesAtStart)
	{
	}

	/**
	 * Tells which processes live, as the requesting run's runner would see them.
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
	 * Returns a requested runner that answers its turns with the runner's records: it replies to each turn except
	 * {@code hold} and {@code second}, which run until they are interrupted, reports an interrupt and then the
	 * interrupted turn's completion, and on {@code complete} writes its receipt and exits.
	 *
	 * @param fixture   the requesting run
	 * @param fileLocks the file locks of the requested run
	 * @param options   the requested runner's options
	 * @param child     receives the runner, so that it can exit
	 * @return the runner
	 * @throws IOException if the requested run cannot be created
	 */
	private static FakeHarnessCli conversingRunner(Fixture fixture, FileLocks fileLocks, List<String> options,
		AtomicReference<FakeHarnessCli> child) throws IOException
	{
		HarnessRun run = new HarnessRunManager(fileLocks, Path.of(option(options, "--run-root")), Clock.systemUTC(),
			Yielding.fileReplacer()).create("claude");
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
							append(output, turnCompleted(fixture, run, turn));
					}
					case "interrupt" ->
					{
						append(output, "{\"type\":\"interrupted\",\"run_id\":\"" + run.id() + "\"}");
						append(output, turnCompleted(fixture, run, turns.get()));
					}
					case "complete" ->
					{
						append(output, "{\"type\":\"completed\",\"run_id\":\"" + run.id() + "\"}");
						HarnessRunReceipt.write(fixture.json, Yielding.fileReplacer(), Path.of(option(options, "--receipt")),
							run, output, List.of());
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
	 * @param fixture the requesting run
	 * @param run     the requested run
	 * @param turn    the turn's number
	 * @return the record
	 */
	private static String turnCompleted(Fixture fixture, HarnessRun run, int turn)
	{
		ObjectNode record = fixture.json.createObjectNode().put("type", "turn_completed").put("run_id", run.id()).
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
	 * Owns one isolated requesting run and removes it after its broker and assertions finish.
	 */
	private static final class Fixture implements AutoCloseable
	{
		private final TemporaryDirectories cleanup = new TemporaryDirectories();
		private final JsonMapper json = JsonMapper.builder().build();
		private final Path root;
		private final HarnessRun run;
		private final Path project;
		private final Path denied;
		private final Path requests;
		private final Path responses;
		private final HarnessRunnerOptions options;

		/**
		 * Creates a requesting run with capacity for one requested session.
		 *
		 * @throws IOException if setup fails
		 */
		private Fixture() throws IOException
		{
			this("--session-requests", "1");
		}

		/**
		 * Creates a requesting run whose runner options include the given options that govern session requests.
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
				run = new HarnessRunManager(jvm.fileLocks(), root.resolve("runs"),
					Clock.systemUTC(), Yielding.fileReplacer()).create("claude");
				project = run.directory().resolve("project");
				denied = Files.createDirectory(root.resolve("denied"));
				Files.writeString(project.resolve("marker"), "original");
				Files.writeString(project.resolve("prompt"), "test the plugin");
				requests = Files.createDirectory(SessionRequestAccess.requestsDirectory(run));
				responses = Files.createDirectory(SessionRequestAccess.responsesDirectory(run));
				List<String> arguments = new ArrayList<>(List.of("--artifact", root.toString(), "--project",
					project.toString(), "--model", "model-a", "--effort", "effort-a", "--keep-run", "--deny-read",
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
