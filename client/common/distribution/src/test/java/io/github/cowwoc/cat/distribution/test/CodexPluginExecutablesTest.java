package io.github.cowwoc.cat.distribution.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE;
import static java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE;
import static java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE;

import io.github.cowwoc.cat.common.PluginInfo;
import io.github.cowwoc.cat.common.PortableFiles;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.distribution.bundle.ArtifactLayer;
import io.github.cowwoc.cat.distribution.bundle.JlinkImageBuilder;
import io.github.cowwoc.cat.distribution.bundle.PluginArtifactBuilder;
import io.github.cowwoc.cat.distribution.secrets.DistributionMainSecrets;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests that the Codex plugin installs its scripts and hook launcher as executables, and that the Codex image launches
 * {@code run-part} and {@code read-part}. {@code jlink-launchers.bats} tests the launcher template against the built
 * Codex image, and {@code codex-plugin-launchers.bats} tests the {@code run-part} and {@code read-part} launchers in
 * the plugin's {@code bin} folder.
 */
public final class CodexPluginExecutablesTest
{
	/** Creates a test instance. */
	public CodexPluginExecutablesTest()
	{
	}

	/**
	 * Returns the paths of the Codex plugin's executables.
	 *
	 * @return one row per executable, holding its path relative to the plugin root
	 */
	@DataProvider
	public static Object[][] executables()
	{
		return new Object[][]
		{
			{"client/run-part-overrides/rg"},
			{"hooks/post-tool-use.sh"}
		};
	}

	/**
	 * Verifies that the Codex image launches {@code run-part} and {@code read-part}.
	 */
	@Test
	public void codexImageLaunchesParts()
	{
		List<JlinkImageBuilder.Launcher> launchers = DistributionMainSecrets.access().codexLaunchers();

		requireThat(launchers, "launchers").
			contains(new JlinkImageBuilder.Launcher("run-part", "io.github.cowwoc.cat.codex.cli",
				"io.github.cowwoc.cat.codex.CodexRunPart")).
			contains(new JlinkImageBuilder.Launcher("read-part", "io.github.cowwoc.cat.codex.cli",
				"io.github.cowwoc.cat.codex.CodexReadPart"));
	}

	/**
	 * Verifies that an executable is a resource of the Codex plugin and that the Codex plugin layer installs it,
	 * unchanged and executable by every user, at the same path.
	 *
	 * @param path the executable's path relative to the plugin root
	 * @throws IOException if the resource cannot be read or the artifact cannot be assembled
	 */
	@Test(dataProvider = "executables")
	public void codexPluginInstallsExecutable(String path) throws IOException
	{
		URL script = CodexPluginExecutablesTest.class.getClassLoader().getResource(path);
		requireThat(script, "script").isNotNull();
		URL manifest = CodexPluginExecutablesTest.class.getClassLoader().getResource(".codex-plugin/plugin.json");
		requireThat(manifest, "manifest").isNotNull();
		String scriptRoot = script.toString().substring(0, script.toString().length() - path.length());
		String manifestRoot = manifest.toString().substring(0,
			manifest.toString().length() - ".codex-plugin/plugin.json".length());
		requireThat(scriptRoot, "scriptRoot").isEqualTo(manifestRoot, "manifestRoot");
		byte[] content;
		try (InputStream in = script.openStream())
		{
			content = in.readAllBytes();
		}

		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("cat-codex-executables-"));
			Path jar = temporary.resolve("codex-plugin.jar");
			try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar)))
			{
				out.putNextEntry(new JarEntry(path));
				out.write(content);
				out.closeEntry();
			}
			Path legal = Files.createDirectories(temporary.resolve("legal"));
			ArtifactLayer layer = DistributionMainSecrets.access().codexPluginLayer(jar);
			Path artifact = new PluginArtifactBuilder(PluginInfo.CAT_VERSION).build(
				List.of(layer), Files.createDirectories(temporary.resolve("runtime")), Set.of(),
				legal, List.of(Files.createDirectories(temporary.resolve("installers"))), temporary.resolve("output"));

			Path installed = artifact.resolve(path);
			requireThat(Files.readAllBytes(installed), "installed").isEqualTo(content, "content");
			// Only a file system with POSIX permissions records the mode that the layer sets
			if (PortableFiles.hasModes(installed))
			{
				Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(installed);
				requireThat(permissions, "permissions").contains(OWNER_EXECUTE).contains(GROUP_EXECUTE).
					contains(OTHERS_EXECUTE);
			}
		}
	}
}
