package com.holdmyspot.cat.common.installer.trust.test;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.installer.trust.ManifestVerifier;
import com.holdmyspot.cat.common.installer.trust.ReleaseVersion;
import com.holdmyspot.cat.common.installer.trust.TrustException;
import com.holdmyspot.cat.common.installer.trust.TrustPolicy;
import com.holdmyspot.cat.common.installer.trust.TrustedKey;
import com.holdmyspot.cat.common.installer.trust.VerifiedManifest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Builds the release manifests, signatures and trust policies that the trust tests verify. Each key pair is generated
 * for the test that uses it, so no test depends on a real release key.
 */
public final class ReleaseFixtures
{
	/**
	 * The release location of the tests' policies.
	 */
	public static final URI RELEASE_BASE_URL = URI.create("https://releases.example/cat/releases/download");
	/**
	 * A SHA-256 digest in the manifest's format.
	 */
	public static final String SHA256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

	/**
	 * Generates an Ed25519 key pair.
	 *
	 * @return the key pair
	 */
	public static KeyPair keyPair()
	{
		try
		{
			return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
		}
		catch (GeneralSecurityException e)
		{
			throw new AssertionError("The JDK cannot generate Ed25519 keys", e);
		}
	}

	/**
	 * Returns the trusted key form of a key pair's public key.
	 *
	 * @param id      the key's name
	 * @param keyPair the key pair
	 * @return the trusted key
	 * @throws TrustException if the key is rejected
	 */
	public static TrustedKey trusted(String id, KeyPair keyPair) throws TrustException
	{
		return TrustedKey.decode(id, Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()));
	}

	/**
	 * Returns a policy that trusts some keys and allows every version from {@code 1.0} with major version 1.
	 *
	 * @param keys the trusted keys
	 * @return the policy
	 * @throws TrustException if the policy is rejected
	 */
	public static TrustPolicy policy(List<TrustedKey> keys) throws TrustException
	{
		return policy(keys, "1.0", 1);
	}

	/**
	 * Returns a policy that trusts some keys.
	 *
	 * @param keys         the trusted keys
	 * @param minimum      the lowest allowed version
	 * @param maximumMajor the highest allowed major version
	 * @return the policy
	 * @throws TrustException if the policy is rejected
	 */
	public static TrustPolicy policy(List<TrustedKey> keys, String minimum, int maximumMajor) throws TrustException
	{
		return TrustPolicy.of(keys, RELEASE_BASE_URL,
			ReleaseVersion.parse(minimum), maximumMajor,
			Map.of("harness-a", Path.of(System.getProperty("cat.installer.test.directory")).
				resolve("harness-homes/harness-a")));
	}

	/**
	 * Signs content with a key pair's private key.
	 *
	 * @param content the content
	 * @param keyPair the key pair
	 * @return the content of the signature file: the base64 of the raw signature followed by a newline
	 */
	public static byte[] sign(byte[] content, KeyPair keyPair)
	{
		try
		{
			Signature signer = Signature.getInstance("Ed25519");
			signer.initSign(keyPair.getPrivate());
			signer.update(content);
			return (Base64.getEncoder().encodeToString(signer.sign()) + "\n").getBytes(UTF_8);
		}
		catch (GeneralSecurityException e)
		{
			throw new AssertionError("The JDK cannot sign with Ed25519", e);
		}
	}

	/**
	 * Verifies a manifest.
	 *
	 * @param manifest  the manifest's bytes
	 * @param signature the signature file's bytes
	 * @param policy    the trust policy
	 * @return the verified manifest
	 * @throws TrustException if the manifest is refused
	 * @throws IOException    if the bytes cannot be read
	 */
	public static VerifiedManifest verify(byte[] manifest, byte[] signature, TrustPolicy policy)
		throws TrustException, IOException
	{
		return ManifestVerifier.verify(new ByteArrayInputStream(manifest), new ByteArrayInputStream(signature),
			policy);
	}

	/**
	 * Signs a manifest with a key pair and verifies it against a policy that trusts only that key pair.
	 *
	 * @param manifest the manifest's text
	 * @param keyPair  the key pair
	 * @return the verified manifest
	 * @throws TrustException if the manifest is refused
	 * @throws IOException    if the bytes cannot be read
	 */
	public static VerifiedManifest signAndVerify(String manifest, KeyPair keyPair) throws TrustException, IOException
	{
		byte[] bytes = manifest.getBytes(UTF_8);
		return verify(bytes, sign(bytes, keyPair), policy(List.of(trusted("test-key", keyPair))));
	}

	/**
	 * Returns a manifest of release {@code version} with one plugin asset for {@code harness-a} on {@code linux-x64}.
	 *
	 * @param version the release's version
	 * @return the manifest's text
	 */
	public static String manifest(String version)
	{
		return manifest(version, "v" + version, pluginAsset(version, "harness-a", "linux-x64"));
	}

	/**
	 * Returns a manifest of product {@code cat}.
	 *
	 * @param version the value of its {@code version} field
	 * @param tag     the value of its {@code tag} field
	 * @param assets  the JSON of its asset entries, separated by commas
	 * @return the manifest's text
	 */
	public static String manifest(String version, String tag, String assets)
	{
		return """
			{
			  "format": 1,
			  "product": "cat",
			  "version": "%s",
			  "tag": "%s",
			  "published": "2026-11-03T13:44:35Z",
			  "assets": [%s]
			}
			""".formatted(version, tag, assets);
	}

	/**
	 * Returns the JSON of a plugin asset entry whose fields agree with each other.
	 *
	 * @param version  the release's version
	 * @param harness  the asset's harness
	 * @param platform the asset's platform
	 * @return the entry's JSON
	 */
	public static String pluginAsset(String version, String harness, String platform)
	{
		String top = "cat-" + harness + "-v" + version + "-" + platform;
		return asset(top + ".tar.gz", "plugin", "\"harness\": \"" + harness + "\", ", platform, "123", SHA256, top);
	}

	/**
	 * Returns the JSON of an installer asset entry whose fields agree with each other.
	 *
	 * @param version  the release's version
	 * @param platform the asset's platform
	 * @return the entry's JSON
	 */
	public static String installerAsset(String version, String platform)
	{
		String top = "cat-updater-v" + version + "-" + platform;
		return asset(top + ".tar.gz", "updater", "", platform, "456", SHA256, top);
	}

	/**
	 * Returns the JSON of an asset entry.
	 *
	 * @param name         the value of its {@code name} field
	 * @param kind         the value of its {@code kind} field
	 * @param harnessField the JSON of its {@code harness} field followed by a comma and a space, or empty
	 * @param platform     the value of its {@code platform} field
	 * @param size         the JSON of its {@code size} field
	 * @param sha256       the value of its {@code sha256} field
	 * @param topDirectory the value of its {@code top_directory} field
	 * @return the entry's JSON
	 */
	public static String asset(String name, String kind, String harnessField, String platform, String size,
		String sha256, String topDirectory)
	{
		return """
			{"name": "%s", "kind": "%s", %s"platform": "%s", "size": %s, "sha256": "%s", "top_directory": "%s"}\
			""".formatted(name, kind, harnessField, platform, size, sha256, topDirectory);
	}

	/**
	 * Creates a new directory for one test below the build's test directory, never in the user's real installer or
	 * harness locations.
	 *
	 * @param prefix the start of the directory's name
	 * @return the directory
	 * @throws IOException if the directory cannot be created
	 */
	public static Path testDirectory(String prefix) throws IOException
	{
		Path base = Path.of(System.getProperty("cat.installer.test.directory"));
		Files.createDirectories(base);
		return Files.createTempDirectory(base, prefix);
	}

	/**
	 * Prevents instantiation.
	 */
	private ReleaseFixtures()
	{
	}
}
