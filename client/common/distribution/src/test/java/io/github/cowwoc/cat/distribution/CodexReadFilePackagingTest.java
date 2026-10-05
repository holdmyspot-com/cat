package io.github.cowwoc.cat.distribution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE;
import static java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE;
import static java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE;

import io.github.cowwoc.cat.common.PluginInfo;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.distribution.bundle.PluginArtifactBuilder;
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
import org.testng.annotations.Test;

/**
 * Tests that the Codex plugin ships its {@code read-file} script as an executable in {@code client/bin}. The packaged
 * script's behavior is tested by {@code read-file.bats} against the built Codex image.
 */
public final class CodexReadFilePackagingTest
{
	private static final String SCRIPT = "client/bin/read-file";

	/** Creates a test instance. */
	public CodexReadFilePackagingTest()
	{
	}

	/**
	 * Verifies that the script is a resource of the Codex plugin and that the Codex plugin layer installs it, unchanged
	 * and executable by every user, at {@code client/bin/read-file}.
	 *
	 * @throws IOException if the resource cannot be read or the artifact cannot be assembled
	 */
	@Test
	public void codexPluginInstallsExecutableScript() throws IOException
	{
		URL script = CodexReadFilePackagingTest.class.getClassLoader().getResource(SCRIPT);
		requireThat(script, "script").isNotNull();
		URL manifest = CodexReadFilePackagingTest.class.getClassLoader().getResource(".codex-plugin/plugin.json");
		requireThat(manifest, "manifest").isNotNull();
		String scriptRoot = script.toString().substring(0, script.toString().length() - SCRIPT.length());
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
			Path temporary = cleanup.add(Files.createTempDirectory("cat-codex-read-file-"));
			Path jar = temporary.resolve("codex-plugin.jar");
			try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar)))
			{
				out.putNextEntry(new JarEntry(SCRIPT));
				out.write(content);
				out.closeEntry();
			}
			Path legal = Files.createDirectories(temporary.resolve("legal"));
			Path artifact = new PluginArtifactBuilder(PluginInfo.CAT_VERSION).build(
				List.of(DistributionMain.codexPluginLayer(jar)), Files.createDirectories(temporary.resolve("runtime")),
				legal, List.of(Files.createDirectories(temporary.resolve("installers"))), temporary.resolve("output"));

			Path installed = artifact.resolve(SCRIPT);
			requireThat(Files.readAllBytes(installed), "installed").isEqualTo(content, "content");
			Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(installed);
			requireThat(permissions, "permissions").contains(OWNER_EXECUTE).contains(GROUP_EXECUTE).
				contains(OTHERS_EXECUTE);
		}
	}
}
