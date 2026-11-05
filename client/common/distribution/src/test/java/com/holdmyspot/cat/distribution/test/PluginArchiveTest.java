package com.holdmyspot.cat.distribution.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.PluginInfo;
import com.holdmyspot.cat.common.installation.PluginVersion;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.distribution.bundle.ApplicationModules;
import com.holdmyspot.cat.distribution.bundle.PluginArtifactBuilder;
import com.holdmyspot.cat.distribution.bundle.PluginInstaller;
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
 * Tests the archive of a plugin assembled as the release build assembles it: its {@code bin} folder staged from the
 * plugin's modules and the release's Java runtime, its launchers, its installer, and the binding that a source
 * checkout's plugin holds.
 */
public final class PluginArchiveTest
{
	/**
	 * The entries that a plugin's {@code bin} folder may hold in its archive: its launchers, its runtime requirement, its
	 * version and its runtime manifest, the Java runtime with the plugin's modules, and the installer's configuration.
	 */
	private static final Pattern BIN_ENTRY = Pattern.compile("bin/(|[^/]+|runtime/.*|conf/|conf/installer/|" +
		"conf/installer/default-policy\\.json)");

	/** Creates a test instance. */
	public PluginArchiveTest()
	{
	}

	/**
	 * Verifies that a source checkout's plugin is bound to the release runtime that the build wrote, and that its
	 * archive is named after its harness, platform and release, holds every launcher, the hooks' and the installer's
	 * included, holds the Java runtime with the plugin's modules in {@code bin/runtime/} and the installer's default
	 * policy in {@code bin/conf/installer/}, has no {@code client/} folder, and leaves out the binding.
	 */
	@Test
	public void archiveHoldsRuntimeAndInstaller() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			TestJvmScope scope = new TestJvmScope())
		{
			Path directory = cleanup.add(Files.createTempDirectory("plugin-archive-"));
			Path jar = Files.writeString(directory.resolve("app.jar"), "module");
			Path legal = Files.createDirectories(directory.resolve("legal"));
			Files.writeString(legal.resolve("LICENSE.txt"), "license");
			Path releaseRuntime = Files.createDirectories(directory.resolve("release runtime/bin"));
			Files.writeString(releaseRuntime.resolve("java"), "java");
			DistributionMainAccess access = DistributionMainSecrets.getDistributionMainAccess();
			ApplicationModules application = new ApplicationModules(List.of(jar), new TreeSet<>(Set.of("java.base")));
			Path bin = access.stageBin(application, releaseRuntime.getParent(), directory.resolve("bin"),
				scope.json());
			access.writeCodexLaunchers(bin, Platform.LINUX_X64);
			new PluginInstaller(scope, PluginInfo.CAT_VERSION, "").write(bin, Platform.LINUX_X64, true);
			Path plugin = new PluginArtifactBuilder(PluginVersion.parse("1.0+b0123456789ab")).build(List.of(), bin,
				legal, List.of(), directory.resolve("plugin"));
			Path runtime = directory.resolve("runtime");
			access.writeRuntimeBinding(plugin, runtime);

			requireThat(Files.readString(plugin.resolve("bin/runtime-binding")), "binding").
				isEqualTo(runtime.toAbsolutePath().normalize() + "\n");
			Path archive = new ReleasePackager(PluginInfo.CAT_VERSION).packageArtifact("codex", Platform.LINUX_X64,
				plugin, directory.resolve("release"));
			requireThat(archive.getFileName().toString(), "archive").
				isEqualTo("cat-codex-linux-x64-v" + PluginInfo.CAT_VERSION + ".tar.gz");
			String prefix = "cat-codex-linux-x64-v" + PluginInfo.CAT_VERSION + "/";
			List<String> binEntries = new ArrayList<>();
			for (String name : entryNames(archive))
			{
				requireThat(name, "name").startsWith(prefix);
				String relative = name.substring(prefix.length());
				requireThat(relative, "relative").doesNotStartWith("client/");
				if (relative.startsWith("bin/"))
					binEntries.add(relative);
			}
			requireThat(binEntries, "binEntries").contains("bin/runtime/modules/app.jar").
				contains("bin/runtime/bin/java").contains("bin/installer").
				contains("bin/conf/installer/default-policy.json").
				contains("bin/runtime-requirement.json").contains("bin/VERSION").contains("bin/java").
				contains("bin/plugin-info").contains("bin/session-start").contains("bin/post-tool-use").
				doesNotContain("bin/installer.ps1").doesNotContain("bin/modules/").
				doesNotContain("bin/runtime-binding");
			for (String entry : binEntries)
				requireThat(BIN_ENTRY.matcher(entry).matches(), entry).isTrue();
		}
	}

	/**
	 * Verifies that a Windows plugin whose launchers are only batch files holds the installer's PowerShell launcher
	 * and no POSIX one, and that a Windows plugin with POSIX launchers holds both.
	 */
	@Test
	public void windowsPluginHoldsPowerShellInstaller() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			TestJvmScope scope = new TestJvmScope())
		{
			Path directory = cleanup.add(Files.createTempDirectory("plugin-archive-"));
			PluginInstaller builder = new PluginInstaller(scope, PluginInfo.CAT_VERSION, "");
			Path batchOnly = Files.createDirectories(directory.resolve("batch-only"));
			builder.write(batchOnly, Platform.WINDOWS_X64, false);
			Path both = Files.createDirectories(directory.resolve("both"));
			builder.write(both, Platform.WINDOWS_X64, true);

			requireThat(Files.isRegularFile(batchOnly.resolve("installer.ps1")), "batchOnly.ps1").isTrue();
			requireThat(Files.exists(batchOnly.resolve("installer")), "batchOnly.posix").isFalse();
			requireThat(Files.isRegularFile(both.resolve("installer.ps1")), "both.ps1").isTrue();
			requireThat(Files.isRegularFile(both.resolve("installer")), "both.posix").isTrue();
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
