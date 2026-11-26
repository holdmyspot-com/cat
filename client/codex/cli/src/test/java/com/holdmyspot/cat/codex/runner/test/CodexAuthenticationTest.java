package com.holdmyspot.cat.codex.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.testng.Assert.expectThrows;

import com.holdmyspot.cat.codex.runner.CodexAuthentication;
import com.holdmyspot.cat.codex.runner.CodexRunner;
import com.holdmyspot.cat.codex.runner.CodexSessionLogin;
import com.holdmyspot.cat.codex.runner.CodexTokenCommand;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.port.HostLogin;
import com.holdmyspot.cat.common.runner.AuthenticationOptions;
import com.holdmyspot.cat.common.runner.HarnessDependencyCaches;
import com.holdmyspot.cat.common.runner.login.CodexLoginClient;
import com.holdmyspot.cat.common.runner.login.CodexLoginService;
import com.holdmyspot.cat.common.runner.login.LoginRefusal;
import com.holdmyspot.cat.common.runner.policy.SessionRefusedException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.toml.TomlMapper;

/**
 * Tests the login that a nested Codex session takes from its source home, for each kind of login: the session's
 * Codex folder holds no {@code auth.json}, its {@code config.toml} stores no login and selects a provider whose token
 * command is CAT's, and no fixture secret is in the configuration or in the app-server's environment; only the key
 * file of a key that cannot change holds the key itself.
 */
public final class CodexAuthenticationTest
{
	private static final String ACCESS_TOKEN = CodexLoginServices.ACCESS_TOKEN;
	private static final String REFRESH_TOKEN = CodexLoginServices.REFRESH_TOKEN;
	private static final String ID_TOKEN = CodexLoginServices.ID_TOKEN;
	private static final String ACCOUNT_ID = CodexLoginServices.ACCOUNT_ID;
	private static final String HEADER_SECRET = "fixture-header-secret";
	private static final String COMMAND_TOKEN = "fixture-command-token";
	private static final String ENVIRONMENT_KEY = "fixture-environment-key";
	private static final List<String> SECRETS = List.of(CodexSourceHomes.SECRET, ACCESS_TOKEN, REFRESH_TOKEN,
		ID_TOKEN, HEADER_SECRET, COMMAND_TOKEN, ENVIRONMENT_KEY);

	/**
	 * Creates the test.
	 */
	public CodexAuthenticationTest()
	{
	}

	/**
	 * A source home and the runner environment of its launch.
	 *
	 * @param home        the source home
	 * @param environment the runner's environment, which names the login service when the test starts one
	 */
	private record Launch(Path home, Map<String, String> environment)
	{
	}

	/**
	 * Reads and opens a source home's login, with no declared gateway class.
	 *
	 * @param launch the launch
	 * @return the session's login
	 * @throws IOException if the login is refused
	 */
	private static CodexSessionLogin open(Launch launch) throws IOException
	{
		return open(launch, Optional.empty());
	}

	/**
	 * Reads and opens a source home's login.
	 *
	 * @param launch   the launch
	 * @param declared the declared gateway class, or empty
	 * @return the session's login
	 * @throws IOException if the login is refused
	 */
	private static CodexSessionLogin open(Launch launch, Optional<HostLogin> declared) throws IOException
	{
		CodexAuthentication authentication = CodexAuthentication.read(new AuthenticationOptions(
			Optional.of(launch.home()), declared, false), launch.environment(), Platform.current());
		return authentication.open(launch.environment());
	}

	/**
	 * Returns the provider that a session's configuration selects.
	 *
	 * @param settings the session's settings
	 * @return the provider's table
	 */
	private static JsonNode provider(String settings)
	{
		JsonNode configuration = TomlMapper.builder().build().readTree(settings);
		return configuration.path("model_providers").path(configuration.path("model_provider").asString());
	}

	/**
	 * Returns what a session's token command prints, run in this process.
	 *
	 * @param keyFile the session's key file
	 * @return what it printed
	 */
	private static String printedToken(Path keyFile)
	{
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		ByteArrayOutputStream errors = new ByteArrayOutputStream();
		int status = CodexTokenCommand.run(new String[]{keyFile.toString()}, output,
			new PrintStream(errors, true, UTF_8));
		requireThat(status, "status").withContext(errors.toString(UTF_8), "errors").isEqualTo(0);
		return output.toString(UTF_8);
	}

	/**
	 * Checks what every session's login must hold: no {@code auth.json} in its Codex folder; a configuration that
	 * stores no login, keeps model requests off websockets and runs CAT's token command; no fixture secret in the
	 * configuration, the app-server's environment or the login's description and evidence; and no variable of the
	 * login service in the app-server's environment.
	 *
	 * @param root        the test's folder
	 * @param login       the session's login
	 * @param environment the runner's environment
	 * @return the session's settings
	 * @throws IOException if a file cannot be written
	 */
	private static String requireSessionHoldsNoLogin(Path root, CodexSessionLogin login,
		Map<String, String> environment) throws IOException
	{
		Path home = Files.createDirectories(root.resolve("session-" + System.nanoTime()).resolve("home"));
		Path codexFolder = Files.createDirectories(home.resolve(".codex"));
		login.writeKeyFile(codexFolder);
		String settings = login.settings(codexFolder);
		requireThat(Files.exists(codexFolder.resolve("auth.json"), LinkOption.NOFOLLOW_LINKS), "authJson").isFalse();
		JsonNode configuration = TomlMapper.builder().build().readTree(settings);
		requireThat(configuration.path("cli_auth_credentials_store").asString(), "store").isEqualTo("ephemeral");
		JsonNode provider = provider(settings);
		requireThat(provider.path("supports_websockets").asBoolean(true), "websockets").isFalse();
		Path program = Path.of(provider.path("auth").path("command").asString());
		requireThat(program.isAbsolute(), "programIsAbsolute").isTrue();
		requireThat(List.of("java", "java.exe").contains(program.getFileName().toString()), "programIsJava").isTrue();
		List<String> arguments = new ArrayList<>();
		for (JsonNode argument : provider.path("auth").path("args"))
			arguments.add(argument.asString());
		requireThat(arguments.getLast(), "keyFile").isEqualTo(CodexAuthentication.keyFile(codexFolder).toString());
		requireThat(provider.path("auth").path("refresh_interval_ms").asLong(), "interval").isEqualTo(300_000L);
		requireThat(provider.has("env_key"), "envKey").isFalse();
		requireThat(provider.has("experimental_bearer_token"), "inlineToken").isFalse();
		requireThat(provider.has("env_http_headers"), "headerVariables").isFalse();
		requireThat(provider.has("gateway_oauth"), "gatewaySignIn").isFalse();
		Map<String, String> appServer = CodexRunner.processEnvironment(environment, HarnessDependencyCaches.NONE,
			home, home.getParent(), home.resolve("tmp"), Platform.current(), login);
		for (String secret : SECRETS)
		{
			requireThat(settings, "settings").doesNotContain(secret);
			requireThat(appServer.values().toString(), "appServer").doesNotContain(secret);
			requireThat(login.toString(), "description").doesNotContain(secret);
			requireThat(login.evidence().toString(), "evidence").doesNotContain(secret);
		}
		requireThat(appServer.keySet(), "variables").doesNotContain(CodexLoginService.SOCKET_VARIABLE).
			doesNotContain(CodexLoginService.KEY_VARIABLE).doesNotContain("CODEX_API_KEY").
			doesNotContain("OPENAI_API_KEY");
		return settings;
	}

	/**
	 * Returns the content of a session's key file, after {@link #requireSessionHoldsNoLogin} wrote it.
	 *
	 * @param root  the test's folder
	 * @param login the session's login
	 * @return the key file's content
	 * @throws IOException if a file cannot be written or read
	 */
	private static String keyFileContent(Path root, CodexSessionLogin login) throws IOException
	{
		Path codexFolder = Files.createDirectories(root.resolve("key-" + System.nanoTime()).resolve(".codex"));
		login.writeKeyFile(codexFolder);
		return Files.readString(CodexAuthentication.keyFile(codexFolder));
	}

	/**
	 * Verifies that an API key in the source's {@code auth.json}, which also stands for an access token that Codex
	 * takes as a key, reaches the session through its key file and the provider {@code https://api.openai.com/v1},
	 * with no {@code auth.json} copy, and that its class is an API key.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void apiKeyInLoginFile() throws IOException
	{
		// Requirement: R-I15, R-I19
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-login-")).toRealPath();
			Path source = CodexSourceHomes.create(root, "source");
			CodexSourceHomes.apiKeyLogin(source.resolve(".codex"));
			Launch launch = new Launch(source, Map.of("PATH", ""));

			try (CodexSessionLogin login = open(launch))
			{
				String settings = requireSessionHoldsNoLogin(root, login, launch.environment());
				requireThat(provider(settings).path("base_url").asString(), "baseUrl").
					isEqualTo("https://api.openai.com/v1");
				requireThat(keyFileContent(root, login), "keyFile").isEqualTo(CodexSourceHomes.SECRET);
				requireThat(login.loginClass(), "class").isEqualTo(HostLogin.API_KEY);
				requireThat(login.evidence().parts(), "parts").isEqualTo(List.of(".codex/auth.json"));
			}
		}
	}

	/**
	 * Returns the variables of the runner's environment that hold an API key.
	 *
	 * @return each variable
	 */
	@DataProvider
	public static Object[][] keyVariables()
	{
		return new Object[][]{{"CODEX_API_KEY"}, {"OPENAI_API_KEY"}};
	}

	/**
	 * Verifies that an API key in the runner's environment, with no stored login, reaches the session through its
	 * key file, never through the app-server's environment.
	 *
	 * @param variable the variable that holds the key
	 * @throws IOException if the fixture cannot be written
	 */
	@Test(dataProvider = "keyVariables")
	public void apiKeyInRunnerEnvironment(String variable) throws IOException
	{
		// Requirement: R-I15, R-I19
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-login-")).toRealPath();
			Path source = CodexSourceHomes.create(root, "source");
			Launch launch = new Launch(source, Map.of("PATH", "", variable, ENVIRONMENT_KEY));

			try (CodexSessionLogin login = open(launch))
			{
				requireSessionHoldsNoLogin(root, login, launch.environment());
				requireThat(keyFileContent(root, login), "keyFile").isEqualTo(ENVIRONMENT_KEY);
				requireThat(login.evidence().parts(), "parts").isEqualTo(List.of("environment:" + variable));
			}
		}
	}

	/**
	 * Verifies that a ChatGPT sign-in in the source's {@code auth.json} is served by the login service: the key file
	 * holds no token, the session's provider is {@code https://chatgpt.com/backend-api/codex} with the account ID in
	 * its headers, its token command prints the access token alone, the source file is unchanged, and the class is a
	 * subscription with no declared class.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void signInInLoginFile() throws IOException
	{
		// Requirement: R-I15, R-I19
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			CodexLoginService service = CodexLoginServices.start(cleanup, new CodexLoginServices.Keyring(null)))
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-login-")).toRealPath();
			Path source = CodexSourceHomes.create(root, "source");
			Path loginFile = CodexSourceHomes.ownerOnly(Files.writeString(source.resolve(".codex/auth.json"),
				CodexLoginServices.signIn()));
			byte[] before = Files.readAllBytes(loginFile);
			Launch launch = new Launch(source, CodexLoginServices.runnerEnvironment(service, Map.of()));

			try (CodexSessionLogin login = open(launch))
			{
				String settings = requireSessionHoldsNoLogin(root, login, launch.environment());
				JsonNode provider = provider(settings);
				requireThat(provider.path("base_url").asString(), "baseUrl").
					isEqualTo("https://chatgpt.com/backend-api/codex");
				requireThat(provider.path("http_headers").path("ChatGPT-Account-ID").asString(), "account").
					isEqualTo(ACCOUNT_ID);
				Path codexFolder = Files.createDirectories(root.resolve("served/.codex"));
				login.writeKeyFile(codexFolder);
				Path keyFile = CodexAuthentication.keyFile(codexFolder);
				for (String secret : SECRETS)
					requireThat(Files.readString(keyFile), "keyFile").doesNotContain(secret);
				requireThat(printedToken(keyFile), "token").isEqualTo(ACCESS_TOKEN);
				requireThat(login.loginClass(), "class").isEqualTo(HostLogin.SUBSCRIPTION);
				requireThat(login.modelHost(), "modelHost").isEqualTo("chatgpt.com");
				requireThat(login.evidence().parts(), "parts").isEqualTo(List.of(".codex/auth.json"));
			}
			requireThat(Files.readAllBytes(loginFile), "source").isEqualTo(before);
		}
	}

	/**
	 * Verifies that a ChatGPT sign-in in the operating system's keyring is served by the login service as the
	 * {@code auth.json} one is, and that the evidence names the keyring item.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void signInInKeyring() throws IOException
	{
		// Requirement: R-I15, R-I19
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			CodexLoginService service = CodexLoginServices.start(cleanup,
				new CodexLoginServices.Keyring(CodexLoginServices.signIn())))
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-login-")).toRealPath();
			Path source = CodexSourceHomes.create(root, "source");
			CodexSourceHomes.configuration(source.resolve(".codex"), "cli_auth_credentials_store = \"keyring\"\n");
			Launch launch = new Launch(source, CodexLoginServices.runnerEnvironment(service, Map.of()));

			try (CodexSessionLogin login = open(launch))
			{
				String settings = requireSessionHoldsNoLogin(root, login, launch.environment());
				requireThat(provider(settings).path("http_headers").path("ChatGPT-Account-ID").asString(), "account").
					isEqualTo(ACCOUNT_ID);
				Path codexFolder = Files.createDirectories(root.resolve("served/.codex"));
				login.writeKeyFile(codexFolder);
				requireThat(printedToken(CodexAuthentication.keyFile(codexFolder)), "token").isEqualTo(ACCESS_TOKEN);
				requireThat(login.loginClass(), "class").isEqualTo(HostLogin.SUBSCRIPTION);
				requireThat(login.evidence().parts(), "parts").contains("keyring:Codex Auth");
			}
		}
	}

	/**
	 * Verifies that a provider's {@code env_key} and inline {@code experimental_bearer_token} reach the session through
	 * its key file, with the source's own table otherwise.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void providerKeyThroughKeyFile() throws IOException
	{
		// Requirement: R-I15, R-I19
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-login-")).toRealPath();
			Map<String, Map<String, String>> lines = Map.of(
				"env_key = \"GATEWAY_KEY\"", Map.of("PATH", "", "GATEWAY_KEY", ENVIRONMENT_KEY),
				"experimental_bearer_token = \"" + ENVIRONMENT_KEY + "\"", Map.of("PATH", ""));
			int index = 0;
			for (Map.Entry<String, Map<String, String>> line : lines.entrySet())
			{
				Path source = CodexSourceHomes.create(root, "source-" + index);
				++index;
				CodexSourceHomes.ownerOnly(CodexSourceHomes.configuration(source.resolve(".codex"), """
					model_provider = "gateway"
					[model_providers.gateway]
					name = "Gateway"
					base_url = "https://gateway.example/v1"
					%s
					""".formatted(line.getKey())));
				Launch launch = new Launch(source, line.getValue());
				try (CodexSessionLogin login = open(launch, Optional.of(HostLogin.API_KEY)))
				{
					String settings = requireSessionHoldsNoLogin(root, login, launch.environment());
					requireThat(provider(settings).path("base_url").asString(), "baseUrl").
						isEqualTo("https://gateway.example/v1");
					requireThat(provider(settings).path("name").asString(), "name").isEqualTo("Gateway");
					requireThat(keyFileContent(root, login), "keyFile").isEqualTo(ENVIRONMENT_KEY);
				}
			}
		}
	}

	/**
	 * Verifies that a provider's own token command is run by the login service alone: the session's provider names
	 * neither the user's command nor its arguments, and the session's token command prints what the user's command
	 * printed.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test(timeOut = 60_000)
	public void providerCommandRunsInService() throws IOException
	{
		// Requirement: R-I15, R-I19
		requireThat(Platform.current().isWindows() && Files.notExists(Path.of("C:/Program Files/Git/bin/sh.exe")),
			"windowsWithoutGitShell").isFalse();
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			CodexLoginService service = CodexLoginServices.start(cleanup, new CodexLoginServices.Keyring(null)))
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-login-")).toRealPath();
			Path source = CodexSourceHomes.create(root, "source");
			CodexSourceHomes.ownerOnly(Files.writeString(source.resolve("key"), COMMAND_TOKEN));
			CodexSourceHomes.configuration(source.resolve(".codex"), """
				model_provider = "gateway"
				[model_providers.gateway]
				base_url = "https://gateway.example/v1"
				[model_providers.gateway.auth]
				command = "sh"
				args = ["-c", "cat ~/key"]
				refresh_interval_ms = 0
				""");
			Launch launch = new Launch(source, CodexLoginServices.runnerEnvironment(service, Map.of()));

			try (CodexSessionLogin login = open(launch, Optional.of(HostLogin.API_KEY)))
			{
				String settings = requireSessionHoldsNoLogin(root, login, launch.environment());
				requireThat(settings, "settings").doesNotContain("cat ~/key");
				Path codexFolder = Files.createDirectories(root.resolve("served/.codex"));
				login.writeKeyFile(codexFolder);
				requireThat(Files.readString(CodexAuthentication.keyFile(codexFolder)), "keyFile").
					doesNotContain(COMMAND_TOKEN);
				requireThat(printedToken(CodexAuthentication.keyFile(codexFolder)), "token").isEqualTo(COMMAND_TOKEN);
			}
		}
	}

	/**
	 * Verifies that a provider whose {@code gateway_oauth} has a {@code credential_export} command is served like a
	 * provider's own token command: the session's provider holds neither {@code gateway_oauth} nor the user's command,
	 * and its token command prints what the export printed; and that one without {@code credential_export} is refused,
	 * naming the provider and {@code gateway_oauth}.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test(timeOut = 60_000)
	public void gatewaySignInNeedsExport() throws IOException
	{
		// Requirement: R-I15, R-I19
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			CodexLoginService service = CodexLoginServices.start(cleanup, new CodexLoginServices.Keyring(null)))
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-login-")).toRealPath();
			Path source = CodexSourceHomes.create(root, "exported");
			CodexSourceHomes.ownerOnly(Files.writeString(source.resolve("export"), COMMAND_TOKEN));
			CodexSourceHomes.configuration(source.resolve(".codex"), """
				model_provider = "gateway"
				[model_providers.gateway]
				base_url = "https://gateway.example/v1"
				[model_providers.gateway.gateway_oauth]
				issuer = "https://issuer.example"
				[model_providers.gateway.gateway_oauth.credential_export]
				command = "sh"
				args = ["-c", "cat ~/export"]
				""");
			Launch launch = new Launch(source, CodexLoginServices.runnerEnvironment(service, Map.of()));
			try (CodexSessionLogin login = open(launch, Optional.of(HostLogin.SUBSCRIPTION)))
			{
				String settings = requireSessionHoldsNoLogin(root, login, launch.environment());
				requireThat(settings, "settings").doesNotContain("cat ~/export").doesNotContain("credential_export").
					doesNotContain("issuer.example");
				Path codexFolder = Files.createDirectories(root.resolve("served/.codex"));
				login.writeKeyFile(codexFolder);
				requireThat(printedToken(CodexAuthentication.keyFile(codexFolder)), "token").isEqualTo(COMMAND_TOKEN);
			}

			Path unexported = CodexSourceHomes.create(root, "unexported");
			CodexSourceHomes.configuration(unexported.resolve(".codex"), """
				model_provider = "gateway"
				[model_providers.gateway]
				base_url = "https://gateway.example/v1"
				[model_providers.gateway.gateway_oauth]
				issuer = "https://issuer.example"
				""");
			SessionRefusedException refusal = expectThrows(SessionRefusedException.class, () ->
				open(new Launch(unexported, CodexLoginServices.runnerEnvironment(service, Map.of())),
					Optional.of(HostLogin.API_KEY)));
			requireThat(refusal.getMessage(), "refusal").contains("gateway").contains("gateway_oauth");
		}
	}

	/**
	 * Verifies that a provider that sends its secret in headers is served through the login service's forwarder:
	 * the session's provider names the forwarder on a loopback address and holds neither the headers nor their
	 * values, and its token command prints a token that is none of the source's secrets.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void headerSecretThroughForwarder() throws IOException
	{
		// Requirement: R-I15, R-I19
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			CodexLoginService service = CodexLoginServices.start(cleanup, new CodexLoginServices.Keyring(null)))
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-login-")).toRealPath();
			Path source = CodexSourceHomes.create(root, "source");
			CodexSourceHomes.ownerOnly(CodexSourceHomes.configuration(source.resolve(".codex"), """
				model_provider = "gateway"
				[model_providers.gateway]
				base_url = "https://gateway.example/v1"
				http_headers = { "X-Inline-Key" = "%s" }
				env_http_headers = { "X-Variable-Key" = "GATEWAY_HEADER" }
				""".formatted(HEADER_SECRET)));
			Launch launch = new Launch(source, CodexLoginServices.runnerEnvironment(service,
				Map.of("GATEWAY_HEADER", HEADER_SECRET)));

			try (CodexSessionLogin login = open(launch, Optional.of(HostLogin.API_KEY)))
			{
				String settings = requireSessionHoldsNoLogin(root, login, launch.environment());
				JsonNode provider = provider(settings);
				String baseUrl = provider.path("base_url").asString();
				requireThat(baseUrl, "baseUrl").startsWith("http://127.").doesNotContain("gateway.example");
				requireThat(settings, "settings").doesNotContain("X-Inline-Key").doesNotContain("X-Variable-Key").
					doesNotContain("GATEWAY_HEADER");
				Path codexFolder = Files.createDirectories(root.resolve("served/.codex"));
				login.writeKeyFile(codexFolder);
				String token = printedToken(CodexAuthentication.keyFile(codexFolder));
				requireThat(SECRETS, "secrets").doesNotContain(token);
				requireThat(login.modelHost(), "modelHost").isEqualTo("gateway.example");
			}
		}
	}

	/**
	 * Verifies that a launch that needs the login service is refused when the runner has none, naming the service.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void servedLoginNeedsService() throws IOException
	{
		// Requirement: R-I15, R-I19
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-login-")).toRealPath();
			Path source = CodexSourceHomes.create(root, "source");
			CodexSourceHomes.ownerOnly(Files.writeString(source.resolve(".codex/auth.json"), CodexLoginServices.signIn()));

			SessionRefusedException refusal = expectThrows(SessionRefusedException.class, () ->
				open(new Launch(source, Map.of("PATH", ""))));
			requireThat(refusal.getMessage(), "refusal").contains("login service");
			for (String secret : SECRETS)
				requireThat(refusal.getMessage(), "refusal").doesNotContain(secret);
		}
	}

	/**
	 * Verifies that a source with no usable login is refused, naming the files, the keyring item and the variables
	 * that the runner read.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void refusesSourceWithoutLogin() throws IOException
	{
		// Requirement: R-I15, R-I19
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-login-")).toRealPath();
			Path source = CodexSourceHomes.create(root, "source");

			SessionRefusedException refusal = expectThrows(SessionRefusedException.class, () ->
				open(new Launch(source, Map.of("PATH", ""))));
			requireThat(refusal.getMessage(), "refusal").contains(source.resolve(".codex/auth.json").toString()).
				contains(source.resolve(".codex/config.toml").toString()).contains("Codex Auth").
				contains("CODEX_API_KEY").contains("OPENAI_API_KEY");
		}
	}

	/**
	 * Verifies that the session's credential stops serving once the session's login closes.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	// The test closes the login itself, which is what it verifies
	@SuppressWarnings("PMD.CloseResource")
	@Test
	public void closeEndsCredential() throws IOException
	{
		// Requirement: R-I15, R-I19
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			CodexLoginService service = CodexLoginServices.start(cleanup,
				new CodexLoginServices.Keyring(CodexLoginServices.signIn())))
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-login-")).toRealPath();
			Path source = CodexSourceHomes.create(root, "source");
			CodexSourceHomes.configuration(source.resolve(".codex"), "cli_auth_credentials_store = \"keyring\"\n");
			Path codexFolder = Files.createDirectories(root.resolve("served/.codex"));
			CodexSessionLogin login = open(new Launch(source, CodexLoginServices.runnerEnvironment(service, Map.of())));
			login.writeKeyFile(codexFolder);
			Path keyFile = CodexAuthentication.keyFile(codexFolder);
			requireThat(printedToken(keyFile), "token").isEqualTo(ACCESS_TOKEN);

			login.close();

			ByteArrayOutputStream errors = new ByteArrayOutputStream();
			int status = CodexTokenCommand.run(new String[]{keyFile.toString()}, new ByteArrayOutputStream(),
				new PrintStream(errors, true, UTF_8));
			requireThat(status, "status").isEqualTo(1);
			requireThat(errors.toString(UTF_8), "errors").doesNotContain(ACCESS_TOKEN);
			expectThrows(LoginRefusal.class, () -> CodexLoginClient.token(service.socket(), "unknown-credential"));
		}
	}

	/**
	 * Returns source Codex settings and logins, and the hosts that each lets the session send its model requests to.
	 *
	 * @return the source configuration, the source login file's content or {@code null} when there is none, the
	 *   declared class or {@code null}, and the hosts
	 */
	@DataProvider
	public static Object[][] modelHosts()
	{
		String apiKey = "{\"auth_mode\": \"apikey\", \"OPENAI_API_KEY\": \"" + CodexSourceHomes.SECRET + "\"}";
		String signIn = CodexLoginServices.signIn();
		return new Object[][]
		{
			{"openai_base_url = \"http://LocalHost:2455/backend-api/codex\"\n", apiKey, HostLogin.API_KEY,
				Set.of("localhost")},
			{"""
				model_provider = "gateway"
				openai_base_url = "https://ignored.example/v1"
				[model_providers.gateway]
				base_url = "https://Gateway.Example/v1"
				""", apiKey, HostLogin.API_KEY, Set.of("gateway.example")},
			{"", apiKey, null, Set.of("api.openai.com")},
			{"forced_login_method = \"api\"\n", apiKey, null, Set.of("api.openai.com")},
			{"", signIn, null, Set.of("chatgpt.com")},
			{"cli_auth_credentials_store = \"keyring\"\n", null, null, Set.of("api.openai.com", "chatgpt.com")}
		};
	}

	/**
	 * Verifies that a session's model hosts are the host of the base URL of the source's selected provider,
	 * otherwise of the source's {@code openai_base_url} for a key, otherwise the host that Codex reaches for the
	 * source's login, and both the API and the ChatGPT host for a keyring login, whose kind only the login service
	 * reads.
	 *
	 * @param configuration the source's Codex configuration
	 * @param login         the source's login file, or {@code null} if it has none
	 * @param declared      the declared class, or {@code null}
	 * @param hosts         the expected model hosts
	 * @throws IOException if fixture I/O fails
	 */
	@Test(dataProvider = "modelHosts")
	public void findsModelHosts(String configuration, String login, HostLogin declared, Set<String> hosts)
		throws IOException
	{
		// Requirement: R-I15
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-model-host-")).toRealPath();
			Path source = CodexSourceHomes.create(root, "source");
			CodexSourceHomes.configuration(source.resolve(".codex"), configuration);
			if (login != null)
				CodexSourceHomes.ownerOnly(Files.writeString(source.resolve(".codex/auth.json"), login));

			CodexAuthentication authentication = CodexAuthentication.read(new AuthenticationOptions(
				Optional.of(source), Optional.ofNullable(declared), false), Map.of("PATH", ""), Platform.current());

			requireThat(authentication.modelHosts(), "modelHosts").isEqualTo(hosts);
		}
	}
}
