package com.holdmyspot.cat.common.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.runner.RequestArchive;
import com.holdmyspot.cat.common.runner.RequestLimits;
import com.holdmyspot.cat.common.runner.SessionLauncherChannel;
import com.holdmyspot.cat.common.runner.SessionLauncherChannel.Request;
import com.holdmyspot.cat.common.runner.SessionLauncherChannel.Response;
import com.holdmyspot.cat.common.runner.SessionLauncherChannel.Status;
import com.holdmyspot.cat.common.runner.policy.ExtraPermissions;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests the session launcher channel's request intake: a request that a sandboxed session packs reaches the launcher
 * with every choice that the session level allows, and a request that chooses anything else is refused by name.
 */
public final class SessionLauncherChannelTest
{
	private final JsonMapper json = JsonMapper.builder().build();

	/**
	 * Creates the test.
	 */
	public SessionLauncherChannelTest()
	{
	}

	/**
	 * Supplies each choice that a session-level request may make, as the field that the request sets.
	 *
	 * @return the field names
	 */
	@DataProvider
	public Object[][] sessionFields()
	{
		return new Object[][]{{"harness"}, {"model_effort"}, {"agent"}, {"turns"}, {"input"}, {"project"},
			{"plugins"}, {"workspace"}, {"read_only_evidence"}, {"install_cat"}, {"session_transcripts"},
			{"keep_run"}, {"session_requests"}, {"request_depth"}, {"capacity_wait"}, {"max_concurrency"},
			{"capacity"}, {"instruction_files"}, {"tool_output_token_limit"}, {"deny_read"}, {"git_name"},
			{"git_email"}, {"extra_permissions"}, {"asking_thread"}};
	}

	/**
	 * Verifies that a request that sets one session-level field reaches the launcher with that field's value, and
	 * with the content of every file and directory that the request carries.
	 *
	 * @param field the field that the request sets
	 * @throws IOException if fixture I/O fails or the launcher refuses the request
	 */
	@Test(dataProvider = "sessionFields")
	public void roundTripsSessionField(String field) throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Request sent = fixture.requestSetting(field);
			Request received = fixture.receive(sent);
			fixture.requireSame(sent, received);
		}
	}

	/**
	 * Supplies the fields of a session-level manifest that a request may not choose, each with a value of the type
	 * that a sender would give it.
	 *
	 * @return the field name and its value
	 */
	@DataProvider
	public Object[][] forbiddenFields()
	{
		return new Object[][]{{"artifact", "\"/opt/cat\""}, {"runner", "\"/opt/cat/bin/claude-runner\""},
			{"harness_executable", "\"/usr/bin/claude\""}, {"run_root", "\"/home/user/runs\""},
			{"environment", "{\"PATH\": \"/bin\"}"}, {"allow_write", "[\"/home/user\"]"},
			{"allow_read", "[\"/home/user/.ssh\"]"}, {"network_hosts", "[\"example.com\"]"},
			{"allow_local_binding", "true"}, {"allow_unix_sockets", "[\"/run/docker.sock\"]"},
			{"sandbox", "false"}, {"permission_mode", "\"bypassPermissions\""}, {"cat_home", "\"/home/user/.cat\""},
			{"lock_directory", "\"/home/user/.cat/locks\""}, {"prompts", "[\"prompts/0\"]"}};
	}

	/**
	 * Verifies that the launcher refuses a session-level request that chooses something that only the launcher
	 * decides, naming the field.
	 *
	 * @param field the forbidden field
	 * @param value its JSON value
	 * @throws IOException if fixture I/O fails
	 */
	@Test(dataProvider = "forbiddenFields")
	public void refusesForbiddenField(String field, String value) throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			ObjectNode manifest = fixture.minimalManifest();
			manifest.set(field, json.readTree(value));
			fixture.requireRefusal(manifest, field);
		}
	}

	/**
	 * Supplies the fields that carry a file or directory, each with a host path in place of the archive entry that
	 * the request must name.
	 *
	 * @return the field name and its JSON value
	 */
	@DataProvider
	public Object[][] hostPaths()
	{
		return new Object[][]{{"project", "\"/home/user/project\""}, {"turns", "\"/home/user/turns.jsonl\""},
			{"plugins", "[\"/home/user/plugin\"]"}, {"workspace", "\"/home/user/workspace\""},
			{"read_only_evidence", "[\"/home/user/evidence\"]"}};
	}

	/**
	 * Verifies that the launcher refuses a request that names a host path where it must name an entry of its own
	 * archive, so that the launcher never opens a path that the parent session chose.
	 *
	 * @param field the field
	 * @param value its JSON value, which names a host path
	 * @throws IOException if fixture I/O fails
	 */
	@Test(dataProvider = "hostPaths")
	public void refusesHostPath(String field, String value) throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			ObjectNode manifest = fixture.minimalManifest();
			manifest.set(field, json.readTree(value));
			fixture.requireRefusal(manifest, field);
		}
	}

	/**
	 * Verifies that the launcher refuses a relative denied read, the one field whose values are host paths.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void refusesRelativeDeniedRead() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			ObjectNode manifest = fixture.minimalManifest();
			manifest.putArray("deny_read").add("relative/secret");
			fixture.requireRefusal(manifest, "deny_read");
		}
	}

	/**
	 * Verifies that the launcher refuses a request whose manifest has the format of an earlier CAT version, which
	 * cannot say which thread asks, and tells the sender to start a new session.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void refusesEarlierFormatVersion() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			ObjectNode manifest = fixture.minimalManifest();
			manifest.put("format_version", 3);
			fixture.requireRefusal(manifest, "start a new session");
		}
	}

	/**
	 * Supplies values of the asking thread that a sender's harness never gives a thread.
	 *
	 * @return the field's JSON value
	 */
	@DataProvider
	public Object[][] malformedAskingThreads()
	{
		return new Object[][]{{"5"}, {"\"\""}, {"\" \""}, {"[\"thread-a\"]"}};
	}

	/**
	 * Verifies that the launcher refuses a request whose asking thread is not a nonblank text, naming the field, so
	 * that a malformed field can never stand for the main thread.
	 *
	 * @param value the field's JSON value
	 * @throws IOException if fixture I/O fails
	 */
	@Test(dataProvider = "malformedAskingThreads")
	public void refusesMalformedAskingThread(String value) throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			ObjectNode manifest = fixture.minimalManifest();
			manifest.set("asking_thread", json.readTree(value));
			fixture.requireRefusal(manifest, "asking_thread");
		}
	}

	/**
	 * Supplies extra permissions that the launcher must refuse, each with the text that the refusal names.
	 *
	 * @return the permissions' JSON and the text that the refusal names
	 */
	@DataProvider
	public Object[][] invalidExtraPermissions()
	{
		return new Object[][]{{"{\"format_version\": 1, \"writes\": [\"relative/cache\"]}", "relative/cache"},
			{"{\"format_version\": 1, \"hosts\": [\"*.example\"]}", "*.example"},
			{"{\"format_version\": 1, \"logins\": []}", "logins"},
			{"{\"format_version\": 2}", "format_version"}, {"[]", "extra_permissions"}};
	}

	/**
	 * Verifies that the launcher refuses extra permissions that name a relative path, a host pattern or an unknown
	 * field, as the runner refuses them in a file, so that the launcher never grants what a sender could not name.
	 *
	 * @param permissions the permissions' JSON
	 * @param problem     the text that the refusal names
	 * @throws IOException if fixture I/O fails
	 */
	@Test(dataProvider = "invalidExtraPermissions")
	public void refusesInvalidExtraPermissions(String permissions, String problem) throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			ObjectNode manifest = fixture.minimalManifest();
			manifest.set("extra_permissions", json.readTree(permissions));
			fixture.requireRefusal(manifest, problem);
		}
	}

	/**
	 * Verifies that a session-level request carries its project as the session sees it, leaving out the null-device
	 * placeholders that the session's sandbox lays over files that it may not read.
	 *
	 * @throws IOException if fixture I/O fails or the launcher refuses the request
	 */
	@Test
	public void omitsSandboxPlaceholders() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			// A sandbox mounts the null device itself over each protected file; a second name of one file stands in for
			// it, since creating a device requires administrator rights.
			Path nullDevice = Files.writeString(fixture.root.resolve("null-device"), "");
			Files.createLink(fixture.project.resolve(".credentials.json"), nullDevice);
			Files.createLink(fixture.workspace.resolve(".credentials.json"), nullDevice);
			Request sent = fixture.requestSetting("workspace");
			Request received = fixture.receive(sent, nullDevice);
			requireThat(Files.readString(received.project().resolve("marker")), "project").isEqualTo("project bytes");
			requireThat(Files.exists(received.project().resolve(".credentials.json")), "projectPlaceholder").isFalse();
			requireThat(Files.exists(received.workspace().orElseThrow().resolve(".credentials.json")),
				"workspacePlaceholder").isFalse();
		}
	}

	/**
	 * Verifies that a response that a launcher publishes reads back as the same response.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void roundTripsResponse() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			String id = SessionLauncherChannel.newId();
			Path directory = Files.createDirectory(fixture.root.resolve(id));
			Response sent = new Response(id, "harness-a", Status.CAPACITY, "capacity", Optional.of(75),
				Optional.of("run-1"), Optional.of(directory.resolve("transcript.jsonl")), Optional.empty(),
				Optional.of(directory.resolve("session-transcripts")), Optional.of(directory.resolve("run")));
			Path file = directory.resolve(SessionLauncherChannel.RESPONSE_FILE);
			sent.write(json, file);
			requireThat(Response.read(json, file), "received").isEqualTo(sent);
		}
	}

	/**
	 * Supplies each status with the exit status of a sender whose request ends with it.
	 *
	 * @return the status and its exit status
	 */
	@DataProvider
	public Object[][] exitStatuses()
	{
		return new Object[][]{{Status.COMPLETED, 0}, {Status.CAPACITY, 75}, {Status.REFUSED, 2}, {Status.FAILED, 1},
			{Status.STOPPED, 1}};
	}

	/**
	 * Verifies the exit status of a sender for each way that its request ends.
	 *
	 * @param status   how the request ended
	 * @param expected the sender's exit status
	 */
	@Test(dataProvider = "exitStatuses")
	public void mapsStatusToExitStatus(Status status, int expected)
	{
		requireThat(status.exitStatus(), "exitStatus").isEqualTo(expected);
	}

	/**
	 * Owns the files that one sandboxed sender packs and the launcher's private copies, and removes them afterwards.
	 */
	private final class Fixture implements AutoCloseable
	{
		private final TemporaryDirectories cleanup = new TemporaryDirectories();
		private final Path root;
		private final Path project;
		private final Path workspace;
		private final Path inbox;

		/**
		 * Creates the sender's files: a project, a plugin, a workspace, two pieces of evidence and a records file.
		 *
		 * @throws IOException if setup fails
		 */
		private Fixture() throws IOException
		{
			try (TestJvmScope jvm = new TestJvmScope())
			{
				root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "channel-")).toRealPath();
			}
			project = Files.createDirectory(root.resolve("project"));
			Files.writeString(project.resolve("marker"), "project bytes");
			workspace = Files.createDirectory(root.resolve("workspace"));
			Files.writeString(workspace.resolve("draft.md"), "workspace bytes");
			Path plugin = Files.createDirectory(root.resolve("plugin"));
			Files.writeString(plugin.resolve("plugin.txt"), "plugin bytes");
			Files.writeString(root.resolve("evidence.txt"), "evidence file bytes");
			Path evidence = Files.createDirectory(root.resolve("evidence"));
			Files.writeString(evidence.resolve("trial.json"), "evidence directory bytes");
			Files.writeString(root.resolve("turns.jsonl"),
				"{\"type\":\"turn\",\"prompt\":\"hello\"}\n{\"type\":\"complete\"}\n");
			inbox = Files.createDirectory(root.resolve("inbox"));
		}

		/**
		 * Returns the smallest request that differs from the default in the given field.
		 *
		 * @param field the field
		 * @return the request, naming the sender's files
		 */
		private Request requestSetting(String field)
		{
			String harness = "claude";
			if (field.equals("harness"))
				harness = "codex";
			Optional<String> model = Optional.of("model-a");
			Optional<String> effort = Optional.of("effort-a");
			Optional<String> agent = Optional.empty();
			if (field.equals("agent"))
			{
				model = Optional.empty();
				effort = Optional.empty();
				agent = Optional.of("agent-a");
			}
			Optional<Path> turns = Optional.of(root.resolve("turns.jsonl"));
			if (field.equals("input"))
				turns = Optional.empty();
			List<Path> plugins = List.of();
			if (field.equals("plugins"))
				plugins = List.of(root.resolve("plugin"));
			Optional<Path> requestWorkspace = Optional.empty();
			if (field.equals("workspace"))
				requestWorkspace = Optional.of(workspace);
			List<Path> evidence = List.of();
			if (field.equals("read_only_evidence"))
				evidence = List.of(root.resolve("evidence.txt"), root.resolve("evidence"));
			int sessionRequests = 0;
			int requestDepth = 0;
			if (field.equals("session_requests") || field.equals("request_depth"))
				sessionRequests = 3;
			if (field.equals("request_depth"))
				requestDepth = 2;
			Optional<Duration> capacityWait = Optional.empty();
			if (field.equals("capacity_wait"))
				capacityWait = Optional.of(Duration.ofSeconds(600));
			Optional<Integer> maximumConcurrency = Optional.empty();
			if (field.equals("max_concurrency"))
				maximumConcurrency = Optional.of(4);
			Optional<String> instructionFiles = Optional.empty();
			if (field.equals("instruction_files"))
				instructionFiles = Optional.of("claude-md");
			Optional<Integer> toolOutputTokenLimit = Optional.empty();
			if (field.equals("tool_output_token_limit"))
				toolOutputTokenLimit = Optional.of(20_000);
			List<Path> deniedReads = List.of();
			if (field.equals("deny_read"))
				deniedReads = List.of(root.resolve("secret"), root.resolve("other secret"));
			ExtraPermissions extraPermissions = ExtraPermissions.NONE;
			if (field.equals("extra_permissions"))
			{
				extraPermissions = new ExtraPermissions(List.of(root.resolve("registry cache")),
					Set.of("registry.example"));
			}
			Optional<String> gitName = Optional.empty();
			if (field.equals("git_name"))
				gitName = Optional.of("Ada Lovelace");
			Optional<String> gitEmail = Optional.empty();
			if (field.equals("git_email"))
				gitEmail = Optional.of("ada@example.com");
			Optional<String> askingThread = Optional.empty();
			if (field.equals("asking_thread"))
				askingThread = Optional.of("thread-a");
			boolean keepRun = field.equals("keep_run") || field.equals("session_transcripts");
			return new Request(harness, model, effort, agent, turns, project, plugins, requestWorkspace, evidence,
				field.equals("install_cat"), field.equals("session_transcripts"), keepRun, sessionRequests, requestDepth,
				capacityWait, maximumConcurrency, field.equals("capacity"), instructionFiles, toolOutputTokenLimit,
				deniedReads, extraPermissions, gitName, gitEmail, askingThread);
		}

		/**
		 * Packs a request into the inbox as its sender does, then copies, unpacks and reads it as the launcher does.
		 *
		 * @param request the sender's request
		 * @return the request that the launcher reads
		 * @throws IOException if packing fails or the launcher refuses the request
		 */
		private Request receive(Request request) throws IOException
		{
			return receive(request, root.resolve("no-null-device"));
		}

		/**
		 * Packs a request into the inbox as its sender does, then copies, unpacks and reads it as the launcher does.
		 *
		 * @param request    the sender's request
		 * @param nullDevice the null device of the sender's sandbox
		 * @return the request that the launcher reads
		 * @throws IOException if packing fails or the launcher refuses the request
		 */
		private Request receive(Request request, Path nullDevice) throws IOException
		{
			String id = SessionLauncherChannel.newId();
			Path published = inbox.resolve(SessionLauncherChannel.requestFileName(id));
			RequestArchive.packSession(json, published, request, nullDevice, RequestLimits.UNBOUNDED);
			return read(published);
		}

		/**
		 * Copies, unpacks and reads an inbox file as the launcher does.
		 *
		 * @param published the inbox file
		 * @return the request
		 * @throws IOException if the launcher refuses the request
		 */
		private Request read(Path published) throws IOException
		{
			Path directory = Files.createDirectory(root.resolve("launcher-" + SessionLauncherChannel.newId()));
			Path copy = directory.resolve("request.tar");
			RequestArchive.copy(published, copy, RequestLimits.UNBOUNDED);
			Path inputs = Files.createDirectory(directory.resolve("inputs"));
			RequestArchive.unpack(copy, inputs, RequestLimits.UNBOUNDED);
			return RequestArchive.readSession(json, inputs);
		}

		/**
		 * Returns the manifest of a valid request with a project and a records file, which
		 * {@link #requireRefusal(ObjectNode, String)} packs beside those entries.
		 *
		 * @return the manifest
		 */
		private ObjectNode minimalManifest()
		{
			return json.createObjectNode().put("format_version", 4).put("harness", "claude").put("model", "model-a").
				put("effort", "effort-a").put("turns", "turns").put("project", "project");
		}

		/**
		 * Writes an archive with the manifest, a project and a records file into the inbox, and verifies that the
		 * launcher refuses it with a reason that names the field.
		 *
		 * @param manifest the manifest
		 * @param field    the field that the refusal must name
		 * @throws IOException if fixture I/O fails
		 */
		private void requireRefusal(ObjectNode manifest, String field) throws IOException
		{
			Path published = inbox.resolve(SessionLauncherChannel.requestFileName(SessionLauncherChannel.newId()));
			try (OutputStream bytes = Files.newOutputStream(published);
				TarArchiveOutputStream archive = new TarArchiveOutputStream(bytes))
			{
				entry(archive, "request.json", json.writeValueAsBytes(manifest));
				entry(archive, "project/", new byte[0]);
				entry(archive, "project/marker", "project bytes".getBytes(StandardCharsets.UTF_8));
				entry(archive, "turns", "{\"type\":\"complete\"}\n".getBytes(StandardCharsets.UTF_8));
			}
			try
			{
				read(published);
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "reason").contains(field);
				return;
			}
			throw new AssertionError("The launcher accepted a request that sets " + field);
		}

		/**
		 * Verifies that the launcher's request makes the sender's choices and carries the content of the sender's
		 * files and directories.
		 *
		 * @param sent     the sender's request
		 * @param received the launcher's request
		 * @throws IOException if a copy cannot be read
		 */
		private void requireSame(Request sent, Request received) throws IOException
		{
			Request expected = new Request(sent.harness(), sent.model(), sent.effort(), sent.agent(),
				received.turns(), received.project(), received.plugins(), received.workspace(),
				received.readOnlyEvidence(), sent.installCat(), sent.sessionTranscripts(), sent.keepRun(),
				sent.sessionRequests(), sent.requestDepth(), sent.capacityWait(), sent.maximumConcurrency(),
				sent.capacityHeld(), sent.instructionFiles(), sent.toolOutputTokenLimit(), sent.deniedReads(),
				sent.extraPermissions(), sent.gitName(), sent.gitEmail(), sent.askingThread());
			requireThat(received, "received").isEqualTo(expected);
			requireThat(contents(received.turns()), "turns").isEqualTo(contents(sent.turns()));
			requireThat(contents(Optional.of(received.project())), "project").
				isEqualTo(contents(Optional.of(sent.project())));
			requireThat(contents(received.workspace()), "workspace").isEqualTo(contents(sent.workspace()));
			requireThat(contents(received.plugins()), "plugins").isEqualTo(contents(sent.plugins()));
			requireThat(contents(received.readOnlyEvidence()), "readOnlyEvidence").
				isEqualTo(contents(sent.readOnlyEvidence()));
		}

		/**
		 * Returns the content of each file or directory in order.
		 *
		 * @param paths the files and directories
		 * @return their {@link #contents(Optional)}
		 * @throws IOException if one cannot be read
		 */
		private List<List<String>> contents(List<Path> paths) throws IOException
		{
			List<List<String>> contents = new ArrayList<>();
			for (Path path : paths)
				contents.add(contents(Optional.of(path)));
			return contents;
		}

		/**
		 * Returns the content of a file, or of each file below a directory with its relative name, in name order.
		 *
		 * @param path the file or directory, or empty
		 * @return the content, or an empty list for an empty path
		 * @throws IOException if it cannot be read
		 */
		private List<String> contents(Optional<Path> path) throws IOException
		{
			List<String> contents = new ArrayList<>();
			if (path.isEmpty())
				return contents;
			Path base = path.get();
			if (Files.isRegularFile(base))
				return List.of(Files.readString(base));
			try (Stream<Path> files = Files.walk(base))
			{
				for (Path file : files.filter(Files::isRegularFile).sorted().toList())
					contents.add(base.relativize(file) + "=" + Files.readString(file));
			}
			return contents;
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

		@Override
		public void close() throws IOException
		{
			cleanup.close();
		}
	}
}
