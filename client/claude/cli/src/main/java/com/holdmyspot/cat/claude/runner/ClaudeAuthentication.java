package com.holdmyspot.cat.claude.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.holdmyspot.cat.claude.credential.ClaudeCredentialFile;
import com.holdmyspot.cat.claude.credential.CredentialRefusedException;
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
 * The Claude authentication that a nested session takes from a source home, as the user's own Claude Code in that home
 * would authenticate. Its parts are the {@code apiKeyHelper} and the {@code env} entries {@code ANTHROPIC_BASE_URL},
 * {@code ANTHROPIC_AUTH_TOKEN}, {@code ANTHROPIC_API_KEY} and {@code CLAUDE_CODE_OAUTH_TOKEN} of the
 * {@code settings.json} of the source home's Claude folder, and CAT's credential file, as {@link ClaudeCredentialFile}
 * reads it. The runner chooses the one credential that the session receives by Claude Code's own order, with CAT's
 * credential file in the place of the stored login: {@code ANTHROPIC_AUTH_TOKEN}, then {@code ANTHROPIC_API_KEY},
 * then {@code apiKeyHelper}, then {@code CLAUDE_CODE_OAUTH_TOKEN}, then CAT's credential file.
 * <p>
 * A chosen token reaches the session's Claude Code only through the session's key file, which the one
 * {@code apiKeyHelper} of the session's settings prints with {@code cat}; no token is in the Claude process's
 * environment, in a settings file or on a command line, so the commands and hooks that Claude Code starts inherit none.
 * A chosen helper is copied into the session's settings, wrapped to run with the source home as its home folder. The
 * base URL goes into the Claude process's environment. A login that Claude Code's {@code /login} stores is never
 * shared, copied or read: the session's Claude Code keeps its own login store, the session's new and empty Claude
 * folder, so sessions never refresh, write or replace the user's login. Nothing else of the source home reaches the
 * session.
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
	 * home's files decide the credential, and the session's Claude Code keeps its own login store: the session's Claude
	 * process receives none of the runner's own values.
	 */
	public static final Set<String> CREDENTIAL_VARIABLES = Set.of("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN",
		"ANTHROPIC_BASE_URL", "CLAUDE_CODE_OAUTH_TOKEN", "CLAUDE_SECURESTORAGE_CONFIG_DIR");
	/**
	 * The file in which Claude Code's {@code /login} stores a login, in a Claude folder. The runner never reads it, but
	 * denies the source's to the session, because it holds the user's own login.
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
	 * The {@code claude-credential} launcher, relative to the plugin folder, which saves CAT's credential file.
	 */
	private static final Path CREDENTIAL_LAUNCHER = Path.of("bin/claude-credential");
	/**
	 * The name of the key file in a session's Claude folder.
	 */
	private static final String KEY_FILE = "cat-api-key";
	/**
	 * The size of the largest settings file that is read.
	 */
	private static final int MAXIMUM_SETTINGS_BYTES = 1_048_576;
	private final SourceHome source;
	private final Path settings;
	private final String baseUrl;
	/**
	 * The name of the part that Claude Code's order selects.
	 */
	private final String selected;
	/**
	 * The selected token, which only the session's key file receives, or {@code null} if the source's helper is
	 * selected.
	 */
	private final String token;
	/**
	 * The source's {@code apiKeyHelper} when Claude Code's order selects it, or {@code null}.
	 */
	private final String helper;
	private final String helperLifetime;
	private final HostLogin loginClass;
	private final String modelHost;
	private final List<Path> secretFiles;
	private final List<Path> sourceFiles;
	/**
	 * The source files that the session and every session that it requests must not read.
	 */
	private final List<Path> deniedFiles;

	/**
	 * Creates authentication.
	 *
	 * @param source         the source home
	 * @param settings       the settings file of the source home's Claude folder
	 * @param baseUrl        the source's {@code ANTHROPIC_BASE_URL}, or {@code null} if it sets none
	 * @param selected       the name of the part that Claude Code's order selects
	 * @param token          the selected token, or {@code null} if the source's helper is selected
	 * @param helper         the source's {@code apiKeyHelper} when it is selected, or {@code null}
	 * @param helperLifetime the source's {@code CLAUDE_CODE_API_KEY_HELPER_TTL_MS} when the helper is selected, or
	 *                       {@code null}
	 * @param loginClass     the class of the selected credential
	 * @param modelHost      the host of the session's model provider
	 * @param secretFiles    the source files that hold a secret
	 * @param sourceFiles    the source files that the runner reads
	 * @param deniedFiles    the source files that the session and every session that it requests must not read
	 */
	private ClaudeAuthentication(SourceHome source, Path settings, String baseUrl, String selected, String token,
		String helper, String helperLifetime, HostLogin loginClass, String modelHost, List<Path> secretFiles,
		List<Path> sourceFiles, List<Path> deniedFiles)
	{
		this.source = source;
		this.settings = settings;
		this.baseUrl = baseUrl;
		this.selected = selected;
		this.token = token;
		this.helper = helper;
		this.helperLifetime = helperLifetime;
		this.loginClass = loginClass;
		this.modelHost = modelHost;
		this.secretFiles = secretFiles;
		this.sourceFiles = sourceFiles;
		this.deniedFiles = deniedFiles;
	}

	/**
	 * Reads the authentication of a launch's source home. The source's Claude folder is {@code .claude} in a home that
	 * the launch names; otherwise it is the folder that the runner's {@code CLAUDE_CONFIG_DIR} names, or else
	 * {@code .claude} in the runner's home folder. CAT's credential file lies in the source home whatever
	 * {@code CLAUDE_CONFIG_DIR} names.
	 * <p>
	 * A source file holds a secret when it is CAT's credential file, a file that the {@code apiKeyHelper} command names
	 * by an absolute path or a path under {@code ~/}, which names the source home, or the settings file when its
	 * {@code env} holds a token. Each one must hold its secret safely, as {@link SecretFile} decides from its metadata.
	 *
	 * @param json        reads the source's settings file
	 * @param options     the launch's choice of source home and gateway login class
	 * @param environment the runner's environment
	 * @param platform    the platform whose home variable names the runner's home folder
	 * @param plugin      the folder of the Claude Code plugin that holds the runner, whose launchers a refusal names
	 * @return the authentication
	 * @throws NullPointerException    if any of the arguments are null
	 * @throws SessionRefusedException if the source home does not exist; if its settings file cannot be parsed; if its
	 *                                 settings select a cloud provider or a profile; if it holds none of the parts, a
	 *                                 login that Claude Code's login command stores alone included; if CAT's
	 *                                 credential file is unusable, as {@link ClaudeCredentialFile#read} decides; if its
	 *                                 base URL names no host, or the launch declares no class of login for it; or if a
	 *                                 file that holds a secret does not hold it safely. The reason names the files or
	 *                                 the setting and never quotes a secret.
	 * @throws IOException             if a source file cannot be read
	 */
	public static ClaudeAuthentication read(JsonMapper json, AuthenticationOptions options,
		Map<String, String> environment, Platform platform, Path plugin) throws IOException
	{
		requireThat(json, "json").isNotNull();
		requireThat(options, "options").isNotNull();
		requireThat(environment, "environment").isNotNull();
		requireThat(platform, "platform").isNotNull();
		requireThat(plugin, "plugin").isNotNull();
		SourceHome source = options.sourceHome(environment, platform);
		Path folder = source.folder(FOLDER, FOLDER_VARIABLE, environment);
		Path settings = folder.resolve(SETTINGS);
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
		Optional<ClaudeCredentialFile> credential;
		try
		{
			credential = ClaudeCredentialFile.read(json, source.directory());
		}
		catch (CredentialRefusedException e)
		{
			throw new SessionRefusedException("CAT does not start the session: " + e.getMessage(), e);
		}
		if (tokens.isEmpty() && helper == null && credential.isEmpty())
		{
			// The refusal gives both fixes without looking for a /login login, which the runner never reads
			throw new SessionRefusedException("CAT does not start the session: the source home " +
				source.directory() + " holds no Claude authentication. The runner read " + settings + ", which sets " +
				"neither " + HELPER + " nor env." + AUTH_TOKEN + ", env." + API_KEY + " or env." + OAUTH_TOKEN +
				", and found no CAT credential file " + ClaudeCredentialFile.path(source.directory()) + ". Sessions do " +
				"not use a login that Claude Code's /login stores. Run claude setup-token and save the token that it " +
				"prints, or save an Anthropic API key, with " + plugin.resolve(CREDENTIAL_LAUNCHER) + " set " +
				AuthenticationOptions.HOME_OPTION + " " + source.directory() + "; or add one of those settings, or " +
				"name another source home with " + AuthenticationOptions.HOME_OPTION + ".");
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
		List<Path> secretFiles = secretFiles(source, settings, tokens, helper, credential, platform);
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
		List<Path> deniedFiles = new ArrayList<>(secretFiles);
		Path login = folder.resolve(STORED_LOGIN);
		if (Files.exists(login, NOFOLLOW_LINKS))
			deniedFiles.add(login);
		String selected = select(tokens, helper);
		String token = tokens.get(selected);
		HostLogin fileClass = null;
		if (selected.equals(ClaudeCredentialFile.RELATIVE_NAME))
		{
			token = credential.orElseThrow().token();
			fileClass = credential.orElseThrow().loginClass();
		}
		String selectedHelper = null;
		String selectedLifetime = null;
		if (selected.equals(HELPER))
		{
			selectedHelper = helper;
			selectedLifetime = helperLifetime;
		}
		HostLogin loginClass = loginClass(selected, fileClass, baseUrl, declared.orElse(null));
		return new ClaudeAuthentication(source, settings, baseUrl, selected, token, selectedHelper, selectedLifetime,
			loginClass, modelHost, secretFiles, List.copyOf(sourceFiles), List.copyOf(deniedFiles));
	}

	/**
	 * Returns the part that Claude Code's order selects among a source's parts.
	 *
	 * @param tokens the source's tokens, by variable
	 * @param helper the source's {@code apiKeyHelper}, or {@code null} if it sets none
	 * @return the name of the selected part: a token's variable, {@code apiKeyHelper} or the path of CAT's credential
	 *   file in the source home, which the caller selects only when the file exists
	 */
	private static String select(Map<String, String> tokens, String helper)
	{
		for (String variable : List.of(AUTH_TOKEN, API_KEY))
		{
			if (tokens.containsKey(variable))
				return variable;
		}
		if (helper != null)
			return HELPER;
		if (tokens.containsKey(OAUTH_TOKEN))
			return OAUTH_TOKEN;
		return ClaudeCredentialFile.RELATIVE_NAME;
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
	 * Returns the class of the credential that Claude Code's order selects among a source's parts.
	 *
	 * @param selected  the name of the selected part
	 * @param fileClass the class of the token of CAT's credential file when it is selected, or {@code null}
	 * @param baseUrl   the source's {@code ANTHROPIC_BASE_URL}, or {@code null} if it sets none
	 * @param declared  the class that the launch declares, or {@code null} if it declares none
	 * @return {@code declared} when the source sends its requests to a gateway; otherwise {@code unknown} for a bearer
	 *   token, whose billing CAT cannot see, {@code api_key} for an API key or a helper, {@code subscription} for a
	 *   {@code claude setup-token} token, and the class of the token of CAT's credential file, which its prefix gives
	 */
	private static HostLogin loginClass(String selected, HostLogin fileClass, String baseUrl, HostLogin declared)
	{
		if (baseUrl != null)
			return declared;
		if (fileClass != null)
			return fileClass;
		return switch (selected)
		{
			case AUTH_TOKEN -> HostLogin.UNKNOWN;
			case API_KEY, HELPER -> HostLogin.API_KEY;
			default -> HostLogin.SUBSCRIPTION;
		};
	}

	/**
	 * Returns the source files that hold a secret.
	 *
	 * @param source     the source home, which {@code ~/} names in the helper's command
	 * @param settings   the source's settings file
	 * @param tokens     the source's tokens
	 * @param helper     the source's {@code apiKeyHelper}, or {@code null} if it sets none
	 * @param credential CAT's credential file of the source home, or empty if it has none
	 * @param platform   the platform of the source home, where a path with a drive letter is absolute if it is Windows
	 * @return the files, each once
	 */
	private static List<Path> secretFiles(SourceHome source, Path settings, Map<String, String> tokens, String helper,
		Optional<ClaudeCredentialFile> credential, Platform platform)
	{
		Set<Path> files = new LinkedHashSet<>();
		if (!tokens.isEmpty())
			files.add(settings);
		credential.ifPresent(file -> files.add(file.file()));
		if (helper != null)
		{
			for (String word : helper.strip().split("\\s+"))
			{
				String unquoted = word.replaceAll("^['\"]|['\"]$", "");
				Path file;
				if (unquoted.startsWith("~/"))
					file = source.directory().resolve(unquoted.substring(2));
				else if (unquoted.startsWith("/") || (platform.isWindows() && isDriveAbsolute(unquoted)))
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
	 * Indicates whether a word of a helper's command names an absolute path with a drive letter, such as
	 * {@code C:\keys\key} or {@code C:/keys/key}, which the shell of Git for Windows accepts.
	 *
	 * @param word a word of the command, without quotes
	 * @return {@code true} if the word begins with a drive letter, a colon and a separator
	 */
	private static boolean isDriveAbsolute(String word)
	{
		return word.length() > 2 && Character.isLetter(word.charAt(0)) && word.charAt(1) == ':' &&
			(word.charAt(2) == '\\' || word.charAt(2) == '/');
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
	 * Returns the variables that the session's Claude process receives for its credential: the source's base URL, which
	 * is not a secret, and {@value HarnessEnvironment#HOST_LOGIN_VARIABLE} stating {@link #loginClass()}. They hold no
	 * token: a selected token reaches Claude Code only through the session's key file, as {@link #writeKeyFile(Path)}
	 * writes it. They name no login store either, so the session's Claude Code keeps its login in its own Claude folder.
	 *
	 * @return the variables, which the caller may change
	 */
	public Map<String, String> environment()
	{
		Map<String, String> environment = new LinkedHashMap<>();
		if (baseUrl != null)
			environment.put(BASE_URL, baseUrl);
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
	 * Adds the session's one {@code apiKeyHelper} to its settings. When Claude Code's order selects a token, the helper
	 * runs {@code cat} with the absolute path of the session's key file, so that the settings name the file and never
	 * the token. When it selects the source's helper, that helper is copied, wrapped so that it runs with the source
	 * home as its home folder, so that a {@code ~} in it names the same file as for the user, together with the helper's
	 * key lifetime when the source sets one.
	 *
	 * @param sessionSettings the session's settings
	 * @param claudeFolder    the session's Claude folder, which holds its key file
	 * @throws NullPointerException if any of the arguments are null
	 */
	public void addTo(ObjectNode sessionSettings, Path claudeFolder)
	{
		requireThat(sessionSettings, "sessionSettings").isNotNull();
		requireThat(claudeFolder, "claudeFolder").isNotNull();
		if (token != null)
		{
			sessionSettings.put(HELPER, "cat " + shellQuoted(keyFile(claudeFolder).toAbsolutePath().toString()));
			return;
		}
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
	 * Returns the key file of a session's Claude folder, which holds the token that Claude Code's order selects while
	 * the session's Claude process runs.
	 *
	 * @param claudeFolder the session's Claude folder
	 * @return the key file
	 * @throws NullPointerException if {@code claudeFolder} is null
	 */
	public static Path keyFile(Path claudeFolder)
	{
		requireThat(claudeFolder, "claudeFolder").isNotNull();
		return claudeFolder.resolve(KEY_FILE);
	}

	/**
	 * Writes the session's key file before its Claude process starts or resumes. The file is created new with access
	 * for its owner alone, set as it is created, and receives the selected token's bytes from memory. A key file that an
	 * earlier launch left is removed first, so a resume writes the resuming launch's token; when Claude Code's order
	 * selects the source's helper, no key file remains.
	 *
	 * @param claudeFolder the session's Claude folder, which exists
	 * @throws NullPointerException if {@code claudeFolder} is null
	 * @throws IOException          if the file cannot be removed, created or written; a file that was created is then
	 *                              removed
	 */
	public void writeKeyFile(Path claudeFolder) throws IOException
	{
		Path file = keyFile(claudeFolder);
		Files.deleteIfExists(file);
		if (token == null)
			return;
		SecretFile.create(file, token.getBytes(UTF_8));
	}

	/**
	 * Returns a launch's options with the source files that hold a secret, and the source's stored login when it exists,
	 * among its denied reads, so that neither the session nor any session that it requests may read them.
	 *
	 * @param options the launch's options
	 * @return the options
	 * @throws NullPointerException if {@code options} is null
	 */
	public HarnessRunnerOptions withDeniedReads(HarnessRunnerOptions options)
	{
		requireThat(options, "options").isNotNull();
		return options.withDeniedReads(deniedFiles);
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
	 * Returns the record of the session's authentication: the source home, the names of the parts that the session
	 * receives, which are the base URL when the source sets one, the part that Claude Code's order selects and, with a
	 * selected helper, its key lifetime when the source sets one, and the class of the selected credential.
	 *
	 * @return the evidence
	 */
	public AuthenticationEvidence evidence()
	{
		List<String> parts = new ArrayList<>();
		if (baseUrl != null)
			parts.add(BASE_URL);
		parts.add(selected);
		if (helperLifetime != null)
			parts.add(HELPER_LIFETIME);
		return new AuthenticationEvidence(source.directory(), parts, loginClass);
	}

	@Override
	public String toString()
	{
		return "ClaudeAuthentication{source=" + source.directory() + ", settings=" + settings + ", parts=" +
			evidence().parts() + ", loginClass=" + loginClass + "}";
	}
}
