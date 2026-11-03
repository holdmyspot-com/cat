package com.holdmyspot.cat.common.updater.trust;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.updater.trust.TrustException.Reason;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.Signature;
import java.util.Arrays;
import java.util.Base64;

/**
 * Checks a release manifest's detached Ed25519 signature against the keys of the user's trust policy, and only then
 * parses the manifest.
 * <p>
 * The signature covers the manifest's exact bytes, so any change to them, including to whitespace, makes verification
 * fail. Verifying before parsing keeps the JSON parser away from content that no trusted key signed.
 */
public final class ManifestVerifier
{
	/**
	 * The largest manifest that the updater reads: 1 MiB.
	 */
	public static final int MAXIMUM_MANIFEST_SIZE = 1024 * 1024;
	/**
	 * The largest signature file that the updater reads: 1 KiB.
	 */
	public static final int MAXIMUM_SIGNATURE_SIZE = 1024;
	/**
	 * The length of a raw Ed25519 signature, in bytes.
	 */
	private static final int SIGNATURE_LENGTH = 64;

	/**
	 * Verifies and parses a release manifest.
	 * <p>
	 * The signature file holds the canonical, padded base64 of the 64-byte raw signature, optionally followed by one
	 * newline ({@code \n}). The manifest verifies when any one key of the policy verifies the signature; the result
	 * names that key.
	 *
	 * @param manifest  the manifest's bytes, which this method reads to the end, up to 1 MiB, and closes
	 * @param signature the signature file's bytes, which this method reads to the end, up to 1 KiB, and closes
	 * @param policy    the trust policy whose keys may sign releases
	 * @return the manifest and the key that verified it
	 * @throws NullPointerException if any of the arguments are null
	 * @throws TrustException       if the manifest is larger than 1 MiB, the signature file is not in the form above,
	 *                              the policy lists no key, no key verifies the signature, or the verified manifest
	 *                              breaks a rule of its format
	 * @throws IOException          if either stream cannot be read
	 */
	public static VerifiedManifest verify(InputStream manifest, InputStream signature, TrustPolicy policy)
		throws TrustException, IOException
	{
		requireThat(manifest, "manifest").isNotNull();
		requireThat(signature, "signature").isNotNull();
		requireThat(policy, "policy").isNotNull();

		byte[] manifestBytes = readCapped(manifest, MAXIMUM_MANIFEST_SIZE);
		if (manifestBytes.length > MAXIMUM_MANIFEST_SIZE)
		{
			throw new TrustException(Reason.MANIFEST_TOO_LARGE, "The release manifest is larger than " +
				MAXIMUM_MANIFEST_SIZE + " bytes");
		}
		byte[] signatureBytes = decodeSignature(readCapped(signature, MAXIMUM_SIGNATURE_SIZE));
		if (policy.keys().isEmpty())
		{
			throw new TrustException(Reason.NO_TRUSTED_KEYS, "The trust policy lists no release key, so no release " +
				"can be verified. Run the CAT setup command of a release that ships its signing key.");
		}
		for (TrustedKey key : policy.keys())
		{
			if (verifies(key, manifestBytes, signatureBytes))
				return new VerifiedManifest(ReleaseManifest.parse(manifestBytes), key);
		}
		throw new TrustException(Reason.SIGNATURE_NOT_VERIFIED, "No key of the trust policy verifies the release " +
			"manifest's signature. Trusted keys: " + String.join(", ", policy.keys().stream().
			map(TrustedKey::id).toList()));
	}

	/**
	 * Reads a stream to its end or one byte past a limit, whichever comes first, and closes it.
	 * <p>
	 * Unlike {@code BoundedInput}, which reports an oversized input as an {@link IOException}, this returns the
	 * oversized bytes so that the caller can refuse them as an untrusted release rather than a failed download.
	 *
	 * @param input   the stream
	 * @param maximum the largest content that the caller accepts, in bytes
	 * @return the content, which is longer than {@code maximum} when the stream has more bytes
	 * @throws IOException if the stream cannot be read
	 */
	private static byte[] readCapped(InputStream input, int maximum) throws IOException
	{
		try (input)
		{
			return input.readNBytes(maximum + 1);
		}
	}

	/**
	 * Decodes a signature file.
	 *
	 * @param content the file's bytes
	 * @return the 64-byte raw signature
	 * @throws TrustException if the content is not the canonical base64 of 64 bytes, optionally followed by one
	 *                        newline
	 */
	private static byte[] decodeSignature(byte[] content) throws TrustException
	{
		int length = content.length;
		if (length > 0 && content[length - 1] == '\n')
			--length;
		byte[] encoded = Arrays.copyOf(content, length);
		byte[] decoded;
		try
		{
			decoded = Base64.getDecoder().decode(encoded);
		}
		catch (IllegalArgumentException _)
		{
			throw malformedSignature();
		}
		// The decoder also accepts unpadded text and nonzero trailing bits; comparing with the canonical encoding
		// gives each signature exactly one accepted spelling.
		if (decoded.length != SIGNATURE_LENGTH || !Arrays.equals(Base64.getEncoder().encode(decoded), encoded))
			throw malformedSignature();
		return decoded;
	}

	/**
	 * Returns the refusal of a malformed signature file.
	 *
	 * @return the exception
	 */
	private static TrustException malformedSignature()
	{
		return new TrustException(Reason.MALFORMED_SIGNATURE, "The release manifest's signature file must hold the " +
			"base64 of a 64-byte Ed25519 signature, optionally followed by one newline");
	}

	/**
	 * Indicates whether a key's signature over some bytes verifies.
	 *
	 * @param key       the trusted key
	 * @param content   the signed bytes
	 * @param signature the 64-byte raw signature
	 * @return {@code true} if the signature is the key's signature over {@code content}
	 */
	private static boolean verifies(TrustedKey key, byte[] content, byte[] signature)
	{
		try
		{
			Signature verifier = Signature.getInstance("Ed25519");
			verifier.initVerify(key.key());
			verifier.update(content);
			return verifier.verify(signature);
		}
		catch (NoSuchAlgorithmException | InvalidKeyException e)
		{
			// java.base provides Ed25519, and TrustedKey accepts only keys that its key factory decoded
			throw new AssertionError("Ed25519 cannot verify the key " + key.id(), e);
		}
		catch (GeneralSecurityException _)
		{
			// A signature that does not encode a valid curve point is not this key's signature
			return false;
		}
	}

	/**
	 * Prevents instantiation.
	 */
	private ManifestVerifier()
	{
	}
}
