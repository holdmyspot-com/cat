package com.holdmyspot.cat.common.installer.cli.internal.test;

import static com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures.sign;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.installer.engine.ReleaseConnection;
import com.holdmyspot.cat.common.installer.engine.test.ReleaseHostClient;
import com.holdmyspot.cat.common.installer.trust.TrustException;
import com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
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
 * Publishes release 1.2 of CAT, whose signed manifest names one plugin archive, for the platform that runs the test: a
 * gzip-compressed tar archive of an extracted plugin folder. The release lies on a release host that CAT's real HTTPS
 * release source reaches through a {@link ReleaseHostClient}, without a network; after {@link #goOffline()} the host
 * fails every request as an unreachable network does.
 */
final class PluginRelease
{
	/**
	 * The release's tag.
	 */
	static final String TAG = "v1.2";
	private final ReleaseHostClient host;

	/**
	 * Creates the release.
	 *
	 * @param plugin         the extracted plugin folder, whose name is the archive's top directory
	 * @param harness        the harness whose plugin the folder holds
	 * @param key            the key that signs the release
	 * @param releaseBaseUrl the location below which the host publishes its releases
	 * @throws IOException if the folder cannot be read
	 */
	PluginRelease(Path plugin, String harness, KeyPair key, URI releaseBaseUrl) throws IOException
	{
		byte[] archive = archive(plugin);
		String top = plugin.getFileName().toString();
		String asset = ReleaseFixtures.asset(top + ".tar.gz", "plugin", "\"harness\": \"" + harness + "\", ",
			Platform.current().id(), Long.toString(archive.length), sha256(archive), top);
		byte[] manifest = ReleaseFixtures.manifest("1.2", TAG, asset).getBytes(UTF_8);
		this.host = new ReleaseHostClient(releaseBaseUrl);
		host.publish(TAG, "cat-release-manifest.json", manifest);
		host.publish(TAG, "cat-release-manifest.json.sig", sign(manifest, key));
		host.publish(TAG, top + ".tar.gz", archive);
		host.latest(TAG);
	}

	/**
	 * Makes every later request fail as if the network were unreachable.
	 */
	void goOffline()
	{
		host.goOffline();
	}

	/**
	 * Opens CAT's release source at a location of the host, as the installer opens it at its policy's location.
	 *
	 * @param releaseBaseUrl the policy's release base URL
	 * @return the source, which the caller closes
	 * @throws TrustException if the source refuses the location
	 */
	ReleaseConnection open(URI releaseBaseUrl) throws TrustException
	{
		return host.open(releaseBaseUrl);
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
