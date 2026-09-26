package io.github.cowwoc.cat.common.account;

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
import java.util.OptionalLong;

/**
 * Registers the first account for a verified provider login.
 */
public final class AccountRegistrationClient
{
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
	private final HttpClient http;
	private final URI accountsUri;
	private final JsonMapper json = JsonMapper.builder().build();

	/**
	 * Creates the account-registration transport.
	 *
	 * @param http HTTP client owned by the plugin
	 * @param serverBase HoldMySpot server origin
	 */
	public AccountRegistrationClient(HttpClient http, URI serverBase)
	{
		this.http = Objects.requireNonNull(http, "http");
		this.accountsUri = HoldMySpotHttp.endpoint(serverBase, "/v1/accounts");
	}

	/**
	 * Creates an account for an unlinked verified login, or confirms that one already exists.
	 *
	 * @param accessToken authenticated provider session token
	 * @return created account ID, or empty if the login was already linked
	 * @throws IOException if the server does not confirm registration
	 * @throws InterruptedException if the request is interrupted
	 */
	public OptionalLong register(String accessToken) throws IOException, InterruptedException
	{
		HttpRequest request = HttpRequest.newBuilder(accountsUri).timeout(REQUEST_TIMEOUT).
			header("Authorization", HoldMySpotHttp.bearer(accessToken)).POST(HttpRequest.BodyPublishers.noBody()).build();
		HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() == 204)
			return OptionalLong.empty();
		if (response.statusCode() != 201)
			throw new IOException("Account registration returned HTTP " + response.statusCode());

		JsonNode root;
		try
		{
			root = json.readTree(response.body());
		}
		catch (JacksonException exception)
		{
			throw new IOException("Account registration returned invalid account ID", exception);
		}
		if (root == null || !root.isObject())
			throw new IOException("Account registration returned invalid account ID");
		JsonNode id = root.path("id");
		if (!id.isIntegralNumber() || !id.canConvertToLong() || id.asLong() < 1)
			throw new IOException("Account registration returned invalid account ID");
		return OptionalLong.of(id.asLong());
	}
}
