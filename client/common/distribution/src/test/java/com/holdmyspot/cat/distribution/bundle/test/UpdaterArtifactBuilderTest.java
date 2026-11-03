package com.holdmyspot.cat.distribution.bundle.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.updater.trust.ReleaseVersion;
import com.holdmyspot.cat.common.updater.trust.TrustException;
import com.holdmyspot.cat.common.updater.trust.TrustPolicy;
import com.holdmyspot.cat.distribution.bundle.UpdaterArtifactBuilder;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.testng.annotations.Test;

/**
 * Tests {@link UpdaterArtifactBuilder}'s default policy through the updater's policy reader.
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
	 * Assembles the policy with small launcher and legal inputs; no runtime process is started.
	 *
	 * @param scope     the test's build scope
	 * @param temporary the test's owned root
	 * @param version   the release version
	 * @param publicKey the release key or empty development value
	 * @return the assembled directory
	 * @throws IOException if assembly fails
	 */
	private static Path assemble(TestJvmScope scope, Path temporary, String version, String publicKey)
		throws IOException
	{
		Path image = Files.createDirectory(temporary.resolve("image"));
		Path legal = Files.createDirectory(temporary.resolve("legal"));
		Files.writeString(legal.resolve("LICENSE.txt"), "test license");
		Path jar = temporary.resolve("updater.jar");
		try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar)))
		{
			for (String suffix : new String[] {"sh", "cmd"})
			{
				output.putNextEntry(new JarEntry("com/holdmyspot/cat/common/updater/cli/versioned-launcher." + suffix));
				output.write("test launcher".getBytes(UTF_8));
				output.closeEntry();
			}
		}
		return new UpdaterArtifactBuilder(scope, version, publicKey).build(image, jar, legal);
	}
}
