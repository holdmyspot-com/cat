package com.holdmyspot.cat.common.installer.secrets;

import com.holdmyspot.cat.common.installer.engine.HttpsReleaseSource;
import com.holdmyspot.cat.common.installer.trust.TrustException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodySubscriber;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Exposes the decisions of {@link HttpsReleaseSource} to its tests: which request it sends, what it makes of the
 * response, and how it writes a download. Each operation takes the plain values that the transport would deliver, so
 * that a test needs no network.
 */
public interface HttpsSourceAccess
{
	/**
	 * Returns the location that a source requests to learn the latest release.
	 *
	 * @param source the source
	 * @return the location
	 */
	URI latestLocation(HttpsReleaseSource source);

	/**
	 * Returns the latest release's tag, as the source reads it from the response to its request for
	 * {@link #latestLocation(HttpsReleaseSource)}.
	 *
	 * @param source           the source
	 * @param status           the response's HTTP status code
	 * @param redirectLocation the response's {@code Location} header, if it has one
	 * @return the canonical tag
	 * @throws IOException    if the response is not a redirect, or a redirect without a {@code Location} or with one that
	 *                        is not a URI
	 * @throws TrustException if the redirect leaves HTTPS, does not name a tag in the source's tag directory, or names a
	 *                        tag that is not in its canonical spelling
	 */
	String latestTag(HttpsReleaseSource source, int status, Optional<String> redirectLocation) throws IOException,
		TrustException;

	/**
	 * Returns the request that a source sends for a release's manifest.
	 *
	 * @param source the source
	 * @param tag    the release tag
	 * @return the manifest's location and its maximum size
	 * @throws TrustException if the tag is not canonical
	 */
	Fetch manifest(HttpsReleaseSource source, String tag) throws TrustException;

	/**
	 * Returns the request that a source sends for a release's manifest signature.
	 *
	 * @param source the source
	 * @param tag    the release tag
	 * @return the signature's location and its maximum size
	 * @throws TrustException if the tag is not canonical
	 */
	Fetch signature(HttpsReleaseSource source, String tag) throws TrustException;

	/**
	 * Returns the request that a source sends for a release's archive.
	 *
	 * @param source       the source
	 * @param tag          the release tag
	 * @param assetName    a single archive filename
	 * @param expectedSize the archive's size, which is also its maximum
	 * @return the archive's location and its maximum size
	 * @throws NullPointerException     if {@code assetName} is null
	 * @throws IllegalArgumentException if {@code assetName} is blank or {@code expectedSize} is negative
	 * @throws TrustException           if the tag is not canonical or {@code assetName} names more than a file
	 */
	Fetch asset(HttpsReleaseSource source, String tag, String assetName, long expectedSize) throws TrustException;

	/**
	 * Returns the HTTP request that a source sends to a location.
	 *
	 * @param source   the source
	 * @param location the location
	 * @return a GET request that times out after the source's request limit
	 * @throws TrustException if the location is not an absolute HTTPS URL with a host and without login or fragment
	 */
	HttpRequest request(HttpsReleaseSource source, URI location) throws TrustException;

	/**
	 * Decides what follows a response to a request for a metadata document or an archive.
	 *
	 * @param location         the location that was requested
	 * @param status           the response's HTTP status code
	 * @param redirectLocation the response's {@code Location} header, if it has one
	 * @return the HTTPS location to request next, or empty when the response is the answer and its body is the download
	 * @throws IOException    if the response is neither a success nor a redirect, or is a redirect without a
	 *                        {@code Location} or with one that is not a URI
	 * @throws TrustException if the redirect leaves HTTPS
	 */
	Optional<URI> next(URI location, int status, Optional<String> redirectLocation) throws IOException, TrustException;

	/**
	 * Returns the consumer with which a source copies a successful response into an output.
	 *
	 * @param output  the destination, which the caller owns
	 * @param maximum the response's maximum size, in bytes
	 * @return the consumer, whose result is the number of bytes copied and which fails when the response exceeds
	 *         {@code maximum}
	 */
	BodySubscriber<Long> subscriber(OutputStream output, long maximum);

	/**
	 * Writes a new file with the bytes that a transfer delivers, as a source writes an archive.
	 *
	 * @param destination  the absent file to create
	 * @param expectedSize the exact byte count that the transfer must deliver
	 * @param transfer     delivers the bytes into the file
	 * @throws IOException    if writing fails, the transfer fails or it delivers another number of bytes; the file does
	 *                        not exist afterwards
	 * @throws TrustException if the transfer refuses its location; the file does not exist afterwards
	 */
	void receive(Path destination, long expectedSize, Transfer transfer) throws IOException, TrustException;

	/**
	 * A request for a document or an archive.
	 *
	 * @param location the location to request
	 * @param maximum  the largest response that the source accepts, in bytes
	 */
	record Fetch(URI location, long maximum)
	{
	}

	/**
	 * Delivers the bytes of one download into an output.
	 */
	@FunctionalInterface
	interface Transfer
	{
		/**
		 * Copies the download into an output.
		 *
		 * @param output the destination
		 * @return the number of bytes copied
		 * @throws IOException    if the transfer fails
		 * @throws TrustException if the transfer refuses its location
		 */
		long copyTo(OutputStream output) throws IOException, TrustException;
	}
}
