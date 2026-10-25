package com.holdmyspot.cat.common.runner.test;

import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.SessionRequestCli;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.nio.file.StandardOpenOption;
import com.holdmyspot.cat.common.fakes.test.ManualClock;
import java.time.Duration;
import java.time.Instant;
import org.testng.annotations.Test;
import org.testng.annotations.DataProvider;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import java.util.HashMap;
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
					"--install-cat"}, requests, time,
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
	 * Supplies launcher inputs that must fail before an archive is published.
	 *
	 * @return unsafe or oversized input categories
	 */
	@DataProvider
	public Object[][] unsafeInputs()
	{
		return new Object[][]{{"tree_link"}, {"prompt_link"}, {"ancestor_link"}, {"prompt_size"}, {"file_size"},
			{"entry_count"}, {"total_size"}};
	}

	/**
	 * Verifies that packing rejects links anywhere in an input path and enforces prompt and tree-file bounds.
	 * <p>
	 * The entry-count case creates 10,001 directories, and the total-size case packs files until it crosses the
	 * cumulative byte limit. Creating, reading and removing these fixtures can take several seconds; inputs below
	 * those limits would not exercise the corresponding refusals.
	 *
	 * @param kind the unsafe input category
	 * @throws IOException if fixture I/O fails or packing rejects the input
	 */
	@Test(dataProvider = "unsafeInputs", expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*(symbolic link|bounds).*", timeOut = 30_000)
	public void refusesUnsafeInputs(String kind) throws IOException
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
				if (kind.equals("entry_count"))
				{
					for (int index = 0; index < 10_001; ++index)
						Files.createDirectory(project.resolve("empty" + index));
				}
				if (kind.equals("total_size"))
				{
					for (int index = 0; index < 5; ++index)
					{
						try (FileChannel channel = FileChannel.open(project.resolve("large" + index),
							StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))
						{
							channel.position(67_108_863);
							channel.write(ByteBuffer.wrap(new byte[]{0}));
						}
					}
				}

				try
				{
					SessionRequestCli.execute(new String[]{"--harness", "harness-a", "--project", project.toString(),
						"--prompt", prompt.toString()}, requests, new ManualClock(Instant.EPOCH), Thread::yield,
						new PrintWriter(new StringWriter()));
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
