package com.holdmyspot.cat.distribution.bundle.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.that;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.PluginInfo;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.distribution.bundle.ReleasePackager;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.testng.annotations.Test;

/**
 * Tests {@link ReleasePackager}.
 */
public final class ReleasePackagerTest
{
	/** Creates a test instance. */
	public ReleasePackagerTest()
	{
	}

	/**
	 * Verifies archive structure and normalized metadata.
	 */
	@Test
	public void packagesStableTopLevelDirectory() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("cat-release-"));
			Path plugin = Files.createDirectories(temporary.resolve("plugin/bin"));
			Files.writeString(plugin.resolve("plugin-info"), "launcher").toFile().setExecutable(true, false);
			Files.writeString(temporary.resolve("plugin/LICENSE.txt"), "license");
			Files.writeString(temporary.resolve("plugin/NOTICE.txt"), "notice");
			Path archive = new ReleasePackager(PluginInfo.CAT_VERSION).packageArtifact("codex", Platform.LINUX_X64,
				temporary.resolve("plugin"), temporary.resolve("release"));

			requireThat(archive.getFileName().toString(), "archiveName").isEqualTo("cat-codex-linux-x64-v1.0.tar.gz");
			List<TarArchiveEntry> entries = readEntries(archive);
			requireThat(entries, "entries").isNotEmpty();
			requireThat(entries.stream().allMatch(entry -> entry.getName().startsWith("cat-codex-linux-x64-v1.0/")),
				"entryNames").isTrue();
			requireThat(entries.stream().allMatch(entry -> entry.getModTime().toInstant().toEpochMilli() == 0),
				"entryTimestamps").isTrue();
			requireThat(entries.stream().filter(entry -> entry.getName().endsWith("/bin/plugin-info")).findFirst().
				orElseThrow().getMode(), "pluginInfoMode").isEqualTo(0755);
			requireThat(entries.stream().anyMatch(entry -> entry.getName().endsWith("LICENSE.txt")), "licenseFile").isTrue();
			requireThat(entries.stream().anyMatch(entry -> entry.getName().endsWith("NOTICE.txt")), "noticeFile").isTrue();
		}
	}

	/**
	 * Verifies that a plugin's archive is named after its harness, its platform and the release version, and that the
	 * installer's archive keeps its name, in which the version precedes the platform.
	 */
	@Test
	public void namesPluginAndInstallerArchives() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("cat-release-"));
			Files.createDirectories(temporary.resolve("artifact"));
			ReleasePackager packager = new ReleasePackager(PluginInfo.CAT_VERSION);
			Path plugin = packager.packageArtifact("claude", Platform.MACOS_ARM64, temporary.resolve("artifact"),
				temporary.resolve("release"));
			Path installer = packager.packageArtifact("updater", Platform.MACOS_ARM64, temporary.resolve("artifact"),
				temporary.resolve("release"));

			requireThat(plugin.getFileName().toString(), "plugin").isEqualTo("cat-claude-macos-arm64-v1.0.tar.gz");
			requireThat(readEntries(plugin).getFirst().getName(), "pluginTop").isEqualTo("cat-claude-macos-arm64-v1.0/");
			requireThat(installer.getFileName().toString(), "installer").isEqualTo("cat-updater-v1.0-macos-arm64.tar.gz");
		}
	}

	/**
	 * Verifies that a plugin's archive leaves out the plugin's runtime binding, which names a runtime on the building
	 * machine.
	 */
	@Test
	public void leavesOutRuntimeBinding() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("cat-release-"));
			Path bin = Files.createDirectories(temporary.resolve("plugin/bin"));
			Files.writeString(bin.resolve("VERSION"), "1.0\n");
			Files.writeString(bin.resolve("runtime-binding"), temporary.resolve("runtime") + "\n");
			Path archive = new ReleasePackager(PluginInfo.CAT_VERSION).packageArtifact("claude", Platform.LINUX_X64,
				temporary.resolve("plugin"), temporary.resolve("release"));

			requireThat(readEntries(archive).stream().map(TarArchiveEntry::getName).toList(), "entries").isEqualTo(
				List.of("cat-claude-linux-x64-v1.0/", "cat-claude-linux-x64-v1.0/bin/",
					"cat-claude-linux-x64-v1.0/bin/VERSION"));
		}
	}

	/**
	 * Verifies that a plugin's archive leaves out the startup cache that an installation wrote, which Java accepts only
	 * at the location where it was written.
	 */
	@Test
	public void leavesOutStartupCache() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("cat-release-"));
			Path bin = Files.createDirectories(temporary.resolve("plugin/bin"));
			Files.writeString(bin.resolve("VERSION"), "1.0\n");
			Path cache = Files.createDirectories(bin.resolve("aot"));
			Files.writeString(cache.resolve("session-start.aot"), "cache");
			Files.writeString(cache.resolve("session-start.aot.tmp"), "partial");
			Path archive = new ReleasePackager(PluginInfo.CAT_VERSION).packageArtifact("claude", Platform.LINUX_X64,
				temporary.resolve("plugin"), temporary.resolve("release"));

			requireThat(readEntries(archive).stream().map(TarArchiveEntry::getName).toList(), "entries").isEqualTo(
				List.of("cat-claude-linux-x64-v1.0/", "cat-claude-linux-x64-v1.0/bin/",
					"cat-claude-linux-x64-v1.0/bin/VERSION"));
		}
	}

	/**
	 * Verifies checksum manifest entries are sorted.
	 */
	@Test
	public void writesSortedChecksumLinesForCatArchives() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("cat-checksums-"));
			Path codex = Files.writeString(temporary.resolve("cat-codex-linux-x64-v1.0.tar.gz"), "codex");
			Path claude = Files.writeString(temporary.resolve("cat-claude-linux-x64-v1.0.tar.gz"), "claude");
			Path manifest = new ReleasePackager(PluginInfo.CAT_VERSION).writeChecksumManifest(List.of(codex, claude),
				temporary.resolve("SHA256SUMS.txt"));
			List<String> lines = Files.readAllLines(manifest);

			requireThat(lines, "checksumLines").size().isEqualTo(2);
			requireThat(lines.get(0), "firstChecksumLine").endsWith("  cat-claude-linux-x64-v1.0.tar.gz");
			requireThat(lines.get(1), "secondChecksumLine").endsWith("  cat-codex-linux-x64-v1.0.tar.gz");
			requireThat(lines.stream().allMatch(
				line -> line.matches("[0-9a-f]{64}  cat-(claude|codex)-linux-x64-v1\\.0\\.tar\\.gz")),
				"checksumLineFormat").isTrue();
		}
	}

	/**
	 * Reads every entry from a gzip-compressed TAR archive.
	 *
	 * @param archive the archive to inspect
	 * @return the archive entries in stored order
	 * @throws IOException if the archive cannot be read
	 */
	private static List<TarArchiveEntry> readEntries(Path archive) throws IOException
	{
		assert that(archive, "archive").isNotNull().elseThrow();
		List<TarArchiveEntry> entries = new ArrayList<>();
		try (InputStream file = Files.newInputStream(archive);
			GzipCompressorInputStream gzip = new GzipCompressorInputStream(file);
			TarArchiveInputStream tar = new TarArchiveInputStream(gzip))
		{
			TarArchiveEntry entry;
			entry = tar.getNextEntry();
			while (entry != null)
			{
				entries.add(entry);
				entry = tar.getNextEntry();
			}
		}
		return entries;
	}
}
