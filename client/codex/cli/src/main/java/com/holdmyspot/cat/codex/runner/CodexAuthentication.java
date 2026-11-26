package com.holdmyspot.cat.codex.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.BoundedInput;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.optimizer.adapter.CredentialFile;
import com.holdmyspot.cat.common.optimizer.port.HostLogin;
import com.holdmyspot.cat.common.runner.AuthenticationEvidence;
import com.holdmyspot.cat.common.runner.AuthenticationOptions;
import com.holdmyspot.cat.common.runner.HarnessRunnerOptions;
import com.holdmyspot.cat.common.runner.SecretFile;
import com.holdmyspot.cat.common.runner.SourceHome;
import com.holdmyspot.cat.common.runner.login.CodexKeyring;
import com.holdmyspot.cat.common.runner.login.CodexLoginClient;
import com.holdmyspot.cat.common.runner.login.CodexLoginPlan;
import com.holdmyspot.cat.common.runner.login.CodexLoginService;
import com.holdmyspot.cat.common.runner.login.CodexLoginSource;
import com.holdmyspot.cat.common.runner.login.LoginRefusal;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.SessionRefusedException;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.toml.TomlMapper;

/**
 * The Codex login that a nested session takes from its launch's source home, chosen as the user's own Codex chooses
 * it from the source's {@code config.toml}, {@code auth.json}, the operating system's keyring and the runner's
 * environment.
 * <p>
 * A nested session never holds a login that can change. A key that cannot change (an API key, the value that a
 * provider's {@code env_key} names, or an inline {@code experimental_bearer_token}) reaches the session through its key
 * file. Every other login (a ChatGPT sign-in in {@code auth.json} or in the keyring, a provider's own token command,
 * a {@code gateway_oauth} provider's {@code credential_export} command, and a provider whose secret travels in
 * headers) is served by the login service of the request server that serves the top-level session, which alone reads
 * the source login and runs the source's commands; the session's key file then holds a per-session credential for that
 * service.
 * In either case the session's provider runs CAT's token command, which prints the key or asks the service.
 * <p>
 * {@link #read} decides the route from the source's files without reading a sign-in; {@link #open} opens the
 * session's login, through the service when the route needs it, and returns what the session's Codex folder receives.
 * <p>
 * The description of an instance holds no secret.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 */
public final class CodexAuthentication
{
	/**
	 * The name of the Codex folder in a home folder.
	 */
	public static final String FOLDER = ".codex";
	/**
	 * The variable with which Codex moves its folder.
	 */
	public static final String FOLDER_VARIABLE = "CODEX_HOME";
	/**
	 * The name of Codex's login file in its folder.
	 */
	public static final String LOGIN = CodexLoginSource.LOGIN_FILE;
	/**
	 * The name of Codex's configuration file in its folder.
	 */
	private static final String CONFIGURATION = "config.toml";
	/**
	 * The most bytes of a source file that the runner reads.
	 */
	private static final int MAXIMUM_FILE_BYTES = 1_048_576;
	/**
	 * The built-in provider that Codex selects when {@code model_provider} is not set.
	 */
	private static final String DEFAULT_PROVIDER = "openai";
	/**
	 * The identifier of the custom provider that stands for the built-in one in a session's configuration.
	 */
	private static final String SESSION_PROVIDER = "cat-openai";
	/**
	 * The base URL of the OpenAI API, which the built-in provider reaches with a key.
	 */
	private static final String API_BASE_URL = "https://api.openai.com/v1";
	/**
	 * The base URL that the built-in provider reaches with a ChatGPT sign-in.
	 */
	private static final String CHATGPT_BASE_URL = "https://chatgpt.com/backend-api/codex";
	/**
	 * The header that names a ChatGPT sign-in's account.
	 */
	static final String ACCOUNT_HEADER = "ChatGPT-Account-ID";
	/**
	 * The top-level settings of {@code config.toml} that choose the session's login or its model host.
	 */
	private static final List<String> LOGIN_SETTINGS = List.of("model_provider", "openai_base_url",
		"forced_login_method", "cli_auth_credentials_store");
	/**
	 * The variables of the runner's environment that hold an API key, in the order in which they are read.
	 */
	private static final List<String> KEY_VARIABLES = List.of("CODEX_API_KEY", "OPENAI_API_KEY");
	/**
	 * The name of the key file in a session's Codex folder.
	 */
	private static final String KEY_FILE = "cat-api-key";
	/**
	 * How long Codex runs a source provider's token command when the provider sets no timeout, in milliseconds.
	 */
	private static final long DEFAULT_COMMAND_TIMEOUT_MS = 5000;
	/**
	 * How long a source provider's token output serves when the provider sets no interval, in milliseconds.
	 */
	private static final long DEFAULT_REFRESH_INTERVAL_MS = 300_000;
	/**
	 * The top-level setting that gates the hosted web search tool.
	 */
	private static final String WEB_SEARCH = "web_search";
	/**
	 * The {@code web_search} value that Codex uses when a configuration sets none.
	 */
	private static final String DEFAULT_WEB_SEARCH = "cached";
	/**
	 * The keyring item's part of the evidence.
	 */
	private static final String KEYRING_PART = "keyring:" + CodexKeyring.SERVICE;

	/**
	 * How the session receives its login.
	 */
	private sealed interface Route
	{
	}

	/**
	 * A key that cannot change, which the session's key file holds.
	 *
	 * @param key  the key
	 * @param part the evidence's name of where the key came from
	 */
	private record Key(String key, String part) implements Route
	{
		@Override
		public String toString()
		{
			return "Key{part=" + part + "}";
		}
	}

	/**
	 * A login that the request server's login service serves.
	 *
	 * @param plan the plan that the service opens
	 */
	private record Served(CodexLoginPlan plan) implements Route
	{
		@Override
		public String toString()
		{
			return "Served{plan=" + plan.getClass().getSimpleName() + "}";
		}
	}

	private final SourceHome source;
	private final Path folder;
	private final Path configuration;
	private final String providerId;
	private final ObjectNode provider;
	private final boolean openaiLogin;
	private final Optional<String> openaiBaseUrl;
	private final String forcedLoginMethod;
	private final Optional<HostLogin> declared;
	private final Route route;
	private final List<String> settingNames;
	private final List<String> loginParts;
	private final List<Path> secretFiles;
	private final List<Path> sourceFiles;
	private final String webSearch;

	/**
	 * Creates an authentication.
	 *
	 * @param source            the source home
	 * @param folder            the source's Codex folder
	 * @param configuration     the source's {@code config.toml}, which may not exist
	 * @param providerId        the identifier of the selected provider
	 * @param provider          the selected custom provider's table, or {@code null} for the built-in provider
	 * @param openaiLogin       {@code true} if the session uses the source's OpenAI login: a stored login, an API key
	 *                          or a key in the runner's environment
	 * @param openaiBaseUrl     the source's {@code openai_base_url}, or empty
	 * @param forcedLoginMethod the source's {@code forced_login_method}, or empty text
	 * @param declared          the gateway class that the launch declares, or empty
	 * @param route             how the session receives its login
	 * @param settingNames      the names of the source's settings that chose the login, in order
	 * @param loginParts        the evidence's names of the logins that the runner or the service reads
	 * @param secretFiles       the source files that hold a secret
	 * @param sourceFiles       the source files that the runner or the service reads for the session's credential
	 * @param webSearch         the source's {@code web_search} value
	 */
	@SuppressWarnings("checkstyle:ParameterNumber")
	private CodexAuthentication(SourceHome source, Path folder, Path configuration, String providerId,
		ObjectNode provider, boolean openaiLogin, Optional<String> openaiBaseUrl, String forcedLoginMethod,
		Optional<HostLogin> declared, Route route, List<String> settingNames, List<String> loginParts,
		List<Path> secretFiles, List<Path> sourceFiles, String webSearch)
	{
		this.source = source;
		this.folder = folder;
		this.configuration = configuration;
		this.providerId = providerId;
		this.provider = provider;
		this.openaiLogin = openaiLogin;
		this.openaiBaseUrl = openaiBaseUrl;
		this.forcedLoginMethod = forcedLoginMethod;
		this.declared = declared;
		this.route = route;
		this.settingNames = settingNames;
		this.loginParts = loginParts;
		this.secretFiles = secretFiles;
		this.sourceFiles = sourceFiles;
		this.webSearch = webSearch;
	}

	/**
	 * Reads the login of a launch's source home. The source's Codex folder is {@code .codex} in a home that the launch
	 * names; otherwise it is the folder that the runner's {@code CODEX_HOME} names, or else {@code .codex} in the
	 * runner's home folder.
	 * <p>
	 * The selected provider decides: a custom provider's header secrets, {@code gateway_oauth} with
	 * {@code credential_export}, own {@code auth} command, {@code experimental_bearer_token} or {@code env_key}, in that
	 * order, or else the source's OpenAI login, which is also the built-in provider's. The OpenAI login is the login that
	 * {@code cli_auth_credentials_store} keeps: the keyring for {@code keyring} and {@code auto}, which the login service
	 * reads; otherwise {@code auth.json}, which holds a ChatGPT sign-in that the service serves or an API key; and
	 * without it the runner's {@code CODEX_API_KEY} or else {@code OPENAI_API_KEY}. The runner tells a sign-in from an
	 * API key by the types of the file's top-level values and reads only an API key.
	 * <p>
	 * A source file holds a secret when it is {@code auth.json}, the keyring's age-encrypted file, a file that the
	 * selected provider's token or {@code credential_export} command names in its arguments by an absolute path or a
	 * path under {@code ~/}, or {@code config.toml} when the selected provider holds {@code experimental_bearer_token}
	 * or {@code http_headers}. Each one must hold its secret safely, as {@link SecretFile} decides from its metadata.
	 *
	 * @param options     the launch's choice of source home and gateway login class
	 * @param environment the runner's environment, which holds the variable that the selected provider's
	 *                    {@code env_key} names and the API key variables
	 * @param platform    the platform whose home variable names the runner's home folder
	 * @return the authentication
	 * @throws NullPointerException    if any of the arguments are null
	 * @throws SessionRefusedException if the source home does not exist; if its {@code config.toml} or
	 *                                 {@code auth.json} cannot be parsed; if the selected provider has no table, signs
	 *                                 in through {@code gateway_oauth} without {@code credential_export}, names in
	 *                                 {@code env_key} or {@code env_http_headers} a variable that {@code environment}
	 *                                 does not set, or sends header secrets without a base URL or with the OpenAI
	 *                                 login; if {@code cli_auth_credentials_store} names no store; if the source holds
	 *                                 no login; if a base URL names no host, or the launch declares no class of login
	 *                                 for it; or if a file that holds a secret does not hold it safely. The reason
	 *                                 names the files or the setting and never quotes a secret.
	 * @throws IOException             if a source file cannot be read
	 */
	public static CodexAuthentication read(AuthenticationOptions options, Map<String, String> environment,
		Platform platform) throws IOException
	{
		requireThat(options, "options").isNotNull();
		requireThat(environment, "environment").isNotNull();
		requireThat(platform, "platform").isNotNull();
		SourceHome source = options.sourceHome(environment, platform);
		Path folder = source.folder(FOLDER, FOLDER_VARIABLE, environment);
		Path configuration = folder.resolve(CONFIGURATION);
		JsonNode values = readConfiguration(configuration);
		String providerId = values.path("model_provider").asString(DEFAULT_PROVIDER);
		JsonNode table = values.path("model_providers").path(providerId);
		boolean builtin = providerId.equals(DEFAULT_PROVIDER);
		if (!builtin && !table.isObject())
		{
			throw new SessionRefusedException("CAT does not start the session: " + configuration + " sets " +
				"model_provider to a provider without a [model_providers." + providerId + "] table, so CAT cannot " +
				"tell where the session's requests go or which credential they use. Add the provider's table, or name " +
				"another source home with " + AuthenticationOptions.HOME_OPTION + ".");
		}
		ObjectNode provider = null;
		if (!builtin)
			provider = (ObjectNode) table;
		Optional<String> openaiBaseUrl = Optional.ofNullable(values.path("openai_base_url").asString(null));
		Optional<HostLogin> declared = options.gatewayLoginClass();
		ReadState state = new ReadState(source, folder, configuration, providerId, environment);
		Route route;
		boolean openaiLogin = false;
		if (provider == null)
		{
			route = state.openaiLogin(values);
			openaiLogin = true;
		}
		else
		{
			Optional<Route> own = state.providerLogin(provider);
			if (own.isPresent())
				route = own.get();
			else
			{
				route = state.openaiLogin(values);
				openaiLogin = true;
			}
		}

		Optional<String> baseUrl = Optional.empty();
		String baseUrlSetting = "";
		if (provider != null && provider.path("base_url").isString())
		{
			baseUrl = Optional.of(provider.path("base_url").asString());
			baseUrlSetting = "model_providers." + providerId + ".base_url";
		}
		else if (provider == null && route instanceof Key && openaiBaseUrl.isPresent())
		{
			baseUrl = openaiBaseUrl;
			baseUrlSetting = "openai_base_url";
		}
		if (baseUrl.isPresent())
		{
			urlHost(configuration, baseUrlSetting, baseUrl.get());
			if (declared.isEmpty())
				throw undeclaredGateway(configuration, baseUrlSetting);
		}
		if (provider == null && openaiBaseUrl.isPresent())
			urlHost(configuration, "openai_base_url", openaiBaseUrl.get());
		for (Path file : state.secretFiles)
		{
			Optional<String> problem = SecretFile.problem(file, SecretFile.read(file));
			if (problem.isPresent())
				throw new SessionRefusedException("CAT does not start the session: " + problem.get());
		}
		Set<Path> sourceFiles = new LinkedHashSet<>();
		if (Files.exists(configuration))
			sourceFiles.add(configuration);
		sourceFiles.addAll(state.secretFiles);
		List<String> settingNames = new ArrayList<>();
		for (String name : LOGIN_SETTINGS)
		{
			if (values.has(name))
				settingNames.add(name);
		}
		if (provider != null)
			settingNames.add("model_providers." + providerId);
		return new CodexAuthentication(source, folder, configuration, providerId, provider, openaiLogin,
			openaiBaseUrl, values.path("forced_login_method").asString(""), declared, route,
			List.copyOf(settingNames), List.copyOf(state.loginParts), List.copyOf(state.secretFiles),
			List.copyOf(sourceFiles), values.path(WEB_SEARCH).asString(DEFAULT_WEB_SEARCH));
	}

	/**
	 * Reads a source's configuration.
	 *
	 * @param configuration the file
	 * @return the file's values, or an empty object if the file does not exist
	 * @throws SessionRefusedException if the file cannot be parsed
	 * @throws IOException             if the file cannot be read
	 */
	private static JsonNode readConfiguration(Path configuration) throws IOException
	{
		TomlMapper mapper = TomlMapper.builder().build();
		if (!Files.exists(configuration))
			return mapper.createObjectNode();
		try
		{
			return mapper.readTree(BoundedInput.readUtf8(configuration, MAXIMUM_FILE_BYTES, "Codex configuration"));
		}
		catch (JacksonException _)
		{
			throw new SessionRefusedException("CAT does not start the session: the source's Codex configuration " +
				configuration + " cannot be parsed, so CAT cannot tell which credential it selects. Correct the file.");
		}
	}

	/**
	 * Returns the refusal of a base URL whose login class the launch does not declare.
	 *
	 * @param configuration the source's {@code config.toml}
	 * @param setting       the setting that names the base URL
	 * @return the refusal
	 */
	private static SessionRefusedException undeclaredGateway(Path configuration, String setting)
	{
		return new SessionRefusedException("CAT does not start the session: " + configuration + " sets " + setting +
			", which sends the session's requests to a gateway, and no file records which kind of login the gateway " +
			"bills. Name it with " + AuthenticationOptions.LOGIN_CLASS_OPTION + " subscription or " +
			AuthenticationOptions.LOGIN_CLASS_OPTION + " api_key.");
	}

	/**
	 * Returns the host of a base URL.
	 *
	 * @param configuration the configuration that names the URL
	 * @param setting       the setting that names the URL
	 * @param baseUrl       the URL
	 * @return the host, in lower case
	 * @throws SessionRefusedException if the URL is malformed or names no host
	 */
	private static String urlHost(Path configuration, String setting, String baseUrl) throws SessionRefusedException
	{
		String host;
		try
		{
			host = URI.create(baseUrl.strip()).getHost();
		}
		catch (IllegalArgumentException e)
		{
			throw new SessionRefusedException("CAT does not start the session: " + configuration + " sets " + setting +
				" to a malformed URL: " + baseUrl + ". Name the gateway's URL there.", e);
		}
		if (host == null || host.isBlank())
		{
			throw new SessionRefusedException("CAT does not start the session: " + configuration + " sets " + setting +
				" to a URL without a host: " + baseUrl + ". Name the gateway's URL there.");
		}
		return host.toLowerCase(Locale.ROOT);
	}

	/**
	 * What {@link #read} collects while it decides the route.
	 */
	private static final class ReadState
	{
		private final SourceHome source;
		private final Path folder;
		private final Path configuration;
		private final String providerId;
		private final String settingPrefix;
		private final Map<String, String> environment;
		private final Set<Path> secretFiles = new LinkedHashSet<>();
		private final List<String> loginParts = new ArrayList<>();

		/**
		 * Creates a state.
		 *
		 * @param source        the source home
		 * @param folder        the source's Codex folder
		 * @param configuration the source's {@code config.toml}
		 * @param providerId    the identifier of the selected provider
		 * @param environment   the runner's environment
		 */
		ReadState(SourceHome source, Path folder, Path configuration, String providerId,
			Map<String, String> environment)
		{
			this.source = source;
			this.folder = folder;
			this.configuration = configuration;
			this.providerId = providerId;
			this.settingPrefix = "model_providers." + providerId + ".";
			this.environment = environment;
		}

		/**
		 * Returns the route of a custom provider's own credential.
		 *
		 * @param provider the provider's table
		 * @return the route, or empty if the provider has no credential of its own
		 * @throws SessionRefusedException if the provider signs in through {@code gateway_oauth} without
		 *                                 {@code credential_export}, names a variable that the environment does not
		 *                                 set, or sends header secrets without a base URL or with the OpenAI login
		 */
		Optional<Route> providerLogin(ObjectNode provider) throws SessionRefusedException
		{
			JsonNode gatewaySignIn = provider.path("gateway_oauth");
			JsonNode export = gatewaySignIn.path("credential_export");
			if (!gatewaySignIn.isMissingNode() && !export.isObject())
			{
				throw new SessionRefusedException("CAT does not start the session: " + configuration + " sets " +
					settingPrefix + "gateway_oauth without credential_export for the selected provider " + providerId +
					", so the provider's sign-in is stored where CAT cannot serve it to the session. Give it a " +
					"credential_export command or a token command, or name another source home with " +
					AuthenticationOptions.HOME_OPTION + ".");
			}
			Optional<CodexLoginPlan.Command> command = Optional.empty();
			if (export.isObject())
			{
				command = Optional.of(command(export, settingPrefix + "gateway_oauth.credential_export",
					export.path("refresh_interval_ms").asLong(gatewaySignIn.path("refresh_interval_ms").
						asLong(DEFAULT_REFRESH_INTERVAL_MS))));
				loginParts.add(settingPrefix + "gateway_oauth.credential_export");
			}
			else
			{
				JsonNode auth = provider.path("auth");
				if (auth.isObject() && auth.path("command").isString())
				{
					command = Optional.of(command(auth, settingPrefix + "auth",
						auth.path("refresh_interval_ms").asLong(DEFAULT_REFRESH_INTERVAL_MS)));
					loginParts.add(settingPrefix + "auth");
				}
			}
			Optional<String> key = providerKey(provider);
			Map<String, String> headers = headers(provider);
			if (!headers.isEmpty())
			{
				JsonNode baseUrl = provider.path("base_url");
				if (!baseUrl.isString())
				{
					throw new SessionRefusedException("CAT does not start the session: " + configuration + " sets " +
						"header secrets for the selected provider " + providerId + " and no " + settingPrefix +
						"base_url, so CAT's forwarder cannot tell where to pass the session's requests on. Name the " +
						"provider's base_url.");
				}
				if (key.isEmpty() && command.isEmpty() && provider.path("requires_openai_auth").asBoolean(false))
				{
					throw new SessionRefusedException("CAT does not start the session: " + configuration + " sets " +
						"header secrets and requires_openai_auth for the selected provider " + providerId + ", and CAT's " +
						"forwarder does not add the OpenAI login to the headers. Give the provider env_key, an " +
						"experimental_bearer_token or a token command, or name another source home with " +
						AuthenticationOptions.HOME_OPTION + ".");
				}
				URI target;
				try
				{
					target = URI.create(baseUrl.asString().strip());
					return Optional.of(new Served(new CodexLoginPlan.Forward(target, headers, key, command)));
				}
				catch (IllegalArgumentException e)
				{
					throw new SessionRefusedException("CAT does not start the session: " + configuration + " sets " +
						settingPrefix + "base_url to a URL that CAT's forwarder cannot reach: " + baseUrl.asString() +
						". Name the provider's HTTP or HTTPS URL there.", e);
				}
			}
			if (command.isPresent())
				return Optional.of(new Served(command.get()));
			return key.map(value -> new Key(value, settingPrefix.substring(0, settingPrefix.length() - 1)));
		}

		/**
		 * Returns the plan of a source command that the login service runs.
		 *
		 * @param table           the command's table, with {@code command}, {@code args}, {@code cwd} and
		 *                        {@code timeout_ms}
		 * @param setting         the table's setting, which a refusal names
		 * @param refreshInterval how long the command's output serves, in milliseconds
		 * @return the plan
		 * @throws SessionRefusedException if the table names no program or a negative interval
		 */
		private CodexLoginPlan.Command command(JsonNode table, String setting, long refreshInterval)
			throws SessionRefusedException
		{
			String program = table.path("command").asString("");
			if (program.isBlank() || refreshInterval < 0)
			{
				throw new SessionRefusedException("CAT does not start the session: " + configuration + " sets " +
					setting + " without a command or with a negative refresh_interval_ms. Correct the setting.");
			}
			List<String> arguments = new ArrayList<>();
			for (JsonNode argument : table.path("args"))
				arguments.add(argument.asString());
			Optional<Path> directory = Optional.empty();
			String cwd = table.path("cwd").asString("");
			if (!cwd.isBlank())
			{
				try
				{
					directory = Optional.of(source.directory().resolve(cwd));
				}
				catch (InvalidPathException e)
				{
					throw new SessionRefusedException("CAT does not start the session: " + configuration + " sets " +
						setting + ".cwd to a path that this platform cannot name: " + cwd + ". Correct the setting.", e);
				}
			}
			long timeout = table.path("timeout_ms").asLong(DEFAULT_COMMAND_TIMEOUT_MS);
			if (timeout <= 0)
				timeout = DEFAULT_COMMAND_TIMEOUT_MS;
			addCommandFiles(arguments);
			return new CodexLoginPlan.Command(program, arguments, directory, timeout, refreshInterval,
				source.directory());
		}

		/**
		 * Adds the files that a command's arguments name by an absolute path or a path under {@code ~/} to the files
		 * that hold a secret.
		 *
		 * @param arguments the command's arguments
		 */
		private void addCommandFiles(List<String> arguments)
		{
			for (String argument : arguments)
			{
				for (String word : argument.strip().split("\\s+"))
				{
					String unquoted = word.replaceAll("^['\"]|['\"]$", "");
					Path file;
					try
					{
						if (unquoted.startsWith("~/"))
							file = source.directory().resolve(unquoted.substring(2));
						else if (Path.of(unquoted).isAbsolute())
							file = Path.of(unquoted);
						else
							continue;
					}
					catch (InvalidPathException _)
					{
						continue;
					}
					if (Files.isRegularFile(file))
						secretFiles.add(file.normalize());
				}
			}
		}

		/**
		 * Returns a provider's key: its inline {@code experimental_bearer_token}, or else the value of the variable
		 * that its {@code env_key} names. The source's {@code config.toml} holds a secret when the token is inline.
		 *
		 * @param provider the provider's table
		 * @return the key, or empty if the provider sets neither setting
		 * @throws SessionRefusedException if {@code env_key} names a variable that the environment does not set or sets
		 *                                 to blank text
		 */
		private Optional<String> providerKey(JsonNode provider) throws SessionRefusedException
		{
			String name = provider.path("env_key").asString("");
			String fromEnvironment = null;
			if (!name.isBlank())
			{
				fromEnvironment = environment.get(name);
				if (fromEnvironment == null || fromEnvironment.isBlank())
				{
					throw new SessionRefusedException("CAT does not start the session: " + configuration + " sets " +
						settingPrefix + "env_key to " + name + " for the selected provider " + providerId + ", and the " +
						"runner's environment does not set " + name + ". Set it where the runner starts, or name " +
						"another source home with " + AuthenticationOptions.HOME_OPTION + ".");
				}
			}
			String inline = provider.path("experimental_bearer_token").asString("");
			if (!inline.isBlank())
			{
				secretFiles.add(configuration);
				return Optional.of(inline);
			}
			return Optional.ofNullable(fromEnvironment);
		}

		/**
		 * Returns the secret headers that a provider sends: its inline {@code http_headers}, and the values of the
		 * variables that its {@code env_http_headers} names. The source's {@code config.toml} holds a secret when the
		 * headers are inline.
		 *
		 * @param provider the provider's table
		 * @return the headers and their values
		 * @throws SessionRefusedException if {@code env_http_headers} names a variable that the environment does not set
		 */
		private Map<String, String> headers(JsonNode provider) throws SessionRefusedException
		{
			Map<String, String> headers = new LinkedHashMap<>();
			JsonNode inline = provider.path("http_headers");
			if (inline.isObject() && !inline.isEmpty())
			{
				secretFiles.add(configuration);
				for (Map.Entry<String, JsonNode> header : inline.properties())
					headers.put(header.getKey(), header.getValue().asString(""));
			}
			JsonNode variables = provider.path("env_http_headers");
			if (variables.isObject())
			{
				for (Map.Entry<String, JsonNode> header : variables.properties())
				{
					String variable = header.getValue().asString("");
					String value = environment.get(variable);
					if (value == null || value.isBlank())
					{
						throw new SessionRefusedException("CAT does not start the session: " + configuration + " sets " +
							settingPrefix + "env_http_headers." + header.getKey() + " to " + variable + " for the " +
							"selected provider " + providerId + ", and the runner's environment does not set " +
							variable + ". Set it where the runner starts, or name another source home with " +
							AuthenticationOptions.HOME_OPTION + ".");
					}
					headers.put(header.getKey(), value);
				}
			}
			return headers;
		}

		/**
		 * Returns the route of the source's OpenAI login.
		 *
		 * @param values the source's configuration
		 * @return the route
		 * @throws SessionRefusedException if {@code cli_auth_credentials_store} names no store, {@code auth.json} cannot
		 *                                 be parsed or holds no login, or the source holds no login
		 * @throws IOException             if {@code auth.json} cannot be read
		 */
		Route openaiLogin(JsonNode values) throws IOException
		{
			String storeSetting = values.path("cli_auth_credentials_store").asString("");
			Path loginFile = folder.resolve(LOGIN);
			if (storeSetting.equals("keyring") || storeSetting.equals("auto"))
			{
				CodexLoginPlan.Store store = CodexLoginPlan.Store.of(storeSetting);
				loginParts.add(KEYRING_PART);
				Path ageFile = folder.resolve("secrets").resolve("codex_auth.age");
				if (Files.isRegularFile(ageFile))
					secretFiles.add(ageFile);
				if (store == CodexLoginPlan.Store.AUTO && Files.exists(loginFile))
				{
					secretFiles.add(loginFile);
					loginParts.add(relative(loginFile));
				}
				return new Served(new CodexLoginPlan.Stored(source.directory(), folder, store));
			}
			if (!storeSetting.isEmpty() && !storeSetting.equals("file") && !storeSetting.equals("ephemeral"))
			{
				throw new SessionRefusedException("CAT does not start the session: " + configuration + " sets " +
					"cli_auth_credentials_store to " + storeSetting + ", which names no store that CAT reads. Set it " +
					"to file, keyring or auto.");
			}
			if (storeSetting.isEmpty() || storeSetting.equals("file"))
			{
				Optional<Route> stored = fileLogin(loginFile);
				if (stored.isPresent())
					return stored.get();
			}
			for (String variable : KEY_VARIABLES)
			{
				String value = environment.get(variable);
				if (value != null && !value.isBlank())
				{
					loginParts.add("environment:" + variable);
					return new Key(value, "environment:" + variable);
				}
			}
			String store = "file";
			if (!storeSetting.isEmpty())
				store = storeSetting;
			throw new SessionRefusedException("CAT does not start the session: the source home " +
				source.directory() + " holds no Codex login that CAT can serve. The runner read " + configuration +
				", whose selected provider " + providerId + " has no token command, env_key or " +
				"experimental_bearer_token and whose cli_auth_credentials_store is " + store + ", so the keyring item " +
				"\"" + CodexKeyring.SERVICE + "\" was not read; found no " + loginFile + "; and found neither " +
				String.join(" nor ", KEY_VARIABLES) + " in its environment. Sign in to Codex on that source home, " +
				"log in with an API key as codex login --with-api-key does, or name another source home with " +
				AuthenticationOptions.HOME_OPTION + ".");
		}

		/**
		 * Returns the route of the login in the source's {@code auth.json}: a ChatGPT sign-in, which the login service
		 * serves, or an API key, which the runner reads.
		 *
		 * @param loginFile the source's {@code auth.json}
		 * @return the route, or empty if the file does not exist
		 * @throws SessionRefusedException if the file cannot be parsed or holds no login
		 * @throws IOException             if the file cannot be read
		 */
		private Optional<Route> fileLogin(Path loginFile) throws IOException
		{
			if (!Files.exists(loginFile))
				return Optional.empty();
			secretFiles.add(loginFile);
			loginParts.add(relative(loginFile));
			Map<String, JsonToken> types;
			try
			{
				types = CredentialFile.topLevelTypes(loginFile, JsonMapper.builder().build());
			}
			catch (JacksonException _)
			{
				throw new SessionRefusedException("CAT does not start the session: the source's Codex login " +
					loginFile + " cannot be parsed, so CAT cannot tell which login it holds. Sign in to Codex again.");
			}
			if (types.get("tokens") == JsonToken.START_OBJECT)
			{
				return Optional.of(new Served(new CodexLoginPlan.Stored(source.directory(), folder,
					CodexLoginPlan.Store.FILE)));
			}
			if (types.get("OPENAI_API_KEY") != JsonToken.VALUE_STRING)
			{
				throw new SessionRefusedException("CAT does not start the session: the source's Codex login " +
					loginFile + " holds neither a ChatGPT sign-in nor an API key. Sign in to Codex again, or name " +
					"another source home with " + AuthenticationOptions.HOME_OPTION + ".");
			}
			try
			{
				return Optional.of(new Key(CodexLoginSource.readFile(loginFile).secret(), relative(loginFile)));
			}
			catch (LoginRefusal e)
			{
				throw new SessionRefusedException("CAT does not start the session: " + e.getMessage(), e);
			}
		}

		/**
		 * Returns the evidence's name of a source file: its path relative to the source home, or its absolute path.
		 *
		 * @param file the file
		 * @return the name
		 */
		private String relative(Path file)
		{
			Path absolute = file.toAbsolutePath().normalize();
			if (absolute.startsWith(source.directory()))
				return source.directory().relativize(absolute).toString().replace('\\', '/');
			return absolute.toString();
		}
	}

	/**
	 * Returns the source home.
	 *
	 * @return the source home
	 */
	public SourceHome source()
	{
		return source;
	}

	/**
	 * Returns the hosts that the session's Codex may send its model requests to: the host of the selected provider's
	 * base URL; otherwise, for a key, the host of {@code openai_base_url} or the OpenAI API's; for a ChatGPT sign-in,
	 * the ChatGPT host; and for a keyring login, whose kind only the login service reads, both.
	 *
	 * @return the hosts, in lower case
	 * @throws SessionRefusedException if a base URL names no host
	 */
	public Set<String> modelHosts() throws SessionRefusedException
	{
		if (provider != null && provider.path("base_url").isString())
		{
			return Set.of(urlHost(configuration, "model_providers." + providerId + ".base_url",
				provider.path("base_url").asString()));
		}
		Set<String> hosts = new LinkedHashSet<>();
		boolean signIn = route instanceof Served(CodexLoginPlan.Stored stored) &&
			stored.store() == CodexLoginPlan.Store.FILE;
		boolean unknown = route instanceof Served(CodexLoginPlan.Stored stored) &&
			stored.store() != CodexLoginPlan.Store.FILE;
		if (!signIn)
			hosts.add(keyHost());
		if (signIn || unknown)
			hosts.add(URI.create(CHATGPT_BASE_URL).getHost());
		return Set.copyOf(hosts);
	}

	/**
	 * Returns the host that the built-in provider reaches with a key.
	 *
	 * @return the host
	 * @throws SessionRefusedException if {@code openai_base_url} names no host
	 */
	private String keyHost() throws SessionRefusedException
	{
		if (provider == null && openaiBaseUrl.isPresent())
			return urlHost(configuration, "openai_base_url", openaiBaseUrl.get());
		return URI.create(API_BASE_URL).getHost();
	}

	/**
	 * Returns the source files that hold a secret.
	 *
	 * @return the files
	 */
	public List<Path> secretFiles()
	{
		return secretFiles;
	}

	/**
	 * Returns the source files that the runner or the login service reads for the session's credential: the source's
	 * {@code config.toml} when it exists, and the files that hold a secret.
	 *
	 * @return the files
	 */
	public List<Path> sourceFiles()
	{
		return sourceFiles;
	}

	/**
	 * Adds the source files that hold a secret to the reads that a launch's options deny, so that every session that
	 * the session requests is denied them too.
	 *
	 * @param options the launch's options
	 * @return the options with the files denied
	 * @throws NullPointerException if {@code options} is null
	 */
	public HarnessRunnerOptions withDeniedReads(HarnessRunnerOptions options)
	{
		requireThat(options, "options").isNotNull();
		return options.withDeniedReads(secretFiles);
	}

	/**
	 * Refuses a launch whose bound, which its starter computed from the requesting session's policy, denies reading a
	 * source file: a session may use only a credential that it may read, and a launch never extends its requester's
	 * reach. A keyring item is no file; the login service checks the top-level session's reach of the keyring instead.
	 *
	 * @param bound the policy that bounds the session
	 * @throws NullPointerException    if {@code bound} is null
	 * @throws SessionRefusedException if the bound denies reading a source file; the reason names the file and the
	 *                                 denial
	 * @throws IOException             if a symbolic link in a path cannot be read
	 */
	public void requireReadable(ParentPolicy bound) throws IOException
	{
		requireThat(bound, "bound").isNotNull();
		for (Path file : sourceFiles)
		{
			Optional<Path> denial = bound.readDenial(file.toAbsolutePath());
			if (denial.isPresent())
			{
				throw new SessionRefusedException("CAT does not start the session: the sandbox of the session that " +
					"requests it denies reading " + denial.get() + ", which holds " + file + ", a file that CAT " +
					"reads from the source home " + source.directory() + " for the session's credential. A launch " +
					"never uses a credential that its requester may not read. Let the requesting session's sandbox " +
					"read " + file + ", or name another source home with " + AuthenticationOptions.HOME_OPTION + ".");
			}
		}
	}

	/**
	 * Returns the record of the session's authentication before its login opens: the source home, the names of the
	 * source settings that chose the login and of the logins that CAT reads, and the class of the login. The class of
	 * a login that the keyring holds is known only once the login service reads it; until then it is a subscription,
	 * which is what Codex keeps there for a ChatGPT sign-in, or the declared class for a base URL that the source set.
	 *
	 * @return the evidence
	 */
	public AuthenticationEvidence evidence()
	{
		HostLogin loginClass;
		if (route instanceof Key)
			loginClass = keyClass();
		else if (provider != null && provider.path("base_url").isString())
			loginClass = declared.orElseThrow();
		else if (openaiLogin)
			loginClass = HostLogin.SUBSCRIPTION;
		else
			loginClass = keyClass();
		List<String> parts = new ArrayList<>(settingNames);
		parts.addAll(loginParts);
		return new AuthenticationEvidence(source.directory(), parts, loginClass);
	}

	/**
	 * Returns the class of a key: the declared class when the source names a base URL for it, otherwise an API key.
	 *
	 * @return the class
	 */
	private HostLogin keyClass()
	{
		boolean gateway = (provider != null && provider.path("base_url").isString()) ||
			(provider == null && openaiBaseUrl.isPresent());
		if (gateway)
			return declared.orElseThrow();
		return HostLogin.API_KEY;
	}

	/**
	 * Opens the session's login. A key needs no service. Every other login is opened by the request server's login
	 * service that the runner's environment names, which checks it, reads it and returns the session's credential; a
	 * login that the keyring holds is served only while the top-level session can reach the keyring.
	 *
	 * @param environment the runner's environment, which names the login service
	 * @return the session's login, which the caller closes when the session's app-server ends
	 * @throws NullPointerException    if {@code environment} is null
	 * @throws SessionRefusedException if the login needs the login service and the environment names none, or the
	 *                                 service is gone or refuses the login; if {@code forced_login_method} names
	 *                                 another kind of login; or if the launch declares no class for a key that the
	 *                                 source sends to its {@code openai_base_url}. The reason holds no secret.
	 */
	public CodexSessionLogin open(Map<String, String> environment) throws SessionRefusedException
	{
		requireThat(environment, "environment").isNotNull();
		String sessionProvider = providerId;
		if (provider == null)
			sessionProvider = SESSION_PROVIDER;
		return switch (route)
		{
			case Key key ->
			{
				requireLoginMethod(false);
				yield CodexSessionLogin.ofKey(sessionProvider, sessionProvider(false, Optional.empty(),
					Optional.empty()), webSearch, key.key(), modelHost(false), keyClass(), evidence());
			}
			case Served served -> openServed(served.plan(), environment, sessionProvider);
		};
	}

	/**
	 * Opens a login that the login service serves.
	 *
	 * @param plan            the login's plan
	 * @param environment     the runner's environment
	 * @param sessionProvider the identifier of the session's provider
	 * @return the session's login
	 * @throws SessionRefusedException if the service is not named, is gone or refuses, or the opened login does not
	 *                                 suit the source's settings
	 */
	private CodexSessionLogin openServed(CodexLoginPlan plan, Map<String, String> environment,
		String sessionProvider) throws SessionRefusedException
	{
		String socketText = environment.getOrDefault(CodexLoginService.SOCKET_VARIABLE, "");
		String serviceKey = environment.getOrDefault(CodexLoginService.KEY_VARIABLE, "");
		if (socketText.isBlank() || serviceKey.isBlank())
		{
			throw new SessionRefusedException("CAT does not start the session: the source home " +
				source.directory() + " keeps a Codex login that only the request server's login service reads and " +
				"serves (" + String.join(", ", loginParts) + "), and the runner was started without one: " +
				CodexLoginService.SOCKET_VARIABLE + " is not set. Start the session through the request server of a " +
				"CAT session, or name a source home that holds an API key with " + AuthenticationOptions.HOME_OPTION +
				".");
		}
		Path socket = Path.of(socketText);
		CodexLoginClient.Opened opened;
		try
		{
			opened = CodexLoginClient.open(socket, serviceKey, plan);
		}
		catch (LoginRefusal e)
		{
			String reason = e.getMessage();
			if (!reason.startsWith("CAT does not start the session: "))
				reason = "CAT does not start the session: " + reason;
			throw new SessionRefusedException(reason, e);
		}
		catch (IOException e)
		{
			throw new SessionRefusedException("CAT does not start the session: the request server's login service at " +
				socket + " cannot be reached (" + e.getClass().getSimpleName() + "), so the session's login cannot " +
				"be served. The request server that started the runner has ended; start the session again.", e);
		}
		try
		{
			boolean storedLogin = plan instanceof CodexLoginPlan.Stored;
			if (storedLogin)
				requireLoginMethod(opened.signIn());
			boolean signIn = storedLogin && opened.signIn();
			HostLogin loginClass;
			if (provider != null && provider.path("base_url").isString())
				loginClass = declared.orElseThrow();
			else if (signIn)
				loginClass = HostLogin.SUBSCRIPTION;
			else if (provider == null && openaiBaseUrl.isPresent())
			{
				if (declared.isEmpty())
					throw undeclaredGateway(configuration, "openai_base_url");
				loginClass = declared.get();
			}
			else
				loginClass = HostLogin.API_KEY;
			List<String> parts = new ArrayList<>(settingNames);
			if (plan instanceof CodexLoginPlan.Stored stored && stored.store() == CodexLoginPlan.Store.AUTO)
			{
				if (opened.inKeyring())
					parts.add(KEYRING_PART);
				else
					parts.addAll(loginParts.stream().filter(part -> !part.equals(KEYRING_PART)).toList());
			}
			else
				parts.addAll(loginParts);
			AuthenticationEvidence evidence = new AuthenticationEvidence(source.directory(), parts, loginClass);
			Optional<String> accountId = Optional.empty();
			if (signIn)
				accountId = opened.accountId();
			return CodexSessionLogin.ofService(sessionProvider, sessionProvider(signIn, accountId,
				opened.forwarder()), webSearch, socket, opened.credential(), commandTimeout(plan), modelHost(signIn),
				loginClass, evidence);
		}
		catch (SessionRefusedException e)
		{
			try
			{
				CodexLoginClient.close(socket, opened.credential());
			}
			catch (IOException closing)
			{
				e.addSuppressed(closing);
			}
			throw e;
		}
	}

	/**
	 * Refuses an OpenAI login of the kind that the source's {@code forced_login_method} excludes.
	 *
	 * @param signIn {@code true} for a ChatGPT sign-in, {@code false} for a key
	 * @throws SessionRefusedException if {@code forced_login_method} names the other kind
	 */
	private void requireLoginMethod(boolean signIn) throws SessionRefusedException
	{
		if (!openaiLogin)
			return;
		String required;
		if (signIn)
			required = "chatgpt";
		else
			required = "api";
		if (forcedLoginMethod.isEmpty() || forcedLoginMethod.equals(required))
			return;
		String kind;
		if (signIn)
			kind = "a ChatGPT sign-in";
		else
			kind = "an API key";
		throw new SessionRefusedException("CAT does not start the session: " + configuration + " sets " +
			"forced_login_method to " + forcedLoginMethod + ", and the source home's login is " + kind + ", which the " +
			"user's own Codex would refuse. Sign in to Codex on that source home with the forced method, or name " +
			"another source home with " + AuthenticationOptions.HOME_OPTION + ".");
	}

	/**
	 * Returns the table of the session's provider, without its token command: the source's own table without its
	 * secrets and commands, or the custom provider that stands for the built-in one.
	 *
	 * @param signIn    {@code true} if the session reaches the built-in provider with a ChatGPT sign-in
	 * @param accountId the sign-in's account ID, or empty
	 * @param forwarder the base URL of the forwarder that the session reaches, or empty
	 * @return the table
	 */
	private ObjectNode sessionProvider(boolean signIn, Optional<String> accountId, Optional<URI> forwarder)
	{
		ObjectNode table;
		if (provider == null)
		{
			table = TomlMapper.builder().build().createObjectNode();
			table.put("name", "OpenAI");
			if (signIn)
				table.put("base_url", CHATGPT_BASE_URL);
			else
				table.put("base_url", openaiBaseUrl.orElse(API_BASE_URL));
			table.put("wire_api", "responses");
		}
		else
		{
			table = provider.deepCopy();
			for (String setting : List.of("env_key", "experimental_bearer_token", "requires_openai_auth",
				"gateway_oauth", "auth", "env_key_instructions"))
			{
				table.remove(setting);
			}
			if (forwarder.isPresent())
			{
				table.remove("http_headers");
				table.remove("env_http_headers");
				table.put("base_url", forwarder.get().toString());
			}
		}
		if (accountId.isPresent())
			table.withObjectProperty("http_headers").put(ACCOUNT_HEADER, accountId.get());
		return table;
	}

	/**
	 * Returns the host that the session's Codex sends its model requests to.
	 *
	 * @param signIn {@code true} if the session reaches the built-in provider with a ChatGPT sign-in
	 * @return the host
	 * @throws SessionRefusedException if a base URL names no host
	 */
	private String modelHost(boolean signIn) throws SessionRefusedException
	{
		if (provider != null && provider.path("base_url").isString())
		{
			return urlHost(configuration, "model_providers." + providerId + ".base_url",
				provider.path("base_url").asString());
		}
		if (signIn)
			return URI.create(CHATGPT_BASE_URL).getHost();
		return keyHost();
	}

	/**
	 * Returns how long the session's token command may wait for the login service, which may run a source command
	 * first.
	 *
	 * @param plan the login's plan
	 * @return the timeout, in milliseconds
	 */
	private static long commandTimeout(CodexLoginPlan plan)
	{
		return switch (plan)
		{
			case CodexLoginPlan.Command command -> CodexSessionLogin.TOKEN_COMMAND_TIMEOUT_MS + command.timeoutMillis();
			case CodexLoginPlan.Forward forward -> CodexSessionLogin.TOKEN_COMMAND_TIMEOUT_MS +
				forward.bearerCommand().map(CodexLoginPlan.Command::timeoutMillis).orElse(0L);
			case CodexLoginPlan.Stored _ -> CodexSessionLogin.TOKEN_COMMAND_TIMEOUT_MS;
		};
	}

	/**
	 * Returns the key file of a session's Codex folder, which holds the session's key or its credential for the login
	 * service while the session's app-server runs.
	 *
	 * @param codexFolder the session's Codex folder
	 * @return the key file
	 * @throws NullPointerException if {@code codexFolder} is null
	 */
	public static Path keyFile(Path codexFolder)
	{
		requireThat(codexFolder, "codexFolder").isNotNull();
		return codexFolder.resolve(KEY_FILE);
	}

	/**
	 * Removes the session's key file, if any.
	 *
	 * @param codexFolder the session's Codex folder
	 * @throws NullPointerException if {@code codexFolder} is null
	 * @throws IOException          if the file cannot be removed
	 */
	public static void removeKeyFile(Path codexFolder) throws IOException
	{
		Files.deleteIfExists(keyFile(codexFolder));
	}

	@Override
	public String toString()
	{
		return "CodexAuthentication{source=" + source.directory() + ", folder=" + folder + ", configuration=" +
			configuration + ", provider=" + providerId + ", route=" + route + ", parts=" + evidence().parts() + "}";
	}
}
