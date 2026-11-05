package com.holdmyspot.cat.distribution.bundle;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.JvmScope;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.installer.trust.ReleaseVersion;
import com.holdmyspot.cat.common.installer.trust.TrustException;
import com.holdmyspot.cat.common.installer.trust.TrustPolicy;
import com.holdmyspot.cat.common.installer.trust.TrustedKey;
import com.holdmyspot.cat.common.runner.PluginDirectory;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Writes the installer that each plugin carries into the plugin's {@code bin} folder: the launchers that run it from
 * the plugin's modules on the Java runtime in the plugin's {@code bin/runtime} folder, and the default policy in
 * {@code bin/conf/installer}, which names the release key that install trusts and the releases it accepts. No other
 * thread or process modifies the folder while it is written.
 */
public final class PluginInstaller
{
	/**
	 * The file from which install obtains the release keys and approval limits.
	 */
	private static final String DEFAULT_POLICY = "default-policy.json";
	private final JvmScope scope;
	private final TrustPolicy policy;

	/**
	 * Creates a writer for one release's plugins.
	 *
	 * @param scope     the build's waiting policy for atomic file replacement
	 * @param version   the release version, consisting of two or three decimal numbers
	 * @param publicKey the base64 X.509 Ed25519 release key, or empty for a development build that trusts no key
	 * @throws NullPointerException if any argument is null
	 * @throws IOException          if the version or release key is invalid
	 */
	public PluginInstaller(JvmScope scope, String version, String publicKey) throws IOException
	{
		requireThat(scope, "scope").isNotNull();
		requireThat(version, "version").isNotNull();
		requireThat(publicKey, "publicKey").isNotNull();
		try
		{
			ReleaseVersion release = ReleaseVersion.parse(version);
			List<TrustedKey> keys = List.of();
			if (!publicKey.isEmpty())
				keys = List.of(TrustedKey.decode("cat-release", publicKey));
			policy = TrustPolicy.of(keys, URI.create("https://github.com/holdmyspot-com/cat/releases/download"),
				release, release.major(), Map.of());
		}
		catch (TrustException e)
		{
			throw new IOException("Cannot generate the installer's default policy: " + e.getMessage(), e);
		}
		this.scope = scope;
	}

	/**
	 * Writes the installer's launchers and default policy into a plugin's {@code bin} folder.
	 *
	 * @param bin      the folder that becomes the plugin's {@code bin} folder
	 * @param platform the platform that the plugin runs on
	 * @param posix    {@code true} to write the POSIX launcher {@code installer}; {@code false} only for a plugin on
	 *                 Windows whose harness runs commands in PowerShell. On Windows, {@code installer.ps1} is written
	 *                 too.
	 * @throws NullPointerException     if any argument is null
	 * @throws IllegalArgumentException if {@code posix} is {@code false} on a platform other than Windows
	 * @throws IOException              if a file cannot be written
	 */
	public void write(Path bin, Platform platform, boolean posix) throws IOException
	{
		requireThat(bin, "bin").isNotNull();
		requireThat(platform, "platform").isNotNull();
		InstallerLaunchers.write(bin, platform, posix);
		Path configuration = Files.createDirectories(bin.resolve(
			PluginDirectory.BIN.relativize(PluginDirectory.INSTALLER_CONFIGURATION)));
		policy.write(configuration.resolve(DEFAULT_POLICY), FileReplacer.retrying(scope.waiting()));
	}
}
