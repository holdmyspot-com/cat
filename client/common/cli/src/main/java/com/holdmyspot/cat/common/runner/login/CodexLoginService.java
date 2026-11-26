package com.holdmyspot.cat.common.runner.login;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.CommandLineArguments;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.runner.SecretFile;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The login service of a request server: it holds the source home's Codex login, outside every session's sandbox,
 * and serves each nested session a bearer token, and nothing else, over an owner-only local socket.
 * <p>
 * A runner opens a session's login before it starts the session's harness, presenting the key that the server gave
 * its runners, and receives the session's credential, which it writes into the session's key file and which the
 * session's token command presents for each token. A login that the operating system's keyring holds opens only when
 * the top-level session, at the root of the runner chain, can itself reach the keyring from its own sandbox, as
 * {@link KeyringReach} decides from that session's policy, read once per opening.
 * <p>
 * The service reads a ChatGPT sign-in read-only at each request and serves its access token, never its refresh
 * token; it runs a source provider's own token command, or a {@code gateway_oauth} provider's
 * {@code credential_export} command, with the source home as its home folder, and keeps its output for the
 * provider's refresh interval, so that the sessions share one run per interval; and it registers a provider whose
 * secret travels in headers with its {@link CodexHeaderForwarder}. No log, refusal or answer other than a token holds
 * a secret.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class CodexLoginService implements AutoCloseable
{
	/**
	 * The variable through which the server gives each runner, and each runner its child runners, the service's socket.
	 */
	public static final String SOCKET_VARIABLE = "CAT_CODEX_LOGIN_SOCKET";
	/**
	 * The variable through which the server gives each runner, and each runner its child runners, the key with which
	 * a runner opens a session's login.
	 */
	public static final String KEY_VARIABLE = "CAT_CODEX_LOGIN_KEY";
	/**
	 * The name of the service's socket in its folder.
	 */
	private static final String SOCKET_NAME = "s";
	/**
	 * The longest socket path, in bytes, that every platform's socket address holds.
	 */
	private static final int MAXIMUM_SOCKET_PATH = 100;
	private static final int CREDENTIAL_BYTES = 32;
	/**
	 * The most bytes of a token command's output that the service reads.
	 */
	private static final int MAXIMUM_TOKEN_BYTES = 65_536;

	/**
	 * Reads the top-level session's policy for its main thread, as the server reads it for a launch.
	 */
	@FunctionalInterface
	public interface TopLevelPolicy
	{
		/**
		 * Reads the policy now.
		 *
		 * @return the policy, with the earlier readings that bound a child intersected
		 * @throws IOException if the policy cannot be read safely
		 */
		ParentPolicy read() throws IOException;
	}

	private final Path folder;
	private final Path socket;
	private final ServerSocketChannel channel;
	private final String key;
	private final TopLevelPolicy topLevel;
	private final CodexLoginSource source;
	private final Platform platform;
	private final Map<String, String> environment;
	private final InstantSource clock;
	private final SecureRandom random = new SecureRandom();
	private final Map<String, Session> sessions = new ConcurrentHashMap<>();
	private final Map<CodexLoginPlan.Command, CachedToken> tokens = new ConcurrentHashMap<>();
	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
	private final Thread acceptor;
	private final ReentrantLock forwarderLock = new ReentrantLock();
	private CodexHeaderForwarder forwarder;
	private volatile boolean closed;

	/**
	 * A session whose login the service opened.
	 *
	 * @param plan           the session's login
	 * @param inKeyring      {@code true} if the keyring holds the login
	 * @param forwarderToken the token that the forwarder issued to the session, or empty
	 */
	private record Session(CodexLoginPlan plan, boolean inKeyring, Optional<String> forwarderToken)
	{
		@Override
		public String toString()
		{
			return "Session{plan=" + plan + ", inKeyring=" + inKeyring + "}";
		}
	}

	/**
	 * A token command's latest output.
	 */
	private static final class CachedToken
	{
		private final ReentrantLock lock = new ReentrantLock();
		private String token;
		private Instant fetched;

		@Override
		public String toString()
		{
			return "CachedToken{fetched=" + fetched + "}";
		}
	}

	/**
	 * Creates a service.
	 *
	 * @param folder      the socket's owner-only folder
	 * @param channel     the listening channel
	 * @param topLevel    reads the top-level session's policy
	 * @param source      reads the source home's stored login
	 * @param platform    the platform that the server runs on
	 * @param environment the server's environment
	 * @param clock       the clock that ages a command's output and a sign-in's expiry
	 */
	private CodexLoginService(Path folder, ServerSocketChannel channel, TopLevelPolicy topLevel,
		CodexLoginSource source, Platform platform, Map<String, String> environment, InstantSource clock)
	{
		this.folder = folder;
		this.socket = folder.resolve(SOCKET_NAME);
		this.channel = channel;
		this.topLevel = topLevel;
		this.source = source;
		this.platform = platform;
		this.environment = Map.copyOf(environment);
		this.clock = clock;
		this.key = newSecret();
		this.acceptor = Thread.ofVirtual().name("codex-login-service").unstarted(this::accept);
	}

	/**
	 * Starts a service whose socket is in an owner-only folder that it creates in a server's folder, or, when that path
	 * would be longer than a socket's address holds, in the first of the temporary folder and, on platforms other than
	 * Windows, {@code /tmp} whose path is short enough.
	 *
	 * @param parent      the server's folder
	 * @param topLevel    reads the top-level session's policy
	 * @param source      reads the source home's stored login
	 * @param platform    the platform that the server runs on
	 * @param environment the server's environment, which a source command and the keyring check receive
	 * @param clock       the clock that ages a command's output and a sign-in's expiry
	 * @return the service
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the folder or the socket cannot be created
	 */
	// The service's close() closes the channel
	@SuppressWarnings("PMD.CloseResource")
	public static CodexLoginService start(Path parent, TopLevelPolicy topLevel, CodexLoginSource source,
		Platform platform, Map<String, String> environment, InstantSource clock) throws IOException
	{
		requireThat(parent, "parent").isNotNull();
		requireThat(topLevel, "topLevel").isNotNull();
		requireThat(source, "source").isNotNull();
		requireThat(platform, "platform").isNotNull();
		requireThat(environment, "environment").isNotNull();
		requireThat(clock, "clock").isNotNull();
		Path folderParent = socketParent(parent.toAbsolutePath(), platform);
		Path folder = Files.createTempDirectory(folderParent, "login-", SecretFile.ownerOnlyFolder(folderParent));
		Path socket = folder.resolve(SOCKET_NAME);
		if (socket.toString().getBytes(UTF_8).length > MAXIMUM_SOCKET_PATH)
		{
			Files.delete(folder);
			throw new IOException("The login service's socket " + socket + " is longer than a socket's address " +
				"holds. Run the session in a project, or with a temporary folder, whose path is shorter.");
		}
		ServerSocketChannel channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
		try
		{
			channel.bind(UnixDomainSocketAddress.of(socket));
		}
		catch (IOException e)
		{
			channel.close();
			Files.deleteIfExists(folder);
			throw e;
		}
		CodexLoginService service = new CodexLoginService(folder, channel, topLevel, source, platform, environment,
			clock);
		service.acceptor.start();
		return service;
	}

	/**
	 * Returns the folder in which the service creates its own folder: the first of the server's folder, the temporary
	 * folder and, on platforms other than Windows, {@code /tmp}, whose path leaves room in a socket's address for the
	 * service's folder and its socket. A session directory, and a temporary folder that a session chose, can be too
	 * deep, while {@code /tmp} exists on every such platform with a short path.
	 *
	 * @param parent   the server's folder, as an absolute path
	 * @param platform the platform that the server runs on
	 * @return the folder
	 * @throws IOException if no folder's path is short enough
	 */
	private static Path socketParent(Path parent, Platform platform) throws IOException
	{
		List<Path> candidates = new ArrayList<>(List.of(parent,
			Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath()));
		if (!platform.isWindows())
			candidates.add(Path.of("/tmp"));
		for (Path candidate : candidates)
		{
			// The service's folder adds a separator and up to 25 bytes of name, and the socket two more
			if (candidate.toString().getBytes(UTF_8).length <= MAXIMUM_SOCKET_PATH - 28 &&
				Files.isDirectory(candidate))
			{
				return candidate;
			}
		}
		throw new IOException("The login service cannot create its socket: the paths of " + candidates + " are " +
			"all longer than a socket's address holds. Run the session in a project, or with a temporary folder, " +
			"whose path is shorter.");
	}

	/**
	 * Returns the folder that holds the socket, which every session's commands are denied.
	 *
	 * @return the folder
	 */
	public Path folder()
	{
		return folder;
	}

	/**
	 * Returns the socket.
	 *
	 * @return the socket
	 */
	public Path socket()
	{
		return socket;
	}

	/**
	 * Returns the variables that the server adds to each runner's environment, which each runner passes on to its
	 * child runners and never to a harness.
	 *
	 * @return the variables
	 */
	public Map<String, String> runnerVariables()
	{
		return Map.of(SOCKET_VARIABLE, socket.toString(), KEY_VARIABLE, key);
	}

	/**
	 * Returns a new random secret.
	 *
	 * @return the secret, as unpadded URL-safe base64
	 */
	private String newSecret()
	{
		byte[] bytes = new byte[CREDENTIAL_BYTES];
		random.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	/**
	 * Accepts connections until the service closes.
	 */
	// serve() closes each connection
	@SuppressWarnings("PMD.CloseResource")
	private void accept()
	{
		while (!closed)
		{
			SocketChannel connection;
			try
			{
				connection = channel.accept();
			}
			catch (IOException _)
			{
				// The channel closed with the service
				return;
			}
			executor.submit(() -> serve(connection));
		}
	}

	/**
	 * Answers a connection's request.
	 *
	 * @param connection the connection
	 */
	private void serve(SocketChannel connection)
	{
		JsonMapper json = JsonMapper.builder().build();
		try (connection)
		{
			// Closing either stream would close the connection before the answer
			InputStream input = Channels.newInputStream(connection);
			byte[] request = input.readNBytes(CodexLoginClient.MAXIMUM_MESSAGE_BYTES + 1);
			ObjectNode answer;
			try
			{
				if (request.length > CodexLoginClient.MAXIMUM_MESSAGE_BYTES)
					throw new LoginRefusal("The login service received a request that is larger than it reads");
				answer = answer(json, json.readTree(new String(request, UTF_8)));
			}
			catch (IOException e)
			{
				// A refusal, or a request that is malformed; neither message holds a secret
				answer = json.createObjectNode();
				answer.put("status", "refused");
				answer.put("reason", e.getMessage());
			}
			catch (JacksonException _)
			{
				answer = json.createObjectNode();
				answer.put("status", "refused");
				answer.put("reason", "The login service received a request that is not JSON");
			}
			catch (RuntimeException e)
			{
				// The reason names the failure's class alone, since its message could quote a request
				answer = json.createObjectNode();
				answer.put("status", "refused");
				answer.put("reason", "The login service failed to answer: " + e.getClass().getName());
			}
			OutputStream output = Channels.newOutputStream(connection);
			output.write((json.writeValueAsString(answer) + "\n").getBytes(UTF_8));
			output.flush();
		}
		catch (IOException _)
		{
			// The client ended the connection; it receives no answer
		}
	}

	/**
	 * Answers a request.
	 *
	 * @param json    the mapper
	 * @param request the request
	 * @return the answer
	 * @throws LoginRefusal if the request is refused
	 * @throws IOException  if the request is malformed
	 */
	private ObjectNode answer(JsonMapper json, JsonNode request) throws IOException
	{
		String operation = request.path("op").asString("");
		return switch (operation)
		{
			case "open" -> open(json, request);
			case "token" ->
			{
				ObjectNode answer = json.createObjectNode();
				answer.put("status", "token");
				answer.put("token", token(session(request)));
				yield answer;
			}
			case "close" ->
			{
				String credential = request.path("credential").asString("");
				Session session = sessions.remove(credential);
				if (session != null && session.forwarderToken().isPresent())
					forwarder().unregister(session.forwarderToken().get());
				ObjectNode answer = json.createObjectNode();
				answer.put("status", "closed");
				yield answer;
			}
			default -> throw new LoginRefusal("The login service does not know the operation " + operation);
		};
	}

	/**
	 * Returns the session whose credential a request presents.
	 *
	 * @param request the request
	 * @return the session
	 * @throws LoginRefusal if the request presents no credential of an open session
	 */
	private Session session(JsonNode request) throws LoginRefusal
	{
		Session session = sessions.get(request.path("credential").asString(""));
		if (session == null)
		{
			throw new LoginRefusal("The login service serves no session with that credential: the session has ended " +
				"or its key file is not the one that its runner wrote");
		}
		return session;
	}

	/**
	 * Opens a session's login.
	 *
	 * @param json    the mapper
	 * @param request the request
	 * @return the answer
	 * @throws LoginRefusal if the login is refused
	 * @throws IOException  if the request is malformed
	 */
	// The service's close() closes the forwarder
	@SuppressWarnings("PMD.CloseResource")
	private ObjectNode open(JsonMapper json, JsonNode request) throws IOException
	{
		if (!MessageDigest.isEqual(request.path("key").asString("").getBytes(UTF_8), key.getBytes(UTF_8)))
			throw new LoginRefusal("The login service opens a login only for a runner that its server started");
		CodexLoginPlan plan = CodexLoginPlan.fromJson(request.path("plan"));
		ObjectNode answer = json.createObjectNode();
		answer.put("status", "open");
		boolean inKeyring = false;
		Optional<String> forwarderToken = Optional.empty();
		switch (plan)
		{
			case CodexLoginPlan.Stored stored ->
			{
				inKeyring = source.inKeyring(stored);
				if (inKeyring)
					requireKeyringReach(stored);
				CodexStoredLogin login = source.read(stored, inKeyring);
				answer.put("sign_in", login.signIn());
				login.accountId().ifPresent(id -> answer.put("account_id", id));
			}
			case CodexLoginPlan.Command _ ->
			{
				// The command runs when the session first asks for a token
			}
			case CodexLoginPlan.Forward forward ->
			{
				String token = newSecret();
				Optional<CodexHeaderForwarder.Bearer> bearer = Optional.empty();
				if (forward.bearer().isPresent())
				{
					String value = forward.bearer().get();
					bearer = Optional.of(() -> value);
				}
				else if (forward.bearerCommand().isPresent())
				{
					CodexLoginPlan.Command command = forward.bearerCommand().get();
					bearer = Optional.of(() -> commandToken(command));
				}
				CodexHeaderForwarder started = forwarder();
				started.register(token, forward.baseUrl(), forward.headers(), bearer);
				forwarderToken = Optional.of(token);
				answer.put("forwarder", started.address().toString());
			}
		}
		answer.put("in_keyring", inKeyring);
		String credential = newSecret();
		sessions.put(credential, new Session(plan, inKeyring, forwarderToken));
		answer.put("credential", credential);
		return answer;
	}

	/**
	 * Refuses a keyring login when the top-level session cannot reach the keyring.
	 *
	 * @param stored the plan
	 * @throws LoginRefusal if the top-level session cannot reach the keyring, or its policy cannot be read
	 */
	private void requireKeyringReach(CodexLoginPlan.Stored stored) throws LoginRefusal
	{
		Optional<String> unreachable;
		try
		{
			unreachable = KeyringReach.unreachable(topLevel.read(), platform, environment);
		}
		catch (LoginRefusal e)
		{
			throw e;
		}
		catch (IOException e)
		{
			throw new LoginRefusal("CAT does not start the session: the source home " + stored.sourceHome() + " keeps " +
				"its Codex login in " + source.keyring().name() + ", and the request server cannot read the top-level " +
				"session's sandbox policy to tell whether that session can reach it: " + e.getMessage(), e);
		}
		if (unreachable.isEmpty())
			return;
		throw new LoginRefusal("CAT does not start the session: the source home " + stored.sourceHome() + " keeps its " +
			"Codex login in " + source.keyring().name() + ", and the top-level session cannot reach that keyring: " +
			unreachable.get() + ". No nested session uses a keyring that the top-level session cannot reach. Start " +
			"the top-level session with that access, or set cli_auth_credentials_store = \"file\" in " +
			stored.codexHome().resolve("config.toml") + " and sign in to Codex again, or name another source home " +
			"with --home.");
	}

	/**
	 * Returns a session's current token.
	 *
	 * @param session the session
	 * @return the token
	 * @throws LoginRefusal if no token can be served
	 */
	private String token(Session session) throws LoginRefusal
	{
		return switch (session.plan())
		{
			case CodexLoginPlan.Stored stored ->
			{
				CodexStoredLogin login = source.read(stored, session.inKeyring());
				if (login.expired(clock.instant()))
				{
					String where;
					if (session.inKeyring())
						where = source.keyring().name() + " of " + stored.codexHome();
					else
						where = stored.codexHome().resolve(CodexLoginSource.LOGIN_FILE).toString();
					throw new LoginRefusal("The ChatGPT sign-in that " + where + " holds, the top Codex login of the " +
						"source home " + stored.sourceHome() + ", has expired, and the source holds no newer one. Open " +
						"Codex on that source home, or sign in to it again, so that it renews the sign-in.");
				}
				yield login.secret();
			}
			case CodexLoginPlan.Command command -> commandToken(command);
			case CodexLoginPlan.Forward _ -> session.forwarderToken().orElseThrow();
		};
	}

	/**
	 * Returns a command's output, running the command when its last output is older than its refresh interval or when
	 * the interval is {@code 0}.
	 *
	 * @param command the command
	 * @return the output, stripped of surrounding white space
	 * @throws LoginRefusal if the command fails, times out or prints nothing
	 */
	private String commandToken(CodexLoginPlan.Command command) throws LoginRefusal
	{
		CachedToken cached = tokens.computeIfAbsent(command, _ -> new CachedToken());
		cached.lock.lock();
		try
		{
			Instant now = clock.instant();
			if (cached.token != null && command.refreshInterval() > 0 &&
				now.isBefore(cached.fetched.plusMillis(command.refreshInterval())))
			{
				return cached.token;
			}
			String token = run(command);
			cached.token = token;
			cached.fetched = now;
			return token;
		}
		finally
		{
			cached.lock.unlock();
		}
	}

	/**
	 * Runs a command with the source home as its home folder.
	 *
	 * @param command the command
	 * @return its output, stripped of surrounding white space
	 * @throws LoginRefusal if the command fails, times out or prints nothing
	 */
	private String run(CodexLoginPlan.Command command) throws LoginRefusal
	{
		List<String> line = new ArrayList<>();
		line.add(command.program());
		for (String argument : command.arguments())
		{
			// Java would pass an argument that holds a space or a quote to a Windows program without escaping its
			// quotes, and an empty one as nothing
			if (platform.isWindows())
				line.add(CommandLineArguments.quote(argument));
			else
				line.add(argument);
		}
		ProcessBuilder builder = new ProcessBuilder(line).redirectError(ProcessBuilder.Redirect.DISCARD).
			directory(command.directory().orElse(command.home()).toFile());
		Map<String, String> variables = new HashMap<>(environment);
		variables.put("HOME", command.home().toString());
		if (platform.isWindows())
			variables.put("USERPROFILE", command.home().toString());
		builder.environment().clear();
		builder.environment().putAll(variables);
		String described = "The token command " + command.program() + " of the source home " + command.home();
		Process process;
		try
		{
			process = builder.start();
		}
		catch (IOException e)
		{
			throw new LoginRefusal(described + " cannot run: " + e.getClass().getSimpleName() + ". Correct the " +
				"command in the source's config.toml.", e);
		}
		try
		{
			process.getOutputStream().close();
			Future<byte[]> output = executor.submit(() ->
			{
				try (InputStream stream = process.getInputStream())
				{
					return stream.readNBytes(MAXIMUM_TOKEN_BYTES);
				}
			});
			if (!process.waitFor(command.timeoutMillis(), TimeUnit.MILLISECONDS))
			{
				throw new LoginRefusal(described + " did not finish within " + command.timeoutMillis() + " ms. " +
					"Correct the command, or give it a longer timeout_ms.");
			}
			if (process.exitValue() != 0)
			{
				throw new LoginRefusal(described + " ended with status " + process.exitValue() + ". Run it yourself on " +
					"that source home to see why.");
			}
			byte[] bytes = output.get(command.timeoutMillis(), TimeUnit.MILLISECONDS);
			String token = new String(bytes, UTF_8).strip();
			if (token.isEmpty())
				throw new LoginRefusal(described + " printed no token.");
			return token;
		}
		catch (IOException | ExecutionException | TimeoutException e)
		{
			throw new LoginRefusal(described + " failed: " + e.getClass().getSimpleName(), e);
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			throw new LoginRefusal(described + " was interrupted", e);
		}
		finally
		{
			process.descendants().forEach(ProcessHandle::destroyForcibly);
			process.destroyForcibly();
		}
	}

	/**
	 * Returns the forwarder, starting it on first use.
	 *
	 * @return the forwarder
	 * @throws IOException if it cannot start
	 */
	private CodexHeaderForwarder forwarder() throws IOException
	{
		forwarderLock.lock();
		try
		{
			if (forwarder == null)
				forwarder = CodexHeaderForwarder.start();
			return forwarder;
		}
		finally
		{
			forwarderLock.unlock();
		}
	}

	@Override
	public void close() throws IOException
	{
		closed = true;
		sessions.clear();
		tokens.clear();
		channel.close();
		executor.shutdownNow();
		forwarderLock.lock();
		try
		{
			if (forwarder != null)
				forwarder.close();
		}
		finally
		{
			forwarderLock.unlock();
		}
		Files.deleteIfExists(socket);
		Files.deleteIfExists(folder);
	}

	@Override
	public String toString()
	{
		return "CodexLoginService{socket=" + socket + ", sessions=" + sessions.size() + "}";
	}
}
