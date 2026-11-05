package com.holdmyspot.cat.common.installer.cli.internal;

import com.holdmyspot.cat.common.installer.engine.ReleaseConnection;
import com.holdmyspot.cat.common.installer.trust.TrustException;
import java.io.IOException;
import java.net.URI;

/**
 * Opens the release source at the location that a trust policy names.
 */
@FunctionalInterface
public interface ReleaseSources
{
	/**
	 * Opens the release source below a policy's release base URL.
	 *
	 * @param releaseBaseUrl the policy's release base URL
	 * @return the source, which the caller closes
	 * @throws TrustException if the location is refused
	 * @throws IOException    if the source cannot be configured
	 */
	ReleaseConnection open(URI releaseBaseUrl) throws IOException, TrustException;
}
