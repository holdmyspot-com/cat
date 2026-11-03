package com.holdmyspot.cat.common.updater.engine;

import com.holdmyspot.cat.common.updater.trust.TrustException;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Downloads releases from the location fixed by the user's policy. Implementations never execute downloaded code.
 */
public interface ReleaseSource
{
	/**
	 * Resolves the latest release's tag; its manifest must still be authenticated and approved.
	 *
	 * @return a canonical release tag
	 * @throws TrustException if the source redirects to an unapproved scheme or supplies an invalid tag
	 * @throws IOException if the request fails
	 */
	String latestTag() throws IOException, TrustException;

	/**
	 * Downloads a manifest, capped at the verifier's maximum manifest size.
	 *
	 * @param tag the canonical release tag
	 * @return the manifest bytes
	 * @throws TrustException if the location is refused
	 * @throws IOException if the request fails or exceeds the cap
	 */
	byte[] manifest(String tag) throws IOException, TrustException;

	/**
	 * Downloads a detached signature, capped at the verifier's maximum signature size.
	 *
	 * @param tag the canonical release tag
	 * @return the signature bytes
	 * @throws TrustException if the location is refused
	 * @throws IOException if the request fails or exceeds the cap
	 */
	byte[] signature(String tag) throws IOException, TrustException;

	/**
	 * Streams an asset into a new file, refusing excess bytes and deleting the destination on any failure.
	 *
	 * @param tag          the canonical release tag
	 * @param assetName    a single archive filename from a verified manifest
	 * @param expectedSize the exact expected byte count
	 * @param destination  the absent file to create
	 * @throws TrustException if the location is refused
	 * @throws IOException if downloading or writing fails, or the length differs
	 */
	void download(String tag, String assetName, long expectedSize, Path destination) throws IOException,
		TrustException;
}
