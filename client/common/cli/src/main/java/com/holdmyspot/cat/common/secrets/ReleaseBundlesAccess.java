package com.holdmyspot.cat.common.secrets;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.release.ReleaseBundles;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;

/**
 * Exposes to the module's tests how a {@link ReleaseBundles} download is timed, which HTTP client and request it uses,
 * and how it interprets the outcome of a request.
 */
public interface ReleaseBundlesAccess
{
	/**
	 * Creates a resolution whose download time limits or clock differ from the defaults.
	 *
	 * @param directory     the directory that holds the downloaded artifacts
	 * @param release       the location of this CAT version's release
	 * @param platform      the platform whose artifact to resolve
	 * @param runtime       the shared Java runtime that the resolution binds a bundle to
	 * @param connectLimit  how long a download waits to connect to the release's server
	 * @param responseLimit how long a download waits for the server's response headers
	 * @param downloadLimit how long a download of one file may take
	 * @param clock         the clock that tells when a bundle was last used
	 * @return the resolution
	 * @throws NullPointerException if any of the arguments are null
	 */
	ReleaseBundles create(Path directory, URI release, Platform platform, Path runtime, Duration connectLimit,
		Duration responseLimit, Duration downloadLimit, Clock clock);

	/**
	 * Returns the HTTP client that a resolution downloads a release file with, which the caller closes.
	 *
	 * @param bundles  the resolution
	 * @param location the file's location
	 * @return the client, with the redirect policy, connect limit, proxy and proxy login of the download
	 * @throws IOException if the environment names a proxy URL that cannot be parsed
	 */
	HttpClient newHttpClient(ReleaseBundles bundles, URI location) throws IOException;

	/**
	 * Returns the request that a resolution sends to download a release file.
	 *
	 * @param bundles  the resolution
	 * @param location the file's location
	 * @return the request
	 */
	HttpRequest newHttpRequest(ReleaseBundles bundles, URI location);

	/**
	 * Returns the failure that a resolution reports when a download over HTTP fails.
	 *
	 * @param bundles the resolution
	 * @param client  the client of the download, as {@link #newHttpClient(ReleaseBundles, URI)} returned it
	 * @param failure the {@code TimeoutException} of a download that did not finish within the download limit, or the
	 *                cause of a request that failed
	 * @return the failure to report
	 */
	IOException httpFailure(ReleaseBundles bundles, HttpClient client, Throwable failure);

	/**
	 * Checks the status of a release file's response.
	 *
	 * @param statusCode the response's status code
	 * @throws IOException if the status is not 200
	 */
	void checkStatus(int statusCode) throws IOException;

	/**
	 * Returns the failure that a resolution reports when a release file cannot be fetched.
	 *
	 * @param location the file's location
	 * @param failure  why the file could not be fetched
	 * @return the failure to report
	 */
	IOException fetchFailure(URI location, IOException failure);
}
