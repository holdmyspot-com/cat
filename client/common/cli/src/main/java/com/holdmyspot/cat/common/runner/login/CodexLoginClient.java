package com.holdmyspot.cat.common.runner.login;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.BoundedInput;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.net.URI;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Asks the login service of a request server over its socket: a runner opens and closes a session's login, and the
 * token command of a session asks for the current token.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class CodexLoginClient
{
	/**
	 * The most bytes of an answer that the client reads.
	 */
	static final int MAXIMUM_MESSAGE_BYTES = 1_048_576;

	/**
	 * What the service answers a runner that opens a session's login.
	 *
	 * @param credential the session's credential, which the token command presents
	 * @param signIn     {@code true} if the login is a ChatGPT sign-in, {@code false} for a key or a command's token
	 * @param accountId  the sign-in's account ID, or empty
	 * @param inKeyring  {@code true} if the operating system's keyring holds the login
	 * @param forwarder  the base URL of the forwarder that the session's provider names, or empty if the session
	 *                   reaches its provider directly
	 */
	public record Opened(String credential, boolean signIn, Optional<String> accountId, boolean inKeyring,
		Optional<URI> forwarder)
	{
		/**
		 * Creates an answer.
		 *
		 * @param credential the session's credential
		 * @param signIn     {@code true} for a ChatGPT sign-in
		 * @param accountId  the sign-in's account ID, or empty
		 * @param inKeyring  {@code true} if the keyring holds the login
		 * @param forwarder  the forwarder's base URL, or empty
		 * @throws NullPointerException     if any of the arguments are null
		 * @throws IllegalArgumentException if {@code credential} is blank
		 */
		public Opened
		{
			requireThat(credential, "credential").isNotBlank();
			requireThat(accountId, "accountId").isNotNull();
			requireThat(forwarder, "forwarder").isNotNull();
		}

		@Override
		public String toString()
		{
			return "Opened{signIn=" + signIn + ", accountId=" + accountId + ", inKeyring=" + inKeyring +
				", forwarder=" + forwarder + "}";
		}
	}

	/**
	 * Prevents construction.
	 */
	private CodexLoginClient()
	{
	}

	/**
	 * Opens a session's login.
	 *
	 * @param socket the service's socket
	 * @param key    the key that the server gave its runners
	 * @param plan   what the session's login is
	 * @return the answer
	 * @throws NullPointerException if any of the arguments are null
	 * @throws LoginRefusal         if the service refuses; the reason holds no secret
	 * @throws IOException          if the service cannot be reached
	 */
	public static Opened open(Path socket, String key, CodexLoginPlan plan) throws IOException
	{
		requireThat(socket, "socket").isNotNull();
		requireThat(key, "key").isNotNull();
		requireThat(plan, "plan").isNotNull();
		JsonMapper json = JsonMapper.builder().build();
		ObjectNode request = json.createObjectNode();
		request.put("op", "open");
		request.put("key", key);
		request.set("plan", plan.toJson(json));
		JsonNode answer = call(socket, request);
		Optional<URI> forwarder = Optional.ofNullable(answer.path("forwarder").asString(null)).map(URI::create);
		return new Opened(answer.path("credential").asString(""), answer.path("sign_in").asBoolean(false),
			Optional.ofNullable(answer.path("account_id").asString(null)), answer.path("in_keyring").asBoolean(false),
			forwarder);
	}

	/**
	 * Asks for a session's current token.
	 *
	 * @param socket     the service's socket
	 * @param credential the session's credential
	 * @return the token
	 * @throws NullPointerException if any of the arguments are null
	 * @throws LoginRefusal         if the service refuses; the reason holds no secret
	 * @throws IOException          if the service cannot be reached
	 */
	public static String token(Path socket, String credential) throws IOException
	{
		requireThat(socket, "socket").isNotNull();
		requireThat(credential, "credential").isNotNull();
		ObjectNode request = JsonMapper.builder().build().createObjectNode();
		request.put("op", "token");
		request.put("credential", credential);
		String token = call(socket, request).path("token").asString("");
		if (token.isEmpty())
			throw new LoginRefusal("The login service answered with no token");
		return token;
	}

	/**
	 * Ends a session's login, so that its credential no longer serves a token.
	 *
	 * @param socket     the service's socket
	 * @param credential the session's credential
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the service cannot be reached
	 */
	public static void close(Path socket, String credential) throws IOException
	{
		requireThat(socket, "socket").isNotNull();
		requireThat(credential, "credential").isNotNull();
		ObjectNode request = JsonMapper.builder().build().createObjectNode();
		request.put("op", "close");
		request.put("credential", credential);
		call(socket, request);
	}

	/**
	 * Sends a request and reads its answer.
	 *
	 * @param socket  the service's socket
	 * @param request the request
	 * @return the answer
	 * @throws LoginRefusal if the service refuses
	 * @throws IOException  if the service cannot be reached or answers with no JSON object
	 */
	// Closing the channel closes its streams
	@SuppressWarnings("PMD.CloseResource")
	static JsonNode call(Path socket, ObjectNode request) throws IOException
	{
		JsonMapper json = JsonMapper.builder().build();
		try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX))
		{
			channel.connect(UnixDomainSocketAddress.of(socket));
			OutputStream output = Channels.newOutputStream(channel);
			output.write((json.writeValueAsString(request) + "\n").getBytes(UTF_8));
			output.flush();
			channel.shutdownOutput();
			InputStream input = Channels.newInputStream(channel);
			String text = BoundedInput.readUtf8(input, socket, MAXIMUM_MESSAGE_BYTES, "login service answer");
			JsonNode answer;
			try
			{
				answer = json.readTree(text);
			}
			catch (JacksonException _)
			{
				throw new IOException("The login service at " + socket + " answered with no JSON");
			}
			if (!answer.isObject())
				throw new IOException("The login service at " + socket + " ended the connection without an answer");
			if (answer.path("status").asString("").equals("refused"))
				throw new LoginRefusal(answer.path("reason").asString("The login service refused"));
			return answer;
		}
	}
}
