package com.holdmyspot.cat.distribution.bundle.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.updater.trust.ReleaseVersion;
import com.holdmyspot.cat.common.updater.trust.TrustException;
import com.holdmyspot.cat.common.updater.trust.TrustPolicy;
import com.holdmyspot.cat.distribution.bundle.ApplicationModules;
import com.holdmyspot.cat.distribution.bundle.UpdaterArtifactBuilder;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;

/**
 * Tests {@link UpdaterArtifactBuilder}: the application directory it writes and its default policy, read through the
 * updater's policy reader.
 */
public final class UpdaterArtifactBuilderTest
{
	/**
	 * Creates a test instance.
	 */
	public UpdaterArtifactBuilderTest()
	{
	}

	/**
	 * Verifies that an unsigned development build trusts no keys and limits updates to its release major.
	 */
	@Test
	public void developmentPolicyTrustsNoKeys() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			TestJvmScope scope = new TestJvmScope())
		{
			Path temporary = temporary(cleanup);
			Path image = assemble(scope, temporary, "2.3.4", "");

			TrustPolicy policy = TrustPolicy.read(image.resolve("conf/cat-updater/default-policy.json"));

			requireThat(policy.keys(), "keys").isEmpty();
			requireThat(policy.minimum(), "minimum").isEqualTo(ReleaseVersion.parse("2.3.4"));
			requireThat(policy.maximumMajor(), "maximumMajor").isEqualTo(2);
			requireThat(policy.harnesses(), "harnesses").isEmpty();
			requireThat(policy.releaseBaseUrl(), "releaseBaseUrl").
				isEqualTo(URI.create("https://github.com/holdmyspot-com/cat/releases/download"));
		}
	}

	/**
	 * Verifies that setup's policy reader obtains exactly the release key supplied to the artifact builder.
	 */
	@Test
	public void releasePolicyTrustsSuppliedKey() throws IOException, TrustException, NoSuchAlgorithmException
	{
		KeyPair key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
		String encoded = Base64.getEncoder().encodeToString(key.getPublic().getEncoded());
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			TestJvmScope scope = new TestJvmScope())
		{
			Path temporary = temporary(cleanup);
			Path image = assemble(scope, temporary, "1.0", encoded);

			TrustPolicy policy = TrustPolicy.read(image.resolve("conf/cat-updater/default-policy.json"));

			requireThat(policy.keys(), "keys").size().isEqualTo(1);
			requireThat(policy.keys().getFirst().id(), "keyId").isEqualTo("cat-release");
			requireThat(policy.keys().getFirst().x509Base64(), "releaseKey").isEqualTo(encoded);
		}
	}

	/**
	 * Verifies that the updater's application directory holds its version, modules, versioned launchers, the stable
	 * launchers that an update installs, its runtime requirement and legal materials, and no Java runtime, which the
	 * release shares between the updater and the plugins.
	 */
	@Test
	public void applicationDirectoryHoldsNoRuntime() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			TestJvmScope scope = new TestJvmScope())
		{
			Path temporary = temporary(cleanup);
			Path application = assemble(scope, temporary, "1.0", "");

			requireThat(application, "application").isEqualTo(temporary.resolve("updater/1.0"));
			requireThat(Files.readString(application.resolve("VERSION")), "version").isEqualTo("1.0\n");
			requireThat(Files.readString(application.resolve("app/updater.jar")), "module").isEqualTo("updater module");
			requireThat(Files.readString(application.resolve("bin/cat-updater")), "posixLauncher").
				isEqualTo("launcher.sh");
			requireThat(Files.readString(application.resolve("bin/cat-updater.ps1")), "powerShellLauncher").
				isEqualTo("launcher.ps1");
			requireThat(Files.isExecutable(application.resolve("bin/cat-updater")), "posixLauncherExecutable").isTrue();
			requireThat(Files.readString(application.resolve("stable-launcher/cat-updater")), "posixStableLauncher").
				isEqualTo("stable launcher.sh");
			requireThat(Files.readString(application.resolve("stable-launcher/cat-updater.ps1")),
				"powerShellStableLauncher").isEqualTo("stable launcher.ps1");
			requireThat(Files.isExecutable(application.resolve("stable-launcher/cat-updater")),
				"posixStableLauncherExecutable").isTrue();
			requireThat(Files.readString(application.resolve("legal/LICENSE.txt")), "license").isEqualTo("test license");
			JsonNode requirement = scope.json().readTree(application.resolve("runtime-requirement.json").toFile());
			requireThat(requirement.get("format").asInt(), "format").isEqualTo(1);
			requireThat(requirement.get("java_feature").asInt(), "javaFeature").isEqualTo(Runtime.version().feature());
			requireThat(requirement.get("modules").toString(), "modules").isEqualTo("[\"java.base\",\"java.net.http\"]");
			try (Stream<Path> entries = Files.list(application))
			{
				requireThat(entries.map(path -> path.getFileName().toString()).sorted().toList(), "entries").
					isEqualTo(List.of("VERSION", "app", "bin", "conf", "legal", "runtime-requirement.json",
						"stable-launcher"));
			}
		}
	}

	/**
	 * Creates a test-owned directory beneath the test JVM's temporary directory.
	 *
	 * @param cleanup removes the directory after the test
	 * @return the fresh directory
	 * @throws IOException if the directory cannot be created
	 */
	private static Path temporary(TemporaryDirectories cleanup) throws IOException
	{
		return cleanup.add(Files.createTempDirectory("updater-policy-"));
	}

	/**
	 * Assembles the updater's application directory from small module, launcher and legal inputs; no runtime process is
	 * started.
	 *
	 * @param scope     the test's build scope
	 * @param temporary the test's owned root
	 * @param version   the release version
	 * @param publicKey the release key or empty development value
	 * @return the assembled directory, {@code updater/<version>} in {@code temporary}
	 * @throws IOException if assembly fails
	 */
	private static Path assemble(TestJvmScope scope, Path temporary, String version, String publicKey)
		throws IOException
	{
		Path legal = Files.createDirectory(temporary.resolve("legal"));
		Files.writeString(legal.resolve("LICENSE.txt"), "test license");
		Files.createDirectory(temporary.resolve("updater-module"));
		Path jar = temporary.resolve("updater.jar");
		try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar)))
		{
			for (String suffix : new String[] {"sh", "ps1"})
			{
				output.putNextEntry(new JarEntry("com/holdmyspot/cat/common/updater/cli/versioned-launcher." + suffix));
				output.write(("launcher." + suffix).getBytes(UTF_8));
				output.closeEntry();
				output.putNextEntry(new JarEntry("com/holdmyspot/cat/common/updater/cli/stable-launcher." + suffix));
				output.write(("stable launcher." + suffix).getBytes(UTF_8));
				output.closeEntry();
			}
		}
		Path module = Files.writeString(temporary.resolve("updater-module/updater.jar"), "updater module");
		ApplicationModules modules = new ApplicationModules(List.of(module), new TreeSet<>(Set.of("java.base",
			"java.net.http")));
		return new UpdaterArtifactBuilder(scope, version, publicKey).build(temporary.resolve("updater/" + version), jar,
			modules, legal);
	}
}
