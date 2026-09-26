package io.github.cowwoc.cat.common.http;

import java.net.URI;
import java.util.Objects;

/**
 * Validates the common host origin and bearer header used by CAT client transports.
 */
public final class HoldMySpotHttp
{
	/**
	 * Prevents construction of this utility class.
	 */
	private HoldMySpotHttp()
	{
	}

	/**
	 * Resolves an API path against an HTTPS service origin or mounted product path.
	 *
	 * @param serverBase HoldMySpot server origin or product mount ending in {@code /}
	 * @param path absolute API path
	 * @return endpoint URI
	 * @throws IllegalArgumentException if the origin or path is invalid
	 */
	public static URI endpoint(URI serverBase, String path)
	{
		Objects.requireNonNull(serverBase, "serverBase");
		Objects.requireNonNull(path, "path");
		boolean local = "http".equals(serverBase.getScheme()) && "localhost".equals(serverBase.getHost());
		if ((!"https".equals(serverBase.getScheme()) && !local) || serverBase.getHost() == null ||
			serverBase.getUserInfo() != null || serverBase.getQuery() != null || serverBase.getFragment() != null)
			throw new IllegalArgumentException("HoldMySpot server base must be an HTTPS service origin");
		String basePath = serverBase.getRawPath();
		if (basePath != null && !basePath.isEmpty() && !"/".equals(basePath) &&
			!basePath.matches("(?:/[a-z0-9][a-z0-9-]*)+/"))
			throw new IllegalArgumentException("HoldMySpot server mount must end in / and use lowercase path segments");
		if (!path.startsWith("/") || path.startsWith("//"))
			throw new IllegalArgumentException("API path must be absolute within the server origin");
		return serverBase.resolve(path.substring(1));
	}

	/**
	 * Formats a nonblank access token without accepting header line breaks.
	 *
	 * @param accessToken authenticated user-session token
	 * @return Authorization header value
	 * @throws IllegalArgumentException if the token cannot form a bearer header
	 */
	public static String bearer(String accessToken)
	{
		if (accessToken == null || accessToken.isBlank() || accessToken.contains("\r") ||
			accessToken.contains("\n"))
			throw new IllegalArgumentException("HoldMySpot access token is invalid");
		return "Bearer " + accessToken;
	}
}
