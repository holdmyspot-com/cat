package com.holdmyspot.cat.distribution.bundle.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.installer.trust.ReleaseVersion;
import com.holdmyspot.cat.common.installer.trust.TrustException;
import com.holdmyspot.cat.common.installer.trust.TrustPolicy;
import com.holdmyspot.cat.distribution.bundle.PluginInstaller;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import org.testng.annotations.Test;

/**
 * Tests the default policy that {@link PluginInstaller} writes into a plugin's {@code bin} folder, read through the
 * installer's policy reader, and which platforms' POSIX installer launchers pass {@code TMPDIR} to Java.
 */
public final class PluginInstallerTest
{
	/**
	 * Creates a test instance.
	 */
	public PluginInstallerTest()
	{
	}

	/**
	 * Verifies that an unsigned development build trusts no keys and limits installs to its release major.
	 */
	@Test
	public void developmentPolicyTrustsNoKeys() throws IOException, TrustException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			TestJvmScope scope = new TestJvmScope())
		{
			Path bin = cleanup.add(Files.createTempDirectory("plugin-installer-")).resolve("bin");
			new PluginInstaller(scope, "2.3.4", "").write(bin, Platform.LINUX_X64, true);

			TrustPolicy policy = TrustPolicy.read(bin.resolve("conf/installer/default-policy.json"));

			requireThat(policy.keys(), "keys").isEmpty();
			requireThat(policy.minimum(), "minimum").isEqualTo(ReleaseVersion.parse("2.3.4"));
			requireThat(policy.maximumMajor(), "maximumMajor").isEqualTo(2);
			requireThat(policy.harnesses(), "harnesses").isEmpty();
			requireThat(policy.releaseBaseUrl(), "releaseBaseUrl").
				isEqualTo(URI.create("https://github.com/holdmyspot-com/cat/releases/download"));
		}
	}

	/**
	 * Verifies that install's policy reader obtains exactly the release key supplied to the writer.
	 */
	@Test
	public void releasePolicyTrustsSuppliedKey() throws IOException, TrustException, NoSuchAlgorithmException
	{
		KeyPair key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
		String encoded = Base64.getEncoder().encodeToString(key.getPublic().getEncoded());
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			TestJvmScope scope = new TestJvmScope())
		{
			Path bin = cleanup.add(Files.createTempDirectory("plugin-installer-")).resolve("bin");
			new PluginInstaller(scope, "1.0", encoded).write(bin, Platform.LINUX_X64, true);

			TrustPolicy policy = TrustPolicy.read(bin.resolve("conf/installer/default-policy.json"));

			requireThat(policy.keys(), "keys").size().isEqualTo(1);
			requireThat(policy.keys().getFirst().id(), "keyId").isEqualTo("cat-release");
			requireThat(policy.keys().getFirst().x509Base64(), "releaseKey").isEqualTo(encoded);
		}
	}

	/**
	 * Verifies that the POSIX installer launcher of a Windows plugin passes no {@code java.io.tmpdir}, while that of a
	 * Linux plugin passes {@code TMPDIR} as {@code java.io.tmpdir}. The plugin's Java on Windows follows {@code TEMP} and
	 * {@code TMP}, and cannot use the {@code /c/...} path that Git Bash gives {@code TMPDIR}.
	 * {@code installer-launcher.bats} runs the Linux launcher with {@code TMPDIR} set, empty and unset.
	 *
	 * @throws IOException if the launchers cannot be written or read
	 */
	@Test
	public void onlyWindowsInstallerIgnoresTmpdir() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			TestJvmScope scope = new TestJvmScope())
		{
			Path directory = cleanup.add(Files.createTempDirectory("plugin-installer-"));
			Path windows = directory.resolve("windows");
			Path linux = directory.resolve("linux");
			PluginInstaller installer = new PluginInstaller(scope, "2.3.4", "");
			installer.write(windows, Platform.WINDOWS_X64, true);
			installer.write(linux, Platform.LINUX_X64, true);

			requireThat(Files.readString(windows.resolve("installer")), "windowsInstaller").
				doesNotContain("-Djava.io.tmpdir");
			requireThat(Files.readString(linux.resolve("installer")), "linuxInstaller").
				contains("tmpdir=-Djava.io.tmpdir=$TMPDIR\n");
		}
	}
}
