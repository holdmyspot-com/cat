package com.holdmyspot.cat.distribution.bundle.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.distribution.bundle.ReleasePackager;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.testng.annotations.Test;

/**
 * Tests the updater archive produced by {@link ReleasePackager} through an archive reader.
 */
public final class UpdaterArchiveTest
{
	/**
	 * Creates a test instance.
	 */
	public UpdaterArchiveTest()
	{
	}

	/**
	 * Verifies that an archive consumer receives the updater's version under its release directory.
	 */
	@Test
	public void archiveConsumerReadsUpdaterVersion() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("updater-archive-"));
			Path image = Files.createDirectory(temporary.resolve("image"));
			Files.writeString(image.resolve("VERSION"), "1.0\n");

			Path archive = new ReleasePackager("1.0").packageArtifact("updater", Platform.LINUX_X64,
				image, temporary.resolve("release"));

			try (InputStream input = Files.newInputStream(archive);
				GzipCompressorInputStream gzip = new GzipCompressorInputStream(input);
				TarArchiveInputStream tar = new TarArchiveInputStream(gzip))
			{
				TarArchiveEntry entry = tar.getNextEntry();
				while (entry != null && !entry.getName().endsWith("/VERSION"))
					entry = tar.getNextEntry();
				requireThat(entry, "versionEntry").isNotNull();
				requireThat(entry.getName(), "versionEntry").isEqualTo("cat-updater-v1.0-linux-x64/VERSION");
				requireThat(tar.readNBytes(4), "versionBytes").isEqualTo(new byte[] {'1', '.', '0', '\n'});
			}
		}
	}
}
