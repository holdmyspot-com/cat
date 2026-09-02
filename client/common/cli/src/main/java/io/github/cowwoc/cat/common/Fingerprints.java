package io.github.cowwoc.cat.common;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Computes stable SHA-256 fingerprints for client artifacts and UTF-8 text.
 */
public final class Fingerprints
{
	private static final int BUFFER_SIZE = 8 * 1024;

	/**
	 * Prevents construction of this utility owner.
	 */
	private Fingerprints()
	{
	}

	/**
	 * Computes the SHA-256 fingerprint of one UTF-8 value.
	 *
	 * @param value the text to fingerprint
	 * @return the lowercase hexadecimal fingerprint
	 * @throws IOException if SHA-256 is unavailable
	 */
	public static String ofUtf8(String value) throws IOException
	{
		Objects.requireNonNull(value, "value must not be null");
		return encodeDigest(newDigest().digest(value.getBytes(StandardCharsets.UTF_8)));
	}

	/**
	 * Computes the SHA-256 fingerprint of one file without loading it wholly into memory.
	 *
	 * @param file the regular file to fingerprint
	 * @return the lowercase hexadecimal fingerprint
	 * @throws IOException if {@code file} cannot be read or SHA-256 is unavailable
	 */
	public static String ofFile(Path file) throws IOException
	{
		Objects.requireNonNull(file, "file must not be null");
		MessageDigest digest = newDigest();
		try (InputStream input = Files.newInputStream(file))
		{
			byte[] buffer = new byte[BUFFER_SIZE];
			int read = input.read(buffer);
			while (read != -1)
			{
				digest.update(buffer, 0, read);
				read = input.read(buffer);
			}
		}
		return encodeDigest(digest.digest());
	}

	/**
	 * Computes the SHA-256 fingerprint of one regular file or every regular file below one directory.
	 *
	 * @param artifact the regular file or directory to fingerprint
	 * @return the lowercase hexadecimal fingerprint
	 * @throws IOException if {@code artifact} cannot be read or is neither a regular file nor a directory
	 */
	public static String ofArtifact(Path artifact) throws IOException
	{
		Objects.requireNonNull(artifact, "artifact must not be null");
		if (Files.isRegularFile(artifact))
			return ofFile(artifact);
		if (!Files.isDirectory(artifact))
			throw new IOException("Artifact must be a regular file or directory: " + artifact);

		MessageDigest digest = newDigest();
		try (Stream<Path> paths = Files.walk(artifact))
		{
			for (Path file : paths.filter(Files::isRegularFile).sorted(Comparator.naturalOrder()).toList())
				updateDigest(digest, "artifact/" + artifact.relativize(file), file);
		}
		return encodeDigest(digest.digest());
	}

	/**
	 * Creates the required SHA-256 digest implementation.
	 *
	 * @return a new SHA-256 digest
	 * @throws IOException if the runtime does not provide SHA-256
	 */
	private static MessageDigest newDigest() throws IOException
	{
		try
		{
			return MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IOException("SHA-256 is unavailable", e);
		}
	}

	/**
	 * Adds one file and its stable relative label to a digest.
	 *
	 * @param digest the digest to update
	 * @param label the file's stable relative label
	 * @param file the regular file to read
	 * @throws IOException if {@code file} cannot be read
	 */
	private static void updateDigest(MessageDigest digest, String label, Path file) throws IOException
	{
		digest.update(label.getBytes(StandardCharsets.UTF_8));
		digest.update((byte) 0);
		try (InputStream input = Files.newInputStream(file))
		{
			byte[] buffer = new byte[BUFFER_SIZE];
			int read = input.read(buffer);
			while (read != -1)
			{
				digest.update(buffer, 0, read);
				read = input.read(buffer);
			}
		}
	}

	/**
	 * Encodes one digest using the feedback and certification contract's lowercase hexadecimal representation.
	 *
	 * @param digest the SHA-256 bytes
	 * @return the lowercase hexadecimal fingerprint
	 */
	private static String encodeDigest(byte[] digest)
	{
		return HexFormat.of().formatHex(digest);
	}
}
