package io.github.cowwoc.cat.common.auth;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/**
 * Exchanges public-client device codes for WorkOS AuthKit user-session tokens.
 */
public final class WorkOsDeviceAuthClient
{
	/**
	 * Production API origin for WorkOS device authorization and token refresh.
	 */
	public static final URI API_BASE = URI.create("https://api.workos.com");

	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
	private final HttpClient http;
	private final URI authorizationUri;
	private final URI authenticationUri;
	private final String clientId;
	private final JsonMapper json = JsonMapper.builder().build();

	/**
	 * Creates a WorkOS public-client transport.
	 *
	 * @param http HTTP client used for WorkOS requests
	 * @param apiBase WorkOS API origin, or a loopback origin in local tests
	 * @param clientId public AuthKit application client ID
	 */
	public WorkOsDeviceAuthClient(HttpClient http, URI apiBase, String clientId)
	{
		this.http = Objects.requireNonNull(http, "http");
		Objects.requireNonNull(apiBase, "apiBase");
		boolean local = "http".equals(apiBase.getScheme()) && "localhost".equals(apiBase.getHost());
		if ((!"https".equals(apiBase.getScheme()) && !local) || apiBase.getHost() == null ||
			apiBase.getUserInfo() != null || apiBase.getQuery() != null || apiBase.getFragment() != null ||
			(!apiBase.getPath().isEmpty() && !"/".equals(apiBase.getPath())))
			throw new IllegalArgumentException("WorkOS API base must be an HTTPS service origin");
		if (clientId == null || !clientId.matches("client_[A-Za-z0-9_-]+"))
			throw new IllegalArgumentException("WorkOS client ID is invalid");
		this.clientId = clientId;
		this.authorizationUri = apiBase.resolve("/user_management/authorize/device");
		this.authenticationUri = apiBase.resolve("/user_management/authenticate");
	}

	/**
	 * Starts one device authorization.
	 *
	 * @return code and verification URI to show to the user
	 * @throws IOException if WorkOS does not return a valid authorization
	 * @throws InterruptedException if the request is interrupted
	 */
	public DeviceAuthorization authorize() throws IOException, InterruptedException
	{
		HttpResponse<String> response = post(authorizationUri, "client_id=" + form(clientId));
		if (response.statusCode() != 200)
			throw new IOException("WorkOS device authorization returned HTTP " + response.statusCode());
		JsonNode body = parse(response.body());
		URI verificationUri;
		try
		{
			verificationUri = URI.create(requiredText(body, "verification_uri"));
		}
		catch (IllegalArgumentException exception)
		{
			throw new IOException("WorkOS device authorization has an invalid verification URI", exception);
		}
		try
		{
			return new DeviceAuthorization(requiredText(body, "device_code"), requiredText(body, "user_code"),
				verificationUri, requiredPositiveInteger(body, "expires_in"),
				requiredPositiveInteger(body, "interval"));
		}
		catch (IllegalArgumentException exception)
		{
			throw new IOException("WorkOS device authorization is incomplete", exception);
		}
	}

	/**
	 * Sends one form-encoded request to WorkOS without a client secret.
	 *
	 * @param uri provider endpoint
	 * @param formBody encoded form body
	 * @return provider response
	 * @throws IOException if the network request fails
	 * @throws InterruptedException if the request is interrupted
	 */
	private HttpResponse<String> post(URI uri, String formBody) throws IOException, InterruptedException
	{
		HttpRequest request = HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT).
			header("Content-Type", "application/x-www-form-urlencoded; charset=utf-8").
			POST(HttpRequest.BodyPublishers.ofString(formBody)).build();
		return http.send(request, HttpResponse.BodyHandlers.ofString());
	}

	/**
	 * Parses one WorkOS response while keeping its token-bearing body out of errors.
	 *
	 * @param document JSON response body
	 * @return parsed response object
	 * @throws IOException if the JSON is malformed or has the wrong root type
	 */
	private JsonNode parse(String document) throws IOException
	{
		try
		{
			JsonNode body = json.readTree(document);
			if (body == null || !body.isObject())
				throw new IOException("WorkOS response must be a JSON object");
			return body;
		}
		catch (JacksonException exception)
		{
			throw new IOException("WorkOS response is malformed JSON", exception);
		}
	}

	/**
	 * Reads a required nonblank response string.
	 *
	 * @param body provider response object
	 * @param field required field name
	 * @return field value
	 * @throws IOException if the field is absent or invalid
	 */
	private static String requiredText(JsonNode body, String field) throws IOException
	{
		JsonNode value = body.path(field);
		if (!value.isString() || value.stringValue().isBlank())
			throw new IOException("WorkOS response has no valid " + field);
		return value.stringValue();
	}

	/**
	 * Reads a required positive integer without coercing a decimal or string.
	 *
	 * @param body provider response object
	 * @param field required field name
	 * @return positive value
	 * @throws IOException if the field is absent or invalid
	 */
	private static long requiredPositiveInteger(JsonNode body, String field) throws IOException
	{
		JsonNode value = body.path(field);
		if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < 1)
			throw new IOException("WorkOS response has no valid " + field);
		return value.asLong();
	}

	/**
	 * Encodes one public OAuth request value.
	 *
	 * @param value code or client ID
	 * @return form-encoded value
	 */
	private static String form(String value)
	{
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	/**
	 * Polls once for the result of a device authorization.
	 *
	 * @param deviceCode secret code returned by {@link #authorize()}
	 * @return pending, slower polling, or completed tokens
	 * @throws IOException if WorkOS rejects the device code or returns an invalid response
	 * @throws InterruptedException if the request is interrupted
	 */
	public PollResult poll(String deviceCode) throws IOException, InterruptedException
	{
		if (deviceCode == null || deviceCode.isBlank())
			throw new IllegalArgumentException("WorkOS device code must be nonblank");
		String body = "grant_type=" + form("urn:ietf:params:oauth:grant-type:device_code") +
			"&device_code=" + form(deviceCode) + "&client_id=" + form(clientId);
		HttpResponse<String> response = post(authenticationUri, body);
		if (response.statusCode() == 200)
			return new PollResult.Complete(readTokens(parse(response.body())));
		if (response.statusCode() != 400)
			throw new IOException("WorkOS device poll returned HTTP " + response.statusCode());
		String error = requiredText(parse(response.body()), "error");
		return switch (error)
		{
			case "authorization_pending" -> new PollResult.Pending();
			case "slow_down" -> new PollResult.SlowDown();
			case "access_denied" -> throw new IOException("WorkOS device authorization was denied");
			case "expired_token" -> throw new IOException("WorkOS device authorization expired");
			default -> throw new IOException("WorkOS device poll returned an unsupported error");
		};
	}

	/**
	 * Reads both user-session tokens from a successful authentication response.
	 *
	 * @param body WorkOS authentication response
	 * @return provider user and rotating access and refresh tokens
	 * @throws IOException if the user or either token is absent
	 */
	private static Tokens readTokens(JsonNode body) throws IOException
	{
		return new Tokens(requiredText(body.path("user"), "id"), requiredText(body, "access_token"),
			requiredText(body, "refresh_token"));
	}

	/**
	 * Exchanges a refresh token for replacement access and refresh tokens.
	 *
	 * @param refreshToken current refresh token
	 * @return replacement tokens
	 * @throws IOException if the session cannot be refreshed
	 * @throws InterruptedException if the request is interrupted
	 */
	private Tokens refresh(String refreshToken) throws IOException, InterruptedException
	{
		if (refreshToken == null || refreshToken.isBlank())
			throw new IllegalArgumentException("WorkOS refresh token must be nonblank");
		String body = "grant_type=refresh_token&refresh_token=" + form(refreshToken) +
			"&client_id=" + form(clientId);
		HttpResponse<String> response = post(authenticationUri, body);
		if (response.statusCode() == 200)
			return readTokens(parse(response.body()));
		if (response.statusCode() == 400 &&
			"invalid_grant".equals(requiredText(parse(response.body()), "error")))
			throw new IOException("WorkOS refresh token is no longer valid; sign in again");
		throw new IOException("WorkOS token refresh returned HTTP " + response.statusCode());
	}

	/**
	 * Refreshes a session while preserving the authenticated user identity.
	 *
	 * @param current tokens belonging to the current user session
	 * @return replacement tokens
	 * @throws IOException if the session cannot be refreshed
	 * @throws InterruptedException if the request is interrupted
	 */
	public Tokens refresh(Tokens current) throws IOException, InterruptedException
	{
		Objects.requireNonNull(current, "current");
		Tokens replacement = refresh(current.refreshToken());
		if (!replacement.userId().equals(current.userId()))
			throw new IOException("WorkOS refreshed a different user; sign in again");
		return replacement;
	}

	/**
	 * Device authorization displayed to the user before polling.
	 *
	 * @param deviceCode secret code used only for polling
	 * @param userCode short code shown to the user
	 * @param verificationUri browser URI for entering the user code
	 * @param expiresInSeconds lifetime of the device code
	 * @param intervalSeconds minimum initial polling interval
	 */
	public record DeviceAuthorization(String deviceCode, String userCode, URI verificationUri,
		long expiresInSeconds, long intervalSeconds)
	{
		/**
		 * Rejects an unusable device authorization.
		 *
		 * @param deviceCode secret code used only for polling
		 * @param userCode short code shown to the user
		 * @param verificationUri browser URI for entering the user code
		 * @param expiresInSeconds lifetime of the device code
		 * @param intervalSeconds minimum initial polling interval
		 */
		public DeviceAuthorization
		{
			if (deviceCode == null || deviceCode.isBlank() || userCode == null || userCode.isBlank() ||
				verificationUri == null || !"https".equals(verificationUri.getScheme()) ||
				verificationUri.getHost() == null || expiresInSeconds < 1 || intervalSeconds < 1)
				throw new IllegalArgumentException("WorkOS device authorization is invalid");
		}

		@Override
		public String toString()
		{
			return "DeviceAuthorization[userCode=" + userCode + ", verificationUri=" + verificationUri +
				", expiresInSeconds=" + expiresInSeconds + ", intervalSeconds=" + intervalSeconds + "]";
		}
	}

	/**
	 * Result of one poll without hiding a still-pending authorization.
	 */
	public sealed interface PollResult
	{
		/** WorkOS has not yet received the user's approval. */
		record Pending() implements PollResult
		{
		}

		/** WorkOS requires a longer interval before the next poll. */
		record SlowDown() implements PollResult
		{
		}

		/**
		 * The user approved this authorization.
		 *
		 * @param tokens access and refresh tokens
		 */
		record Complete(Tokens tokens) implements PollResult
		{
			/**
			 * Requires completed tokens.
			 *
			 * @param tokens access and refresh tokens
			 */
			public Complete
			{
				Objects.requireNonNull(tokens, "tokens");
			}
		}
	}

	/**
	 * Rotating tokens for a WorkOS user session.
	 *
	 * @param userId authenticated WorkOS user who owns the tokens
	 * @param accessToken short-lived bearer token for the API
	 * @param refreshToken replacement token for subsequent refreshes
	 */
	public record Tokens(String userId, String accessToken, String refreshToken)
	{
		/**
		 * Requires usable replacement tokens.
		 *
		 * @param userId authenticated WorkOS user
		 * @param accessToken short-lived bearer token
		 * @param refreshToken replacement refresh token
		 */
		public Tokens
		{
			if (userId == null || !userId.startsWith("user_") || userId.length() > 255 ||
				accessToken == null || accessToken.isBlank() || refreshToken == null || refreshToken.isBlank())
				throw new IllegalArgumentException("WorkOS authentication returned incomplete tokens");
		}

		@Override
		public String toString()
		{
			return "Tokens[redacted]";
		}
	}
}
