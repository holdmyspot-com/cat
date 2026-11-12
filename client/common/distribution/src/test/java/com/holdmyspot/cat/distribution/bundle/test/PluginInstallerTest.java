package com.holdmyspot.cat.distribution.bundle.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.PortableFiles;
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
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import org.testng.annotations.Test;

/**
 * Tests the default policy that {@link PluginInstaller} writes into a plugin's {@code bin} folder, read through the
 * installer's policy reader, and the options that the POSIX installer launcher it writes passes to Java.
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
	 * Verifies that the POSIX installer launcher of a Windows plugin passes no {@code java.io.tmpdir} when
	 * {@code TMPDIR} is set, whatever the host that runs it, because the plugin's Java on Windows follows {@code TEMP}
	 * and {@code TMP} and cannot use the {@code /c/...} path that Git Bash gives {@code TMPDIR}.
	 */
	@Test
	public void windowsInstallerPassesNoTmpdir() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			TestJvmScope scope = new TestJvmScope())
		{
			Path bin = cleanup.add(Files.createTempDirectory("plugin installer ")).toRealPath().resolve("bin");
			new PluginInstaller(scope, "2.3.4", "").write(bin, Platform.WINDOWS_X64, true);
			Path java = Files.createDirectories(bin.resolve("runtime/bin")).resolve("java");
			Files.writeString(java, """
				#!/bin/sh
				printf '%s\\n' "$@"
				""");
			if (PortableFiles.hasModes(java))
				Files.setPosixFilePermissions(java, PosixFilePermissions.fromString("rwxr-xr-x"));
			List<String> command = List.of("sh", bin.resolve("installer").toString().replace('\\', '/'));
			ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
			builder.environment().put("LC_ALL", "C.UTF-8");
			builder.environment().put("TMPDIR", "/c/session temp");

			try (Process process = builder.start())
			{
				process.getOutputStream().close();
				String output = new String(process.getInputStream().readAllBytes(), UTF_8);
				requireThat(process.waitFor(), "status").withContext(output, "output").isEqualTo(0);
				requireThat(output, "output").doesNotContain("java.io.tmpdir");
			}
		}
	}
}
