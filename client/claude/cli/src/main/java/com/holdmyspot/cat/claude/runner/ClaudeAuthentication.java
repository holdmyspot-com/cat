package com.holdmyspot.cat.claude.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.holdmyspot.cat.common.BoundedInput;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.optimizer.port.HostLogin;
import com.holdmyspot.cat.common.runner.AuthenticationEvidence;
import com.holdmyspot.cat.common.runner.AuthenticationOptions;
import com.holdmyspot.cat.common.runner.HarnessEnvironment;
import com.holdmyspot.cat.common.runner.HarnessRunnerOptions;
import com.holdmyspot.cat.common.runner.SecretFile;
import com.holdmyspot.cat.common.runner.SourceHome;
import com.holdmyspot.cat.common.runner.policy.ChildPolicy;
import com.holdmyspot.cat.common.runner.policy.SessionRefusedException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Claude authentication that a nested session takes from a source home's Claude folder, as the user's own Claude
 * Code in that home would authenticate. Its parts are the {@code apiKeyHelper} and the {@code env} entries
 * {@code ANTHROPIC_BASE_URL}, {@code ANTHROPIC_AUTH_TOKEN}, {@code ANTHROPIC_API_KEY} and
 * {@code CLAUDE_CODE_OAUTH_TOKEN} of the folder's {@code settings.json}, and the stored login that Claude Code's
 * {@code /login} keeps in {@code .credentials.json}. Claude Code chooses among them by its own order:
 * {@code ANTHROPIC_AUTH_TOKEN}, then {@code ANTHROPIC_API_KEY}, then {@code apiKeyHelper}, then
 * {@code CLAUDE_CODE_OAUTH_TOKEN}, then the stored login.
 * <p>
 * The session receives the helper in its own settings, wrapped to run with the source home as its home folder, the
 * tokens and the base URL in its Claude process's environment, never in a file, and shares the stored login through
 * {@code CLAUDE_SECURESTORAGE_CONFIG_DIR}, which names the source's Claude folder: the session's Claude Code reads and
 * refreshes that login in place, so a refresh never stops the user's own login. Nothing else of the source home reaches
 * the session.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class ClaudeAuthentication
{
	/**
	 * The name of the Claude folder in a home folder.
	 */
	public static final String FOLDER = ".claude";
	/**
	 * The variable with which Claude Code moves its folder away from the home folder.
	 */
	public static final String FOLDER_VARIABLE = "CLAUDE_CONFIG_DIR";
	/**
	 * The variables that select a session's credential or the login store that its Claude Code reads. Only the source
	 * home's files decide them: the session's Claude process receives none of the runner's own values.
	 */
	public static final Set<String> CREDENTIAL_VARIABLES = Set.of("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN",
		"ANTHROPIC_BASE_URL", "CLAUDE_CODE_OAUTH_TOKEN", "CLAUDE_SECURESTORAGE_CONFIG_DIR");
	/**
	 * The file in which Claude Code's {@code /login} stores a login, in a Claude folder.
	 */
	private static final String STORED_LOGIN = ".credentials.json";
	/**
	 * The settings file of a Claude folder.
	 */
	private static final String SETTINGS = "settings.json";
	/**
	 * The setting that names a command that prints an API key.
	 */
	private static final String HELPER = "apiKeyHelper";
	/**
	 * The {@code env} entry that sets how long Claude Code keeps the key that {@link #HELPER} printed.
	 */
	private static final String HELPER_LIFETIME = "CLAUDE_CODE_API_KEY_HELPER_TTL_MS";
	/**
	 * The {@code env} entry that names the base URL to which Claude Code sends its requests.
	 */
	private static final String BASE_URL = "ANTHROPIC_BASE_URL";
	/**
	 * The {@code env} entry of a bearer token, which Claude Code ranks first.
	 */
	private static final String AUTH_TOKEN = "ANTHROPIC_AUTH_TOKEN";
	/**
	 * The {@code env} entry of an API key, which Claude Code ranks after {@link #AUTH_TOKEN}.
	 */
	private static final String API_KEY = "ANTHROPIC_API_KEY";
	/**
	 * The {@code env} entry of a subscription's OAuth token, which Claude Code ranks after {@link #HELPER}.
	 */
	private static final String OAUTH_TOKEN = "CLAUDE_CODE_OAUTH_TOKEN";
	/**
	 * The {@code env} entries that select a credential that the runner does not copy, a cloud provider or a profile.
	 * A source that sets one is refused: the session would otherwise bill another credential than the user's own Claude
	 * Code.
	 */
	private static final List<String> UNCOPIED_CREDENTIALS = List.of("CLAUDE_CODE_USE_BEDROCK",
		"CLAUDE_CODE_USE_VERTEX", "CLAUDE_CODE_USE_FOUNDRY", "ANTHROPIC_PROFILE");
	/**
	 * The variable that names the Claude folder whose stored login Claude Code reads and refreshes.
	 */
	private static final String LOGIN_STORE = "CLAUDE_SECURESTORAGE_CONFIG_DIR";
	/**
	 * The variable that has Claude Code remove credentials from the environment of every command and hook that it
	 * starts.
	 */
	private static final String SCRUB = "CLAUDE_CODE_SUBPROCESS_ENV_SCRUB";
	/**
	 * The size of the largest settings file that is read.
	 */
	private static final int MAXIMUM_SETTINGS_BYTES = 1_048_576;
	private final SourceHome source;
	private final Path folder;
	private final Path settings;
	/**
	 * The tokens of the source's {@code env}, by variable, in the order that Claude Code ranks them.
	 */
	private final Map<String, String> tokens;
	private final String baseUrl;
	private final String helper;
	private final String helperLifetime;
	private final boolean storedLogin;
	private final HostLogin loginClass;
	private final String modelHost;
	private final List<Path> secretFiles;
	private final List<Path> sourceFiles;

	/**
	 * Creates authentication.
	 *
	 * @param source         the source home
	 * @param folder         the source home's Claude folder
	 * @param settings       the folder's settings file
	 * @param tokens         the tokens of the source's {@code env}, by variable, in the order that Claude Code ranks
	 *                       them
	 * @param baseUrl        the source's {@code ANTHROPIC_BASE_URL}, or {@code null} if it sets none
	 * @param helper         the source's {@code apiKeyHelper}, or {@code null} if it sets none
	 * @param helperLifetime the source's {@code CLAUDE_CODE_API_KEY_HELPER_TTL_MS}, or {@code null} if it sets none or
	 *                       sets no helper
	 * @param storedLogin    {@code true} if the folder holds a stored login
	 * @param loginClass     the class of the login that Claude Code selects
	 * @param modelHost      the host of the session's model provider
	 * @param secretFiles    the source files that hold a secret
	 * @param sourceFiles    the source files that the runner reads
	 */
	private ClaudeAuthentication(SourceHome source, Path folder, Path settings, Map<String, String> tokens,
		String baseUrl, String helper, String helperLifetime, boolean storedLogin, HostLogin loginClass,
		String modelHost, List<Path> secretFiles, List<Path> sourceFiles)
	{
		this.source = source;
		this.folder = folder;
		this.settings = settings;
		this.tokens = tokens;
		this.baseUrl = baseUrl;
		this.helper = helper;
		this.helperLifetime = helperLifetime;
		this.storedLogin = storedLogin;
		this.loginClass = loginClass;
		this.modelHost = modelHost;
		this.secretFiles = secretFiles;
		this.sourceFiles = sourceFiles;
	}

	/**
	 * Reads the authentication of a launch's source home. The source's Claude folder is {@code .claude} in a home that
	 * the launch names; otherwise it is the folder that the runner's {@code CLAUDE_CONFIG_DIR} names, or else
	 * {@code .claude} in the runner's home folder.
	 * <p>
	 * A source file holds a secret when it is the stored login, a file that the {@code apiKeyHelper} command names by an
	 * absolute path or a path under {@code ~/}, which names the source home, or the settings file when its {@code env}
	 * holds a token. Each one must hold its secret safely, as {@link SecretFile} decides from its metadata.
	 *
	 * @param json        reads the source's settings file
	 * @param options     the launch's choice of source home and gateway login class
	 * @param environment the runner's environment
	 * @param platform    the platform whose home variable names the runner's home folder
	 * @return the authentication
	 * @throws NullPointerException    if any of the arguments are null
	 * @throws SessionRefusedException if the source home does not exist; if its settings file cannot be parsed; if its
	 *                                 {@code env} selects a cloud provider or a profile; if it holds none of the parts;
	 *                                 if its base URL names no host, or the launch declares no class of login for it;
	 *                                 or if a file that holds a secret does not hold it safely. The reason names the
	 *                                 files or the setting and never quotes a secret.
	 * @throws IOException             if a source file cannot be read
	 */
	public static ClaudeAuthentication read(JsonMapper json, AuthenticationOptions options,
		Map<String, String> environment, Platform platform) throws IOException
	{
		requireThat(json, "json").isNotNull();
		requireThat(options, "options").isNotNull();
		requireThat(environment, "environment").isNotNull();
		requireThat(platform, "platform").isNotNull();
		SourceHome source = options.sourceHome(environment, platform);
		Path folder = source.folder(FOLDER, FOLDER_VARIABLE, environment);
		Path settings = folder.resolve(SETTINGS);
		Path login = folder.resolve(STORED_LOGIN);
		JsonNode document = readSettings(json, settings);
		JsonNode env = document.path("env");
		for (String variable : UNCOPIED_CREDENTIALS)
		{
			if (!env.path(variable).isMissingNode() && !env.path(variable).isNull())
			{
				throw new SessionRefusedException("CAT does not start the session: " + settings + " sets env." + variable +
					", which selects a credential that CAT does not copy into a session, so the session would bill " +
					"another credential than the user's own Claude Code. Remove the setting, or name another source " +
					"home with " + AuthenticationOptions.HOME_OPTION + ".");
			}
		}
		Map<String, String> tokens = new LinkedHashMap<>();
		for (String variable : List.of(AUTH_TOKEN, API_KEY, OAUTH_TOKEN))
		{
			String value = text(env.path(variable));
			if (value != null)
				tokens.put(variable, value);
		}
		String baseUrl = text(env.path(BASE_URL));
		String helper = text(document.path(HELPER));
		String helperLifetime = null;
		if (helper != null)
			helperLifetime = text(env.path(HELPER_LIFETIME));
		boolean storedLogin = Files.exists(login, NOFOLLOW_LINKS);
		if (tokens.isEmpty() && helper == null && !storedLogin)
		{
			throw new SessionRefusedException("CAT does not start the session: the source home " +
				source.directory() + " holds no Claude authentication. The runner read " + settings + ", which sets " +
				"neither " + HELPER + " nor env." + AUTH_TOKEN + ", env." + API_KEY + " or env." + OAUTH_TOKEN +
				", and found no stored login " + login + ". Log in to Claude Code with /login in that home, add one " +
				"of those settings, or name another source home with " + AuthenticationOptions.HOME_OPTION + ".");
		}
		Optional<HostLogin> declared = options.gatewayLoginClass();
		String modelHost = modelHost(settings, baseUrl);
		if (baseUrl != null && declared.isEmpty())
		{
			throw new SessionRefusedException("CAT does not start the session: " + settings + " sets env." + BASE_URL +
				", which sends the session's requests to a gateway, and no file records which kind of login the " +
				"gateway bills. Name it with " + AuthenticationOptions.LOGIN_CLASS_OPTION + " subscription or " +
				AuthenticationOptions.LOGIN_CLASS_OPTION + " api_key.");
		}
		HostLogin loginClass = loginClass(tokens, helper, declared.orElse(null));
		List<Path> secretFiles = secretFiles(source, settings, login, tokens, helper, storedLogin);
		for (Path file : secretFiles)
		{
			Optional<String> problem = SecretFile.problem(file, SecretFile.read(file));
			if (problem.isPresent())
				throw new SessionRefusedException("CAT does not start the session: " + problem.get());
		}
		Set<Path> sourceFiles = new LinkedHashSet<>();
		if (Files.exists(settings))
			sourceFiles.add(settings);
		sourceFiles.addAll(secretFiles);
		return new ClaudeAuthentication(source, folder, settings, Collections.unmodifiableMap(tokens), baseUrl, helper,
			helperLifetime, storedLogin, loginClass, modelHost, secretFiles, List.copyOf(sourceFiles));
	}

	/**
	 * Reads a source's settings file.
	 *
	 * @param json     the JSON mapper
	 * @param settings the file
	 * @return the file's JSON object, or a missing node if the file does not exist
	 * @throws SessionRefusedException if the file is not a JSON object
	 * @throws IOException             if the file cannot be read
	 */
	private static JsonNode readSettings(JsonMapper json, Path settings) throws IOException
	{
		if (!Files.exists(settings))
			return json.missingNode();
		JsonNode document;
		try
		{
			document = json.readTree(BoundedInput.readUtf8(settings, MAXIMUM_SETTINGS_BYTES, "Claude settings"));
		}
		catch (JacksonException _)
		{
			throw new SessionRefusedException("CAT does not start the session: the source's Claude settings file " +
				settings + " cannot be parsed, so CAT cannot tell which credential it selects. Correct the file.");
		}
		if (!document.isObject())
		{
			throw new SessionRefusedException("CAT does not start the session: the source's Claude settings file " +
				settings + " is not a JSON object. Correct the file.");
		}
		return document;
	}

	/**
	 * Returns a setting's text.
	 *
	 * @param value the setting
	 * @return its text, or {@code null} if it is not text or is blank
	 */
	private static String text(JsonNode value)
	{
		if (!value.isString() || value.stringValue().isBlank())
			return null;
		return value.stringValue();
	}

	/**
	 * Returns the host of a session's model provider.
	 *
	 * @param settings the source's settings file, which a refusal names
	 * @param baseUrl  the source's {@code ANTHROPIC_BASE_URL}, or {@code null} if it sets none
	 * @return the base URL's host, or Anthropic's API host without one
	 * @throws SessionRefusedException if the base URL names no host
	 */
	private static String modelHost(Path settings, String baseUrl) throws SessionRefusedException
	{
		Map<String, String> connection = Map.of();
		if (baseUrl != null)
			connection = Map.of(BASE_URL, baseUrl);
		try
		{
			return ClaudeConnectionSettings.modelHost(connection);
		}
		catch (IllegalArgumentException e)
		{
			throw new SessionRefusedException("CAT does not start the session: env." + BASE_URL + " in " + settings +
				" names no host. Name the gateway's URL there.", e);
		}
	}

	/**
	 * Returns the class of the login that Claude Code selects among a source's parts.
	 *
	 * @param tokens   the source's tokens
	 * @param helper   the source's {@code apiKeyHelper}, or {@code null} if it sets none
	 * @param declared the class that the launch declares for the source's base URL, or {@code null} if the source sets
	 *                 none
	 * @return {@code declared} when the source sends its requests to a gateway; otherwise {@code unknown} for a bearer
	 *   token, whose billing CAT cannot see, {@code api_key} for an API key or a helper, and {@code subscription} for
	 *   an OAuth token or a stored login
	 */
	private static HostLogin loginClass(Map<String, String> tokens, String helper, HostLogin declared)
	{
		if (declared != null)
			return declared;
		if (tokens.containsKey(AUTH_TOKEN))
			return HostLogin.UNKNOWN;
		if (tokens.containsKey(API_KEY) || helper != null)
			return HostLogin.API_KEY;
		return HostLogin.SUBSCRIPTION;
	}

	/**
	 * Returns the source files that hold a secret.
	 *
	 * @param source      the source home, which {@code ~/} names in the helper's command
	 * @param settings    the source's settings file
	 * @param login       the source's stored login
	 * @param tokens      the source's tokens
	 * @param helper      the source's {@code apiKeyHelper}, or {@code null} if it sets none
	 * @param storedLogin {@code true} if the source holds a stored login
	 * @return the files, each once
	 */
	private static List<Path> secretFiles(SourceHome source, Path settings, Path login, Map<String, String> tokens,
		String helper, boolean storedLogin)
	{
		Set<Path> files = new LinkedHashSet<>();
		if (!tokens.isEmpty())
			files.add(settings);
		if (storedLogin)
			files.add(login);
		if (helper != null)
		{
			for (String word : helper.strip().split("\\s+"))
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
	 * Returns the source home's Claude folder, whose stored login the session shares.
	 *
	 * @return the folder
	 */
	public Path folder()
	{
		return folder;
	}

	/**
	 * Returns the variables that the session's Claude process receives for its credential: the source's tokens and base
	 * URL, {@code CLAUDE_CODE_SUBPROCESS_ENV_SCRUB=1} whenever a token is among them, so that Claude Code passes no token
	 * to the commands and hooks that it starts, {@code CLAUDE_SECURESTORAGE_CONFIG_DIR} naming the source's Claude
	 * folder, and {@value HarnessEnvironment#HOST_LOGIN_VARIABLE} stating {@link #loginClass()}.
	 *
	 * @return the variables, which the caller may change
	 */
	public Map<String, String> environment()
	{
		Map<String, String> environment = new LinkedHashMap<>(tokens);
		if (baseUrl != null)
			environment.put(BASE_URL, baseUrl);
		if (!tokens.isEmpty())
			environment.put(SCRUB, "1");
		environment.put(LOGIN_STORE, folder.toString());
		environment.put(HarnessEnvironment.HOST_LOGIN_VARIABLE, AuthenticationOptions.classValue(loginClass));
		return environment;
	}

	/**
	 * Returns the host of the session's model provider, the one host that the session needs for its model.
	 *
	 * @return the host of the source's base URL, in lower case, or Anthropic's API host without one
	 */
	public String modelHost()
	{
		return modelHost;
	}

	/**
	 * Returns the class of the login that Claude Code selects among the source's parts.
	 *
	 * @return the class
	 */
	public HostLogin loginClass()
	{
		return loginClass;
	}

	/**
	 * Returns the source files that hold a secret, which the session's commands and file tools, and those of every
	 * session that it requests, must not read.
	 *
	 * @return the files
	 */
	public List<Path> secretFiles()
	{
		return secretFiles;
	}

	/**
	 * Returns the source files that the runner reads, or that the session's Claude Code or its helper read, for the
	 * session's credential: the settings file if it exists and each file that holds a secret.
	 *
	 * @return the files
	 */
	public List<Path> sourceFiles()
	{
		return sourceFiles;
	}

	/**
	 * Adds the source's helper to a session's settings, wrapped so that it runs with the source home as its home
	 * folder, so that a {@code ~} in it names the same file as for the user, together with the helper's key lifetime
	 * when the source sets one.
	 *
	 * @param sessionSettings the session's settings
	 * @throws NullPointerException if {@code sessionSettings} is null
	 */
	public void addTo(ObjectNode sessionSettings)
	{
		requireThat(sessionSettings, "sessionSettings").isNotNull();
		if (helper == null)
			return;
		sessionSettings.put(HELPER, "env HOME=" + shellQuoted(source.directory().toString()) + " sh -c " +
			shellQuoted(helper));
		if (helperLifetime == null)
			return;
		JsonNode env = sessionSettings.path("env");
		ObjectNode entries;
		if (env instanceof ObjectNode object)
			entries = object;
		else
			entries = sessionSettings.putObject("env");
		entries.put(HELPER_LIFETIME, helperLifetime);
	}

	/**
	 * Quotes text as one word of a POSIX shell command.
	 *
	 * @param text the text
	 * @return the text in single quotes, with each single quote in it closed, escaped and reopened
	 */
	private static String shellQuoted(String text)
	{
		return "'" + text.replace("'", "'\\''") + "'";
	}

	/**
	 * Returns a launch's options with the source files that hold a secret among its denied reads, so that neither the
	 * session nor any session that it requests may read them.
	 *
	 * @param options the launch's options
	 * @return the options
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
	public void requireReadable(ChildPolicy bound) throws IOException
	{
		requireThat(bound, "bound").isNotNull();
		for (Path file : sourceFiles)
		{
			Optional<Path> denial = bound.asParentPolicy().readDenial(file.toAbsolutePath());
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
	 * Returns the record of the session's authentication: the source home, the names of the copied and shared parts,
	 * in the order that Claude Code ranks them, and the class of the login that it selects.
	 *
	 * @return the evidence
	 */
	public AuthenticationEvidence evidence()
	{
		List<String> parts = new ArrayList<>();
		if (baseUrl != null)
			parts.add(BASE_URL);
		for (String variable : List.of(AUTH_TOKEN, API_KEY))
		{
			if (tokens.containsKey(variable))
				parts.add(variable);
		}
		if (helper != null)
			parts.add(HELPER);
		if (helperLifetime != null)
			parts.add(HELPER_LIFETIME);
		if (tokens.containsKey(OAUTH_TOKEN))
			parts.add(OAUTH_TOKEN);
		if (storedLogin)
			parts.add(STORED_LOGIN);
		return new AuthenticationEvidence(source.directory(), parts, loginClass);
	}

	@Override
	public String toString()
	{
		return "ClaudeAuthentication{source=" + source.directory() + ", settings=" + settings + ", parts=" +
			evidence().parts() + ", loginClass=" + loginClass + "}";
	}
}
