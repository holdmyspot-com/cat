package com.holdmyspot.cat.common.installer.trust.test;

import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.keyPair;
import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.manifest;
import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.policy;
import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.sign;
import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.trusted;
import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.verify;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.installer.trust.ReleaseApproval;
import com.holdmyspot.cat.common.installer.trust.ReleaseVersion;
import com.holdmyspot.cat.common.installer.trust.TrustException;
import com.holdmyspot.cat.common.installer.trust.TrustException.Reason;
import com.holdmyspot.cat.common.installer.trust.TrustPolicy;
import com.holdmyspot.cat.common.installer.trust.TrustedKey;
import com.holdmyspot.cat.common.installer.trust.VerifiedManifest;
import java.io.IOException;
import java.security.KeyPair;
import java.util.List;
import org.testng.annotations.Test;

/**
 * Tests {@link ReleaseApproval}: which verified releases the user's trust policy lets an update install.
 */
public final class ReleaseApprovalTest
{
	/**
	 * Creates a test instance.
	 */
	public ReleaseApprovalTest()
	{
	}

	/**
	 * A release that the policy allows.
	 *
	 * @param version the release's version
	 * @param keyPair the key pair that signs it
	 * @param policy  the policy that verifies it
	 * @return the verified release
	 * @throws TrustException if the manifest is refused
	 * @throws IOException    if the bytes cannot be read
	 */
	private static VerifiedManifest release(String version, KeyPair keyPair, TrustPolicy policy)
		throws TrustException, IOException
	{
		byte[] manifest = manifest(version).getBytes(UTF_8);
		return verify(manifest, sign(manifest, keyPair), policy);
	}

	/**
	 * Approves a release for a routine update and returns the refusal.
	 *
	 * @param release   the verified release
	 * @param policy    the policy
	 * @param requested the requested version, or {@code null} for the latest
	 * @return the reason for the refusal
	 * @throws TrustException if a version is malformed
	 */
	private static Reason updateRefusal(VerifiedManifest release, TrustPolicy policy, String requested)
		throws TrustException
	{
		ReleaseVersion requestedVersion = parseOrNull(requested);
		try
		{
			ReleaseApproval.approveUpdate(release, policy, requestedVersion);
		}
		catch (TrustException e)
		{
			return e.reason();
		}
		throw new AssertionError("Release " + release + " was approved");
	}

	/**
	 * Parses a version.
	 *
	 * @param version the version, or {@code null}
	 * @return the version, or {@code null}
	 * @throws TrustException if the version is malformed
	 */
	private static ReleaseVersion parseOrNull(String version) throws TrustException
	{
		if (version == null)
			return null;
		return ReleaseVersion.parse(version);
	}

	/**
	 * A release signed by a trusted key and within the policy's versions is approved for an update whether it is the
	 * latest or the requested version.
	 *
	 * @throws TrustException if the release is refused
	 * @throws IOException    if the bytes cannot be read
	 */
	@Test
	public void approvesAllowedRelease() throws TrustException, IOException
	{
		KeyPair signer = keyPair();
		TrustPolicy policy = policy(List.of(trusted("release-key", signer)));
		VerifiedManifest release = release("1.2", signer, policy);

		ReleaseApproval.approveUpdate(release, policy, null);
		ReleaseApproval.approveUpdate(release, policy, ReleaseVersion.parse("1.2"));
	}

	/**
	 * A release whose version differs from the requested one is refused, even when it is newer.
	 *
	 * @throws TrustException if the release is refused before approval
	 * @throws IOException    if the bytes cannot be read
	 */
	@Test
	public void refusesVersionOtherThanRequested() throws TrustException, IOException
	{
		KeyPair signer = keyPair();
		TrustPolicy policy = policy(List.of(trusted("release-key", signer)));
		VerifiedManifest release = release("1.2", signer, policy);

		requireThat(updateRefusal(release, policy, "1.1"), "older").isEqualTo(Reason.VERSION_MISMATCH);
		requireThat(updateRefusal(release, policy, "1.2.0"), "otherSpelling").
			isEqualTo(Reason.VERSION_MISMATCH);
	}

	/**
	 * A release older than every other one is approved, because whether it may replace the active release depends on
	 * the installation's data, which the engine checks.
	 *
	 * @throws TrustException if the release is refused
	 * @throws IOException    if the bytes cannot be read
	 */
	@Test
	public void approvesOlderRelease() throws TrustException, IOException
	{
		KeyPair signer = keyPair();
		TrustPolicy policy = policy(List.of(trusted("release-key", signer)));
		VerifiedManifest release = release("1.9", signer, policy);

		ReleaseApproval.approveUpdate(release, policy, ReleaseVersion.parse("1.9"));
	}

	/**
	 * A release below the policy's minimum, or with a major version above its maximum, is refused.
	 *
	 * @throws TrustException if a release is refused before approval
	 * @throws IOException    if the bytes cannot be read
	 */
	@Test
	public void refusesVersionOutsidePolicy() throws TrustException, IOException
	{
		KeyPair signer = keyPair();
		TrustPolicy policy = policy(List.of(trusted("release-key", signer)), "1.2", 1);
		VerifiedManifest belowMinimum = release("1.1.9", signer, policy);
		VerifiedManifest aboveMajor = release("2.0", signer, policy);

		requireThat(updateRefusal(belowMinimum, policy, null), "belowMinimum").isEqualTo(Reason.BELOW_MINIMUM);
		requireThat(updateRefusal(aboveMajor, policy, null), "aboveMajor").isEqualTo(Reason.ABOVE_MAXIMUM_MAJOR);
	}

	/**
	 * A release verified by a key that the policy no longer lists is refused.
	 *
	 * @throws TrustException if the release is refused before approval
	 * @throws IOException    if the bytes cannot be read
	 */
	@Test
	public void refusesReleaseVerifiedByKeyNotInPolicy() throws TrustException, IOException
	{
		KeyPair signer = keyPair();
		TrustedKey key = trusted("release-key", signer);
		VerifiedManifest release = release("1.2", signer, policy(List.of(key)));
		TrustPolicy replaced = policy(List.of(trusted("release-key", keyPair())));

		requireThat(updateRefusal(release, replaced, null), "reason").isEqualTo(Reason.SIGNATURE_NOT_VERIFIED);
	}
}
