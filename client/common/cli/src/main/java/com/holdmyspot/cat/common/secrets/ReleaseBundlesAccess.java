package com.holdmyspot.cat.common.secrets;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.release.ReleaseBundles;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Exposes how a release download is timed to the module's tests.
 */
@FunctionalInterface
public interface ReleaseBundlesAccess
{
	/**
	 * Creates a resolution whose download time limits differ from the defaults.
	 *
	 * @param directory       the directory that holds the downloaded artifacts
	 * @param release         the location of this CAT version's release
	 * @param platform        the platform whose artifact to resolve
	 * @param connectLimit    how long a download waits to connect to the release's server
	 * @param responseLimit   how long a download waits for the server's response headers
	 * @param downloadLimit   how long a download of one file may take
	 * @return the resolution
	 * @throws NullPointerException if any of the arguments are null
	 */
	ReleaseBundles create(Path directory, URI release, Platform platform, Duration connectLimit,
		Duration responseLimit, Duration downloadLimit);
}
