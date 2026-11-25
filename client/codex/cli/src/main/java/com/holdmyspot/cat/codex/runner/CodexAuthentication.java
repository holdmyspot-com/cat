package com.holdmyspot.cat.codex.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.BoundedInput;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.optimizer.port.HostLogin;
import com.holdmyspot.cat.common.runner.AuthenticationEvidence;
import com.holdmyspot.cat.common.runner.AuthenticationOptions;
import com.holdmyspot.cat.common.runner.HarnessEnvironment;
import com.holdmyspot.cat.common.runner.HarnessRunnerOptions;
import com.holdmyspot.cat.common.runner.SecretFile;
import com.holdmyspot.cat.common.runner.SourceHome;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.SessionRefusedException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.toml.TomlMapper;

/**
 * The Codex authentication that a nested session takes from its launch's source home: the source's {@code auth.json}
 * when it holds an API key, and the settings of the source's {@code config.toml} that choose the model provider and its
 * credential, which are {@code model_provider}, {@code openai_base_url}, {@code forced_login_method} and the table of
 * the provider that {@code model_provider} selects.
 * <p>
 * The session's Codex chooses among these parts by its own rules. The runner copies them, and nothing else of the
 * source's Codex folder, into the session's Codex folder: the settings into the session's {@code config.toml}, with
 * the provider's token command wrapped so that it runs with the source home as its home folder, and the
 * {@code auth.json} as a copy that its owner alone may read, which the session's commands may not read and which is
 * removed when the session's app-server ends.
 * <p>
 * A selected provider that takes its token from the variable that its {@code env_key} names, or that holds it inline as
 * an {@code experimental_bearer_token}, receives it through the session's key file instead: the session's copy of the
 * provider drops both settings and gains an {@code auth} token command that prints the key file, which Codex runs
 * outside the command sandbox, so that the token is neither in the session's {@code config.toml} nor in any
 * environment.
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
	public static final String LOGIN = "auth.json";
	/**
	 * The name of Codex's configuration file in its folder.
	 */
	private static final String CONFIGURATION = "config.toml";
	/**
	 * The most bytes of a source file that the runner reads.
	 */
	private static final int MAXIMUM_FILE_BYTES = 1_048_576;
	/**
	 * The provider that Codex selects when {@code model_provider} is not set.
	 */
	private static final String DEFAULT_PROVIDER = "openai";
	/**
	 * The top-level settings of {@code config.toml} that choose the session's credential or its model host, in the
	 * order in which the session receives them.
	 */
	private static final List<String> TOP_LEVEL_SETTINGS = List.of("model_provider", "openai_base_url",
		"forced_login_method");
	/**
	 * The name of the key file in a session's Codex folder.
	 */
	private static final String KEY_FILE = "cat-api-key";
	/**
	 * How long Codex waits for the token command that prints the key file, in milliseconds.
	 */
	private static final int KEY_FILE_COMMAND_TIMEOUT_MS = 30_000;
	/**
	 * The settings of a provider's table that send a secret in headers, which cannot become a bearer token.
	 */
	private static final List<String> HEADER_SECRET_SETTINGS = List.of("env_http_headers", "http_headers");
	/**
	 * The values of {@code cli_auth_credentials_store} that keep the login in the operating system's store.
	 */
	private static final Set<String> SYSTEM_STORES = Set.of("keyring", "auto");

	private final SourceHome source;
	private final Path folder;
	private final Path configuration;
	private final ObjectNode settings;
	private final List<String> settingNames;
	private final Path login;
	private final HostLogin loginClass;
	private final String modelHost;
	private final List<Path> secretFiles;
	private final List<Path> sourceFiles;
	private final String providerId;
	private final String keyFileToken;

	/**
	 * Creates an authentication.
	 *
	 * @param source        the source home
	 * @param folder        the source's Codex folder
	 * @param configuration the source's {@code config.toml}, which may not exist
	 * @param settings      the settings that the session takes, as dotted keys of the configuration's root
	 * @param settingNames  the names of the settings that the session takes, in order
	 * @param login         the source's {@code auth.json} when it holds an API key, or {@code null}
	 * @param loginClass    the class of the session's login
	 * @param modelHost     the host of the session's model requests
	 * @param secretFiles   the source files that hold a secret
	 * @param sourceFiles   the source files that the runner reads for the session's credential
	 * @param providerId    the identifier of the selected provider
	 * @param keyFileToken  the token that the session's key file holds, or {@code null} if the selected provider does
	 *                      not supply one through {@code env_key} or {@code experimental_bearer_token}
	 */
	private CodexAuthentication(SourceHome source, Path folder, Path configuration, ObjectNode settings,
		List<String> settingNames, Path login, HostLogin loginClass, String modelHost, List<Path> secretFiles,
		List<Path> sourceFiles, String providerId, String keyFileToken)
	{
		this.source = source;
		this.folder = folder;
		this.configuration = configuration;
		this.settings = settings;
		this.settingNames = settingNames;
		this.login = login;
		this.loginClass = loginClass;
		this.modelHost = modelHost;
		this.secretFiles = secretFiles;
		this.sourceFiles = sourceFiles;
		this.providerId = providerId;
		this.keyFileToken = keyFileToken;
	}

	/**
	 * Reads the authentication of a launch's source home. The source's Codex folder is {@code .codex} in a home that
	 * the launch names; otherwise it is the folder that the runner's {@code CODEX_HOME} names, or else {@code .codex}
	 * in the runner's home folder.
	 * <p>
	 * A source file holds a secret when it is {@code auth.json} or a file that the selected provider's token command
	 * names in its arguments by an absolute path or a path under {@code ~/}, which names the source home. Each one must
	 * hold its secret safely, as {@link SecretFile} decides from its metadata.
	 * <p>
	 * When the selected provider holds an inline {@code experimental_bearer_token}, that token is the session's key file
	 * content; otherwise, when it sets {@code env_key}, the content is the value of that variable in {@code environment}.
	 *
	 * @param options     the launch's choice of source home and gateway login class
	 * @param environment the runner's environment, which holds the variable that the selected provider's
	 *                    {@code env_key} names
	 * @param platform    the platform whose home variable names the runner's home folder
	 * @return the authentication
	 * @throws NullPointerException    if any of the arguments are null
	 * @throws SessionRefusedException if the source home does not exist; if its {@code config.toml} or
	 *                                 {@code auth.json} cannot be parsed; if the selected provider has no table, sets
	 *                                 {@code env_http_headers}, holds an inline {@code http_headers}, names in
	 *                                 {@code env_key} a variable that {@code environment} does not set, or signs in
	 *                                 through {@code gateway_oauth} without {@code credential_export}; if the source
	 *                                 keeps its login in the operating system's store; if its {@code auth.json} holds a
	 *                                 ChatGPT sign-in or no API key; if it holds no authentication; if a base URL names
	 *                                 no host, or the launch declares no class of login for it; or if a file that holds
	 *                                 a secret does not hold it safely. The reason names the files or the setting and
	 *                                 never quotes a secret.
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
		Path loginFile = folder.resolve(LOGIN);
		JsonNode values = readConfiguration(configuration);
		String providerId = values.path("model_provider").asString(DEFAULT_PROVIDER);
		JsonNode provider = values.path("model_providers").path(providerId);
		if (!provider.isObject() && !providerId.equals(DEFAULT_PROVIDER))
		{
			throw new SessionRefusedException("CAT does not start the session: " + configuration + " sets " +
				"model_provider to a provider without a [model_providers." + providerId + "] table, so CAT cannot " +
				"tell where the session's requests go or which credential they use. Add the provider's table, or name " +
				"another source home with " + AuthenticationOptions.HOME_OPTION + ".");
		}
		String settingPrefix = "model_providers." + providerId + ".";
		Optional<String> headerSecret = headerSecretSetting(provider);
		if (headerSecret.isPresent())
		{
			throw new SessionRefusedException("CAT does not start the session: " + configuration + " sets " +
				settingPrefix + headerSecret.get() + " for the selected provider " + providerId + ". A secret that " +
				"is sent in headers cannot become the bearer token that the session's key file holds. Give the " +
				"provider env_key, an experimental_bearer_token or a token command in its auth table instead, or " +
				"name another source home with " + AuthenticationOptions.HOME_OPTION + ".");
		}
		String keyFileToken = keyFileToken(provider, configuration, settingPrefix, providerId, environment);
		JsonNode gatewaySignIn = provider.path("gateway_oauth");
		if (!gatewaySignIn.isMissingNode() && gatewaySignIn.path("credential_export").isMissingNode())
		{
			throw new SessionRefusedException("CAT does not start the session: " + configuration + " sets " +
				settingPrefix + "gateway_oauth without credential_export, so the provider's sign-in is stored where " +
				"the runner cannot copy it into the session. Give it a credential_export command or a token command, " +
				"or name another source home with " + AuthenticationOptions.HOME_OPTION + ".");
		}
		boolean loginExists = Files.exists(loginFile, NOFOLLOW_LINKS);
		String store = values.path("cli_auth_credentials_store").asString("");
		if (SYSTEM_STORES.contains(store) && !loginExists)
		{
			throw new SessionRefusedException("CAT does not start the session: " + configuration + " sets " +
				"cli_auth_credentials_store = \"" + store + "\" and " + loginFile + " does not exist, so the source's " +
				"Codex login is in the operating system's credential store, which the runner cannot copy into a " +
				"session. Log in with cli_auth_credentials_store = \"file\", or name another source home with " +
				AuthenticationOptions.HOME_OPTION + ".");
		}
		Path apiKeyLogin = null;
		if (loginExists)
		{
			requireApiKeyLogin(loginFile);
			apiKeyLogin = loginFile;
		}
		JsonNode tokenCommand = provider.path("auth");
		boolean hasTokenCommand = tokenCommand.isObject() && tokenCommand.path("command").isString();
		boolean hasExport = !gatewaySignIn.isMissingNode();
		if (apiKeyLogin == null && !hasTokenCommand && !hasExport && keyFileToken == null)
		{
			throw new SessionRefusedException("CAT does not start the session: the source home " +
				source.directory() + " holds no Codex authentication. The runner read " + configuration + ", whose " +
				"selected provider " + providerId + " has no token command, env_key or experimental_bearer_token, and " +
				"found no " + loginFile + ". Log in " +
				"with an API key, as codex login --with-api-key does, or give the selected provider a token command, " +
				"or name another source home with " + AuthenticationOptions.HOME_OPTION + ".");
		}
		String baseUrl = provider.path("base_url").asString(null);
		String baseUrlSetting = settingPrefix + "base_url";
		if (baseUrl == null)
		{
			baseUrl = values.path("openai_base_url").asString(null);
			baseUrlSetting = "openai_base_url";
		}
		Optional<HostLogin> declared = options.gatewayLoginClass();
		if (baseUrl != null && declared.isEmpty())
		{
			throw new SessionRefusedException("CAT does not start the session: " + configuration + " sets " +
				baseUrlSetting + ", which sends the session's requests to a gateway, and no file records which kind " +
				"of login the gateway bills. Name it with " + AuthenticationOptions.LOGIN_CLASS_OPTION +
				" subscription or " + AuthenticationOptions.LOGIN_CLASS_OPTION + " api_key.");
		}
		String modelHost;
		try
		{
			modelHost = CodexConnectionConfiguration.modelHost(configuration, loginFile);
		}
		catch (SessionRefusedException e)
		{
			throw e;
		}
		catch (IOException e)
		{
			throw new SessionRefusedException("CAT does not start the session: " + e.getMessage() + ". Name the " +
				"gateway's URL there.", e);
		}
		List<Path> secretFiles = secretFiles(source, apiKeyLogin, tokenCommand);
		for (Path file : secretFiles)
		{
			Optional<String> problem = SecretFile.problem(file, SecretFile.read(file));
			if (problem.isPresent())
				throw new SessionRefusedException("CAT does not start the session: " + problem.get());
		}
		Set<Path> sourceFiles = new LinkedHashSet<>();
		if (Files.exists(configuration))
			sourceFiles.add(configuration);
		sourceFiles.addAll(secretFiles);
		ObjectNode settings = TomlMapper.builder().build().createObjectNode();
		List<String> settingNames = new ArrayList<>();
		for (String name : TOP_LEVEL_SETTINGS)
		{
			if (values.has(name))
			{
				settings.set(name, values.get(name));
				settingNames.add(name);
			}
		}
		if (provider.isObject())
		{
			ObjectNode copied = ((ObjectNode) provider).deepCopy();
			if (keyFileToken != null)
			{
				copied.remove("env_key");
				copied.remove("experimental_bearer_token");
			}
			else if (hasTokenCommand)
				copied.set("auth", wrapped(source, tokenCommand));
			settings.putObject("model_providers").set(providerId, copied);
			settingNames.add("model_providers." + providerId);
		}
		HostLogin loginClass = HostLogin.API_KEY;
		if (baseUrl != null)
			loginClass = declared.orElseThrow();
		return new CodexAuthentication(source, folder, configuration, settings, List.copyOf(settingNames),
			apiKeyLogin, loginClass, modelHost, secretFiles, List.copyOf(sourceFiles), providerId, keyFileToken);
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
	 * Returns the setting of the selected provider's table that sends a secret in headers.
	 *
	 * @param provider the selected provider's table, or a missing node
	 * @return the setting's name, or empty if the provider sets none of them
	 */
	private static Optional<String> headerSecretSetting(JsonNode provider)
	{
		for (String setting : HEADER_SECRET_SETTINGS)
		{
			JsonNode value = provider.path(setting);
			if (value.isMissingNode() || value.isNull())
				continue;
			if (value.isObject() && value.isEmpty())
				continue;
			return Optional.of(setting);
		}
		return Optional.empty();
	}

	/**
	 * Returns the token that the session's key file holds: the selected provider's inline
	 * {@code experimental_bearer_token}, or else the value of the variable that its {@code env_key} names.
	 *
	 * @param provider      the selected provider's table, or a missing node
	 * @param configuration the source's {@code config.toml}
	 * @param settingPrefix the prefix of the provider's settings in messages
	 * @param providerId    the identifier of the selected provider
	 * @param environment   the runner's environment
	 * @return the token, or {@code null} if the provider sets neither setting
	 * @throws SessionRefusedException if {@code env_key} names a variable that the environment does not set or sets to
	 *                                 blank text
	 */
	private static String keyFileToken(JsonNode provider, Path configuration, String settingPrefix, String providerId,
		Map<String, String> environment) throws SessionRefusedException
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
					"runner's environment does not set " + name + ". Set it where the runner starts, or name another " +
					"source home with " + AuthenticationOptions.HOME_OPTION + ".");
			}
		}
		String inline = provider.path("experimental_bearer_token").asString("");
		if (!inline.isBlank())
			return inline;
		return fromEnvironment;
	}

	/**
	 * Refuses a source login that holds no API key.
	 *
	 * @param loginFile the source's {@code auth.json}, which exists
	 * @throws SessionRefusedException if the file cannot be parsed, holds a ChatGPT sign-in or holds no API key
	 * @throws IOException             if the file cannot be read
	 */
	private static void requireApiKeyLogin(Path loginFile) throws IOException
	{
		JsonNode document;
		try
		{
			document = JsonMapper.builder().build().readTree(BoundedInput.readUtf8(loginFile, MAXIMUM_FILE_BYTES,
				"Codex login"));
		}
		catch (JacksonException _)
		{
			throw new SessionRefusedException("CAT does not start the session: the source's Codex login " + loginFile +
				" cannot be parsed, so CAT cannot tell which login it holds. Log in again.");
		}
		String mode = document.path("auth_mode").asString("");
		JsonNode tokens = document.path("tokens");
		boolean signIn = (tokens.isObject()) || (!mode.isEmpty() && !mode.equalsIgnoreCase("apikey"));
		if (signIn)
		{
			throw new SessionRefusedException("CAT does not start the session: the source's Codex login " + loginFile +
				" holds a ChatGPT sign-in, which Codex refreshes and which concurrent Codex processes cannot share. " +
				"Log in with an API key, as codex login --with-api-key does, or name another source home with " +
				AuthenticationOptions.HOME_OPTION + ".");
		}
		JsonNode key = document.path("OPENAI_API_KEY");
		if (!key.isString() || key.asString().isBlank())
		{
			throw new SessionRefusedException("CAT does not start the session: the source's Codex login " + loginFile +
				" holds no API key. Log in with an API key, as codex login --with-api-key does, or name another " +
				"source home with " + AuthenticationOptions.HOME_OPTION + ".");
		}
	}

	/**
	 * Returns a provider's token command, wrapped so that it runs with the source home as its home folder.
	 *
	 * @param source  the source home
	 * @param command the provider's {@code auth} table
	 * @return the wrapped table, whose other settings are the source's
	 */
	private static ObjectNode wrapped(SourceHome source, JsonNode command)
	{
		ObjectNode wrapped = ((ObjectNode) command).deepCopy();
		ArrayNode arguments = wrapped.putArray("args");
		arguments.add("HOME=" + source.directory());
		arguments.add(command.path("command").asString());
		for (JsonNode argument : command.path("args"))
			arguments.add(argument);
		wrapped.put("command", "env");
		return wrapped;
	}

	/**
	 * Returns the source files that hold a secret.
	 *
	 * @param source       the source home, which {@code ~/} names in the token command's arguments
	 * @param login        the source's {@code auth.json} when it holds an API key, or {@code null}
	 * @param tokenCommand the selected provider's {@code auth} table, or a missing node
	 * @return the files, each once
	 */
	private static List<Path> secretFiles(SourceHome source, Path login, JsonNode tokenCommand)
	{
		Set<Path> files = new LinkedHashSet<>();
		if (login != null)
			files.add(login);
		for (JsonNode argument : tokenCommand.path("args"))
		{
			if (!argument.isString())
				continue;
			for (String word : argument.asString().strip().split("\\s+"))
			{
				String unquoted = word.replaceAll("^['\"]|['\"]$", "");
				Path file;
				if (unquoted.startsWith("~/"))
					file = source.directory().resolve(unquoted.substring(2));
				else if (unquoted.startsWith("/"))
					file = Path.of(unquoted);
				else
					continue;
				if (Files.isRegularFile(file))
					files.add(file.normalize());
			}
		}
		return List.copyOf(files);
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
	 * Returns the host that the session's Codex sends its model requests to.
	 *
	 * @return the host, in lower case
	 */
	public String modelHost()
	{
		return modelHost;
	}

	/**
	 * Returns the class of the session's login: the declared class when the source names a base URL, otherwise an API
	 * key.
	 *
	 * @return the class
	 */
	public HostLogin loginClass()
	{
		return loginClass;
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
	 * Returns the source files that the runner reads for the session's credential: the source's {@code config.toml}
	 * when it exists, and the files that hold a secret.
	 *
	 * @return the files
	 */
	public List<Path> sourceFiles()
	{
		return sourceFiles;
	}

	/**
	 * Returns the settings that the session's {@code config.toml} takes from the source, as TOML dotted keys that belong
	 * at the file's root, before any table header. A provider whose token the session's key file holds has an
	 * {@code auth} token command that prints that file, in place of its {@code env_key} and
	 * {@code experimental_bearer_token}.
	 *
	 * @param codexFolder the session's Codex folder, which holds the key file
	 * @return the TOML text, empty if the source sets none of them
	 * @throws NullPointerException if {@code codexFolder} is null
	 */
	public String settings(Path codexFolder)
	{
		requireThat(codexFolder, "codexFolder").isNotNull();
		if (settings.isEmpty())
			return "";
		ObjectNode session = settings.deepCopy();
		if (keyFileToken != null)
		{
			ObjectNode tokenCommand = ((ObjectNode) session.path("model_providers").path(providerId)).putObject("auth");
			CodexKeyFileCommand.Invocation invocation = CodexKeyFileCommand.invocation(
				keyFile(codexFolder).toAbsolutePath());
			tokenCommand.put("command", invocation.program());
			ArrayNode arguments = tokenCommand.putArray("args");
			for (String argument : invocation.arguments())
				arguments.add(argument);
			// A Java program starts slower than the default allows on a loaded machine
			tokenCommand.put("timeout_ms", KEY_FILE_COMMAND_TIMEOUT_MS);
		}
		String text = TomlMapper.builder().build().writeValueAsString(session);
		if (!text.endsWith("\n"))
			text += System.lineSeparator();
		return text;
	}

	/**
	 * Returns the key file of a session's Codex folder, which holds the token that the session's token command prints
	 * while the session's app-server runs.
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
	 * Writes the session's key file before its app-server starts or resumes. The file is created new with access for its
	 * owner alone, set as it is created, and receives the token from memory. A key file that an earlier launch left is
	 * removed first, so a resume writes the resuming launch's token; for a provider that supplies no token through
	 * {@code env_key} or {@code experimental_bearer_token}, no key file remains.
	 *
	 * @param codexFolder the session's Codex folder, which exists
	 * @throws NullPointerException if {@code codexFolder} is null
	 * @throws IOException          if the file cannot be removed, created or written; a file that was created is then
	 *                              removed
	 */
	public void writeKeyFile(Path codexFolder) throws IOException
	{
		Path file = keyFile(codexFolder);
		removeKeyFile(codexFolder);
		if (keyFileToken == null)
			return;
		byte[] bytes = keyFileToken.getBytes(UTF_8);
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

	/**
	 * Writes the session's copy of the source's {@code auth.json} before its app-server starts or resumes. A copy that
	 * an earlier launch left is removed first, so a resume copies the resuming launch's file; for a source without an
	 * API-key {@code auth.json}, no copy remains. The copy is created new with access for its owner alone, set as it is
	 * created.
	 *
	 * @param codexFolder the session's Codex folder, which exists
	 * @throws NullPointerException if {@code codexFolder} is null
	 * @throws IOException          if the copy cannot be removed, created or written; a copy that was created is then
	 *                              removed
	 */
	public void copyLogin(Path codexFolder) throws IOException
	{
		requireThat(codexFolder, "codexFolder").isNotNull();
		removeLogin(codexFolder);
		if (login == null)
			return;
		byte[] bytes = Files.readAllBytes(login);
		try
		{
			SecretFile.create(codexFolder.resolve(LOGIN), bytes);
		}
		finally
		{
			Arrays.fill(bytes, (byte) 0);
		}
	}

	/**
	 * Removes the session's copy of the source's {@code auth.json}, if any.
	 *
	 * @param codexFolder the session's Codex folder
	 * @throws NullPointerException if {@code codexFolder} is null
	 * @throws IOException          if the copy cannot be removed
	 */
	public static void removeLogin(Path codexFolder) throws IOException
	{
		requireThat(codexFolder, "codexFolder").isNotNull();
		Files.deleteIfExists(codexFolder.resolve(LOGIN));
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
	 * reach.
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
					"requests it denies reading " + denial.get() + ", which holds " + file + ", a file that the runner " +
					"reads from the source home " + source.directory() + " for the session's credential. A launch " +
					"never uses a credential that its requester may not read. Let the requesting session's sandbox " +
					"read " + file + ", or name another source home with " + AuthenticationOptions.HOME_OPTION + ".");
			}
		}
	}

	/**
	 * Returns the record of the session's authentication: the source home, the names of the settings that the session
	 * takes and the path of the source's {@code auth.json} when the session takes it, and the class of its login.
	 *
	 * @return the evidence
	 */
	public AuthenticationEvidence evidence()
	{
		List<String> parts = new ArrayList<>(settingNames);
		if (login != null)
		{
			Path absolute = login.toAbsolutePath().normalize();
			if (absolute.startsWith(source.directory()))
				parts.add(source.directory().relativize(absolute).toString().replace('\\', '/'));
			else
				parts.add(absolute.toString());
		}
		return new AuthenticationEvidence(source.directory(), parts, loginClass);
	}

	@Override
	public String toString()
	{
		return "CodexAuthentication{source=" + source.directory() + ", folder=" + folder + ", configuration=" +
			configuration + ", parts=" + evidence().parts() + ", loginClass=" + loginClass + "}";
	}
}
