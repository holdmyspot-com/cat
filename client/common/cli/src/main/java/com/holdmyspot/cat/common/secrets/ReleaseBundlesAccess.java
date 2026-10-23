package com.holdmyspot.cat.common.secrets;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.release.ReleaseBundles;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;

/**
 * Exposes how a release download and a bundle's age are timed to the module's tests.
 */
@FunctionalInterface
public interface ReleaseBundlesAccess
{
	/**
	 * Creates a resolution whose download time limits or clock differ from the defaults.
	 *
	 * @param directory       the directory that holds the downloaded artifacts
	 * @param release         the location of this CAT version's release
	 * @param platform        the platform whose artifact to resolve
	 * @param connectLimit    how long a download waits to connect to the release's server
	 * @param responseLimit   how long a download waits for the server's response headers
	 * @param downloadLimit   how long a download of one file may take
	 * @param clock           the clock that tells when a bundle was last used
	 * @return the resolution
	 * @throws NullPointerException if any of the arguments are null
	 */
	ReleaseBundles create(Path directory, URI release, Platform platform, Duration connectLimit,
		Duration responseLimit, Duration downloadLimit, Clock clock);
}
