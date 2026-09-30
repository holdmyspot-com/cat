package io.github.cowwoc.cat.common.optimizer.runner;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceReader;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Reads immutable bytes only when they match their content address.
 */
final class VerifiedEvidence
{
	/**
	 * Prevents construction of a stateless reader.
	 */
	private VerifiedEvidence()
	{
	}

	/**
	 * Reads and rehashes one immutable evidence item.
	 *
	 * @param reader the source of retained bytes
	 * @param reference the expected content address
	 * @return a private copy of the verified bytes
	 * @throws IOException if the source is missing, unreadable, or has changed bytes
	 */
	static byte[] read(EvidenceReader reader, EvidenceRef reference) throws IOException
	{
		Objects.requireNonNull(reader, "reader");
		Objects.requireNonNull(reference, "reference");
		byte[] returned = reader.read(reference);
		if (returned == null)
			throw new IOException("Evidence reader returned no bytes: " + reference.collection());
		byte[] bytes = returned.clone();
		try
		{
			String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
			if (!hash.equals(reference.evidenceId()))
				throw new IOException("Evidence hash mismatch: " + reference.collection());
			return bytes;
		}
		catch (NoSuchAlgorithmException exception)
		{
			throw new IOException("SHA-256 is unavailable for immutable evidence", exception);
		}
	}
}
