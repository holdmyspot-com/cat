package com.holdmyspot.cat.common.installer.engine;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.holdmyspot.cat.common.installer.trust.ReleaseAsset;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Checks a downloaded archive against the size and digest authenticated by its release manifest.
 */
final class ReleaseDownload
{
	/**
	 * Prevents instantiation.
	 */
	private ReleaseDownload()
	{
	}

	/**
	 * Checks an archive without loading it into memory.
	 *
	 * @param archive the downloaded file
	 * @param asset   the signed declaration
	 * @throws IOException if the file is not regular, has different bytes, or cannot be read
	 */
	static void validate(Path archive, ReleaseAsset asset) throws IOException
	{
		if (!Files.isRegularFile(archive, NOFOLLOW_LINKS))
			throw new IOException("The downloaded release archive is not a regular file: " + archive);
		if (Files.size(archive) != asset.size())
			throw new IOException("The downloaded release archive's size differs from its signed manifest: " + archive);
		MessageDigest digest;
		try
		{
			digest = MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new AssertionError("The Java platform cannot provide SHA-256", e);
		}
		try (InputStream input = Files.newInputStream(archive))
		{
			byte[] buffer = new byte[64 * 1024];
			int count = input.read(buffer);
			while (count != -1)
			{
				digest.update(buffer, 0, count);
				count = input.read(buffer);
			}
		}
		if (!HexFormat.of().formatHex(digest.digest()).equals(asset.sha256()))
			throw new IOException("The downloaded release archive's SHA-256 differs from its signed manifest: " + archive);
	}
}
