package io.github.cowwoc.cat.distribution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.that;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.Platform;
import io.github.cowwoc.cat.common.PluginInfo;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.distribution.bundle.ReleasePackager;
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
			Path plugin = Files.createDirectories(temporary.resolve("plugin/client/bin"));
			Files.writeString(plugin.resolve("plugin-info"), "launcher").toFile().setExecutable(true, false);
			Files.writeString(temporary.resolve("plugin/LICENSE.txt"), "license");
			Files.writeString(temporary.resolve("plugin/NOTICE.txt"), "notice");
			Path archive = new ReleasePackager(PluginInfo.CAT_VERSION).packageArtifact("codex", Platform.LINUX_X64,
				temporary.resolve("plugin"), temporary.resolve("release"));

			requireThat(archive.getFileName().toString(), "archiveName").isEqualTo("cat-codex-v1.2-linux-x64.tar.gz");
			List<TarArchiveEntry> entries = readEntries(archive);
			requireThat(entries, "entries").isNotEmpty();
			requireThat(entries.stream().allMatch(entry -> entry.getName().startsWith("cat-codex-v1.2-linux-x64/")),
				"entryNames").isTrue();
			requireThat(entries.stream().allMatch(entry -> entry.getModTime().toInstant().toEpochMilli() == 0),
				"entryTimestamps").isTrue();
			requireThat(entries.stream().filter(entry -> entry.getName().endsWith("client/bin/plugin-info")).findFirst().
				orElseThrow().getMode(), "pluginInfoMode").isEqualTo(0755);
			requireThat(entries.stream().anyMatch(entry -> entry.getName().endsWith("LICENSE.txt")), "licenseFile").isTrue();
			requireThat(entries.stream().anyMatch(entry -> entry.getName().endsWith("NOTICE.txt")), "noticeFile").isTrue();
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
			Path codex = Files.writeString(temporary.resolve("cat-codex-v1.0-linux-x64.tar.gz"), "codex");
			Path claude = Files.writeString(temporary.resolve("cat-claude-v1.0-linux-x64.tar.gz"), "claude");
			Path manifest = new ReleasePackager(PluginInfo.CAT_VERSION).writeChecksumManifest(List.of(codex, claude),
				temporary.resolve("SHA256SUMS.txt"));
			List<String> lines = Files.readAllLines(manifest);

			requireThat(lines, "checksumLines").size().isEqualTo(2);
			requireThat(lines.get(0), "firstChecksumLine").endsWith("  cat-claude-v1.0-linux-x64.tar.gz");
			requireThat(lines.get(1), "secondChecksumLine").endsWith("  cat-codex-v1.0-linux-x64.tar.gz");
			requireThat(lines.stream().allMatch(
				line -> line.matches("[0-9a-f]{64}  cat-(claude|codex)-v1\\.0-linux-x64\\.tar\\.gz")),
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
