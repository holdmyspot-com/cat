package com.holdmyspot.cat.common.installer.cli.internal.test;

import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.sign;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.installer.engine.ReleaseConnection;
import com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

/**
 * Serves release 1.2 of CAT, whose signed manifest names one plugin archive, for the platform that runs the test: a
 * gzip-compressed tar archive of an extracted plugin folder. The source supplies the release's files and never reaches
 * a network; after {@link #goOffline()} it fails every request as an unreachable network does.
 */
final class PluginRelease implements ReleaseConnection
{
	/**
	 * The release's tag.
	 */
	static final String TAG = "v1.2";
	private final byte[] manifest;
	private final byte[] signature;
	private final byte[] archive;
	private boolean offline;

	/**
	 * Creates the release.
	 *
	 * @param plugin  the extracted plugin folder, whose name is the archive's top directory
	 * @param harness the harness whose plugin the folder holds
	 * @param key     the key that signs the release
	 * @throws IOException if the folder cannot be read
	 */
	PluginRelease(Path plugin, String harness, KeyPair key) throws IOException
	{
		this.archive = archive(plugin);
		String top = plugin.getFileName().toString();
		String asset = ReleaseFixtures.asset(top + ".tar.gz", "plugin", "\"harness\": \"" + harness + "\", ",
			Platform.current().id(), Long.toString(archive.length), sha256(archive), top);
		this.manifest = ReleaseFixtures.manifest("1.2", TAG, asset).getBytes(UTF_8);
		this.signature = sign(manifest, key);
	}

	/**
	 * Makes every later request fail as if the network were unreachable.
	 */
	void goOffline()
	{
		offline = true;
	}

	@Override
	public String latestTag()
	{
		return TAG;
	}

	@Override
	public byte[] manifest(String tag) throws IOException
	{
		return serve(tag, manifest);
	}

	@Override
	public byte[] signature(String tag) throws IOException
	{
		return serve(tag, signature);
	}

	@Override
	public void download(String tag, String assetName, long expectedSize, Path destination) throws IOException
	{
		Files.write(destination, serve(tag, archive));
	}

	@Override
	public void close()
	{
	}

	/**
	 * Returns the release's file.
	 *
	 * @param tag     the requested tag
	 * @param content the file of this release
	 * @return a copy of the file
	 * @throws IOException if the network is unreachable, or the tag names another release, which the source does not
	 *                     have
	 */
	private byte[] serve(String tag, byte[] content) throws IOException
	{
		if (offline)
			throw new IOException("The network is unreachable: " + tag);
		if (!tag.equals(TAG))
			throw new IOException("The release source has no release " + tag);
		return content.clone();
	}

	/**
	 * Archives a plugin folder below its own name, keeping which files are executable.
	 *
	 * @param plugin the folder
	 * @return the archive's bytes
	 * @throws IOException if the folder cannot be read
	 */
	private static byte[] archive(Path plugin) throws IOException
	{
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		Path parent = plugin.getParent();
		try (TarArchiveOutputStream tar = new TarArchiveOutputStream(new GZIPOutputStream(bytes));
			Stream<Path> paths = Files.walk(plugin))
		{
			for (Path path : paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).toList())
			{
				byte[] content = Files.readAllBytes(path);
				TarArchiveEntry entry = new TarArchiveEntry(parent.relativize(path).toString().replace('\\', '/'));
				entry.setSize(content.length);
				if (Files.isExecutable(path))
					entry.setMode(0755);
				else
					entry.setMode(0644);
				tar.putArchiveEntry(entry);
				tar.write(content);
				tar.closeArchiveEntry();
			}
		}
		return bytes.toByteArray();
	}

	/**
	 * Calculates the digest that the release's signed manifest states.
	 *
	 * @param bytes the archive's bytes
	 * @return the lowercase digest
	 */
	private static String sha256(byte[] bytes)
	{
		try
		{
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new AssertionError("The platform cannot provide SHA-256", e);
		}
	}
}
