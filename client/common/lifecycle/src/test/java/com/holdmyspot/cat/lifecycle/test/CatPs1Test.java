package com.holdmyspot.cat.lifecycle.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_16LE;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.StringJoiner;
import java.util.stream.Stream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.testng.SkipException;
import org.testng.annotations.Test;

/**
 * Tests the release's PowerShell entry script {@code cat.ps1}, which installs CAT on Windows by running the install of
 * the installer in the release's plugin for the harness. Each test runs the script the way that the installation
 * instructions do, as a script block in Windows PowerShell, against a fake release whose plugin archives hold a
 * {@code bin\installer.ps1} that records how it was run instead of installing anything. A function that copies files
 * from the fake release's folder replaces the cmdlet that downloads them.
 */
public final class CatPs1Test
{
	/**
	 * The folder that holds the entry script templates, found from this module's {@code target/test-classes} folder.
	 */
	private static final Path RELEASE_SOURCES = moduleFolder().resolve("src/main/release");
	/**
	 * The pinned download directory of the fixture's release.
	 */
	private static final String RELEASE_URL = "https://github.com/holdmyspot-com/cat/releases/download/v1.0";
	/**
	 * The top directory of the fixture's Windows plugin archive for Claude Code.
	 */
	private static final String CLAUDE_PLUGIN = "cat-claude-windows-x64-v1.0";
	/**
	 * The top directory of the fixture's Windows plugin archive for Codex.
	 */
	private static final String CODEX_PLUGIN = "cat-codex-windows-x64-v1.0";

	/**
	 * Returns this module's folder.
	 *
	 * @return the folder
	 */
	private static Path moduleFolder()
	{
		try
		{
			Path classes = Path.of(CatPs1Test.class.getProtectionDomain().getCodeSource().getLocation().toURI());
			// target/test-classes is two levels below the module
			return classes.resolve("../..").normalize();
		}
		catch (URISyntaxException e)
		{
			throw new AssertionError(e);
		}
	}

	/** Creates a test instance. */
	public CatPs1Test()
	{
	}

	/**
	 * Verifies that the entry script downloads only the named harness's plugin archive, runs the install of the
	 * plugin's installer for the harness and its home from the extracted archive, beside the plugin's runtime, prints
	 * the installer's output and deletes its temporary folder.
	 *
	 * @throws IOException          if the fixture cannot be written or PowerShell cannot run
	 * @throws InterruptedException if PowerShell is interrupted
	 */
	@Test
	public void runsInstallFromExtractedPlugin() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup);
			fixture.release(false);

			Result result = fixture.run(fixture.entry("-Harness claude"));

			requireThat(result.status(), "status").withContext(result.output(), "output").isEqualTo(0);
			requireThat(result.output(), "output").contains("{\"result\":\"installed\"}");
			requireThat(fixture.downloads(), "downloads").isEqualTo(List.of(RELEASE_URL + "/" + CLAUDE_PLUGIN + ".tar.gz"));
			requireThat(fixture.installArguments(), "installArguments").
				isEqualTo(List.of("--home", fixture.claudeHome.toString(), "install", "--harness", "claude"));
			List<String> location = Files.readAllLines(fixture.installLocation, UTF_8);
			requireThat(location.getFirst(), "launcher").
				endsWith("\\" + CLAUDE_PLUGIN + "\\bin\\installer.ps1");
			requireThat(location.get(1), "runtime").isEqualTo("release runtime");
			requireThat(fixture.temporaryEntries(), "temporaryEntries").isEmpty();
		}
	}

	/**
	 * Verifies that the installer's nonzero exit status reaches the caller as {@code $LASTEXITCODE} and as an error, and
	 * that the temporary folder is still deleted.
	 *
	 * @throws IOException          if the fixture cannot be written or PowerShell cannot run
	 * @throws InterruptedException if PowerShell is interrupted
	 */
	@Test
	public void passesInstallStatusThrough() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup);
			fixture.release(false);

			Result result = fixture.run("$env:FAKE_INSTALL_STATUS = '5'\n" + fixture.entry("-Harness codex"));

			requireThat(result.status(), "status").withContext(result.output(), "output").isEqualTo(5);
			requireThat(result.output(), "output").contains("installer install exited with status 5");
			requireThat(fixture.installArguments(), "installArguments").
				isEqualTo(List.of("--home", fixture.codexHome.toString(), "install", "--harness", "codex"));
			requireThat(fixture.temporaryEntries(), "temporaryEntries").isEmpty();
		}
	}

	/**
	 * Verifies that inside a Claude Code shell the entry script installs CAT for Claude Code without
	 * {@code -Harness}, and that it refuses when the shell belongs to both harnesses or to neither.
	 *
	 * @throws IOException          if the fixture cannot be written or PowerShell cannot run
	 * @throws InterruptedException if PowerShell is interrupted
	 */
	@Test
	public void detectsHarnessOnlyWhenUnambiguous() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup);
			fixture.release(false);

			Result claude = fixture.run("$env:CLAUDECODE = '1'\n" + fixture.entry(""));

			requireThat(claude.status(), "status").withContext(claude.output(), "output").isEqualTo(0);
			requireThat(fixture.installArguments(), "installArguments").
				isEqualTo(List.of("--home", fixture.claudeHome.toString(), "install", "--harness", "claude"));
			Files.delete(fixture.installArguments);

			for (String shell : List.of("", "$env:CLAUDECODE = '1'\n$env:CODEX_THREAD_ID = 'thread'\n"))
			{
				Result ambiguous = fixture.run(shell + fixture.entry(""));

				requireThat(ambiguous.status(), "status").withContext(ambiguous.output(), "output").isEqualTo(1);
				requireThat(ambiguous.output(), "output").contains("-Harness claude or -Harness codex");
				requireThat(fixture.downloads(), "downloads").isEmpty();
				requireThat(Files.exists(fixture.installArguments), "installRan").isFalse();
			}
		}
	}

	/**
	 * Verifies that a plugin archive whose digest differs from the pinned one is refused before extraction or install.
	 *
	 * @throws IOException          if the fixture cannot be written or PowerShell cannot run
	 * @throws InterruptedException if PowerShell is interrupted
	 */
	@Test
	public void rejectsPluginWithWrongChecksum() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup);
			fixture.release(true);

			Result result = fixture.run(fixture.entry("-Harness codex"));

			requireThat(result.status(), "status").withContext(result.output(), "output").isEqualTo(1);
			requireThat(result.output(), "output").contains("does not match the SHA-256 digest");
			requireThat(Files.exists(fixture.installArguments), "installRan").isFalse();
			requireThat(fixture.temporaryEntries(), "temporaryEntries").isEmpty();
		}
	}

	/**
	 * Verifies that each retired switch is refused, before anything is downloaded, with a message that says how to
	 * uninstall CAT.
	 *
	 * @throws IOException          if the fixture cannot be written or PowerShell cannot run
	 * @throws InterruptedException if PowerShell is interrupted
	 */
	@Test
	public void refusesRetiredSwitches() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Fixture fixture = new Fixture(cleanup);
			fixture.release(false);
			for (String retired : List.of("-UpdaterOnly", "-Uninstall", "-DeleteData"))
			{
				Result result = fixture.run(fixture.entry("-Harness codex " + retired));

				requireThat(result.status(), "status").withContext(result.output(), "output").isEqualTo(1);
				requireThat(result.output(), "output").contains(retired + " is no longer supported").
					contains("ask your agent to uninstall CAT");
				requireThat(fixture.downloads(), "downloads").isEmpty();
				requireThat(Files.exists(fixture.installArguments), "installRan").isFalse();
			}
		}
	}

	/**
	 * Returns a PowerShell string literal.
	 *
	 * @param value the string's value
	 * @return the literal
	 */
	private static String literal(Object value)
	{
		return "'" + value.toString().replace("'", "''") + "'";
	}

	/**
	 * The output and exit status of a script.
	 *
	 * @param status the exit status
	 * @param output standard output and standard error
	 */
	private record Result(int status, String output)
	{
	}

	/**
	 * The folders of one test: harness homes, a fake release, a temporary folder for the entry script and the files in
	 * which the fake installer and the fake download record what happened.
	 */
	private static final class Fixture
	{
		private final Path root;
		private final Path claudeHome;
		private final Path codexHome;
		private final Path release;
		private final Path temporary;
		private final Path downloadLog;
		private final Path installArguments;
		private final Path installLocation;

		/**
		 * Creates the folders of a test.
		 *
		 * @param cleanup the folders to delete after the test
		 * @throws IOException if the folders cannot be created
		 */
		private Fixture(TemporaryDirectories cleanup) throws IOException
		{
			if (!Platform.current().isWindows())
				throw new SkipException("cat.ps1 installs CAT only on Windows");
			Path target = Files.createDirectories(moduleFolder().resolve("target"));
			this.root = cleanup.add(Files.createTempDirectory(target, "cat.ps1 entry-")).toAbsolutePath();
			this.claudeHome = root.resolve("claude home");
			this.codexHome = root.resolve("codex home");
			this.release = Files.createDirectories(root.resolve("release"));
			this.temporary = Files.createDirectories(root.resolve("temp"));
			this.downloadLog = root.resolve("downloads.log");
			this.installArguments = root.resolve("install-arguments.log");
			this.installLocation = root.resolve("install-location.log");
		}

		/**
		 * Publishes the plugin archive of each harness and fills the entry template with their pinned digests.
		 *
		 * @param wrongChecksum indicates whether to alter the plugin archives after pinning their digests
		 * @throws IOException if the fixture cannot be written
		 */
		private void release(boolean wrongChecksum) throws IOException
		{
			List<Path> archives = List.of(archive(plugin(CLAUDE_PLUGIN)), archive(plugin(CODEX_PLUGIN)));
			StringJoiner sums = new StringJoiner("\n");
			for (Path archive : archives)
				sums.add(sha256(archive) + "  " + archive.getFileName());
			Files.writeString(release.resolve("cat.ps1"), Files.readString(RELEASE_SOURCES.resolve("cat.ps1")).
				replace("@CAT_RELEASE_TAG@", "v1.0").replace("@CAT_SHA256SUMS@", sums.toString()));
			if (wrongChecksum)
			{
				for (Path archive : archives)
					Files.writeString(archive, "tampered");
			}
		}

		/**
		 * Creates a plugin folder whose installer records how it was run and what it found in the plugin's Java
		 * runtime.
		 *
		 * @param name the plugin folder's name
		 * @return the folder
		 * @throws IOException if the folder cannot be written
		 */
		private Path plugin(String name) throws IOException
		{
			Path plugin = root.resolve(name);
			Path runtime = Files.createDirectories(plugin.resolve("bin/runtime"));
			Files.writeString(runtime.resolve("marker"), "release runtime\n");
			Files.writeString(plugin.resolve("bin/installer.ps1"), """
				$args | Set-Content -LiteralPath $env:INSTALL_ARGUMENTS -Encoding UTF8
				$runtime = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'runtime\\marker')
				@($PSCommandPath, $runtime) | Set-Content -LiteralPath $env:INSTALL_LOCATION -Encoding UTF8
				Write-Output '{"result":"installed"}'
				exit [int] $env:FAKE_INSTALL_STATUS
				""");
			return plugin;
		}

		/**
		 * Packs one fixture directory as a release asset.
		 *
		 * @param directory the directory to pack, including its top directory name
		 * @return the archive
		 * @throws IOException if the files cannot be read or written
		 */
		private Path archive(Path directory) throws IOException
		{
			Path archive = release.resolve(directory.getFileName() + ".tar.gz");
			try (OutputStream out = Files.newOutputStream(archive);
				TarArchiveOutputStream tar = new TarArchiveOutputStream(new GzipCompressorOutputStream(out));
				Stream<Path> files = Files.walk(directory))
			{
				tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
				for (Path file : files.toList())
				{
					String name = directory.getFileName() + "/" + directory.relativize(file).toString().replace('\\', '/');
					tar.putArchiveEntry(new TarArchiveEntry(file, name));
					if (Files.isRegularFile(file))
						Files.copy(file, tar);
					tar.closeArchiveEntry();
				}
			}
			return archive;
		}

		/**
		 * Returns the statement that invokes the generated release entry as a script block.
		 *
		 * @param arguments the entry's parameters
		 * @return the PowerShell statement
		 */
		private String entry(String arguments)
		{
			return "& ([scriptblock]::Create([IO.File]::ReadAllText(" + literal(release.resolve("cat.ps1")) + "))) " +
				arguments + "\n";
		}

		/**
		 * Returns the SHA-256 checksum of a file.
		 *
		 * @param file the file
		 * @return the checksum in lowercase hexadecimal
		 * @throws IOException if the file cannot be read
		 */
		private static String sha256(Path file) throws IOException
		{
			try
			{
				return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
			}
			catch (NoSuchAlgorithmException e)
			{
				throw new AssertionError(e);
			}
		}

		/**
		 * Returns the URLs that the entry script downloaded during the last run, in order.
		 *
		 * @return the URLs
		 * @throws IOException if the log cannot be read
		 */
		private List<String> downloads() throws IOException
		{
			return lines(downloadLog);
		}

		/**
		 * Returns the arguments that the fake installer received, one per element.
		 *
		 * @return the arguments
		 * @throws IOException if the fake installer did not run or its record cannot be read
		 */
		private List<String> installArguments() throws IOException
		{
			return lines(installArguments);
		}

		/**
		 * Returns the entries left in the entry script's temporary folder.
		 *
		 * @return the entries' names
		 * @throws IOException if the folder cannot be read
		 */
		private List<String> temporaryEntries() throws IOException
		{
			try (Stream<Path> entries = Files.list(temporary))
			{
				return entries.map(path -> path.getFileName().toString()).toList();
			}
		}

		/**
		 * Returns the non-blank lines of a file without the byte order mark that Windows PowerShell writes.
		 *
		 * @param file the file
		 * @return the lines
		 * @throws IOException if the file cannot be read
		 */
		private static List<String> lines(Path file) throws IOException
		{
			List<String> lines = new ArrayList<>();
			for (String line : Files.readAllLines(file, UTF_8))
			{
				String stripped = line.replace("﻿", "").strip();
				if (!stripped.isEmpty())
					lines.add(stripped);
			}
			return lines;
		}

		/**
		 * Runs PowerShell statements in Windows PowerShell, in the test's root folder, with the fake download function
		 * defined and the harness variables cleared. An error ends the run with {@code $LASTEXITCODE} when a program
		 * set a nonzero one, and with status 1 otherwise.
		 *
		 * @param statements the statements
		 * @return the output and exit status
		 * @throws IOException          if PowerShell cannot be run
		 * @throws InterruptedException if the thread is interrupted while PowerShell runs
		 */
		private Result run(String statements) throws IOException, InterruptedException
		{
			Files.writeString(downloadLog, "");
			String script = "$ErrorActionPreference = 'Stop'\n" +
				"$env:CLAUDE_CONFIG_DIR = " + literal(claudeHome) + "\n" +
				"$env:CODEX_HOME = " + literal(codexHome) + "\n" +
				"$env:TEMP = " + literal(temporary) + "\n" +
				"$env:INSTALL_ARGUMENTS = " + literal(installArguments) + "\n" +
				"$env:INSTALL_LOCATION = " + literal(installLocation) + "\n" +
				"Remove-Item Env:CLAUDECODE, Env:CODEX_THREAD_ID, Env:FAKE_INSTALL_STATUS -ErrorAction SilentlyContinue\n" +
				"""
				function Invoke-WebRequest
				{
					param([string] $Uri, [string] $OutFile, [switch] $UseBasicParsing)
					Add-Content -LiteralPath %s -Value $Uri
					Copy-Item -LiteralPath (Join-Path %s $Uri.Substring($Uri.LastIndexOf('/') + 1)) -Destination $OutFile
				}
				""".formatted(literal(downloadLog), literal(release)) +
				"$global:LASTEXITCODE = 0\n" +
				"try\n{\n" + statements + "}\ncatch\n{\n" +
				"\t[Console]::Out.WriteLine('error: ' + $_.Exception.Message)\n" +
				"\tif ($LASTEXITCODE) { exit $LASTEXITCODE }\n\texit 1\n}\n";
			String encoded = Base64.getEncoder().encodeToString(script.getBytes(UTF_16LE));
			ProcessBuilder builder = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
				"-EncodedCommand", encoded).directory(root.toFile()).redirectErrorStream(true);
			try (Process process = builder.start())
			{
				process.getOutputStream().close();
				String output = new String(process.getInputStream().readAllBytes(), UTF_8);
				return new Result(process.waitFor(), output);
			}
		}
	}
}
