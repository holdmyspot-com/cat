package com.holdmyspot.cat.common.updater.trust;

/**
 * A release manifest together with the trusted key that verified its signature. Only {@link ManifestVerifier} creates
 * one, so holding one shows that the manifest's exact bytes were signed by that key.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class VerifiedManifest
{
	private final ReleaseManifest manifest;
	/**
	 * The trusted key whose signature over the manifest's bytes verified.
	 */
	private final TrustedKey key;

	/**
	 * Creates a verified manifest.
	 *
	 * @param manifest the manifest
	 * @param key      the key that verified its signature
	 */
	VerifiedManifest(ReleaseManifest manifest, TrustedKey key)
	{
		this.manifest = manifest;
		this.key = key;
	}

	/**
	 * Returns the manifest.
	 *
	 * @return the manifest
	 */
	public ReleaseManifest manifest()
	{
		return manifest;
	}

	/**
	 * Returns the name of the trusted key that verified the manifest's signature.
	 *
	 * @return the key's id
	 */
	public String keyId()
	{
		return key.id();
	}

	/**
	 * Returns the trusted key that verified the manifest's signature.
	 *
	 * @return the key
	 */
	TrustedKey key()
	{
		return key;
	}

	@Override
	public String toString()
	{
		return manifest + " verified by " + key.id();
	}
}
