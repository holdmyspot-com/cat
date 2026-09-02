package io.github.cowwoc.cat.common.commercial;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

/**
 * Tests the bounded JDK HTTPS boundary used for confirmed feedback.
 */
public final class FeedbackHttpClientTest
{
	/**
	 * Creates the test fixture.
	 */
	public FeedbackHttpClientTest()
	{
	}

	/**
	 * Clears an interruption raised by the interruption test.
	 */
	@AfterMethod(alwaysRun = true)
	public void clearInterruption()
	{
		Thread.interrupted();
	}

	/**
	 * Verifies the public operation submits the exact HTTPS request and returns the bounded success response.
	 *
	 * @throws IOException if the fixture unexpectedly fails
	 */
	@Test
	public void submitsConfirmedPayload() throws IOException
	{
		FakeClient client = new FakeClient(201, "{\"receipt\":\"feedback-1\"}", false);
		UUID idempotencyKey = UUID.randomUUID();
		try (FeedbackHttpClient feedback = new FeedbackHttpClient(client, Duration.ofSeconds(3), 256))
		{
			String receipt = feedback.submit(URI.create("https://catsforbots.com/api/feedback"),
				"{\"kind\":\"failure\"}", "secret-token", idempotencyKey);

			requireThat(receipt, "receipt").isEqualTo("{\"receipt\":\"feedback-1\"}");
			requireThat(client.request.method(), "method").isEqualTo("POST");
			requireThat(client.request.headers().firstValue("Authorization").orElseThrow(), "authorization").
				isEqualTo("Bearer secret-token");
			String requestKey = client.request.headers().firstValue("Idempotency-Key").orElseThrow();
			requireThat(requestKey, "idempotencyKey").isEqualTo(idempotencyKey.toString());
			requireThat(client.request.timeout().orElseThrow(), "requestTimeout").isEqualTo(Duration.ofSeconds(3));
		}
	}

	/**
	 * Verifies an unencrypted endpoint is rejected before the client can send content.
	 *
	 * @throws IOException if transport is attempted unexpectedly
	 */
	@Test(expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*endpoint must be an absolute HTTPS URI with a host.*")
	public void rejectsHttpEndpoint() throws IOException
	{
		try (FeedbackHttpClient feedback = new FeedbackHttpClient(new FakeClient(201, "receipt", false),
			Duration.ofSeconds(3), 256))
		{
			feedback.submit(URI.create("http://catsforbots.com/api/feedback"), "{}", "secret-token", UUID.randomUUID());
		}
	}

	/**
	 * Verifies an oversized server response is rejected instead of being accepted or read without a bound.
	 *
	 * @throws IOException for the expected oversized response
	 */
	@Test(expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*Feedback response exceeds 8 bytes.*")
	public void rejectsOversizedResponse() throws IOException
	{
		try (FeedbackHttpClient feedback = new FeedbackHttpClient(new FakeClient(201, "123456789", false),
			Duration.ofSeconds(3), 8))
		{
			feedback.submit(URI.create("https://catsforbots.com/api/feedback"), "{}", "secret-token", UUID.randomUUID());
		}
	}

	/**
	 * Verifies interrupted delivery restores the owner interruption before returning an I/O failure.
	 */
	@Test
	public void preservesInterruption()
	{
		try (FeedbackHttpClient feedback = new FeedbackHttpClient(new FakeClient(201, "receipt", true),
			Duration.ofSeconds(3), 256))
		{
			try
			{
				feedback.submit(URI.create("https://catsforbots.com/api/feedback"), "{}", "secret-token",
					UUID.randomUUID());
				throw new AssertionError("Interrupted feedback unexpectedly completed");
			}
			catch (IOException expected)
			{
				requireThat(expected.getMessage(), "message").contains("Feedback delivery was interrupted");
				requireThat(Thread.currentThread().isInterrupted(), "interrupted").isTrue();
			}
		}
	}

	/**
	 * Supplies deterministic HTTP outcomes without network access.
	 */
	private static final class FakeClient extends HttpClient
	{
		private final int status;
		private final String body;
		private final boolean interrupted;
		private HttpRequest request;

		/**
		 * Creates one deterministic response.
		 *
		 * @param status the HTTP status
		 * @param body the UTF-8 response body
		 * @param interrupted whether send is interrupted
		 */
		private FakeClient(int status, String body, boolean interrupted)
		{
			this.status = status;
			this.body = body;
			this.interrupted = interrupted;
		}

		@Override
		public Optional<CookieHandler> cookieHandler()
		{
			return Optional.empty();
		}

		@Override
		public Optional<Duration> connectTimeout()
		{
			return Optional.of(Duration.ofSeconds(2));
		}

		@Override
		public Redirect followRedirects()
		{
			return Redirect.NEVER;
		}

		@Override
		public Optional<ProxySelector> proxy()
		{
			return Optional.empty();
		}

		@Override
		public SSLContext sslContext()
		{
			return null;
		}

		@Override
		public SSLParameters sslParameters()
		{
			return new SSLParameters();
		}

		@Override
		public Optional<Authenticator> authenticator()
		{
			return Optional.empty();
		}

		@Override
		public Version version()
		{
			return Version.HTTP_2;
		}

		@Override
		public Optional<Executor> executor()
		{
			return Optional.empty();
		}

		@Override
		@SuppressWarnings("unchecked")
		public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
			throws InterruptedException
		{
			if (interrupted)
				throw new InterruptedException("fixture interruption");
			this.request = request;
			return (HttpResponse<T>) new FakeResponse(request, status, body);
		}

		@Override
		public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
			HttpResponse.BodyHandler<T> handler)
		{
			throw new UnsupportedOperationException();
		}

		@Override
		public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
			HttpResponse.BodyHandler<T> handler, HttpResponse.PushPromiseHandler<T> pushPromiseHandler)
		{
			throw new UnsupportedOperationException();
		}
	}

	/**
	 * Holds a deterministic response body.
	 *
	 * @param request the submitted request
	 * @param status the HTTP status
	 * @param text the response text
	 */
	private record FakeResponse(HttpRequest request, int status, String text) implements HttpResponse<InputStream>
	{
		@Override
		public int statusCode()
		{
			return status;
		}

		@Override
		public Optional<HttpResponse<InputStream>> previousResponse()
		{
			return Optional.empty();
		}

		@Override
		public HttpHeaders headers()
		{
			return HttpHeaders.of(Map.of(), (first, second) -> true);
		}

		@Override
		public InputStream body()
		{
			return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
		}

		@Override
		public Optional<SSLSession> sslSession()
		{
			return Optional.empty();
		}

		@Override
		public URI uri()
		{
			return request.uri();
		}

		@Override
		public HttpClient.Version version()
		{
			return HttpClient.Version.HTTP_2;
		}
	}
}
