package com.holdmyspot.cat.distribution.bundle.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Platform;
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
 * Tests the installer archive produced by {@link ReleasePackager} through an archive reader.
 */
public final class InstallerArchiveTest
{
	/**
	 * Creates a test instance.
	 */
	public InstallerArchiveTest()
	{
	}

	/**
	 * Verifies that an archive consumer receives the installer's data layout under the archive's top directory: the
	 * release's runtime in {@code runtime/} and the installer's version in {@code <version>/VERSION}.
	 */
	@Test
	public void archiveConsumerReadsInstallerVersion() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("installer-archive-"));
			Path image = Files.createDirectory(temporary.resolve("installer"));
			Files.writeString(Files.createDirectories(image.resolve("1.0")).resolve("VERSION"), "1.0\n");
			Path runtime = Files.createDirectories(image.resolve("runtime"));
			Files.writeString(runtime.resolve("release"), "JAVA_VERSION=\"27\"\n");

			Path archive = new ReleasePackager("1.0").packageArtifact("updater", Platform.LINUX_X64,
				image, temporary.resolve("release"));

			try (InputStream input = Files.newInputStream(archive);
				GzipCompressorInputStream gzip = new GzipCompressorInputStream(input);
				TarArchiveInputStream tar = new TarArchiveInputStream(gzip))
			{
				List<String> names = new ArrayList<>();
				byte[] version = null;
				for (TarArchiveEntry entry = tar.getNextEntry(); entry != null; entry = tar.getNextEntry())
				{
					names.add(entry.getName());
					if (entry.getName().equals("cat-updater-v1.0-linux-x64/1.0/VERSION"))
						version = tar.readAllBytes();
				}
				requireThat(names, "names").isEqualTo(List.of("cat-updater-v1.0-linux-x64/",
					"cat-updater-v1.0-linux-x64/1.0/", "cat-updater-v1.0-linux-x64/1.0/VERSION",
					"cat-updater-v1.0-linux-x64/runtime/", "cat-updater-v1.0-linux-x64/runtime/release"));
				requireThat(version, "versionBytes").isEqualTo(new byte[] {'1', '.', '0', '\n'});
			}
		}
	}
}
