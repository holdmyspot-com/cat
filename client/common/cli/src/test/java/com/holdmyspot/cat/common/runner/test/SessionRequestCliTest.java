package com.holdmyspot.cat.common.runner.test;

import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.fakes.test.Yielding;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.PortableFiles;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.HarnessRun;
import com.holdmyspot.cat.common.runner.HarnessRunManager;
import com.holdmyspot.cat.common.runner.RequestArchive;
import com.holdmyspot.cat.common.runner.RequestLimits;
import com.holdmyspot.cat.common.runner.SessionRequestAccess;
import com.holdmyspot.cat.common.runner.SessionRequestCli;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.InputStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.FileChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.ByteBuffer;
import java.nio.file.StandardOpenOption;
import com.holdmyspot.cat.common.fakes.test.ManualClock;
import java.time.Clock;
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
import java.util.Set;
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
	 * Verifies that a submission becomes a versioned request and times out with an actionable await command, and that
	 * waiting again exits 0 for a completed session, 2 for a refused request and 1 for a failed session.
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
				requireThat(output.toString(), "output").contains("\"$CAT_REQUEST_SESSION\" --await ");
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
					requireThat(request.path("format_version").intValue(), "version").isEqualTo(3);
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
						"refused").isEqualTo(2);
					Files.writeString(response, "{\"status\":\"failed\"}");
					requireThat(SessionRequestCli.execute(new String[]{"--await", id}, requests, time,
						() ->
						{
							throw new AssertionError("Response exists");
						}, new PrintWriter(new StringWriter())),
						"failed").isEqualTo(1);
				}
			}
		}
	}

	/**
	 * Verifies that a timed-out request prints the PowerShell command that waits for it again.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void printsPowerShellAwaitCommand() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope())
		{
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "await-powershell-"));
				Path requests = Files.createDirectory(root.resolve("requests"));
				Files.createDirectories(root.resolve("responses/first/response.json"));
				ManualClock time = new ManualClock(Instant.EPOCH);
				StringWriter output = new StringWriter();
				int status = SessionRequestCli.execute(new String[]{"--await", "first"}, requests,
					SessionRequestAccess.Shell.POWERSHELL, time,
					() -> time.advance(Duration.ofSeconds(300)), new PrintWriter(output));
				requireThat(status, "status").isEqualTo(3);
				requireThat(output.toString(), "output").contains("& $env:CAT_REQUEST_SESSION --await first");
			}
		}
	}

	/**
	 * Verifies that a request that names a file of extra permissions carries the file's writes and hosts, that a
	 * request without the option carries none, and that an invalid file refuses the request before it is published.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void carriesExtraPermissions() throws IOException
	{
		// Requirement: R-I11
		try (TestJvmScope jvm = new TestJvmScope())
		{
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "request-")).toRealPath();
				Path requests = Files.createDirectory(root.resolve("requests"));
				Path project = Files.createDirectory(root.resolve("project"));
				Files.writeString(project.resolve("marker"), "project bytes");
				Path prompt = Files.writeString(root.resolve("prompt"), "prompt bytes");
				Path cache = root.resolve("cache");
				Path needs = Files.writeString(root.resolve("needs.json"), "{\"format_version\": 1, \"writes\": [" +
					JsonMapper.builder().build().writeValueAsString(cache.toString()) +
					"], \"hosts\": [\"registry.example.com\"]}");
				Path invalid = Files.writeString(root.resolve("invalid.json"), "{\"format_version\": 1, \"logins\": []}");
				ManualClock time = new ManualClock(Instant.EPOCH);
				List<String> base = List.of("--harness", "harness-a", "--project", project.toString(), "--prompt",
					prompt.toString(), "--model", "model-a", "--effort", "effort-a");

				List<String> withFile = new ArrayList<>(base);
				withFile.addAll(List.of("--extra-permissions", needs.toString()));
				SessionRequestCli.execute(withFile.toArray(String[]::new), requests, time,
					() -> time.advance(Duration.ofSeconds(300)), new PrintWriter(new StringWriter()));
				JsonNode request = manifest(requests);
				requireThat(request.path("extra_permissions").path("writes").get(0).asString(), "write").
					isEqualTo(cache.toString());
				requireThat(request.path("extra_permissions").path("hosts").get(0).asString(), "host").
					isEqualTo("registry.example.com");
				try (Stream<Path> files = Files.list(requests))
				{
					for (Path file : files.toList())
						Files.delete(file);
				}

				SessionRequestCli.execute(base.toArray(String[]::new), requests, time,
					() -> time.advance(Duration.ofSeconds(300)), new PrintWriter(new StringWriter()));
				requireThat(manifest(requests).has("extra_permissions"), "plainHasExtra").isFalse();
				try (Stream<Path> files = Files.list(requests))
				{
					for (Path file : files.toList())
						Files.delete(file);
				}

				List<String> withInvalid = new ArrayList<>(base);
				withInvalid.addAll(List.of("--extra-permissions", invalid.toString()));
				try
				{
					SessionRequestCli.execute(withInvalid.toArray(String[]::new), requests, time,
						() -> time.advance(Duration.ofSeconds(300)), new PrintWriter(new StringWriter()));
					requireThat(false, "invalidAccepted").isTrue();
				}
				catch (IllegalArgumentException e)
				{
					requireThat(e.getMessage(), "message").contains("logins").contains(invalid.toString());
				}
				try (Stream<Path> files = Files.list(requests))
				{
					requireThat(files.count(), "published").isEqualTo(0L);
				}
			}
		}
	}

	/**
	 * Reads the manifest of the only request in a directory.
	 *
	 * @param requests the directory
	 * @return the manifest
	 * @throws IOException if the request cannot be read
	 */
	private static JsonNode manifest(Path requests) throws IOException
	{
		try (Stream<Path> files = Files.list(requests))
		{
			Path file = files.findFirst().orElseThrow();
			try (InputStream bytes = Files.newInputStream(file);
				TarArchiveInputStream archive = new TarArchiveInputStream(bytes))
			{
				for (TarArchiveEntry entry = archive.getNextEntry(); entry != null; entry = archive.getNextEntry())
				{
					if (entry.getName().equals("request.json"))
						return JsonMapper.builder().build().readTree(archive.readNBytes(65_536));
				}
			}
		}
		throw new AssertionError("The request has no manifest");
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
					RequestLimits.IN_RUN, time,
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
	 * Verifies that a request carries a project's symbolic link whose target lies inside the project as a link with the
	 * same target, which the receiver extracts as a link that resolves inside the extracted project, and a link whose
	 * target lies outside the project as a read-only copy of the target's file or directory tree, even when a link's
	 * target is longer than a tar header holds. The receiver extracts each copy beside the project and links to it, so
	 * that the run's copy of the extracted project holds read-only copies of them, which the run checks for changes;
	 * the targets stay unchanged, and the run and the extracted request still delete.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void carriesProjectLinks() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "links-")).toRealPath();
			Path requests = Files.createDirectory(root.resolve("requests"));
			Path outside = Files.createDirectories(root.resolve("outside/tree"));
			Files.writeString(root.resolve("outside/notes.md"), "notes");
			Files.writeString(outside.resolve("a.md"), "a");
			Path project = Files.createDirectory(root.resolve("project"));
			Files.writeString(Files.createDirectory(project.resolve("shared")).resolve("guide.md"), "guide");
			Files.createSymbolicLink(Files.createDirectory(project.resolve("docs")).resolve("guide.md"),
				Path.of("../shared/guide.md"));
			Files.createSymbolicLink(project.resolve("alias"), Path.of("shared"));
			// A target longer than a tar header's 100-byte field travels in a long-link header
			Path deep = Path.of("shared", "d".repeat(60), "e".repeat(60));
			Files.writeString(Files.createDirectories(project.resolve(deep)).resolve("deep.md"), "deep");
			Files.createSymbolicLink(project.resolve("deep.md"), deep.resolve("deep.md"));
			Files.createSymbolicLink(project.resolve("notes.md"), Path.of("../outside/notes.md"));
			Files.createSymbolicLink(project.resolve("tree"), outside);
			Path prompt = Files.writeString(root.resolve("prompt"), "prompt bytes");
			ManualClock time = new ManualClock(Instant.EPOCH);
			SessionRequestCli.execute(new String[]{"--harness", "harness-a", "--project", project.toString(),
					"--prompt", prompt.toString(), "--model", "model-a", "--effort", "effort-a"}, requests,
				root.resolve("absent-null-device"), RequestLimits.IN_RUN, time,
				() -> time.advance(Duration.ofSeconds(300)), new PrintWriter(new StringWriter()));
			Path inputs = Files.createDirectory(root.resolve("inputs"));
			try (Stream<Path> files = Files.list(requests))
			{
				RequestArchive.unpack(files.findFirst().orElseThrow(), inputs, RequestLimits.IN_RUN);
			}

			Path copy = inputs.resolve("project");
			for (String name : List.of("docs/guide.md", "alias", "deep.md"))
			{
				requireThat(Files.readSymbolicLink(copy.resolve(name)), name).
					isEqualTo(Files.readSymbolicLink(project.resolve(name)));
				requireThat(copy.resolve(name).toRealPath().startsWith(copy), name + "ResolvesInsideCopy").isTrue();
			}
			requireThat(Files.readString(copy.resolve("alias/guide.md")), "alias").isEqualTo("guide");
			requireThat(Files.readString(copy.resolve("deep.md")), "deep").isEqualTo("deep");
			// The copies of the targets outside the project lie beside it, and the project links to them
			Path linkTargets = inputs.resolve("link-targets").toRealPath();
			for (String name : List.of("notes.md", "tree"))
			{
				requireThat(Files.isSymbolicLink(copy.resolve(name)), name).isTrue();
				requireThat(copy.resolve(name).toRealPath().startsWith(linkTargets), name + "InLinkTargets").isTrue();
			}
			requireThat(Files.readString(copy.resolve("notes.md")), "notes").isEqualTo("notes");
			requireThat(Files.readString(copy.resolve("tree/a.md")), "a").isEqualTo("a");
			requireReadOnlyCopies(copy);

			// The runner copies the extracted project into the run, whose project links to read-only copies of the
			// targets beside it, which the runner checks for changes
			HarnessRunManager manager = new HarnessRunManager(jvm.fileLocks(), root.resolve("runs"), Clock.systemUTC(),
				Yielding.fileReplacer());
			HarnessRun run = manager.create("harness");
			manager.copyProject(copy, run);
			Path runProject = run.directory().resolve("project");
			Path runLinkTargets = HarnessRunManager.linkTargets(run.directory()).toRealPath();
			for (String name : List.of("notes.md", "tree"))
			{
				requireThat(Files.isSymbolicLink(runProject.resolve(name)), name + "InRun").isTrue();
				requireThat(runProject.resolve(name).toRealPath().startsWith(runLinkTargets), name + "InRunLinkTargets").
					isTrue();
			}
			requireReadOnlyCopies(runProject);
			requireThat(manager.changedLinkCopies(run), "unchanged").isEmpty();
			Path changedCopy = runProject.resolve("tree/a.md").toRealPath();
			PortableFiles.clearReadOnly(changedCopy);
			Files.writeString(changedCopy, "changed");
			requireThat(manager.changedLinkCopies(run).orElseThrow(), "changed").
				contains("link-targets/" + runLinkTargets.relativize(changedCopy).toString().replace('\\', '/') +
					" (content changed, made writable)");
			requireThat(Files.readString(root.resolve("outside/notes.md")), "target").isEqualTo("notes");
			requireThat(Files.readString(outside.resolve("a.md")), "treeTarget").isEqualTo("a");
			manager.fail(run, "test cleanup");
			manager.deleteRun(run);
			requireThat(Files.exists(run.directory()), "runDirectory").isFalse();
			TemporaryDirectories.deleteRecursively(inputs);
			requireThat(Files.exists(inputs), "inputs").isFalse();
		}
	}

	/**
	 * Verifies that a copy of the project of {@link #carriesProjectLinks()} holds the copies of its links' targets
	 * outside it read-only: a write to a copied file fails, and adding a file to a copied directory fails where the
	 * file system records POSIX permissions. The project's own files stay writable.
	 *
	 * @param project the copy of the project
	 * @throws IOException if fixture I/O fails
	 */
	private static void requireReadOnlyCopies(Path project) throws IOException
	{
		for (String name : List.of("notes.md", "tree/a.md"))
		{
			try
			{
				Files.writeString(project.resolve(name), "changed");
				throw new AssertionError("A write to the copy of " + name + " succeeded");
			}
			catch (AccessDeniedException _)
			{
				// The copy is read-only
			}
		}
		if (PortableFiles.hasModes(project))
		{
			try
			{
				Files.writeString(project.resolve("tree/added.md"), "added");
				throw new AssertionError("Adding a file to the copied directory succeeded");
			}
			catch (AccessDeniedException _)
			{
				// The copied directory is read-only
			}
		}
		Files.writeString(project.resolve("shared/guide.md"), "edited");
	}

	/**
	 * Verifies that a request carries the mode of each of the project's own files, so that the requested session's
	 * project and run hold each file with the mode that it has in the requesting session's project: a read-only file
	 * stays a read-only file of the project rather than becoming a copy of a link's target, and an editable file keeps
	 * the permissions of its group and of others.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void carriesFileModes() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "modes-")).toRealPath();
			Path requests = Files.createDirectory(root.resolve("requests"));
			Path project = Files.createDirectory(root.resolve("project"));
			Map<String, String> modes = Map.of(
				"shared.md", "rw-r--r--",
				"private.md", "rw-------",
				"read-only.md", "r--------",
				"docs/read-only-shared.md", "r--r--r--",
				"run.sh", "rwxr-x---");
			Files.createDirectory(project.resolve("docs"));
			for (Map.Entry<String, String> file : modes.entrySet())
			{
				Path path = Files.writeString(project.resolve(file.getKey()), file.getKey());
				PortableFiles.setMode(path, PosixFilePermissions.fromString(file.getValue()));
			}
			Path prompt = Files.writeString(root.resolve("prompt"), "prompt bytes");
			ManualClock time = new ManualClock(Instant.EPOCH);
			SessionRequestCli.execute(new String[]{"--harness", "harness-a", "--project", project.toString(),
					"--prompt", prompt.toString(), "--model", "model-a", "--effort", "effort-a"}, requests,
				root.resolve("absent-null-device"), RequestLimits.IN_RUN, time,
				() -> time.advance(Duration.ofSeconds(300)), new PrintWriter(new StringWriter()));
			Path inputs = Files.createDirectory(root.resolve("inputs"));
			try (Stream<Path> files = Files.list(requests))
			{
				RequestArchive.unpack(files.findFirst().orElseThrow(), inputs, RequestLimits.IN_RUN);
			}
			Path copy = inputs.resolve("project");
			HarnessRunManager manager = new HarnessRunManager(jvm.fileLocks(), root.resolve("runs"), Clock.systemUTC(),
				Yielding.fileReplacer());
			HarnessRun run = manager.create("harness");
			manager.copyProject(copy, run);
			Path runProject = run.directory().resolve("project");
			for (String name : modes.keySet())
			{
				Set<PosixFilePermission> expected = PortableFiles.mode(project.resolve(name));
				for (Path received : List.of(copy.resolve(name), runProject.resolve(name)))
				{
					requireThat(Files.isRegularFile(received, LinkOption.NOFOLLOW_LINKS), received + "IsFile").isTrue();
					requireThat(PortableFiles.mode(received), received + "Mode").isEqualTo(expected);
					requireThat(Files.readString(received), received + "Content").isEqualTo(name);
				}
			}
			requireThat(Files.exists(inputs.resolve("link-targets")), "linkTargets").isFalse();
			requireThat(manager.changedLinkCopies(run), "changedLinkCopies").isEmpty();
			manager.fail(run, "test cleanup");
			manager.deleteRun(run);
			TemporaryDirectories.deleteRecursively(inputs);
		}
	}

	/**
	 * Supplies project links that a request refuses to carry.
	 *
	 * @return the name of the link
	 */
	@DataProvider
	public Object[][] refusedProjectLinks()
	{
		return new Object[][]{{"missing"}, {"hidden"}};
	}

	/**
	 * Verifies that packing refuses, naming it, a project's symbolic link whose target does not exist, and one whose
	 * target the session's sandbox hides behind the null device, and publishes no request.
	 *
	 * @param name the name of the link
	 * @throws IOException if fixture I/O fails
	 */
	@Test(dataProvider = "refusedProjectLinks")
	public void refusesUnusableProjectLink(String name) throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "links-")).toRealPath();
			Path requests = Files.createDirectory(root.resolve("requests"));
			Path project = Files.createDirectory(root.resolve("project"));
			// A sandbox mounts the null device itself over each protected file; a regular file stands in for it, since
			// creating a device requires administrator rights.
			Path nullDevice = Files.writeString(root.resolve("null-device"), "");
			Path target = root.resolve("absent.md");
			if (name.equals("hidden"))
				target = nullDevice;
			Files.createSymbolicLink(project.resolve(name), target);
			Path prompt = Files.writeString(root.resolve("prompt"), "prompt bytes");

			try
			{
				SessionRequestCli.execute(new String[]{"--harness", "harness-a", "--project", project.toString(),
						"--prompt", prompt.toString(), "--model", "model-a", "--effort", "effort-a"}, requests, nullDevice,
					RequestLimits.IN_RUN, new ManualClock(Instant.EPOCH), Thread::yield, new PrintWriter(new StringWriter()));
				throw new AssertionError("The request was published");
			}
			catch (IOException e)
			{
				requireThat(e.getMessage(), "message").contains("symbolic link " + name + " ").
					contains(target.toString());
			}
			try (Stream<Path> files = Files.list(requests))
			{
				requireThat(files.count(), "publishedOrTemporaryFiles").isEqualTo(0);
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
		return new Object[][]{{"prompt_link"}, {"ancestor_link"}, {"prompt_size"}, {"file_size"}, {"entry_count"},
			{"linked_tree_size"}, {"total_size"}, {"special_file"}};
	}

	/**
	 * Verifies that packing rejects links anywhere in an input path and special files other than the null device, and
	 * enforces prompt and tree-file bounds, which also count the copy of a directory that a project's link leads to.
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
				RequestLimits limits = RequestLimits.IN_RUN;
				if (kind.equals("entry_count"))
				{
					limits = new RequestLimits(10, limits.entryBytes(), limits.totalBytes(), limits.archiveBytes());
					// With the project directory itself, the tree holds one entry more than the bound
					for (int index = 0; index < limits.entries(); ++index)
						Files.createDirectory(project.resolve("empty" + index));
				}
				if (kind.equals("linked_tree_size"))
				{
					limits = new RequestLimits(10, limits.entryBytes(), limits.totalBytes(), limits.archiveBytes());
					// With the project directory and the copy of the linked directory, the tree holds two entries more than
					// the bound
					Path outside = Files.createDirectory(root.resolve("outside"));
					for (int index = 0; index < limits.entries(); ++index)
						Files.writeString(outside.resolve("file" + index), "file");
					Files.createSymbolicLink(project.resolve("linked"), outside);
				}
				if (kind.equals("total_size"))
				{
					limits = new RequestLimits(limits.entries(), limits.entryBytes(), 1000, limits.archiveBytes());
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
	 * Verifies that awaiting a request returns its response when the response file exists but cannot be read at the
	 * first check and can be read at a later check before the deadline.
	 * <p>
	 * On Windows the broker's freshly moved {@code response.json} can be held open briefly by another process, so the
	 * awaiting session's read fails; some file systems can also briefly show an incomplete document. In the
	 * {@code locked} case a directory at the response path stands for the held file: it exists, and reading it fails.
	 * In the {@code incomplete} case the file holds a truncated document. Between checks, the pause replaces either one
	 * with the broker's response, as the other process releasing the file would.
	 *
	 * @param kind the way the first read fails
	 * @throws IOException if fixture I/O fails
	 */
	@Test(dataProvider = "unreadableResponses")
	public void retriesUnreadableResponse(String kind) throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope())
		{
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "await-"));
				Path requests = Files.createDirectory(root.resolve("requests"));
				Path response = root.resolve("responses/first/response.json");
				if (kind.equals("locked"))
					Files.createDirectories(response);
				else
					Files.writeString(Files.createDirectories(response.getParent()).resolve("response.json"), "{\"status\":");
				StringWriter output = new StringWriter();
				int status = SessionRequestCli.execute(new String[]{"--await", "first"}, requests,
					new ManualClock(Instant.EPOCH), () ->
					{
						Files.delete(response);
						Files.writeString(response, "{\"status\":\"completed\"}");
					}, new PrintWriter(output));
				requireThat(status, "status").isEqualTo(0);
				requireThat(output.toString().strip(), "output").isEqualTo(response.toString());
			}
		}
	}

	/**
	 * Returns the ways that the first read of an existing response can fail.
	 *
	 * @return {@code locked} for a file that another process holds, {@code incomplete} for a truncated document
	 */
	@DataProvider
	public Object[][] unreadableResponses()
	{
		return new Object[][]{{"locked"}, {"incomplete"}};
	}

	/**
	 * Verifies that awaiting a request whose response file still cannot be read at the deadline times out with the
	 * follow-up command and names the file that could not be read.
	 *
	 * @throws IOException if fixture I/O fails
	 */
	@Test
	public void reportsUnreadableResponseAtDeadline() throws IOException
	{
		try (TestJvmScope jvm = new TestJvmScope())
		{
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "await-"));
				Path requests = Files.createDirectory(root.resolve("requests"));
				// A directory at the response path exists, and reading it fails, like a file that another process keeps
				// open.
				Path response = Files.createDirectories(root.resolve("responses/first/response.json"));
				ManualClock time = new ManualClock(Instant.EPOCH);
				StringWriter output = new StringWriter();
				int status = SessionRequestCli.execute(new String[]{"--await", "first"}, requests, time,
					() -> time.advance(Duration.ofSeconds(300)), new PrintWriter(output));
				requireThat(status, "status").isEqualTo(3);
				requireThat(output.toString(), "output").contains("id=first").
					contains("\"$CAT_REQUEST_SESSION\" --await first").contains("unreadable=" + response);
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
