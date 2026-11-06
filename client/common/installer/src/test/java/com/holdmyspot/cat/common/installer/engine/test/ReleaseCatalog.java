package com.holdmyspot.cat.common.installer.engine.test;

import com.holdmyspot.cat.common.installer.engine.ReleaseSource;
import com.holdmyspot.cat.common.installer.trust.TrustException;
import com.holdmyspot.cat.common.installer.trust.TrustPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

/**
 * Serves several releases that one key signs, each with its own migration registry. After {@link #goOffline()} it
 * fails every request as an unreachable network does, so that a test can tell whether an operation needed the network.
 */
final class ReleaseCatalog implements ReleaseSource
{
	private final Releases signer;
	private final Map<String, Releases> releases = new HashMap<>();
	private String latest;
	private boolean offline;

	/**
	 * Creates a catalog of one release.
	 *
	 * @param version  the release version
	 * @param registry the release's migration registry
	 * @throws IOException if creating the release fails
	 */
	ReleaseCatalog(String version, String registry) throws IOException
	{
		this.signer = new Releases(version, "");
		add(version, registry);
	}

	/**
	 * Adds a release, which becomes the latest one.
	 *
	 * @param version  the release version
	 * @param registry the release's migration registry
	 * @return this catalog
	 * @throws IOException if creating the release fails
	 */
	ReleaseCatalog add(String version, String registry) throws IOException
	{
		releases.put("v" + version, new Releases(version, registry, signer));
		latest = "v" + version;
		return this;
	}

	/**
	 * Publishes a release again from another commit under the same tag, as a republished release is: the tag then
	 * serves only the new build, which becomes the latest release.
	 *
	 * @param version  the release version
	 * @param build    the first twelve lower-case hexadecimal digits of the new build's commit
	 * @param registry the new build's migration registry
	 * @return this catalog
	 * @throws IOException if creating the release fails
	 */
	ReleaseCatalog republish(String version, String build, String registry) throws IOException
	{
		releases.put("v" + version, new Releases(version, build, registry, signer));
		latest = "v" + version;
		return this;
	}

	/**
	 * Adds a release whose one property a test case corrupts, which becomes the latest one.
	 *
	 * @param version the release version
	 * @param defect  the corrupted property, as {@link Releases#Releases(String, String)} names it
	 * @return this catalog
	 * @throws IOException if creating the release fails
	 */
	ReleaseCatalog addDefective(String version, String defect) throws IOException
	{
		releases.put("v" + version, Releases.defective(version, defect, signer));
		latest = "v" + version;
		return this;
	}

	/**
	 * Names the release that the catalog reports as the latest one.
	 *
	 * @param version the release version
	 * @return this catalog
	 */
	ReleaseCatalog latest(String version)
	{
		latest = "v" + version;
		return this;
	}

	/**
	 * Makes every later request fail as if the network were unreachable.
	 */
	void goOffline()
	{
		offline = true;
	}

	/**
	 * Creates a policy that trusts the catalog's key.
	 *
	 * @param home    the test-owned harness home
	 * @param minimum the lowest release version that the policy approves
	 * @return the policy
	 * @throws TrustException if the policy is invalid
	 */
	TrustPolicy policy(Path home, String minimum) throws TrustException
	{
		return signer.policy(home, minimum);
	}

	@Override
	public String latestTag() throws IOException
	{
		request("latest");
		return latest;
	}

	@Override
	public byte[] manifest(String tag) throws IOException
	{
		request("manifest " + tag);
		return release(tag).manifest(tag);
	}

	@Override
	public byte[] signature(String tag) throws IOException
	{
		request("signature " + tag);
		return release(tag).signature(tag);
	}

	@Override
	public void download(String tag, String assetName, long expectedSize, Path destination) throws IOException
	{
		request("download " + assetName);
		release(tag).download(tag, assetName, expectedSize, destination);
	}

	/**
	 * Extracts a release's plugin archive without recording a request.
	 *
	 * @param version the release version
	 * @param parent  the directory that receives the archive's top directory
	 * @return the archive's top directory
	 * @throws IOException if the catalog has no such release or the archive cannot be extracted
	 */
	Path extract(String version, Path parent) throws IOException
	{
		String tag = "v" + version;
		String top = "cat-harness-a-linux-x64-v" + version;
		Path archive = parent.resolve(top + ".tar.gz");
		release(tag).download(tag, archive.getFileName().toString(), 0, archive);
		try (TarArchiveInputStream tar = new TarArchiveInputStream(new GZIPInputStream(
			Files.newInputStream(archive))))
		{
			for (TarArchiveEntry entry = tar.getNextEntry(); entry != null; entry = tar.getNextEntry())
			{
				Path target = parent.resolve(entry.getName()).normalize();
				if (!target.startsWith(parent.resolve(top)))
					throw new IOException("The fixture archive entry leaves its top directory: " + entry.getName());
				if (entry.isDirectory())
				{
					Files.createDirectories(target);
					continue;
				}
				Files.createDirectories(target.getParent());
				Files.copy(tar, target);
				if ((entry.getMode() & 0100) != 0 && !target.toFile().setExecutable(true))
					throw new IOException("Cannot make " + target + " executable");
			}
		}
		Files.delete(archive);
		return parent.resolve(top);
	}

	/**
	 * Writes the files that an installation keeps of a release, as an install of that release leaves them: the signed
	 * manifest, its signature and the plugin archive. Writing them records no request.
	 *
	 * @param version   the release version
	 * @param directory the directory that receives the files
	 * @throws IOException if the catalog has no such release or a file cannot be written
	 */
	void writeKept(String version, Path directory) throws IOException
	{
		String tag = "v" + version;
		String archive = "cat-harness-a-linux-x64-v" + version + ".tar.gz";
		Releases release = release(tag);
		Files.createDirectories(directory);
		Files.write(directory.resolve("cat-release-manifest.json"), release.manifest(tag));
		Files.write(directory.resolve("cat-release-manifest.json.sig"), release.signature(tag));
		release.download(tag, archive, 0, directory.resolve(archive));
	}

	/**
	 * Fails a request while the catalog is offline.
	 *
	 * @param request the request
	 * @throws IOException if the catalog is offline
	 */
	private void request(String request) throws IOException
	{
		if (offline)
			throw new IOException("The network is unreachable: " + request);
	}

	/**
	 * Returns a release.
	 *
	 * @param tag the release tag
	 * @return the release
	 * @throws IOException if the catalog has no such release
	 */
	private Releases release(String tag) throws IOException
	{
		Releases release = releases.get(tag);
		if (release == null)
			throw new IOException("No release " + tag);
		return release;
	}
}
