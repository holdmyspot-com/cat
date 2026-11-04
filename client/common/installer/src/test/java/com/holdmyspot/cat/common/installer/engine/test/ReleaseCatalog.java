package com.holdmyspot.cat.common.installer.engine.test;

import com.holdmyspot.cat.common.installer.engine.ReleaseSource;
import com.holdmyspot.cat.common.installer.trust.TrustException;
import com.holdmyspot.cat.common.installer.trust.TrustPolicy;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Serves several releases that one key signs, each with its own migration registry, and records what it was asked
 * for, so that a test can tell whether an operation used the network.
 */
final class ReleaseCatalog implements ReleaseSource
{
	private final Releases signer;
	private final Map<String, Releases> releases = new HashMap<>();
	private final List<String> requests = new ArrayList<>();
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
	 * Returns what the catalog was asked for: {@code manifest <tag>}, {@code signature <tag>} or
	 * {@code download <asset>}.
	 *
	 * @return the requests in order
	 */
	List<String> requests()
	{
		return List.copyOf(requests);
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
	 * Records a request, and fails it while the catalog is offline.
	 *
	 * @param request the request
	 * @throws IOException if the catalog is offline
	 */
	private void request(String request) throws IOException
	{
		requests.add(request);
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
