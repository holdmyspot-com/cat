package com.holdmyspot.cat.common.optimizer.inventory;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * Computes a {@link Sha256} identity over a sequence of values, each written with its length so that no two
 * different sequences produce the same input bytes.
 * <p>
 * Persisted identities depend on this encoding; changing it changes every identity computed afterwards.
 */
final class CanonicalDigest
{
	private final ByteArrayOutputStream encoded = new ByteArrayOutputStream();

	/**
	 * Creates a digest whose first value names the identity's format, so identities of different kinds never
	 * coincide.
	 *
	 * @param format the name and version of the identity's format
	 */
	CanonicalDigest(String format)
	{
		text(format);
	}

	/**
	 * Adds text.
	 *
	 * @param value the text
	 * @return this digest
	 */
	CanonicalDigest text(String value)
	{
		byte[] bytes = value.getBytes(UTF_8);
		encoded.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
		encoded.writeBytes(bytes);
		return this;
	}

	/**
	 * Returns the identity of the values added so far.
	 *
	 * @return the identity
	 */
	Sha256 finish()
	{
		return Sha256.of(encoded.toByteArray());
	}
}
