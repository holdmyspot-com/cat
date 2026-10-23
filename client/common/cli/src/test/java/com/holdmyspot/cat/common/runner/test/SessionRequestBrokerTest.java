package com.holdmyspot.cat.common.runner.test;

import com.holdmyspot.cat.common.fakes.test.TestJvmScope;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.Yielding;
import com.holdmyspot.cat.common.runner.HarnessRun;
import com.holdmyspot.cat.common.runner.HarnessRunManager;
import com.holdmyspot.cat.common.runner.HarnessRunReceipt;
import com.holdmyspot.cat.common.runner.HarnessRunnerOptions;
import com.holdmyspot.cat.common.runner.HarnessTemporaryRoot;
import com.holdmyspot.cat.common.runner.SessionRequestAccess;
import com.holdmyspot.cat.common.runner.SessionRequestBroker;
import com.holdmyspot.cat.common.runner.SessionRequestCli;
import com.holdmyspot.cat.common.runner.fakes.test.FakeHarnessCli;
import java.io.IOException;
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
import com.holdmyspot.cat.common.runner.HarnessProcess;
import com.holdmyspot.cat.common.runner.HarnessProcessResult;
import com.holdmyspot.cat.common.runner.HarnessRunSession;
import com.holdmyspot.cat.common.runner.HarnessRunnerBridge;
import com.holdmyspot.cat.common.runner.fakes.test.TestHarnessRunnerScope;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

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
						HarnessRun run = new HarnessRunManager(Path.of(option(options, "--run-root")), Clock.systemUTC(),
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
				requireThat(fixture.await("second").path("reason").asString(), "limit").isEqualTo("limit");
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
			{"link"}, {"hardlink_entry"}, {"special"}, {"duplicate"}, {"directory_duplicate"},
			{"too_many"}, {"large_entry"}, {"pax_chain"}, {"pax_size"}, {"long_chain"}, {"long_size"},
			{"manifest_size"}, {"prompt_size"}, {"total_size"}, {"checksum"}, {"sparse"},
			{"request_link"}, {"request_hardlink"}, {"oversized"},
			{"unknown_field"}, {"wrong_type"}, {"bad_version"}, {"unsupported_harness"}, {"malformed"},
			{"empty"}, {"trailing"}, {"harness_path"}, {"prompt_outside"}, {"plugin_outside"}};
	}

	/**
	 * Verifies that hostile inputs produce refusals and never reach the child starter.
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
			}))
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
			requireThat(Files.exists(fixture.responses.resolve(".temporary")), "temporaryResponse").isFalse();
		}
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
		// Requirement: R-I9
		try (Fixture fixture = new Fixture();
			TestHarnessRunnerScope scope = new TestHarnessRunnerScope();
			FakeHarnessCli child = new FakeHarnessCli(_ ->
			{
			}))
		{
			CountDownLatch started = new CountDownLatch(1);
			CountDownLatch interrupted = new CountDownLatch(1);
			HarnessProcess parent = new HarnessProcess()
			{
				@Override
				public HarnessProcessResult sendTurn(String request) throws IOException
				{
					fixture.request("child");
					scope.heartbeatTicker().tick();
					awaitStarted(started);
					if (mode.equals("interrupt"))
						awaitStarted(interrupted);
					if (mode.equals("failure"))
						throw new IOException("Parent turn failed");
					return new HarnessProcessResult(0, "", "", true, "done", List.of("done"));
				}

				@Override
				public void interrupt()
				{
					interrupted.countDown();
				}

				@Override
				public boolean isAlive()
				{
					return true;
				}

				@Override
				public void close()
				{
				}
			};
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
						new HarnessRunManager(fixture.root.resolve("runs"), Clock.systemUTC(), Yielding.fileReplacer()),
						fixture.options, fixture.json, Yielding.fileReplacer(), input, new StringWriter(), (_, _, _) ->
						{
							started.countDown();
							return child;
						});
				}
				catch (IOException failure)
				{
					requireThat(failure.getMessage(), "failure").isEqualTo("Parent turn failed");
					failed = true;
				}
				requireThat(failed, "failed").isEqualTo(mode.equals("failure"));
			}
			requireThat(child.isAlive(), "childAlive").isFalse();
			requireThat(fixture.await("child").path("status").asString(), "status").isEqualTo("stopped");
		}
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
			try (TestJvmScope jvm = new TestJvmScope())
			{
				root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "broker-"));
				run = new HarnessRunManager(root.resolve("runs"), Clock.systemUTC(), Yielding.fileReplacer()).create("claude");
				project = run.directory().resolve("project");
				denied = Files.createDirectory(root.resolve("denied"));
				Files.writeString(project.resolve("marker"), "original");
				Files.writeString(project.resolve("prompt"), "test the plugin");
				requests = Files.createDirectory(SessionRequestAccess.requestsDirectory(run));
				responses = Files.createDirectory(SessionRequestAccess.responsesDirectory(run));
				options = HarnessRunnerOptions.parse(new String[]{"--artifact", root.toString(), "--project",
					project.toString(), "--keep-run", "--session-requests", "1", "--deny-read", denied.toString()}, "harness",
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
			ObjectNode request = json.createObjectNode().put("format_version", 2).put("harness", "claude").
				put("project", "project").put("session_transcripts", true);
			request.putArray("prompts").add("prompts/0").add("prompts/1");
			request.putArray("plugins").add("plugins/0");
			writeRequest(id, request);
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
					case "backslash" -> entry(archive, "project\\outside", new byte[0]);
					case "duplicate" -> entry(archive, "project/marker", new byte[0]);
					case "directory_duplicate" -> entry(archive, "project/", new byte[0]);
					case "link", "hardlink_entry", "special", "sparse" ->
					{
						byte type = '2';
						if (kind.equals("hardlink_entry"))
							type = '1';
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
						for (int index = 0; index < 10_001; ++index)
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
			if (kind.equals("checksum"))
			{
				byte[] content = Files.readAllBytes(file);
				content[0] ^= 1;
				Files.write(file, content);
			}
			if (kind.equals("total_size"))
			{
				try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE,
					StandardOpenOption.TRUNCATE_EXISTING))
				{
					for (int index = 0; index < 5; ++index)
					{
						TarArchiveEntry entry = new TarArchiveEntry("project/large" + index);
						entry.setSize(67_108_864);
						byte[] header = new byte[512];
						entry.writeEntryHeader(header);
						channel.write(ByteBuffer.wrap(header));
						if (index < 4)
							channel.position(channel.position() + entry.getSize());
					}
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
					channel.position(300_000_000);
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
			try (OutputStream bytes = Files.newOutputStream(requests.resolve(id + ".tar"));
				TarArchiveOutputStream archive = new TarArchiveOutputStream(bytes))
			{
				entry(archive, "request.json", json.writeValueAsBytes(request));
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
