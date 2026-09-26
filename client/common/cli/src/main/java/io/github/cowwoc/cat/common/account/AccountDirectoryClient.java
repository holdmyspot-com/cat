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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Finds the HoldMySpot accounts linked to an authenticated provider login.
 */
public final class AccountDirectoryClient
{
	/**
	 * Indicates that the account server rejected an access token that may be refreshable.
	 */
	public static final class AuthenticationRequiredException extends IOException
	{
		private static final long serialVersionUID = 1L;

		/**
		 * Describes the rejected token without exposing its value.
		 */
		public AuthenticationRequiredException()
		{
			super("Account directory returned HTTP 401");
		}
	}

	/**
	 * Indicates that the account server explicitly denied access to this login.
	 */
	public static final class AccessDeniedException extends IOException
	{
		private static final long serialVersionUID = 1L;

		/**
		 * Describes the server's denial without exposing the access token.
		 */
		public AccessDeniedException()
		{
			super("Account directory returned HTTP 403");
		}
	}

	/**
	 * Indicates that the account server could not answer an authorization check.
	 */
	public static final class UnavailableException extends IOException
	{
		private static final long serialVersionUID = 1L;

		/**
		 * Retains the failed request's diagnostic for the caller's offline decision.
		 *
		 * @param message description of the unavailable server
		 */
		public UnavailableException(String message)
		{
			super(message);
		}

		/**
		 * Retains the transport failure that prevented an authorization response.
		 *
		 * @param message description of the unavailable server
		 * @param cause request failure
		 */
		public UnavailableException(String message, IOException cause)
		{
			super(message, cause);
		}
	}

	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
	private final HttpClient http;
	private final URI accountsUri;
	private final JsonMapper json = JsonMapper.builder().build();

	/**
	 * Creates an account-directory transport.
	 *
	 * @param http HTTP client owned by the plugin
	 * @param serverBase HoldMySpot server origin
	 */
	public AccountDirectoryClient(HttpClient http, URI serverBase)
	{
		this.http = Objects.requireNonNull(http, "http");
		this.accountsUri = HoldMySpotHttp.endpoint(serverBase, "/v1/accounts");
	}

	/**
	 * Lists accounts linked to the bearer token's verified login.
	 *
	 * @param accessToken authenticated provider session token
	 * @return linked accounts in the server's account-ID order
	 * @throws IOException if the server does not return a valid account list
	 * @throws InterruptedException if the request is interrupted
	 */
	public List<LinkedAccount> list(String accessToken) throws IOException, InterruptedException
	{
		HttpRequest request = HttpRequest.newBuilder(accountsUri).timeout(REQUEST_TIMEOUT).
			header("Authorization", HoldMySpotHttp.bearer(accessToken)).GET().build();
		HttpResponse<String> response;
		try
		{
			response = http.send(request, HttpResponse.BodyHandlers.ofString());
		}
		catch (IOException exception)
		{
			throw new UnavailableException("Account directory could not be reached", exception);
		}
		if (response.statusCode() >= 500 && response.statusCode() <= 599)
			throw new UnavailableException("Account directory returned HTTP " + response.statusCode());
		if (response.statusCode() == 401)
			throw new AuthenticationRequiredException();
		if (response.statusCode() == 403)
			throw new AccessDeniedException();
		if (response.statusCode() != 200)
			throw new IOException("Account directory returned HTTP " + response.statusCode());

		JsonNode root;
		try
		{
			root = json.readTree(response.body());
		}
		catch (JacksonException exception)
		{
			throw new IOException("Account directory returned invalid account list", exception);
		}
		if (root == null || !root.isObject() || !root.path("accounts").isArray())
			throw new IOException("Account directory returned invalid account list");
		List<LinkedAccount> linked = new ArrayList<>();
		long previousId = 0;
		for (JsonNode item: root.path("accounts"))
		{
			if (!item.isObject())
				throw new IOException("Account directory returned invalid account list");
			JsonNode id = item.path("id");
			JsonNode email = item.path("email");
			JsonNode verified = item.path("verified");
			if (!id.isIntegralNumber() || !id.canConvertToLong() || id.asLong() <= previousId ||
				!email.isString() || email.stringValue().isBlank() || email.stringValue().length() > 320 ||
				!verified.isBoolean())
				throw new IOException("Account directory returned invalid account list");
			previousId = id.asLong();
			linked.add(new LinkedAccount(previousId, email.stringValue(), verified.asBoolean()));
		}
		if (linked.isEmpty())
			throw new IOException("Account directory returned invalid account list");
		return List.copyOf(linked);
	}

	/**
	 * One linked account shown during client account selection.
	 *
	 * @param id HoldMySpot account ID
	 * @param email current account email
	 * @param verified whether the current email is verified
	 */
	public record LinkedAccount(long id, String email, boolean verified)
	{
		/**
		 * Validates an account choice before it can be stored or shown to a user.
		 *
		 * @param id positive HoldMySpot account ID
		 * @param email nonblank current email of at most 320 characters
		 * @param verified whether the current email is verified
		 */
		public LinkedAccount
		{
			if (id < 1 || email == null || email.isBlank() || email.length() > 320)
				throw new IllegalArgumentException("Linked account requires a positive ID and current email");
		}
	}
}
