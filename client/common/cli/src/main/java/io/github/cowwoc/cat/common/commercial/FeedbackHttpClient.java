package io.github.cowwoc.cat.common.commercial;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * Submits one already-confirmed private feedback payload over HTTPS and bounds the receipt response.
 */
public final class FeedbackHttpClient implements AutoCloseable
{
	private final HttpClient client;
	private final Duration requestTimeout;
	private final int maximumResponseBytes;

	/**
	 * Creates a transport with finite connection and request timeouts.
	 *
	 * @param connectTimeout the maximum time allowed to establish a connection
	 * @param requestTimeout the maximum time allowed for one complete request
	 * @param maximumResponseBytes the largest accepted receipt response
	 */
	public FeedbackHttpClient(Duration connectTimeout, Duration requestTimeout, int maximumResponseBytes)
	{
		this(HttpClient.newBuilder().connectTimeout(requirePositive(connectTimeout, "connectTimeout")).
			followRedirects(HttpClient.Redirect.NEVER).build(), requestTimeout, maximumResponseBytes);
	}

	/**
	 * Creates a transport around a supplied client for isolated boundary testing.
	 *
	 * @param client the HTTP client
	 * @param requestTimeout the maximum time allowed for one complete request
	 * @param maximumResponseBytes the largest accepted receipt response
	 */
	FeedbackHttpClient(HttpClient client, Duration requestTimeout, int maximumResponseBytes)
	{
		this.client = Objects.requireNonNull(client, "client must not be null");
		this.requestTimeout = requirePositive(requestTimeout, "requestTimeout");
		if (maximumResponseBytes <= 0 || maximumResponseBytes == Integer.MAX_VALUE)
			throw new IllegalArgumentException("maximumResponseBytes must be positive and below Integer.MAX_VALUE");
		this.maximumResponseBytes = maximumResponseBytes;
	}

	/**
	 * Submits an exact confirmed JSON payload and returns the bounded success response.
	 *
	 * @param endpoint the CAT HTTPS feedback endpoint
	 * @param payload the exact confirmed JSON payload
	 * @param accessToken the current CAT account access token
	 * @param idempotencyKey the stable UUID for the confirmed submission
	 * @return the bounded UTF-8 receipt response
	 * @throws IOException if transport fails, the server rejects the request, or the response is too large
	 */
	public String submit(URI endpoint, String payload, String accessToken, UUID idempotencyKey) throws IOException
	{
		validateEndpoint(endpoint);
		payload = requireText(payload, "payload");
		accessToken = requireText(accessToken, "accessToken");
		Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
		HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(requestTimeout).
			header("Accept", "application/json").header("Content-Type", "application/json; charset=UTF-8").
			header("Authorization", "Bearer " + accessToken).header("Idempotency-Key", idempotencyKey.toString()).
			POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8)).build();
		HttpResponse<InputStream> response;
		try
		{
			response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			throw new IOException("Feedback delivery was interrupted", e);
		}
		byte[] body;
		try (InputStream input = response.body())
		{
			body = input.readNBytes(maximumResponseBytes + 1);
		}
		if (body.length > maximumResponseBytes)
			throw new IOException("Feedback response exceeds " + maximumResponseBytes + " bytes");
		if (response.statusCode() < 200 || response.statusCode() >= 300)
			throw new IOException("CAT feedback endpoint returned HTTP " + response.statusCode());
		return new String(body, StandardCharsets.UTF_8);
	}

	/**
	 * Closes the underlying JDK HTTP client.
	 */
	@Override
	public void close()
	{
		client.close();
	}

	/**
	 * Requires a positive duration.
	 *
	 * @param value the duration
	 * @param name the parameter name
	 * @return the validated duration
	 */
	private static Duration requirePositive(Duration value, String name)
	{
		Objects.requireNonNull(value, name + " must not be null");
		if (value.isZero() || value.isNegative())
			throw new IllegalArgumentException(name + " must be positive");
		return value;
	}

	/**
	 * Requires nonblank text.
	 *
	 * @param value the text
	 * @param name the parameter name
	 * @return the validated text
	 */
	private static String requireText(String value, String name)
	{
		Objects.requireNonNull(value, name + " must not be null");
		if (value.isBlank())
			throw new IllegalArgumentException(name + " must not be blank");
		return value;
	}

	/**
	 * Requires a complete HTTPS endpoint.
	 *
	 * @param endpoint the endpoint
	 */
	private static void validateEndpoint(URI endpoint)
	{
		Objects.requireNonNull(endpoint, "endpoint must not be null");
		if (!"https".equals(endpoint.getScheme()) || endpoint.getHost() == null || endpoint.getHost().isBlank())
			throw new IllegalArgumentException("endpoint must be an absolute HTTPS URI with a host");
	}
}
