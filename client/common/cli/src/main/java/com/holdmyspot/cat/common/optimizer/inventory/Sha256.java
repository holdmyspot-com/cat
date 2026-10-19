package com.holdmyspot.cat.common.optimizer.inventory;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * A SHA-256 digest, used for file content, inventory and candidate identities.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 *
 * @param hex the digest as 64 lowercase hexadecimal digits
 */
public record Sha256(String hex)
{
	private static final String HEX_DIGITS = "^[0-9a-f]{64}$";

	/**
	 * Creates a digest.
	 *
	 * @param hex the digest as 64 lowercase hexadecimal digits
	 * @throws NullPointerException if {@code hex} is null
	 * @throws IllegalArgumentException if {@code hex} is not 64 lowercase hexadecimal digits
	 */
	public Sha256
	{
		requireThat(hex, "hex").matches(HEX_DIGITS);
	}

	/**
	 * Returns the digest of bytes.
	 *
	 * @param bytes the bytes to digest
	 * @return the digest
	 * @throws NullPointerException if {@code bytes} is null
	 */
	public static Sha256 of(byte[] bytes)
	{
		MessageDigest digest = newDigest();
		digest.update(bytes);
		return of(digest);
	}

	/**
	 * Returns a new SHA-256 message digest.
	 *
	 * @return the message digest
	 */
	static MessageDigest newDigest()
	{
		try
		{
			return MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException e)
		{
			// Every Java platform implementation must support SHA-256
			throw new AssertionError(e);
		}
	}

	/**
	 * Completes a message digest.
	 *
	 * @param digest a SHA-256 message digest that has received all of its input; it is reset
	 * @return the digest
	 */
	static Sha256 of(MessageDigest digest)
	{
		return new Sha256(HexFormat.of().formatHex(digest.digest()));
	}
}
