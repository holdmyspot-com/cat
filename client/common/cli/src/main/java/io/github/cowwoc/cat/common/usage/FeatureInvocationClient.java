package io.github.cowwoc.cat.common.usage;

import io.github.cowwoc.cat.common.http.HoldMySpotHttp;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sends a retained CAT rule delivery to the beta feature-invocation endpoint.
 */
public final class FeatureInvocationClient implements FeatureInvocationOutbox.Sender
{
	/**
	 * Indicates that the server rejected the access token used for a feature invocation.
	 */
	public static final class AuthenticationRequiredException extends IOException
	{
		private static final long serialVersionUID = 1L;

		/**
		 * Creates the response failure after HTTP 401.
		 */
		private AuthenticationRequiredException()
		{
			super("Feature invocation server rejected the access token with HTTP 401");
		}
	}

	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
	private final HttpClient http;
	private final URI invocationUri;
	private final JsonMapper json = JsonMapper.builder().build();

	/**
	 * Creates a feature-invocation transport for the selected host.
	 *
	 * @param http HTTP client owned by the plugin
	 * @param serverBase server origin
	 */
	public FeatureInvocationClient(HttpClient http, URI serverBase)
	{
		this.http = Objects.requireNonNull(http, "http");
		this.invocationUri = HoldMySpotHttp.endpoint(serverBase, "/v1/feature-invocations");
	}

	/**
	 * Sends one previously identified feature invocation.
	 *
	 * @param accessToken authenticated user-session token
	 * @param accountId selected HoldMySpot account ID
	 * @param featureId installed rule path relative to {@code rules/}
	 * @param invocationId stable invocation identity retained across retries
	 * @throws IOException if the server does not confirm the invocation
	 * @throws InterruptedException if delivery is interrupted
	 */
	@Override
	public void send(String accessToken, long accountId, String featureId, UUID invocationId)
		throws IOException, InterruptedException
	{
		if (accountId < 1)
			throw new IllegalArgumentException("Selected HoldMySpot account ID must be positive");
		if (featureId == null || featureId.isBlank())
			throw new IllegalArgumentException("CAT feature ID must not be blank");
		Objects.requireNonNull(invocationId, "invocationId");

		String body = json.writeValueAsString(Map.of("product_id", "cat", "feature_id", featureId,
			"invocation_id", invocationId.toString()));
		HttpRequest request = HttpRequest.newBuilder(invocationUri).timeout(REQUEST_TIMEOUT).
			header("Authorization", HoldMySpotHttp.bearer(accessToken)).
			header("X-HoldMySpot-Account-ID", Long.toString(accountId)).
			header("Content-Type", "application/json; charset=utf-8").
			POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
		HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
		if (response.statusCode() == 401)
			throw new AuthenticationRequiredException();
		if (response.statusCode() == 422)
			throw new FeatureInvocationOutbox.UnregisteredFeatureException();
		if (response.statusCode() != 204)
			throw new IOException("Feature invocation server returned HTTP " + response.statusCode());
	}
}
