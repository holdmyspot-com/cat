package com.holdmyspot.cat.common.updater.trust.test;

import static com.holdmyspot.cat.common.updater.trust.test.ReleaseFixtures.asset;
import static com.holdmyspot.cat.common.updater.trust.test.ReleaseFixtures.keyPair;
import static com.holdmyspot.cat.common.updater.trust.test.ReleaseFixtures.manifest;
import static com.holdmyspot.cat.common.updater.trust.test.ReleaseFixtures.pluginAsset;
import static com.holdmyspot.cat.common.updater.trust.test.ReleaseFixtures.policy;
import static com.holdmyspot.cat.common.updater.trust.test.ReleaseFixtures.sign;
import static com.holdmyspot.cat.common.updater.trust.test.ReleaseFixtures.signAndVerify;
import static com.holdmyspot.cat.common.updater.trust.test.ReleaseFixtures.trusted;
import static com.holdmyspot.cat.common.updater.trust.test.ReleaseFixtures.updaterAsset;
import static com.holdmyspot.cat.common.updater.trust.test.ReleaseFixtures.verify;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.updater.trust.AssetKind;
import com.holdmyspot.cat.common.updater.trust.ManifestVerifier;
import com.holdmyspot.cat.common.updater.trust.ReleaseAsset;
import com.holdmyspot.cat.common.updater.trust.ReleaseManifest;
import com.holdmyspot.cat.common.updater.trust.TrustException;
import com.holdmyspot.cat.common.updater.trust.TrustException.Reason;
import com.holdmyspot.cat.common.updater.trust.TrustPolicy;
import com.holdmyspot.cat.common.updater.trust.VerifiedManifest;
import java.io.IOException;
import java.security.KeyPair;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.testng.annotations.Test;

/**
 * Tests {@link ManifestVerifier}: which signed release manifests the updater accepts as authentic and well formed.
 */
public final class ManifestVerifierTest
{
	/**
	 * Creates a test instance.
	 */
	public ManifestVerifierTest()
	{
	}

	/**
	 * Verifies a manifest and returns the reason that it was refused.
	 *
	 * @param manifest the manifest's text, signed with a key that the policy trusts
	 * @return the reason
	 * @throws IOException if the bytes cannot be read
	 */
	private static Reason refusal(String manifest) throws IOException
	{
		try
		{
			signAndVerify(manifest, keyPair());
		}
		catch (TrustException e)
		{
			return e.reason();
		}
		throw new AssertionError("The manifest was accepted:\n" + manifest);
	}

	/**
	 * Verifies a manifest and returns the refusal.
	 *
	 * @param manifest  the manifest's bytes
	 * @param signature the signature file's bytes
	 * @param policy    the trust policy
	 * @return the refusal
	 * @throws IOException if the bytes cannot be read
	 */
	private static TrustException refusal(byte[] manifest, byte[] signature, TrustPolicy policy) throws IOException
	{
		try
		{
			verify(manifest, signature, policy);
		}
		catch (TrustException e)
		{
			return e;
		}
		throw new AssertionError("The manifest was accepted");
	}

	/**
	 * A manifest that a trusted key signed is accepted, its values are returned, and the result names the key that
	 * verified it among the policy's keys.
	 *
	 * @throws TrustException if the manifest is refused
	 * @throws IOException    if the bytes cannot be read
	 */
	@Test
	public void acceptsManifestSignedByTrustedKey() throws TrustException, IOException
	{
		KeyPair other = keyPair();
		KeyPair signer = keyPair();
		byte[] manifest = manifest("1.0", "v1.0", pluginAsset("1.0", "harness-a", "linux-x64") + ", " +
			updaterAsset("1.0", "linux-x64")).getBytes(UTF_8);
		TrustPolicy policy = policy(List.of(trusted("other-key", other), trusted("release-key", signer)));

		VerifiedManifest verified = verify(manifest, sign(manifest, signer), policy);

		requireThat(verified.keyId(), "keyId").isEqualTo("release-key");
		ReleaseManifest release = verified.manifest();
		requireThat(release.version().toString(), "version").isEqualTo("1.0");
		requireThat(release.tag(), "tag").isEqualTo("v1.0");
		requireThat(release.published(), "published").isEqualTo(Instant.parse("2026-11-03T13:44:35Z"));
		ReleaseAsset plugin = release.findPlugin("harness-a", "linux-x64").orElseThrow();
		requireThat(plugin.name(), "plugin.name").isEqualTo("cat-harness-a-v1.0-linux-x64.tar.gz");
		requireThat(plugin.kind(), "plugin.kind").isEqualTo(AssetKind.PLUGIN);
		requireThat(plugin.harness(), "plugin.harness").isEqualTo(Optional.of("harness-a"));
		requireThat(plugin.size(), "plugin.size").isEqualTo(123L);
		requireThat(plugin.sha256(), "plugin.sha256").isEqualTo(ReleaseFixtures.SHA256);
		requireThat(plugin.topDirectory(), "plugin.topDirectory").isEqualTo("cat-harness-a-v1.0-linux-x64");
		ReleaseAsset updater = release.findUpdater("linux-x64").orElseThrow();
		requireThat(updater.name(), "updater.name").isEqualTo("cat-updater-v1.0-linux-x64.tar.gz");
		requireThat(updater.harness(), "updater.harness").isEmpty();
		requireThat(release.findPlugin("harness-b", "linux-x64"), "otherHarness").isEmpty();
		requireThat(release.findPlugin("harness-a", "macos-arm64"), "otherPlatform").isEmpty();
	}

	/**
	 * A manifest signed by a key that the policy does not list is refused.
	 *
	 * @throws TrustException if the policy is refused
	 * @throws IOException    if the bytes cannot be read
	 */
	@Test
	public void refusesManifestSignedByUntrustedKey() throws TrustException, IOException
	{
		byte[] manifest = manifest("1.0").getBytes(UTF_8);
		TrustPolicy policy = policy(List.of(trusted("release-key", keyPair())));

		TrustException refusal = refusal(manifest, sign(manifest, keyPair()), policy);

		requireThat(refusal.reason(), "reason").isEqualTo(Reason.SIGNATURE_NOT_VERIFIED);
		requireThat(refusal.getMessage(), "message").contains("release-key");
	}

	/**
	 * Changing any one byte of a signed manifest, including a byte of whitespace that leaves its JSON value unchanged,
	 * makes the signature fail, which shows that the signature covers the exact bytes.
	 *
	 * @throws TrustException if the policy is refused
	 * @throws IOException    if the bytes cannot be read
	 */
	@Test
	public void refusesManifestChangedAfterSigning() throws TrustException, IOException
	{
		KeyPair signer = keyPair();
		TrustPolicy policy = policy(List.of(trusted("release-key", signer)));
		byte[] manifest = manifest("1.0").getBytes(UTF_8);
		byte[] signature = sign(manifest, signer);

		byte[] changedValue = manifest.clone();
		int digit = new String(manifest, UTF_8).indexOf("\"size\": 123") + "\"size\": ".length();
		changedValue[digit] = '2';
		byte[] changedWhitespace = manifest.clone();
		int space = new String(manifest, UTF_8).indexOf(": 1,");
		changedWhitespace[space + 1] = '\t';
		byte[] addedNewline = Arrays.copyOf(manifest, manifest.length + 1);
		addedNewline[manifest.length] = '\n';

		for (byte[] changed : List.of(changedValue, changedWhitespace, addedNewline))
		{
			requireThat(refusal(changed, signature, policy).reason(), "reason").
				isEqualTo(Reason.SIGNATURE_NOT_VERIFIED);
		}
	}

	/**
	 * A signature file that is truncated, is not base64, or has anything but one newline after the base64 is refused.
	 *
	 * @throws TrustException if the policy is refused
	 * @throws IOException    if the bytes cannot be read
	 */
	@Test
	public void refusesMalformedSignatureFile() throws TrustException, IOException
	{
		KeyPair signer = keyPair();
		TrustPolicy policy = policy(List.of(trusted("release-key", signer)));
		byte[] manifest = manifest("1.0").getBytes(UTF_8);
		String encoded = new String(sign(manifest, signer), UTF_8).strip();

		List<String> signatures = List.of(
			encoded.substring(0, 40) + "\n",
			encoded.substring(0, encoded.length() - 2) + "\n",
			"not base64 at all!\n",
			encoded + "\n\n",
			encoded + " \n",
			encoded + "\r\n",
			"");
		for (String signature : signatures)
		{
			requireThat(refusal(manifest, signature.getBytes(UTF_8), policy).reason(), "reason").
				isEqualTo(Reason.MALFORMED_SIGNATURE);
		}
		// The signature without its newline is accepted
		verify(manifest, encoded.getBytes(UTF_8), policy);
	}

	/**
	 * A policy whose key list is empty parses, but no manifest verifies against it.
	 *
	 * @throws TrustException if the policy is refused
	 * @throws IOException    if the bytes cannot be read
	 */
	@Test
	public void refusesEveryManifestWhenPolicyHasNoKeys() throws TrustException, IOException
	{
		byte[] manifest = manifest("1.0").getBytes(UTF_8);
		TrustPolicy policy = policy(List.of());

		requireThat(refusal(manifest, sign(manifest, keyPair()), policy).reason(), "reason").
			isEqualTo(Reason.NO_TRUSTED_KEYS);
	}

	/**
	 * A manifest larger than 1 MiB is refused before its signature is checked.
	 *
	 * @throws TrustException if the policy is refused
	 * @throws IOException    if the bytes cannot be read
	 */
	@Test
	public void refusesOversizedManifest() throws TrustException, IOException
	{
		KeyPair signer = keyPair();
		byte[] manifest = new byte[ManifestVerifier.MAXIMUM_MANIFEST_SIZE + 1];
		Arrays.fill(manifest, (byte) ' ');

		requireThat(refusal(manifest, sign(manifest, signer), policy(List.of(trusted("key", signer)))).reason(),
			"reason").isEqualTo(Reason.MANIFEST_TOO_LARGE);
	}

	/**
	 * A signed manifest whose tag is not {@code v} followed by its version is refused.
	 *
	 * @throws IOException if the bytes cannot be read
	 */
	@Test
	public void refusesTagThatDiffersFromVersion() throws IOException
	{
		requireThat(refusal(manifest("1.0", "v1.0.1", pluginAsset("1.0", "harness-a", "linux-x64"))), "reason").
			isEqualTo(Reason.MALFORMED_MANIFEST);
		requireThat(refusal(manifest("1.0", "1.0", pluginAsset("1.0", "harness-a", "linux-x64"))), "reason").
			isEqualTo(Reason.MALFORMED_MANIFEST);
	}

	/**
	 * A signed manifest of a product other than {@code cat} is refused.
	 *
	 * @throws IOException if the bytes cannot be read
	 */
	@Test
	public void refusesOtherProduct() throws IOException
	{
		String manifest = manifest("1.0").replace("\"product\": \"cat\"", "\"product\": \"dog\"");

		requireThat(refusal(manifest), "reason").isEqualTo(Reason.MALFORMED_MANIFEST);
	}

	/**
	 * A signed manifest whose version has a leading zero, a suffix or too many numbers is refused.
	 *
	 * @throws IOException if the bytes cannot be read
	 */
	@Test
	public void refusesMalformedManifestVersion() throws IOException
	{
		for (String version : List.of("01.0", "1.0-beta", "1.2.3.40.5"))
		{
			String manifest = manifest(version, "v" + version, pluginAsset(version, "harness-a", "linux-x64"));
			requireThat(refusal(manifest), "reason").isEqualTo(Reason.MALFORMED_MANIFEST);
		}
	}

	/**
	 * A signed manifest whose asset name disagrees with the asset's harness, kind, platform or the release version is
	 * refused.
	 *
	 * @throws IOException if the bytes cannot be read
	 */
	@Test
	public void refusesAssetNameThatDisagreesWithFields() throws IOException
	{
		List<String> assets = List.of(
			asset("cat-harness-b-v1.0-linux-x64.tar.gz", "plugin", "\"harness\": \"harness-a\", ", "linux-x64",
				"1", ReleaseFixtures.SHA256, "cat-harness-b-v1.0-linux-x64"),
			asset("cat-harness-a-v1.1-linux-x64.tar.gz", "plugin", "\"harness\": \"harness-a\", ", "linux-x64",
				"1", ReleaseFixtures.SHA256, "cat-harness-a-v1.1-linux-x64"),
			asset("cat-harness-a-v1.0-linux-arm64.tar.gz", "plugin", "\"harness\": \"harness-a\", ", "linux-x64",
				"1", ReleaseFixtures.SHA256, "cat-harness-a-v1.0-linux-arm64"),
			asset("cat-harness-a-v1.0-linux-x64.tar.gz", "updater", "", "linux-x64", "1", ReleaseFixtures.SHA256,
				"cat-harness-a-v1.0-linux-x64"),
			asset("../cat-harness-a-v1.0-linux-x64.tar.gz", "plugin", "\"harness\": \"harness-a\", ", "linux-x64",
				"1", ReleaseFixtures.SHA256, "../cat-harness-a-v1.0-linux-x64"));
		for (String asset : assets)
			requireThat(refusal(manifest("1.0", "v1.0", asset)), "reason").isEqualTo(Reason.MALFORMED_MANIFEST);
	}

	/**
	 * A signed manifest that lists the same asset twice is refused.
	 *
	 * @throws IOException if the bytes cannot be read
	 */
	@Test
	public void refusesDuplicateAsset() throws IOException
	{
		String plugin = pluginAsset("1.0", "harness-a", "linux-x64");

		requireThat(refusal(manifest("1.0", "v1.0", plugin + ", " + plugin)), "reason").
			isEqualTo(Reason.MALFORMED_MANIFEST);
	}

	/**
	 * A signed manifest whose asset's top directory is not its name without {@code .tar.gz} is refused.
	 *
	 * @throws IOException if the bytes cannot be read
	 */
	@Test
	public void refusesTopDirectoryThatDiffersFromName() throws IOException
	{
		String asset = asset("cat-harness-a-v1.0-linux-x64.tar.gz", "plugin", "\"harness\": \"harness-a\", ",
			"linux-x64", "1", ReleaseFixtures.SHA256, "cat");

		requireThat(refusal(manifest("1.0", "v1.0", asset)), "reason").isEqualTo(Reason.MALFORMED_MANIFEST);
	}

	/**
	 * A signed manifest whose asset size lies outside 1 byte to 512 MiB, or is not an integer, is refused, while both
	 * limits are accepted.
	 *
	 * @throws TrustException if a manifest within the limits is refused
	 * @throws IOException    if the bytes cannot be read
	 */
	@Test
	public void refusesSizeOutOfRange() throws TrustException, IOException
	{
		for (String size : List.of("0", "-1", String.valueOf(ReleaseAsset.MAXIMUM_SIZE + 1), "1.5", "\"1\""))
			requireThat(refusal(manifestWithSize(size)), "reason").isEqualTo(Reason.MALFORMED_MANIFEST);
		for (String size : List.of("1", String.valueOf(ReleaseAsset.MAXIMUM_SIZE)))
			signAndVerify(manifestWithSize(size), keyPair());
	}

	/**
	 * Returns a manifest whose plugin asset has a size.
	 *
	 * @param size the JSON of the asset's {@code size} field
	 * @return the manifest's text
	 */
	private static String manifestWithSize(String size)
	{
		return manifest("1.0", "v1.0", asset("cat-harness-a-v1.0-linux-x64.tar.gz", "plugin",
			"\"harness\": \"harness-a\", ", "linux-x64", size, ReleaseFixtures.SHA256, "cat-harness-a-v1.0-linux-x64"));
	}

	/**
	 * A signed manifest whose asset's SHA-256 digest is not 64 lowercase hexadecimal digits is refused.
	 *
	 * @throws IOException if the bytes cannot be read
	 */
	@Test
	public void refusesMalformedSha256() throws IOException
	{
		List<String> digests = List.of(ReleaseFixtures.SHA256.toUpperCase(Locale.ROOT),
			ReleaseFixtures.SHA256.substring(1), ReleaseFixtures.SHA256 + "0", "g".repeat(64));
		for (String digest : digests)
		{
			String asset = asset("cat-harness-a-v1.0-linux-x64.tar.gz", "plugin", "\"harness\": \"harness-a\", ",
				"linux-x64", "1", digest, "cat-harness-a-v1.0-linux-x64");
			requireThat(refusal(manifest("1.0", "v1.0", asset)), "reason").isEqualTo(Reason.MALFORMED_MANIFEST);
		}
	}

	/**
	 * A signed manifest with a field that its format does not define, at the top level or in an asset, is refused.
	 *
	 * @throws IOException if the bytes cannot be read
	 */
	@Test
	public void refusesUnknownField() throws IOException
	{
		String topLevel = manifest("1.0").replace("\"format\": 1,", "\"format\": 1, \"install_script\": \"x\",");
		String inAsset = manifest("1.0").replace("\"kind\": \"plugin\",", "\"kind\": \"plugin\", \"url\": \"x\",");

		requireThat(refusal(topLevel), "topLevel").isEqualTo(Reason.MALFORMED_MANIFEST);
		requireThat(refusal(inAsset), "inAsset").isEqualTo(Reason.MALFORMED_MANIFEST);
	}

	/**
	 * A signed manifest whose plugin lacks a harness, whose updater names one, or whose harness identifier is malformed
	 * is refused.
	 *
	 * @throws IOException if the bytes cannot be read
	 */
	@Test
	public void refusesMisplacedHarness() throws IOException
	{
		String pluginWithoutHarness = asset("cat-harness-a-v1.0-linux-x64.tar.gz", "plugin", "", "linux-x64", "1",
			ReleaseFixtures.SHA256, "cat-harness-a-v1.0-linux-x64");
		String updaterWithHarness = asset("cat-updater-v1.0-linux-x64.tar.gz", "updater",
			"\"harness\": \"harness-a\", ", "linux-x64", "1", ReleaseFixtures.SHA256, "cat-updater-v1.0-linux-x64");
		String malformedHarness = pluginAsset("1.0", "Harness_A", "linux-x64");

		for (String asset : List.of(pluginWithoutHarness, updaterWithHarness, malformedHarness))
			requireThat(refusal(manifest("1.0", "v1.0", asset)), "reason").isEqualTo(Reason.MALFORMED_MANIFEST);
	}

	/**
	 * A signed manifest of another format, with no assets, or with a repeated key is refused.
	 *
	 * @throws IOException if the bytes cannot be read
	 */
	@Test
	public void refusesMalformedStructure() throws IOException
	{
		List<String> manifests = List.of(
			manifest("1.0").replace("\"format\": 1", "\"format\": 2"),
			manifest("1.0", "v1.0", ""),
			manifest("1.0").replace("\"product\": \"cat\",", "\"product\": \"cat\", \"product\": \"cat\","),
			manifest("1.0").replace("2026-11-03T13:44:35Z", "yesterday"),
			"[]");
		for (String manifest : manifests)
			requireThat(refusal(manifest), "reason").isEqualTo(Reason.MALFORMED_MANIFEST);
	}
}
