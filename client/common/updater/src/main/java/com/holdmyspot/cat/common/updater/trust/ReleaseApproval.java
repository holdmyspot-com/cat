package com.holdmyspot.cat.common.updater.trust;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.updater.trust.TrustException.Reason;

/**
 * Decides whether the user's trust policy approves installing a verified release.
 * <p>
 * A routine update, which a project may request, never installs a release older than the active one.
 */
public final class ReleaseApproval
{
	/**
	 * Approves a release for a routine update.
	 * <p>
	 * The release is approved when one of the policy's keys verified its manifest, it is the requested version, it is at
	 * least the policy's minimum, its major version is at most the policy's maximum, and it is not older than the active
	 * installation. A release equal to the active one is approved; the caller then reports that the installation is up
	 * to date.
	 *
	 * @param release   the verified release manifest
	 * @param policy    the user's trust policy
	 * @param requested the version that the user or project asked for, or {@code null} for the latest release
	 * @param active    the version of the active installation, or {@code null} when CAT is not installed
	 * @throws NullPointerException if {@code release} or {@code policy} are null
	 * @throws TrustException       if no key of the policy verified the manifest, the manifest's version differs
	 *                              from {@code requested}, is below the policy's minimum, has a major version above
	 *                              the policy's maximum, or is lower than {@code active}
	 */
	public static void approveUpdate(VerifiedManifest release, TrustPolicy policy, ReleaseVersion requested,
		ReleaseVersion active) throws TrustException
	{
		approveSigned(release, policy, requested);
		ReleaseVersion version = release.manifest().version();
		if (active != null && version.compareTo(active) < 0)
		{
			throw new TrustException(Reason.DOWNGRADE, "Release " + version + " is older than the active version " +
				active + ".");
		}
	}

	/**
	 * Approves a release that the policy's key signed, whatever the active version.
	 * <p>
	 * The release is approved when one of the policy's keys verified its manifest, it is the requested version, it is at
	 * least the policy's minimum, and its major version is at most the policy's maximum.
	 *
	 * @param release   the verified release manifest
	 * @param policy    the user's trust policy
	 * @param requested the requested version, or {@code null} to accept the manifest's version
	 * @throws NullPointerException if {@code release} or {@code policy} are null
	 * @throws TrustException       if no key of the policy verified the manifest, the manifest's version differs
	 *                              from {@code requested}, is below the policy's minimum, or has a major version above
	 *                              the policy's maximum
	 */
	private static void approveSigned(VerifiedManifest release, TrustPolicy policy, ReleaseVersion requested)
		throws TrustException
	{
		requireThat(release, "release").isNotNull();
		requireThat(policy, "policy").isNotNull();
		// A manifest verified against another policy, such as one that an earlier run read before setup replaced it,
		// carries a key that the user may have since removed.
		if (!policy.keys().contains(release.key()))
		{
			throw new TrustException(Reason.SIGNATURE_NOT_VERIFIED, "The release manifest was verified by the key \"" +
				release.keyId() + "\", which the trust policy does not list");
		}
		ReleaseVersion version = release.manifest().version();
		if (requested != null && !version.equals(requested))
		{
			throw new TrustException(Reason.VERSION_MISMATCH, "Version " + requested + " was requested, but the " +
				"release manifest declares version " + version);
		}
		if (version.compareTo(policy.minimum()) < 0)
		{
			throw new TrustException(Reason.BELOW_MINIMUM, "Release " + version + " is older than the trust " +
				"policy's minimum version " + policy.minimum());
		}
		if (version.major() > policy.maximumMajor())
		{
			throw new TrustException(Reason.ABOVE_MAXIMUM_MAJOR, "Release " + version + " has major version " +
				version.major() + ", but the trust policy allows at most major version " + policy.maximumMajor());
		}
	}

	/**
	 * Prevents instantiation.
	 */
	private ReleaseApproval()
	{
	}
}
