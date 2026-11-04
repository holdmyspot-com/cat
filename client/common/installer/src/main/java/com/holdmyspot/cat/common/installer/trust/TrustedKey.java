package com.holdmyspot.cat.common.installer.trust;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.installer.trust.TrustException.Reason;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * A public key whose signature on a release manifest the user trusts, as listed in the trust policy.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class TrustedKey
{
	/**
	 * The name of the key in the policy and in the installer's results, such as {@code cat-release-2026}.
	 */
	private final String id;
	/**
	 * The base64 of the key's X.509 {@code SubjectPublicKeyInfo} encoding, as the policy stores it.
	 */
	private final String x509Base64;
	/**
	 * The decoded Ed25519 public key.
	 */
	private final PublicKey key;

	/**
	 * Decodes a key.
	 *
	 * @param id         the key's name, which is not blank
	 * @param x509Base64 the base64 of the key's X.509 {@code SubjectPublicKeyInfo} encoding
	 * @return the key
	 * @throws NullPointerException if any of the arguments are null
	 * @throws TrustException       if {@code id} is blank, or {@code x509Base64} is not the canonical base64 of an
	 *                              Ed25519 public key
	 */
	public static TrustedKey decode(String id, String x509Base64) throws TrustException
	{
		requireThat(id, "id").isNotNull();
		requireThat(x509Base64, "x509Base64").isNotNull();
		if (id.isBlank())
			throw new TrustException(Reason.MALFORMED_POLICY, "A trusted key's id must not be blank");
		byte[] encoded;
		try
		{
			encoded = Base64.getDecoder().decode(x509Base64);
		}
		catch (IllegalArgumentException _)
		{
			throw notEd25519(id);
		}
		if (!Base64.getEncoder().encodeToString(encoded).equals(x509Base64))
			throw notEd25519(id);
		try
		{
			PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
			return new TrustedKey(id, x509Base64, key);
		}
		catch (GeneralSecurityException _)
		{
			throw notEd25519(id);
		}
	}

	/**
	 * Returns the refusal of a key that is not an Ed25519 public key.
	 *
	 * @param id the key's name
	 * @return the exception
	 */
	private static TrustException notEd25519(String id)
	{
		return new TrustException(Reason.MALFORMED_POLICY, "The trusted key \"" + TrustText.excerpt(id) +
			"\" is not the base64 of an Ed25519 public key's X.509 encoding");
	}

	/**
	 * Creates a key.
	 *
	 * @param id         the key's name
	 * @param x509Base64 the base64 of the key's X.509 encoding
	 * @param key        the decoded key
	 */
	private TrustedKey(String id, String x509Base64, PublicKey key)
	{
		this.id = id;
		this.x509Base64 = x509Base64;
		this.key = key;
	}

	/**
	 * Returns the key's name.
	 *
	 * @return the name, such as {@code cat-release-2026}
	 */
	public String id()
	{
		return id;
	}

	/**
	 * Returns the key in the form that the policy stores.
	 *
	 * @return the base64 of the key's X.509 {@code SubjectPublicKeyInfo} encoding
	 */
	public String x509Base64()
	{
		return x509Base64;
	}

	/**
	 * Returns the decoded key.
	 *
	 * @return the Ed25519 public key
	 */
	PublicKey key()
	{
		return key;
	}

	/**
	 * Indicates whether another object is a key with the same name and encoding.
	 *
	 * @param object the other object
	 * @return {@code true} if {@code object} is the same key
	 */
	@Override
	public boolean equals(Object object)
	{
		return object instanceof TrustedKey other && other.id.equals(id) && other.x509Base64.equals(x509Base64);
	}

	@Override
	public int hashCode()
	{
		return 31 * id.hashCode() + x509Base64.hashCode();
	}

	@Override
	public String toString()
	{
		return id;
	}
}
