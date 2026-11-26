package com.holdmyspot.cat.common.runner.login;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.Platform;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The macOS keyring, the Keychain, whose item of the service {@code Codex Auth} trusts only the signed {@code codex}
 * that created it. The server reads no item's value itself: it asks {@code security} whether the item exists, which
 * reads its attributes alone, and starts a short-lived app-server of the user's own {@code codex}, which reads the
 * Keychain without a prompt, asking it for the login's token with {@code getAuthStatus}, {@code includeToken} true and
 * {@code refreshToken} false. The app-server returns the access token or the key and never the refresh token.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class MacosKeychain implements CodexKeyring
{
	/**
	 * The program that reads the Keychain's attributes.
	 */
	public static final String SECURITY = "/usr/bin/security";
	/**
	 * How long one read may take.
	 */
	private static final Duration TIMEOUT = Duration.ofSeconds(30);
	/**
	 * The status with which {@code security} reports that no item matches.
	 */
	private static final int NOT_FOUND = 44;

	/**
	 * Starts a program.
	 */
	@FunctionalInterface
	public interface Programs
	{
		/**
		 * Starts a program whose standard error is discarded.
		 *
		 * @param command     the program and its arguments
		 * @param environment the program's whole environment
		 * @return the process
		 * @throws IOException if the program cannot start
		 */
		Process start(List<String> command, Map<String, String> environment) throws IOException;
	}

	private final Optional<Path> codex;
	private final Map<String, String> environment;
	private final Platform platform;
	private final Programs programs;

	/**
	 * Creates the keyring.
	 *
	 * @param codex       the user's signed {@code codex}, or empty if the server finds none
	 * @param environment the server's environment, which the app-server receives with {@code CODEX_HOME} set
	 * @param platform    the platform, which spells the Codex folder's canonical path
	 * @param programs    starts {@code security} and the app-server
	 * @throws NullPointerException if any of the arguments are null
	 */
	public MacosKeychain(Optional<Path> codex, Map<String, String> environment, Platform platform, Programs programs)
	{
		requireThat(codex, "codex").isNotNull();
		requireThat(environment, "environment").isNotNull();
		requireThat(platform, "platform").isNotNull();
		requireThat(programs, "programs").isNotNull();
		this.codex = codex;
		this.environment = Map.copyOf(environment);
		this.platform = platform;
		this.programs = programs;
	}

	/**
	 * Returns the keyring that runs the {@code codex} on the server's {@code PATH}.
	 *
	 * @param environment the server's environment
	 * @param platform    the platform
	 * @return the keyring
	 */
	static MacosKeychain of(Map<String, String> environment, Platform platform)
	{
		Optional<Path> codex = Optional.empty();
		for (String folder : environment.getOrDefault("PATH", "").split(":"))
		{
			if (folder.isBlank())
				continue;
			Path candidate = Path.of(folder, "codex");
			if (Files.isExecutable(candidate))
			{
				codex = Optional.of(candidate);
				break;
			}
		}
		return new MacosKeychain(codex, environment, platform, (command, variables) ->
		{
			ProcessBuilder builder = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD);
			builder.environment().clear();
			builder.environment().putAll(variables);
			return builder.start();
		});
	}

	@Override
	public String name()
	{
		return "the Keychain item \"" + SERVICE + "\"";
	}

	// Each process is destroyed when its read ends
	@SuppressWarnings("PMD.CloseResource")
	@Override
	public boolean holds(Path codexHome) throws LoginRefusal
	{
		requireThat(codexHome, "codexHome").isNotNull();
		String account = CodexLoginSource.account(CodexLoginSource.canonical(codexHome, platform));
		try
		{
			Process process = programs.start(List.of(SECURITY, "find-generic-password", "-s", SERVICE, "-a", account),
				environment);
			try
			{
				process.getOutputStream().close();
				// The attributes that it prints hold no secret, and the server needs none of them
				process.getInputStream().transferTo(OutputStream.nullOutputStream());
				if (!process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
					throw new LoginRefusal(SECURITY + " did not answer within " + TIMEOUT.toSeconds() + " seconds");
				int status = process.exitValue();
				if (status == 0)
					return true;
				if (status == NOT_FOUND)
					return false;
				throw new LoginRefusal("The request server cannot ask the Keychain for " + name() + " of the Codex " +
					"folder " + codexHome + ": " + SECURITY + " ended with status " + status + ".");
			}
			finally
			{
				process.destroyForcibly();
			}
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			throw new LoginRefusal("The request server was interrupted while it asked the Keychain", e);
		}
		catch (LoginRefusal e)
		{
			throw e;
		}
		catch (IOException e)
		{
			throw new LoginRefusal("The request server cannot ask the Keychain for " + name() + " of the Codex folder " +
				codexHome + ": " + e.getMessage(), e);
		}
	}

	// Each process is destroyed when its read ends
	@SuppressWarnings("PMD.CloseResource")
	@Override
	public CodexStoredLogin read(Path codexHome) throws LoginRefusal
	{
		requireThat(codexHome, "codexHome").isNotNull();
		if (codex.isEmpty())
		{
			throw new LoginRefusal("The request server finds no codex on its PATH, which alone reads " + name() +
				" of the Codex folder " + codexHome + " without a prompt. Put the signed codex on the PATH, or set " +
				"cli_auth_credentials_store = \"file\" in that folder's config.toml and sign in to Codex again.");
		}
		Map<String, String> variables = new HashMap<>(environment);
		variables.put("CODEX_HOME", codexHome.toString());
		Process process;
		try
		{
			process = programs.start(List.of(codex.get().toString(), "app-server"), variables);
		}
		catch (IOException e)
		{
			throw new LoginRefusal("The request server cannot start " + codex.get() + " app-server to read " + name() +
				" of the Codex folder " + codexHome + ": " + e.getMessage(), e);
		}
		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor())
		{
			Future<String> token = executor.submit(() -> authToken(process));
			return CodexStoredLogin.parse(token.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
				name() + " of the Codex folder " + codexHome);
		}
		catch (TimeoutException _)
		{
			throw new LoginRefusal(codex.get() + " app-server did not return the login of " + name() + " of the " +
				"Codex folder " + codexHome + " within " + TIMEOUT.toSeconds() + " seconds; the Keychain may be locked " +
				"or prompting. Unlock it, or set cli_auth_credentials_store = \"file\" in that folder's config.toml and " +
				"sign in to Codex again.");
		}
		catch (ExecutionException e)
		{
			if (e.getCause() instanceof LoginRefusal refusal)
				throw refusal;
			throw new LoginRefusal(codex.get() + " app-server failed to return the login of " + name() + " of the " +
				"Codex folder " + codexHome + ": " + e.getCause().getClass().getSimpleName(), e.getCause());
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			throw new LoginRefusal("The request server was interrupted while it read the Keychain", e);
		}
		finally
		{
			process.destroyForcibly();
		}
	}

	/**
	 * Asks an app-server for the login's token.
	 *
	 * @param process the app-server
	 * @return the login's text, as {@code auth.json} would hold the token: an access token under {@code tokens} for a
	 *   ChatGPT sign-in, otherwise an API key
	 * @throws IOException if the app-server ends or answers with no token
	 */
	// The caller destroys the process, which closes its streams
	@SuppressWarnings("PMD.CloseResource")
	private static String authToken(Process process) throws IOException
	{
		JsonMapper json = JsonMapper.builder().build();
		OutputStream input = process.getOutputStream();
		ObjectNode initialize = json.createObjectNode();
		initialize.put("id", 1);
		initialize.put("method", "initialize");
		ObjectNode client = initialize.putObject("params").putObject("clientInfo");
		client.put("name", "cat");
		client.put("title", "CAT");
		client.put("version", "1");
		send(input, json, initialize);
		ObjectNode initialized = json.createObjectNode();
		initialized.put("method", "initialized");
		send(input, json, initialized);
		ObjectNode status = json.createObjectNode();
		status.put("id", 2);
		status.put("method", "getAuthStatus");
		ObjectNode params = status.putObject("params");
		params.put("includeToken", true);
		params.put("refreshToken", false);
		send(input, json, status);
		BufferedReader output = new BufferedReader(new InputStreamReader(process.getInputStream(), UTF_8));
		while (true)
		{
			String line = output.readLine();
			if (line == null)
				throw new LoginRefusal("The app-server ended before it returned the login");
			JsonNode message;
			try
			{
				message = json.readTree(line);
			}
			catch (JacksonException _)
			{
				continue;
			}
			if (message.path("id").asInt(-1) != 2)
				continue;
			input.close();
			JsonNode result = message.path("result");
			String token = result.path("authToken").asString("");
			if (token.isBlank())
			{
				throw new LoginRefusal("The app-server returned no login: the Keychain holds none it can read, or Codex " +
					"is signed out");
			}
			ObjectNode login = json.createObjectNode();
			String method = result.path("authMethod").asString("");
			if (method.equalsIgnoreCase("apikey") || method.equalsIgnoreCase("api_key"))
				login.put("OPENAI_API_KEY", token);
			else
				login.putObject("tokens").put("access_token", token);
			return json.writeValueAsString(login);
		}
	}

	/**
	 * Sends a message to the app-server.
	 *
	 * @param input   the app-server's standard input
	 * @param json    the mapper
	 * @param message the message
	 * @throws IOException if the message cannot be written
	 */
	private static void send(OutputStream input, JsonMapper json, ObjectNode message) throws IOException
	{
		input.write((json.writeValueAsString(message) + "\n").getBytes(UTF_8));
		input.flush();
	}
}
