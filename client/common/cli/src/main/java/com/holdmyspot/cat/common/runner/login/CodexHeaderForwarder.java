package com.holdmyspot.cat.common.runner.login;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The loopback forwarder through which a nested session reaches a provider whose secret travels in headers: it checks
 * the token that it issued to the session, removes it, adds the provider's secret headers and bearer token, and passes
 * the request on to the provider's own base URL, streaming the answer back.
 * <p>
 * It listens on the loopback interface alone, serves the sessions of its own server, and refuses, without passing it
 * on, a request that carries no token, a wrong one or that of a session that has ended.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class CodexHeaderForwarder implements AutoCloseable
{
	/**
	 * The request headers that the forwarder never passes on: the session's token, the connection's own headers and
	 * those that the HTTP client sets itself.
	 */
	private static final Set<String> DROPPED_REQUEST_HEADERS = Set.of("authorization", "connection", "content-length",
		"expect", "host", "upgrade", "keep-alive", "proxy-connection", "transfer-encoding", "te", "trailer",
		"proxy-authorization");
	/**
	 * The response headers that the forwarder never passes back, because its own server sets them.
	 */
	private static final Set<String> DROPPED_RESPONSE_HEADERS = Set.of("connection", "content-length",
		"transfer-encoding", "keep-alive", ":status");
	private static final String BEARER = "Bearer ";

	/**
	 * Supplies a provider's bearer token for each request.
	 */
	@FunctionalInterface
	public interface Bearer
	{
		/**
		 * Returns the token.
		 *
		 * @return the token
		 * @throws LoginRefusal if the token cannot be supplied; the reason holds no secret
		 */
		String token() throws LoginRefusal;
	}

	/**
	 * A session's route to its provider.
	 *
	 * @param baseUrl the provider's own base URL
	 * @param headers the headers that the forwarder adds
	 * @param bearer  supplies the provider's bearer token, or empty
	 */
	private record Route(URI baseUrl, Map<String, String> headers, Optional<Bearer> bearer)
	{
		@Override
		public String toString()
		{
			return "Route{baseUrl=" + baseUrl + ", headers=" + headers.keySet() + "}";
		}
	}

	private final HttpServer server;
	private final ExecutorService executor;
	private final HttpClient client;
	private final Map<String, Route> routes = new ConcurrentHashMap<>();

	/**
	 * Creates a forwarder.
	 *
	 * @param server   the listener
	 * @param executor runs each exchange
	 */
	private CodexHeaderForwarder(HttpServer server, ExecutorService executor)
	{
		this.server = server;
		this.executor = executor;
		this.client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).
			connectTimeout(Duration.ofSeconds(30)).followRedirects(HttpClient.Redirect.NEVER).build();
	}

	/**
	 * Starts a forwarder on a free port of the loopback interface.
	 *
	 * @return the forwarder
	 * @throws IOException if it cannot listen
	 */
	// The forwarder's close() shuts the executor down
	@SuppressWarnings("PMD.CloseResource")
	public static CodexHeaderForwarder start() throws IOException
	{
		HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
		server.setExecutor(executor);
		CodexHeaderForwarder forwarder = new CodexHeaderForwarder(server, executor);
		server.createContext("/", forwarder::handle);
		server.start();
		return forwarder;
	}

	/**
	 * Returns the forwarder's address, the base URL that a session's provider names.
	 *
	 * @return the address
	 */
	public URI address()
	{
		InetSocketAddress address = server.getAddress();
		String host = address.getAddress().getHostAddress();
		if (host.contains(":"))
			host = "[" + host + "]";
		return URI.create("http://" + host + ":" + address.getPort());
	}

	/**
	 * Returns the interface that the forwarder listens on.
	 *
	 * @return the address
	 */
	public InetAddress listener()
	{
		return server.getAddress().getAddress();
	}

	/**
	 * Registers a session's route and issues its token.
	 *
	 * @param token   the session's token, which the session's provider sends as its bearer token
	 * @param baseUrl the provider's own base URL
	 * @param headers the headers that the forwarder adds
	 * @param bearer  supplies the provider's bearer token, or empty
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code token} is blank or already registered
	 */
	public void register(String token, URI baseUrl, Map<String, String> headers, Optional<Bearer> bearer)
	{
		requireThat(token, "token").isNotBlank();
		requireThat(baseUrl, "baseUrl").isNotNull();
		requireThat(headers, "headers").isNotNull();
		requireThat(bearer, "bearer").isNotNull();
		if (routes.putIfAbsent(token, new Route(baseUrl, Map.copyOf(headers), bearer)) != null)
			throw new IllegalArgumentException("The token is already registered");
	}

	/**
	 * Ends a session's route, so that its token no longer reaches the provider.
	 *
	 * @param token the session's token
	 * @throws NullPointerException if {@code token} is null
	 */
	public void unregister(String token)
	{
		requireThat(token, "token").isNotNull();
		routes.remove(token);
	}

	/**
	 * Handles a request.
	 *
	 * @param exchange the exchange
	 * @throws IOException if the exchange fails
	 */
	private void handle(HttpExchange exchange) throws IOException
	{
		try (exchange)
		{
			Optional<Route> route = route(exchange.getRequestHeaders().getFirst("Authorization"));
			if (route.isEmpty())
			{
				refuse(exchange, 401, "The request carries no token that CAT's forwarder issued to a running session.");
				return;
			}
			forward(exchange, route.get());
		}
	}

	/**
	 * Returns the route of a request's token.
	 *
	 * @param authorization the request's {@code Authorization} header, or null
	 * @return the route, or empty if the token is missing or unknown
	 */
	private Optional<Route> route(String authorization)
	{
		if (authorization == null || !authorization.startsWith(BEARER))
			return Optional.empty();
		byte[] given = authorization.substring(BEARER.length()).strip().getBytes(UTF_8);
		for (Map.Entry<String, Route> entry : routes.entrySet())
		{
			if (MessageDigest.isEqual(entry.getKey().getBytes(UTF_8), given))
				return Optional.of(entry.getValue());
		}
		return Optional.empty();
	}

	/**
	 * Passes a request on to its provider and streams the answer back.
	 *
	 * @param exchange the exchange
	 * @param route    the session's route
	 * @throws IOException if the exchange fails
	 */
	private void forward(HttpExchange exchange, Route route) throws IOException
	{
		String base = route.baseUrl().toString();
		while (base.endsWith("/"))
			base = base.substring(0, base.length() - 1);
		String path = exchange.getRequestURI().getRawPath();
		String query = exchange.getRequestURI().getRawQuery();
		String target = base + path;
		if (query != null)
			target += "?" + query;
		String bearer = null;
		if (route.bearer().isPresent())
		{
			try
			{
				bearer = route.bearer().get().token();
			}
			catch (LoginRefusal e)
			{
				refuse(exchange, 502, e.getMessage());
				return;
			}
		}
		byte[] body = exchange.getRequestBody().readAllBytes();
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(target)).
			method(exchange.getRequestMethod(), HttpRequest.BodyPublishers.ofByteArray(body));
		for (Map.Entry<String, List<String>> header : exchange.getRequestHeaders().entrySet())
		{
			String name = header.getKey().toLowerCase(Locale.ROOT);
			if (DROPPED_REQUEST_HEADERS.contains(name) || containsIgnoringCase(route.headers(), name))
				continue;
			for (String value : header.getValue())
				request.header(header.getKey(), value);
		}
		for (Map.Entry<String, String> header : route.headers().entrySet())
			request.header(header.getKey(), header.getValue());
		if (bearer != null)
			request.header("Authorization", BEARER + bearer);
		HttpResponse<InputStream> response;
		try
		{
			response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
		}
		catch (InterruptedException _)
		{
			Thread.currentThread().interrupt();
			refuse(exchange, 502, "CAT's forwarder was interrupted");
			return;
		}
		catch (IOException e)
		{
			refuse(exchange, 502, "CAT's forwarder cannot reach the provider at " + route.baseUrl() + ": " +
				e.getClass().getSimpleName());
			return;
		}
		for (Map.Entry<String, List<String>> header : response.headers().map().entrySet())
		{
			if (DROPPED_RESPONSE_HEADERS.contains(header.getKey().toLowerCase(Locale.ROOT)))
				continue;
			exchange.getResponseHeaders().put(header.getKey(), header.getValue());
		}
		try (InputStream input = response.body())
		{
			exchange.sendResponseHeaders(response.statusCode(), 0);
			try (OutputStream output = exchange.getResponseBody())
			{
				byte[] buffer = new byte[8192];
				while (true)
				{
					int read = input.read(buffer);
					if (read < 0)
						break;
					output.write(buffer, 0, read);
					// A stream of server-sent events must reach the session as it arrives
					output.flush();
				}
			}
		}
	}

	/**
	 * Returns whether a map has a key, ignoring case.
	 *
	 * @param map  the map
	 * @param name the key, in lower case
	 * @return {@code true} if it has the key
	 */
	private static boolean containsIgnoringCase(Map<String, String> map, String name)
	{
		for (String key : map.keySet())
		{
			if (key.toLowerCase(Locale.ROOT).equals(name))
				return true;
		}
		return false;
	}

	/**
	 * Answers a request with a refusal.
	 *
	 * @param exchange the exchange
	 * @param status   the status
	 * @param reason   the reason, which holds no secret
	 * @throws IOException if the answer cannot be sent
	 */
	private static void refuse(HttpExchange exchange, int status, String reason) throws IOException
	{
		String escaped = reason.replace("\\", "\\\\").replace("\"", "\\\"");
		byte[] body = ("{\"error\":{\"message\":\"" + escaped + "\"}}").getBytes(UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, body.length);
		try (OutputStream output = exchange.getResponseBody())
		{
			output.write(body);
		}
	}

	@Override
	public void close()
	{
		routes.clear();
		server.stop(0);
		executor.shutdownNow();
		client.close();
	}
}
