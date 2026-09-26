package io.github.cowwoc.cat.common.usage;

import io.github.cowwoc.cat.common.http.HoldMySpotHttp;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/**
 * Sends a retained usage report to the beta host and confirms its committed event and turn total.
 */
public final class UsageReportClient implements UsageReportOutbox.Sender
{
	/**
	 * Indicates that the server rejected the access token used for a usage report.
	 */
	public static final class AuthenticationRequiredException extends IOException
	{
		private static final long serialVersionUID = 1L;

		/**
		 * Creates the response failure after HTTP 401.
		 */
		private AuthenticationRequiredException()
		{
			super("Usage server rejected the access token with HTTP 401");
		}
	}

	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
	private final HttpClient http;
	private final URI reportUri;
	private final JsonMapper json = JsonMapper.builder().build();

	/**
	 * Creates the shared usage-report transport.
	 *
	 * @param http HTTP client owned by the plugin
	 * @param serverBase server origin
	 */
	public UsageReportClient(HttpClient http, URI serverBase)
	{
		this.http = Objects.requireNonNull(http, "http");
		this.reportUri = HoldMySpotHttp.endpoint(serverBase, "/v1/verified-usage-reports");
	}

	/**
	 * Sends the outbox's exact retained body and confirms its delivery event and committed count.
	 *
	 * @param accessToken authenticated user-session token
	 * @param accountId selected HoldMySpot account ID
	 * @param report retained report body and event ID
	 * @return committed acknowledgement
	 * @throws IOException if the server does not confirm this delivery
	 * @throws InterruptedException if the HTTP request is interrupted
	 */
	@Override
	public UsageReportOutbox.Acknowledgement send(String accessToken, long accountId,
		UsageReportOutbox.PendingReport report) throws IOException, InterruptedException
	{
		if (accountId < 1)
			throw new IllegalArgumentException("Selected HoldMySpot account ID must be positive");
		Objects.requireNonNull(report, "report");

		HttpRequest request = HttpRequest.newBuilder(reportUri).timeout(REQUEST_TIMEOUT).
			header("Authorization", HoldMySpotHttp.bearer(accessToken)).
			header("X-HoldMySpot-Account-ID", Long.toString(accountId)).
			header("Content-Type", "application/json; charset=utf-8").
			POST(HttpRequest.BodyPublishers.ofString(report.body(), StandardCharsets.UTF_8)).build();
		HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() == 401)
			throw new AuthenticationRequiredException();
		if (response.statusCode() != 200)
			throw new IOException("Usage server returned HTTP " + response.statusCode());

		JsonNode acknowledgement;
		try
		{
			acknowledgement = json.readTree(response.body());
		}
		catch (JacksonException exception)
		{
			throw new IOException("Usage server returned malformed acknowledgement", exception);
		}
		if (acknowledgement == null || !acknowledgement.isObject())
			throw new IOException("Usage server returned invalid acknowledgement");
		JsonNode returnedEvent = acknowledgement.path("event_id");
		JsonNode returnedTotal = acknowledgement.path("accounted_output_tokens");
		if (!returnedEvent.isString() || !report.eventId().toString().equals(returnedEvent.stringValue()) ||
			!returnedTotal.isIntegralNumber() || !returnedTotal.canConvertToLong() ||
			returnedTotal.asLong() < report.outputTokensSoFar())
			throw new IOException("Usage server did not confirm this delivery and count");
		return new UsageReportOutbox.Acknowledgement(report.eventId(), returnedTotal.asLong());
	}
}
