package com.holdmyspot.cat.codex.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.optimizer.port.HostLogin;
import com.holdmyspot.cat.common.runner.AuthenticationEvidence;
import com.holdmyspot.cat.common.runner.AuthenticationOptions;
import com.holdmyspot.cat.common.runner.HarnessEnvironment;
import com.holdmyspot.cat.common.runner.SecretFile;
import com.holdmyspot.cat.common.runner.login.CodexLoginClient;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.toml.TomlMapper;

/**
 * The opened login of one nested Codex session: what its Codex folder receives and what states its class. The
 * session's {@code config.toml} stores no login and selects a provider whose token command is CAT's; its key file holds
 * the key that cannot change, or its credential for the request server's login service, which the token command
 * presents. Closing the login ends the credential, so that the service serves the session no more tokens.
 * <p>
 * The description of an instance holds no secret.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class CodexSessionLogin implements AutoCloseable
{
	/**
	 * How long Codex waits for CAT's token command when the login service runs no source command, in milliseconds. A
	 * Java program starts slower than Codex's default timeout allows on a loaded machine.
	 */
	static final long TOKEN_COMMAND_TIMEOUT_MS = 30_000;
	/**
	 * How long Codex keeps the token that CAT's token command printed, in milliseconds, which is Codex's default.
	 */
	private static final long REFRESH_INTERVAL_MS = 300_000;

	private final String providerId;
	private final ObjectNode provider;
	private final String webSearch;
	private final String key;
	private final Path socket;
	private final String credential;
	private final long commandTimeout;
	private final String modelHost;
	private final HostLogin loginClass;
	private final AuthenticationEvidence evidence;
	private final AtomicBoolean closed = new AtomicBoolean();

	/**
	 * Creates a login.
	 *
	 * @param providerId     the identifier of the session's provider
	 * @param provider       the table of the session's provider, without its token command
	 * @param webSearch      the session's {@code web_search} value
	 * @param key            the key that the key file holds, or {@code null} for a login that the service serves
	 * @param socket         the login service's socket, or {@code null} for a key
	 * @param credential     the session's credential for the login service, or {@code null} for a key
	 * @param commandTimeout how long Codex waits for the token command, in milliseconds
	 * @param modelHost      the host of the session's model requests
	 * @param loginClass     the class of the session's login
	 * @param evidence       the record of the session's authentication
	 */
	@SuppressWarnings("checkstyle:ParameterNumber")
	private CodexSessionLogin(String providerId, ObjectNode provider, String webSearch, String key, Path socket,
		String credential, long commandTimeout, String modelHost, HostLogin loginClass, AuthenticationEvidence evidence)
	{
		this.providerId = providerId;
		this.provider = provider;
		this.webSearch = webSearch;
		this.key = key;
		this.socket = socket;
		this.credential = credential;
		this.commandTimeout = commandTimeout;
		this.modelHost = modelHost;
		this.loginClass = loginClass;
		this.evidence = evidence;
	}

	/**
	 * Returns the login of a key that cannot change, which the session's key file holds.
	 *
	 * @param providerId the identifier of the session's provider
	 * @param provider   the table of the session's provider, without its token command
	 * @param webSearch  the session's {@code web_search} value
	 * @param key        the key
	 * @param modelHost  the host of the session's model requests
	 * @param loginClass the class of the session's login
	 * @param evidence   the record of the session's authentication
	 * @return the login
	 */
	static CodexSessionLogin ofKey(String providerId, ObjectNode provider, String webSearch, String key,
		String modelHost, HostLogin loginClass, AuthenticationEvidence evidence)
	{
		return new CodexSessionLogin(providerId, provider, webSearch, key, null, null, TOKEN_COMMAND_TIMEOUT_MS,
			modelHost, loginClass, evidence);
	}

	/**
	 * Returns a login that the request server's login service serves.
	 *
	 * @param providerId     the identifier of the session's provider
	 * @param provider       the table of the session's provider, without its token command
	 * @param webSearch      the session's {@code web_search} value
	 * @param socket         the service's socket
	 * @param credential     the session's credential for the service
	 * @param commandTimeout how long Codex waits for the token command, in milliseconds
	 * @param modelHost      the host of the session's model requests
	 * @param loginClass     the class of the session's login
	 * @param evidence       the record of the session's authentication
	 * @return the login
	 */
	@SuppressWarnings("checkstyle:ParameterNumber")
	static CodexSessionLogin ofService(String providerId, ObjectNode provider, String webSearch, Path socket,
		String credential, long commandTimeout, String modelHost, HostLogin loginClass, AuthenticationEvidence evidence)
	{
		return new CodexSessionLogin(providerId, provider, webSearch, null, socket, credential, commandTimeout,
			modelHost, loginClass, evidence);
	}

	/**
	 * Returns the variables that the runner adds to the app-server's environment for the session's authentication:
	 * {@code CAT_HOST_LOGIN}, which states the class of the session's login to the session's commands.
	 *
	 * @return the variables
	 */
	public Map<String, String> environment()
	{
		return Map.of(HarnessEnvironment.HOST_LOGIN_VARIABLE, AuthenticationOptions.classValue(loginClass));
	}

	/**
	 * Returns the host that the session's Codex sends its model requests to, or that CAT's forwarder passes them on to.
	 *
	 * @return the host, in lower case
	 */
	public String modelHost()
	{
		return modelHost;
	}

	/**
	 * Returns the class of the session's login.
	 *
	 * @return the class
	 */
	public HostLogin loginClass()
	{
		return loginClass;
	}

	/**
	 * Returns the record of the session's authentication: the source home, the names of the source settings that
	 * chose the login and of the logins that CAT read, and the class of the login.
	 *
	 * @return the evidence
	 */
	public AuthenticationEvidence evidence()
	{
		return evidence;
	}

	/**
	 * Returns the settings that the session's {@code config.toml} receives, as TOML dotted keys that belong at the
	 * file's root, before any table header: {@code model_provider}, {@code cli_auth_credentials_store = "ephemeral"},
	 * the source's {@code web_search} value and the session's provider, whose {@code supports_websockets} is
	 * {@code false} and whose {@code auth} token command is CAT's, which prints the key file for a key and otherwise
	 * asks the login service.
	 *
	 * @param codexFolder the session's Codex folder, which holds the key file
	 * @return the TOML text
	 * @throws NullPointerException if {@code codexFolder} is null
	 */
	public String settings(Path codexFolder)
	{
		requireThat(codexFolder, "codexFolder").isNotNull();
		TomlMapper mapper = TomlMapper.builder().build();
		ObjectNode session = mapper.createObjectNode();
		session.put("model_provider", providerId);
		session.put("cli_auth_credentials_store", "ephemeral");
		session.put("web_search", webSearch);
		ObjectNode table = provider.deepCopy();
		table.put("supports_websockets", false);
		Path keyFile = CodexAuthentication.keyFile(codexFolder).toAbsolutePath();
		CodexKeyFileCommand.Invocation invocation;
		if (key != null)
			invocation = CodexKeyFileCommand.invocation(keyFile);
		else
			invocation = CodexTokenCommand.invocation(keyFile);
		ObjectNode tokenCommand = table.putObject("auth");
		tokenCommand.put("command", invocation.program());
		ArrayNode arguments = tokenCommand.putArray("args");
		for (String argument : invocation.arguments())
			arguments.add(argument);
		tokenCommand.put("timeout_ms", commandTimeout);
		tokenCommand.put("refresh_interval_ms", REFRESH_INTERVAL_MS);
		session.putObject("model_providers").set(providerId, table);
		String text = mapper.writeValueAsString(session);
		if (!text.endsWith("\n"))
			text += System.lineSeparator();
		return text;
	}

	/**
	 * Writes the session's key file before its app-server starts or resumes. The file is created new with access for its
	 * owner alone, set as it is created, and receives the key, or the login service's socket and the session's
	 * credential, from memory. A key file that an earlier launch left is removed first.
	 *
	 * @param codexFolder the session's Codex folder, which exists
	 * @throws NullPointerException if {@code codexFolder} is null
	 * @throws IOException          if the file cannot be removed, created or written; a file that was created is then
	 *                              removed
	 */
	public void writeKeyFile(Path codexFolder) throws IOException
	{
		Path file = CodexAuthentication.keyFile(codexFolder);
		CodexAuthentication.removeKeyFile(codexFolder);
		byte[] bytes;
		if (key != null)
			bytes = key.getBytes(UTF_8);
		else
			bytes = CodexTokenCommand.keyFileContent(socket, credential);
		try
		{
			SecretFile.create(file, bytes);
		}
		finally
		{
			Arrays.fill(bytes, (byte) 0);
		}
	}

	/**
	 * Ends the session's credential at the login service, so that it serves the session no more tokens. Closing again
	 * has no effect.
	 *
	 * @throws IOException if the service cannot be reached; the credential then ends with the service
	 */
	@Override
	public void close() throws IOException
	{
		if (socket == null || !closed.compareAndSet(false, true))
			return;
		CodexLoginClient.close(socket, credential);
	}

	@Override
	public String toString()
	{
		String route;
		if (key != null)
			route = "key";
		else
			route = "service at " + socket;
		return "CodexSessionLogin{provider=" + providerId + ", route=" + route + ", modelHost=" + modelHost +
			", loginClass=" + loginClass + ", parts=" + evidence.parts() + "}";
	}
}
