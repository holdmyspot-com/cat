package com.holdmyspot.cat.common.updater.secrets;

import com.holdmyspot.cat.common.updater.engine.HttpsReleaseSource;
import com.holdmyspot.cat.common.updater.trust.TrustException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Exposes the transport boundary of {@link HttpsReleaseSource} to its tests.
 */
@FunctionalInterface
public interface HttpsSourceAccess
{
	/**
	 * Creates a source borrowing an HTTP client; closing the source does not close the borrowed client.
	 *
	 * @param releaseBaseUrl absolute HTTPS release base URL
	 * @param client         transport that returns redirects without following them
	 * @param requestLimit   positive per-request timeout
	 * @return release source
	 * @throws NullPointerException     if any argument is null
	 * @throws IllegalArgumentException if the timeout is not positive or the client follows redirects
	 * @throws TrustException           if the release base URL is refused
	 */
	HttpsReleaseSource create(URI releaseBaseUrl, HttpClient client, Duration requestLimit) throws TrustException;
}
