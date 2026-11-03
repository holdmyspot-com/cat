package com.holdmyspot.cat.common.updater.engine.test;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.ResponseInfo;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow.Subscription;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

/**
 * Supplies HTTP statuses, headers and body chunks using HttpClient's documented BodyHandler/BodySubscriber protocol.
 * It records outgoing requests so tests can assert the source's URL and redirect decisions without opening a socket.
 */
public final class FakeHttpsClient extends HttpClient
{
	private final Deque<Reply> replies = new ArrayDeque<>();
	private final List<HttpRequest> requests = new ArrayList<>();
	private boolean closed;

	/**
	 * Adds the next HTTP response.
	 *
	 * @param status   response status
	 * @param location redirect Location, or empty for no Location
	 * @param body     response bytes
	 */
	public void reply(int status, String location, byte[] body)
	{
		Map<String, List<String>> headers = Map.of();
		if (!location.isEmpty())
			headers = Map.of("Location", List.of(location));
		replies.addLast(new Reply(status, HttpHeaders.of(headers, (_, _) -> true), body.clone()));
	}

	/**
	 * Returns requests sent by the source, in order.
	 *
	 * @return outgoing requests
	 */
	public List<HttpRequest> requests()
	{
		return List.copyOf(requests);
	}

	/**
	 * Indicates whether the consumer closed this client.
	 *
	 * @return {@code true} after close
	 */
	public boolean closed()
	{
		return closed;
	}

	@Override
	public <T> HttpResponse<T> send(HttpRequest request, BodyHandler<T> handler) throws IOException
	{
		requests.add(request);
		Reply reply = replies.removeFirst();
		BodySubscriber<T> subscriber = handler.apply(new ResponseInfo()
		{
			@Override
			public int statusCode()
			{
				return reply.status();
			}

			@Override
			public HttpHeaders headers()
			{
				return reply.headers();
			}

			@Override
			public Version version()
			{
				return Version.HTTP_1_1;
			}
		});
		subscriber.onSubscribe(new Subscription()
		{
			@Override
			public void request(long count)
			{
			}

			@Override
			public void cancel()
			{
			}
		});
		subscriber.onNext(List.of(ByteBuffer.wrap(reply.bytes())));
		subscriber.onComplete();
		try
		{
			T body = subscriber.getBody().toCompletableFuture().join();
			return new Response<>(request, reply.status(), reply.headers(), body);
		}
		catch (CompletionException failure)
		{
			if (failure.getCause() instanceof IOException cause)
				throw cause;
			throw failure;
		}
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
		throw new UnsupportedOperationException("The fake transport opens no TLS connection");
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
		return Version.HTTP_1_1;
	}

	@Override
	public Optional<Executor> executor()
	{
		return Optional.empty();
	}

	@Override
	public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, BodyHandler<T> handler)
	{
		throw new UnsupportedOperationException("The release source uses synchronous requests");
	}

	@Override
	public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, BodyHandler<T> handler,
		HttpResponse.PushPromiseHandler<T> pushPromiseHandler)
	{
		throw new UnsupportedOperationException("The release source uses synchronous requests");
	}

	@Override
	public void close()
	{
		closed = true;
	}

	/**
	 * An HTTP response the source receives.
	 *
	 * @param status  response status
	 * @param headers response headers
	 * @param bytes   response body
	 */
	private record Reply(int status, HttpHeaders headers, byte[] bytes)
	{
	}

	/**
	 * A completed response with the body produced by the source's subscriber.
	 *
	 * @param request    outgoing request
	 * @param statusCode HTTP status
	 * @param headers    response headers
	 * @param body       subscriber result
	 * @param <T>        body result type
	 */
	private record Response<T>(HttpRequest request, int statusCode, HttpHeaders headers, T body)
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
