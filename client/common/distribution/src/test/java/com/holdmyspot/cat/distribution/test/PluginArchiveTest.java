package com.holdmyspot.cat.distribution.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.PluginInfo;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.distribution.bundle.ApplicationModules;
import com.holdmyspot.cat.distribution.bundle.PluginArtifactBuilder;
import com.holdmyspot.cat.distribution.bundle.ReleasePackager;
import com.holdmyspot.cat.distribution.secrets.DistributionMainAccess;
import com.holdmyspot.cat.distribution.secrets.DistributionMainSecrets;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.testng.annotations.Test;

/**
 * Tests the archive of a plugin assembled as the release build assembles it: its {@code client} folder staged from the
 * plugin's modules, its launchers, and the binding that a source checkout's plugin holds.
 */
public final class PluginArchiveTest
{
	/**
	 * The entries that a plugin's {@code client} folder may hold in its archive: its launchers, its modules, its runtime
	 * requirement, its version and its runtime manifest.
	 */
	private static final Pattern CLIENT_ENTRY = Pattern.compile("client/(|bin/|bin/[^/]+|modules/|modules/[^/]+\\.jar|" +
		"runtime-requirement\\.json|VERSION|runner-runtime\\.manifest)");

	/** Creates a test instance. */
	public PluginArchiveTest()
	{
	}

	/**
	 * Verifies that a source checkout's plugin is bound to the release runtime that the build wrote, and that its
	 * archive leaves out the binding and holds no file of a Java runtime.
	 */
	@Test
	public void archiveHoldsNoRuntime() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			TestJvmScope scope = new TestJvmScope())
		{
			Path directory = cleanup.add(Files.createTempDirectory("plugin-archive-"));
			Path jar = Files.writeString(directory.resolve("app.jar"), "module");
			Path legal = Files.createDirectories(directory.resolve("legal"));
			Files.writeString(legal.resolve("LICENSE.txt"), "license");
			DistributionMainAccess access = DistributionMainSecrets.getDistributionMainAccess();
			ApplicationModules application = new ApplicationModules(List.of(jar), new TreeSet<>(Set.of("java.base")));
			Path client = access.stageClient(application, directory.resolve("client"), scope.json());
			access.writeCodexLaunchers(client, Platform.LINUX_X64);
			Path plugin = new PluginArtifactBuilder(PluginInfo.CAT_VERSION).build(List.of(), client,
				access.codexAgentLaunchers(), legal, List.of(), directory.resolve("plugin"));
			Path runtime = directory.resolve("runtime");
			access.writeRuntimeBinding(plugin, runtime);

			requireThat(Files.readString(plugin.resolve("client/runtime-binding")), "binding").
				isEqualTo(runtime.toAbsolutePath().normalize() + "\n");
			Path archive = new ReleasePackager(PluginInfo.CAT_VERSION).packageArtifact("codex", Platform.LINUX_X64,
				plugin, directory.resolve("release"));
			String prefix = "cat-codex-v" + PluginInfo.CAT_VERSION + "-linux-x64/";
			List<String> clientEntries = new ArrayList<>();
			for (String name : entryNames(archive))
			{
				requireThat(name, "name").startsWith(prefix);
				String relative = name.substring(prefix.length());
				if (relative.startsWith("client/"))
					clientEntries.add(relative);
			}
			requireThat(clientEntries, "clientEntries").contains("client/modules/app.jar").
				contains("client/runtime-requirement.json").contains("client/bin/java").
				doesNotContain("client/runtime-binding");
			for (String entry : clientEntries)
				requireThat(CLIENT_ENTRY.matcher(entry).matches(), entry).isTrue();
		}
	}

	/**
	 * Returns the names of the entries of a gzip-compressed TAR archive.
	 *
	 * @param archive the archive
	 * @return the names in stored order
	 * @throws IOException if the archive cannot be read
	 */
	private static List<String> entryNames(Path archive) throws IOException
	{
		List<String> names = new ArrayList<>();
		try (InputStream file = Files.newInputStream(archive);
			GzipCompressorInputStream gzip = new GzipCompressorInputStream(file);
			TarArchiveInputStream tar = new TarArchiveInputStream(gzip))
		{
			for (TarArchiveEntry entry = tar.getNextEntry(); entry != null; entry = tar.getNextEntry())
				names.add(entry.getName());
		}
		return names;
	}
}
