package com.holdmyspot.cat.common.runner.test;

import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.RequestLimits;
import com.holdmyspot.cat.common.runner.SessionRequestCli;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.FileChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.ByteBuffer;
import java.nio.file.StandardOpenOption;
import com.holdmyspot.cat.common.fakes.test.ManualClock;
import java.time.Duration;
import java.time.Instant;
import org.testng.annotations.Test;
import org.testng.annotations.DataProvider;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Tests file submission, bounded waiting, and resuming a request through {@link SessionRequestCli}.
 */
public final class SessionRequestCliTest
{
	/**
	 * Creates the test.
	 */
	public SessionRequestCliTest()
	{
	}

	/**
	 * Verifies that a submission becomes a versioned request and times out with an actionable await command.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void submitsThenAwaits() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-I9
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				// The launcher refuses a link anywhere in an input's path, and macOS reaches its temporary directory
				// through /var, a link to /private/var.
				Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "request-")).toRealPath();
				Path requests = Files.createDirectory(root.resolve("requests"));
				Path project = Files.createDirectory(root.resolve("project"));
				Files.writeString(project.resolve("marker"), "project bytes");
				Files.writeString(project.resolve("long".repeat(40)), "long path bytes");
				Path prompt = Files.writeString(root.resolve("prompt"), "prompt bytes");
				Path second = Files.writeString(root.resolve("second"), "second prompt");
				Path plugin = Files.createDirectory(root.resolve("plugin"));
				Files.writeString(plugin.resolve("plugin.txt"), "plugin bytes");
				ManualClock time = new ManualClock(Instant.EPOCH);
				StringWriter output = new StringWriter();
				int status = SessionRequestCli.execute(new String[]{"--harness", "harness-a", "--project", project.toString(),
					"--prompt", prompt.toString(), "--prompt", second.toString(), "--plugin", plugin.toString(),
					"--model", "model-a", "--effort", "effort-a", "--install-cat"}, requests, time,
					() -> time.advance(Duration.ofSeconds(300)), new PrintWriter(output));
				requireThat(status, "status").isEqualTo(3);
				requireThat(output.toString(), "output").contains("--await");
				try (Stream<Path> files = Files.list(requests))
				{
					Path file = files.findFirst().orElseThrow();
					requireThat(file.getFileName().toString(), "archive").endsWith(".tar");
					Map<String, String> entries = new HashMap<>();
					try (InputStream bytes = Files.newInputStream(file);
						TarArchiveInputStream archive = new TarArchiveInputStream(bytes))
					{
						for (TarArchiveEntry entry = archive.getNextEntry(); entry != null; entry = archive.getNextEntry())
							entries.put(entry.getName(), new String(archive.readNBytes(65_536), StandardCharsets.UTF_8));
					}
					requireThat(entries.get("project/marker"), "project").isEqualTo("project bytes");
					requireThat(entries.get("project/" + "long".repeat(40)), "longPath").isEqualTo("long path bytes");
					requireThat(entries.get("prompts/0"), "first").isEqualTo("prompt bytes");
					requireThat(entries.get("prompts/1"), "second").isEqualTo("second prompt");
					requireThat(entries.get("plugins/0/plugin.txt"), "plugin").isEqualTo("plugin bytes");
					JsonNode request = JsonMapper.builder().build().readTree(entries.get("request.json"));
					requireThat(request.path("format_version").intValue(), "version").isEqualTo(2);
					requireThat(request.path("prompts").get(1).asString(), "promptEntry").isEqualTo("prompts/1");
					requireThat(request.path("install_cat").asBoolean(false), "installCat").isTrue();
					requireThat(request.path("model").asString(), "model").isEqualTo("model-a");
					requireThat(request.path("effort").asString(), "effort").isEqualTo("effort-a");
					String id = file.getFileName().toString().replace(".tar", "");
					Path response = Files.createDirectories(root.resolve("responses").resolve(id)).resolve("response.json");
					Files.writeString(response, "{\"status\":\"completed\"}");
					StringWriter resumed = new StringWriter();
					requireThat(SessionRequestCli.execute(new String[]{"--await", id}, requests, time,
						() ->
						{
							throw new AssertionError("Response exists");
						}, new PrintWriter(resumed)), "resumed").isEqualTo(0);
					requireThat(resumed.toString().strip(), "response").isEqualTo(response.toString());
					Files.writeString(response, "{\"status\":\"refused\"}");
					requireThat(SessionRequestCli.execute(new String[]{"--await", id}, requests, time,
						() ->
						{
							throw new AssertionError("Response exists");
						}, new PrintWriter(new StringWriter())),
						"refused").isEqualTo(1);
				}
			}
		}
	}

	/**
	 * Verifies that a request carries the project as its session sees it, leaving out the null-device placeholders
	 * that the session's sandbox puts over files that it may not read.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void omitsSandboxPlaceholders() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-I9
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "placeholder-")).toRealPath();
				Path requests = Files.createDirectory(root.resolve("requests"));
				Path project = Files.createDirectory(root.resolve("project"));
				Files.writeString(project.resolve("marker"), "project bytes");
				// A sandbox mounts the null device itself over each protected file; a second name of one file stands in for
				// it, since creating a device requires administrator rights.
				Path nullDevice = Files.writeString(root.resolve("null-device"), "");
				Files.createLink(project.resolve(".credentials.json"), nullDevice);
				Path prompt = Files.writeString(root.resolve("prompt"), "prompt bytes");
				ManualClock time = new ManualClock(Instant.EPOCH);
				SessionRequestCli.execute(new String[]{"--harness", "harness-a", "--project", project.toString(),
					"--prompt", prompt.toString(), "--model", "model-a", "--effort", "effort-a"}, requests, nullDevice,
					RequestLimits.DEFAULT, time,
					() -> time.advance(Duration.ofSeconds(300)), new PrintWriter(new StringWriter()));
				Map<String, String> entries = new HashMap<>();
				try (Stream<Path> files = Files.list(requests);
					InputStream bytes = Files.newInputStream(files.findFirst().orElseThrow());
					TarArchiveInputStream archive = new TarArchiveInputStream(bytes))
				{
					for (TarArchiveEntry entry = archive.getNextEntry(); entry != null; entry = archive.getNextEntry())
						entries.put(entry.getName(), new String(archive.readNBytes(65_536), StandardCharsets.UTF_8));
				}
				requireThat(entries.get("project/marker"), "project").isEqualTo("project bytes");
				requireThat(entries.keySet(), "entries").doesNotContain("project/.credentials.json");
			}
		}
	}

	/**
	 * Supplies launcher inputs that must fail before an archive is published.
	 *
	 * @return unsafe or oversized input categories
	 */
	@DataProvider
	public Object[][] unsafeInputs()
	{
		return new Object[][]{{"tree_link"}, {"prompt_link"}, {"ancestor_link"}, {"prompt_size"}, {"file_size"},
			{"entry_count"}, {"total_size"}, {"special_file"}};
	}

	/**
	 * Verifies that packing rejects links anywhere in an input path and special files other than the null device, and
	 * enforces prompt and tree-file bounds.
	 * <p>
	 * The entry-count and total-size cases pack under bounds of 10 entries and 1,000 bytes, so that a few small files
	 * cross them; the per-file and prompt cases use the bounds of every request.
	 *
	 * @param kind the unsafe input category
	 * @throws IOException          if fixture I/O fails or packing rejects the input
	 * @throws InterruptedException if the thread is interrupted while creating a named pipe
	 */
	@Test(dataProvider = "unsafeInputs", expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*(symbolic link|special types|bounds).*", timeOut = 30_000)
	public void refusesUnsafeInputs(String kind) throws IOException, InterruptedException
	{
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-I9
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "pack-"));
				Path requests = Files.createDirectory(root.resolve("requests"));
				Path project = Files.createDirectory(root.resolve("project"));
				Path prompt = Files.writeString(root.resolve("prompt"), "prompt");
				if (kind.equals("tree_link"))
					Files.createSymbolicLink(project.resolve("link"), prompt);
				if (kind.equals("prompt_link"))
					prompt = Files.createSymbolicLink(root.resolve("linked"), prompt);
				if (kind.equals("ancestor_link"))
					project = Files.createSymbolicLink(root.resolve("linked"), project);
				if (kind.equals("prompt_size") || kind.equals("file_size"))
				{
					Path oversized = prompt;
					long size = 1_048_576;
					if (kind.equals("file_size"))
					{
						oversized = Files.createFile(project.resolve("large"));
						size = 67_108_864;
					}
					try (FileChannel channel = FileChannel.open(oversized, StandardOpenOption.WRITE))
					{
						channel.position(size);
						channel.write(ByteBuffer.wrap(new byte[]{0}));
					}
				}
				if (kind.equals("special_file"))
				{
					// Windows has no named pipes in its file system, and a sandbox may refuse to bind a Unix socket.
					if (Platform.current().isWindows())
					{
						try (ServerSocketChannel socket = ServerSocketChannel.open(StandardProtocolFamily.UNIX))
						{
							socket.bind(UnixDomainSocketAddress.of(project.resolve("socket")));
						}
					}
					else
					{
						try (Process mkfifo = new ProcessBuilder("mkfifo", project.resolve("pipe").toString()).
							redirectErrorStream(true).start())
						{
							requireThat(mkfifo.waitFor(), "mkfifo").isEqualTo(0);
						}
					}
				}
				RequestLimits limits = RequestLimits.DEFAULT;
				if (kind.equals("entry_count"))
				{
					limits = new RequestLimits(10, limits.totalBytes(), limits.archiveBytes());
					// With the project directory itself, the tree holds one entry more than the bound
					for (int index = 0; index < limits.entries(); ++index)
						Files.createDirectory(project.resolve("empty" + index));
				}
				if (kind.equals("total_size"))
				{
					limits = new RequestLimits(limits.entries(), 1000, limits.archiveBytes());
					// Each file is within the per-file bound, but together they exceed the total
					for (int index = 0; index < 2; ++index)
						Files.write(project.resolve("large" + index), new byte[600]);
				}

				try
				{
					// The null device is the one a sandbox mounts over each file that the session may not read
					SessionRequestCli.execute(new String[]{"--harness", "harness-a", "--project", project.toString(),
						"--prompt", prompt.toString(), "--model", "model-a", "--effort", "effort-a"}, requests,
						Path.of("/dev/null"), limits,
						new ManualClock(Instant.EPOCH), Thread::yield, new PrintWriter(new StringWriter()));
				}
				finally
				{
					try (Stream<Path> files = Files.list(requests))
					{
						requireThat(files.count(), "publishedOrTemporaryFiles").isEqualTo(0);
					}
				}
			}
		}
	}

	/**
	 * Returns requests that lack a model, an effort or both, each with the start of the refusal.
	 *
	 * @return the profile options of each request and the refusal's expected start
	 */
	@DataProvider
	public Object[][] incompleteProfiles()
	{
		return new Object[][]{{List.of("--effort", "low"), "Missing --model."},
			{List.of("--model", "gpt-6-luna"), "Missing --effort."},
			{List.of(), "Missing --model and --effort."}};
	}

	/**
	 * Verifies that a session request without {@code --model} or {@code --effort} is refused with a message that names
	 * the missing option and where to list the supported values, and publishes no request.
	 *
	 * @param profile  the request's profile options
	 * @param expected the start of the refusal
	 * @throws IOException if fixture I/O fails
	 */
	@Test(dataProvider = "incompleteProfiles")
	public void refusesRequestWithoutProfile(List<String> profile, String expected) throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-I9
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "profile-")).toRealPath();
				Path requests = Files.createDirectory(root.resolve("requests"));
				Path project = Files.createDirectory(root.resolve("project"));
				Path prompt = Files.writeString(root.resolve("prompt"), "prompt bytes");
				List<String> arguments = new ArrayList<>(List.of("--harness", "codex", "--project", project.toString(),
					"--prompt", prompt.toString()));
				arguments.addAll(profile);
				try
				{
					SessionRequestCli.execute(arguments.toArray(String[]::new), requests, new ManualClock(Instant.EPOCH),
						() ->
						{
							throw new AssertionError("The request was published");
						}, new PrintWriter(new StringWriter()));
					throw new AssertionError("The request was not refused");
				}
				catch (IllegalArgumentException e)
				{
					requireThat(e.getMessage(), "message").startsWith(expected).contains("`codex debug models`");
				}
				try (Stream<Path> files = Files.list(requests))
				{
					requireThat(files.count(), "requests").isEqualTo(0);
				}
			}
		}
	}

	/**
	 * Verifies that awaiting an existing request rejects submission options.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test(expectedExceptions = IllegalArgumentException.class, expectedExceptionsMessageRegExp = ".*--await.*")
	public void rejectsMixedAwaitOptions() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope())
		{
			// Requirement: R-I9
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "await-"));
				Path requests = Files.createDirectory(root.resolve("requests"));
				Files.writeString(Files.createDirectories(root.resolve("responses/first")).resolve("response.json"),
					"{\"status\":\"completed\"}");
				SessionRequestCli.execute(new String[]{"--await", "first", "--project", root.toString()}, requests,
					new ManualClock(Instant.EPOCH), Thread::yield, new PrintWriter(new StringWriter()));
			}
		}
	}
}
