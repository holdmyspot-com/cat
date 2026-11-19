package com.holdmyspot.cat.common.installer.engine.test;

import com.holdmyspot.cat.common.installer.engine.ReleaseConnection;
import com.holdmyspot.cat.common.installer.engine.ReleaseSource;
import com.holdmyspot.cat.common.installer.trust.TrustException;
import com.holdmyspot.cat.common.installer.trust.TrustPolicy;
import com.holdmyspot.cat.common.installer.trust.test.ReleaseFixtures;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

/**
 * Publishes several releases that one key signs, each with its own migration registry, on a release host that CAT's
 * real HTTPS release source reaches through a {@link ReleaseHostClient}. After {@link #goOffline()} the host fails
 * every request as an unreachable network does, so that a test can tell whether an operation needed the network.
 */
final class ReleaseCatalog
{
	private final Releases signer;
	private final Map<String, Releases> releases = new HashMap<>();
	private final ReleaseHostClient host = new ReleaseHostClient(ReleaseFixtures.RELEASE_BASE_URL);
	private final ReleaseConnection source;

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
		try
		{
			this.source = host.open(ReleaseFixtures.RELEASE_BASE_URL);
		}
		catch (TrustException e)
		{
			throw new AssertionError("The fixture's release location is an HTTPS URL", e);
		}
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
		return publish(version, new Releases(version, registry, signer));
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
		return publish(version, new Releases(version, build, registry, signer));
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
		return publish(version, Releases.defective(version, defect, signer));
	}

	/**
	 * Names the release that the catalog reports as the latest one.
	 *
	 * @param version the release version
	 * @return this catalog
	 */
	ReleaseCatalog latest(String version)
	{
		host.latest("v" + version);
		return this;
	}

	/**
	 * Makes every later request fail as if the network were unreachable.
	 */
	void goOffline()
	{
		host.goOffline();
	}

	/**
	 * Returns CAT's release source of the host that publishes the catalog.
	 *
	 * @return the source
	 */
	ReleaseSource source()
	{
		return source;
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

	/**
	 * Extracts a release's plugin archive without a request to the host.
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
		Path archive = Files.write(parent.resolve(top + ".tar.gz"), release(tag).files().get(top + ".tar.gz"));
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
	 * manifest, its signature and the plugin archive. Writing them sends no request to the host.
	 *
	 * @param version   the release version
	 * @param directory the directory that receives the files
	 * @throws IOException if the catalog has no such release or a file cannot be written
	 */
	void writeKept(String version, Path directory) throws IOException
	{
		Files.createDirectories(directory);
		for (Map.Entry<String, byte[]> file : release("v" + version).files().entrySet())
			Files.write(directory.resolve(file.getKey()), file.getValue());
	}

	/**
	 * Publishes a release under its tag, replacing the files of an earlier release of that tag, and makes it the latest
	 * one.
	 *
	 * @param version the release version
	 * @param release the release
	 * @return this catalog
	 */
	private ReleaseCatalog publish(String version, Releases release)
	{
		String tag = "v" + version;
		releases.put(tag, release);
		for (Map.Entry<String, byte[]> file : release.files().entrySet())
			host.publish(tag, file.getKey(), file.getValue());
		host.latest(tag);
		return this;
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
