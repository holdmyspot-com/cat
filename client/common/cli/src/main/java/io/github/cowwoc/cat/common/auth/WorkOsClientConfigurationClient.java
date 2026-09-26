package io.github.cowwoc.cat.common.auth;

import io.github.cowwoc.cat.common.http.HoldMySpotHttp;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;

/**
 * Discovers the WorkOS application accepted by one HoldMySpot host.
 */
public final class WorkOsClientConfigurationClient
{
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
	private final HttpClient http;
	private final URI configurationUri;
	private final JsonMapper json = JsonMapper.builder().build();

	/**
	 * Creates a transport for the host's public authentication configuration.
	 *
	 * @param http HTTP client owned by the caller
	 * @param serverBase HoldMySpot service origin or product mount
	 */
	public WorkOsClientConfigurationClient(HttpClient http, URI serverBase)
	{
		this.http = Objects.requireNonNull(http, "http");
		configurationUri = HoldMySpotHttp.endpoint(serverBase, "/v1/workos-client-configuration");
	}

	/**
	 * Returns the WorkOS client ID required for device authorization.
	 *
	 * @return configured public client ID
	 * @throws IOException if the host does not provide a valid client ID
	 * @throws InterruptedException if the request is interrupted
	 */
	public String fetchClientId() throws IOException, InterruptedException
	{
		HttpRequest request = HttpRequest.newBuilder(configurationUri).timeout(REQUEST_TIMEOUT).GET().build();
		HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200)
			throw new IOException("HoldMySpot client configuration returned HTTP " + response.statusCode());
		JsonNode body;
		try
		{
			body = json.readTree(response.body());
		}
		catch (JacksonException exception)
		{
			throw new IOException("HoldMySpot host returned malformed client configuration", exception);
		}
		if (body == null || !body.isObject() || !body.path("client_id").isString() ||
			body.path("client_id").stringValue().isBlank())
			throw new IOException("HoldMySpot host returned no WorkOS client ID");
		return body.path("client_id").stringValue();
	}
}
