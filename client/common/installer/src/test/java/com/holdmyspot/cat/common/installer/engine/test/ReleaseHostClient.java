package com.holdmyspot.cat.common.installer.engine.test;

import com.holdmyspot.cat.common.installer.engine.HttpsReleaseSource;
import com.holdmyspot.cat.common.installer.engine.ReleaseConnection;
import com.holdmyspot.cat.common.installer.secrets.HttpsSourceSecrets;
import com.holdmyspot.cat.common.installer.trust.TrustException;
import java.io.IOException;
import java.net.Authenticator;
import java.net.ConnectException;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.PushPromiseHandler;
import java.net.http.HttpResponse.ResponseInfo;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow.Subscription;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

/**
 * Plays the JDK HTTP client of {@link HttpsReleaseSource} against a release host whose files a test publishes, so that
 * the real source downloads releases without a network. The host lays its files out as GitHub's documentation of
 * release links describes them: each release's files lie in {@code <download>/<tag>/<name>}, and
 * {@code <releases>/latest} redirects to {@code <releases>/tag/<tag>} of the latest release, where {@code <releases>}
 * is the parent of the download directory. A location that the host has no file for answers HTTP 404. After
 * {@link #goOffline()}, every request fails with the {@link ConnectException} that the JDK client reports when the
 * network is unreachable.
 * <p>
 * The host's files outlive every source that sends requests through this client, so closing a source leaves the client
 * able to serve the next one, as the network serves the source that the next installer command opens. Requests may
 * arrive from several threads.
 */
public final class ReleaseHostClient extends HttpClient
{
	/**
	 * The deadline for one request of a source, which no request reaches because the client answers at once.
	 */
	private static final Duration REQUEST_LIMIT = Duration.ofSeconds(5);
	private final URI download;
	private final URI latest;
	/**
	 * The body of each published file, by its location.
	 */
	private final Map<URI, byte[]> files = new ConcurrentHashMap<>();
	/**
	 * The location of the latest release's page, or {@code null} while the host has no latest release.
	 */
	private volatile URI latestPage;
	private volatile boolean offline;

	/**
	 * Creates a client of a release host that has no files.
	 *
	 * @param releaseBaseUrl the host's download directory, without a trailing slash
	 */
	public ReleaseHostClient(URI releaseBaseUrl)
	{
		this.download = releaseBaseUrl;
		this.latest = releaseBaseUrl.resolve("latest");
	}

	/**
	 * Publishes a file of a release, replacing a file of the same name.
	 *
	 * @param tag     the release's tag
	 * @param name    the file's name
	 * @param content the file's bytes
	 */
	public void publish(String tag, String name, byte[] content)
	{
		files.put(URI.create(download + "/" + tag + "/" + name), content.clone());
	}

	/**
	 * Makes a release the latest one.
	 *
	 * @param tag the release's tag
	 */
	public void latest(String tag)
	{
		latestPage = latest.resolve("tag/" + tag);
	}

	/**
	 * Makes every later request fail as if the network were unreachable.
	 */
	public void goOffline()
	{
		offline = true;
	}

	/**
	 * Opens CAT's release source at a location, sending its requests through this client.
	 *
	 * @param releaseBaseUrl the release base URL that the source requests below
	 * @return the source
	 * @throws TrustException if the source refuses the location
	 */
	public ReleaseConnection open(URI releaseBaseUrl) throws TrustException
	{
		return HttpsSourceSecrets.getHttpsSourceAccess().create(releaseBaseUrl, REQUEST_LIMIT, this);
	}

	@Override
	public <T> HttpResponse<T> send(HttpRequest request, BodyHandler<T> responseBodyHandler) throws IOException
	{
		if (offline)
			throw new ConnectException("The network is unreachable: " + request.uri());
		URI location = request.uri();
		int status;
		Map<String, List<String>> headers;
		byte[] body;
		if (location.equals(latest) && latestPage != null)
		{
			status = 302;
			headers = Map.of("Location", List.of(latestPage.toString()));
			body = new byte[0];
		}
		else if (files.containsKey(location))
		{
			status = 200;
			headers = Map.of();
			body = files.get(location);
		}
		else
		{
			status = 404;
			headers = Map.of();
			body = new byte[0];
		}
		HttpHeaders httpHeaders = HttpHeaders.of(headers, (_, _) -> true);
		BodySubscriber<T> subscriber = responseBodyHandler.apply(new Info(status, httpHeaders));
		subscriber.onSubscribe(new BodySubscription(subscriber, body));
		CompletableFuture<T> result = subscriber.getBody().toCompletableFuture();
		if (!result.isDone())
			throw new IOException("The response's body subscriber did not finish reading the body");
		try
		{
			return new Response<>(status, request, httpHeaders, result.get());
		}
		catch (ExecutionException e)
		{
			throw new IOException(e.getCause().getMessage(), e.getCause());
		}
		catch (InterruptedException e)
		{
			throw new AssertionError("A completed body cannot be interrupted", e);
		}
	}

	@Override
	public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
		BodyHandler<T> responseBodyHandler)
	{
		throw new UnsupportedOperationException("The release source sends requests synchronously");
	}

	@Override
	public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, BodyHandler<T> responseBodyHandler,
		PushPromiseHandler<T> pushPromiseHandler)
	{
		throw new UnsupportedOperationException("The release source sends requests synchronously");
	}

	@Override
	public Optional<CookieHandler> cookieHandler()
	{
		return Optional.empty();
	}

	@Override
	public Optional<Duration> connectTimeout()
	{
		return Optional.empty();
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
		throw new UnsupportedOperationException("The release host is reached without TLS");
	}

	@Override
	public SSLParameters sslParameters()
	{
		throw new UnsupportedOperationException("The release host is reached without TLS");
	}

	@Override
	public Optional<Authenticator> authenticator()
	{
		return Optional.empty();
	}

	@Override
	public Version version()
	{
		return Version.HTTP_1_1;
	}

	@Override
	public Optional<Executor> executor()
	{
		return Optional.empty();
	}

	/**
	 * The status and headers of a response, which choose how its body is read.
	 *
	 * @param statusCode the HTTP status code
	 * @param headers    the response's headers
	 */
	private record Info(int statusCode, HttpHeaders headers) implements ResponseInfo
	{
		@Override
		public Version version()
		{
			return Version.HTTP_1_1;
		}
	}

	/**
	 * Delivers a response's body to its subscriber in one buffer once the subscriber requests it, then completes.
	 */
	private static final class BodySubscription implements Subscription
	{
		private final BodySubscriber<?> subscriber;
		private final byte[] body;
		private boolean delivered;
		private boolean cancelled;

		/**
		 * Creates a subscription.
		 *
		 * @param subscriber the subscriber
		 * @param body       the response's body
		 */
		private BodySubscription(BodySubscriber<?> subscriber, byte[] body)
		{
			this.subscriber = subscriber;
			this.body = body;
		}

		@Override
		public void request(long count)
		{
			if (delivered || cancelled)
				return;
			delivered = true;
			if (body.length > 0)
				subscriber.onNext(List.of(ByteBuffer.wrap(body.clone())));
			if (!cancelled)
				subscriber.onComplete();
		}

		@Override
		public void cancel()
		{
			cancelled = true;
		}
	}

	/**
	 * A response whose body a subscriber has read.
	 *
	 * @param <T>        the body's type
	 * @param statusCode the HTTP status code
	 * @param request    the request that the response answers
	 * @param headers    the response's headers
	 * @param body       the body
	 */
	private record Response<T>(int statusCode, HttpRequest request, HttpHeaders headers, T body)
		implements HttpResponse<T>
	{
		@Override
		public Optional<HttpResponse<T>> previousResponse()
		{
			return Optional.empty();
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
		public Version version()
		{
			return Version.HTTP_1_1;
		}
	}
}
