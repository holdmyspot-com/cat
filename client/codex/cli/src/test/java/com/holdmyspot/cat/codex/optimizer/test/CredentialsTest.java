package com.holdmyspot.cat.codex.optimizer.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.codex.optimizer.Credentials;
import com.holdmyspot.cat.codex.runner.CodexAuthentication;
import com.holdmyspot.cat.codex.runner.CodexSessionLogin;
import com.holdmyspot.cat.codex.runner.test.CodexLoginServices;
import com.holdmyspot.cat.codex.runner.test.CodexSourceHomes;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.port.HostLogin;
import com.holdmyspot.cat.common.runner.AuthenticationOptions;
import com.holdmyspot.cat.common.runner.login.CodexLoginService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests {@link Credentials} with credential files whose values are placeholders.
 */
public final class CredentialsTest
{
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * Creates the test.
	 */
	public CredentialsTest()
	{
	}

	/**
	 * Writes a credential file.
	 *
	 * @param directory the Codex home directory
	 * @param content the file's content
	 * @throws IOException if the file cannot be written
	 */
	private static void writeAuth(Path directory, String content) throws IOException
	{
		Files.writeString(directory.resolve("auth.json"), content, UTF_8);
	}

	/**
	 * Verifies that an {@code OPENAI_API_KEY} string marks an API-key login.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void classifiesApiKeyLogin() throws IOException
	{
		// Requirement: R-E7
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory("cat-codex-home-"));
			writeAuth(home, "{\"auth_mode\":\"placeholder\",\"OPENAI_API_KEY\":\"placeholder\",\"tokens\":null}");
			requireThat(Credentials.classify(home, Map.of(), JSON), "login").isEqualTo(HostLogin.API_KEY);
		}
	}

	/**
	 * Verifies that a {@code tokens} object marks a subscription login (a ChatGPT sign-in).
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void classifiesSubscriptionLogin() throws IOException
	{
		// Requirement: R-E7
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory("cat-codex-home-"));
			writeAuth(home, "{\"auth_mode\":\"placeholder\",\"OPENAI_API_KEY\":null," +
				"\"tokens\":{\"id_token\":\"placeholder\",\"access_token\":\"placeholder\"}}");
			requireThat(Credentials.classify(home, Map.of(), JSON), "login").isEqualTo(HostLogin.SUBSCRIPTION);
		}
	}

	/**
	 * Verifies that the class that a runner states in {@code CAT_HOST_LOGIN} wins over the login file, and that the
	 * runner's {@code CODEX_API_KEY} and {@code OPENAI_API_KEY}, which no session receives, do not change the class.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void statedClassWinsAndKeyVariablesDoNot() throws IOException
	{
		// Requirement: R-E7, R-I15
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory("cat-codex-home-"));
			writeAuth(home, "{\"OPENAI_API_KEY\":null,\"tokens\":{\"id_token\":\"placeholder\"}}");
			requireThat(Credentials.classify(home, Map.of("CAT_HOST_LOGIN", "api_key"), JSON), "statedApiKey").
				isEqualTo(HostLogin.API_KEY);
			requireThat(Credentials.classify(home, Map.of("CODEX_API_KEY", "placeholder"), JSON), "codexKey").
				isEqualTo(HostLogin.SUBSCRIPTION);
			requireThat(Credentials.classify(home, Map.of("OPENAI_API_KEY", "placeholder"), JSON), "openaiKey").
				isEqualTo(HostLogin.SUBSCRIPTION);
			writeAuth(home, "{\"OPENAI_API_KEY\":\"placeholder\"}");
			requireThat(Credentials.classify(home, Map.of("CAT_HOST_LOGIN", "subscription"), JSON),
				"statedSubscription").isEqualTo(HostLogin.SUBSCRIPTION);
		}
	}

	/**
	 * Verifies the class of each kind of source login, with no declared class for a key or a ChatGPT sign-in: an API
	 * key in {@code auth.json} or the runner's environment is {@code api_key}, a ChatGPT sign-in in {@code auth.json}
	 * or the keyring is {@code subscription}, an API key in the keyring is {@code api_key} once the login service reads
	 * it, and a provider with a base URL takes the declared class. The runner's own login file, which holds a ChatGPT
	 * sign-in, changes none of them; the class that the session states in {@code CAT_HOST_LOGIN} is the class; and the
	 * evidence names {@code .codex/auth.json}, {@code keyring:Codex Auth} or the provider table, and never a value.
	 *
	 * @throws IOException if the fixture cannot be written or the source is refused
	 */
	@Test(timeOut = 60_000)
	public void classifiesSourceHomeLogin() throws IOException
	{
		// Requirement: R-I15
		String keyringKey = "{\"auth_mode\": \"apikey\", \"OPENAI_API_KEY\": \"" + CodexSourceHomes.SECRET + "\"}";
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			CodexLoginService fileService = CodexLoginServices.start(cleanup, new CodexLoginServices.Keyring(null));
			CodexLoginService signInKeyring = CodexLoginServices.start(cleanup,
				new CodexLoginServices.Keyring(CodexLoginServices.signIn()));
			CodexLoginService keyKeyring = CodexLoginServices.start(cleanup, new CodexLoginServices.Keyring(keyringKey)))
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-source-class-")).toRealPath();
			Path runner = CodexSourceHomes.create(root, "runner");
			CodexSourceHomes.signInLogin(runner.resolve(".codex"));
			Map<String, String> runnerHome = Map.of(Platform.current().homeVariable(), runner.toString());
			Map<String, String> fileRunner = CodexLoginServices.runnerEnvironment(fileService, runnerHome);

			Path apiKey = CodexSourceHomes.create(root, "api-key");
			CodexSourceHomes.apiKeyLogin(apiKey.resolve(".codex"));
			requireClass(apiKey, Optional.empty(), fileRunner, HostLogin.API_KEY, ".codex/auth.json");

			Path variable = CodexSourceHomes.create(root, "variable");
			Map<String, String> withKey = new HashMap<>(runnerHome);
			withKey.put("CODEX_API_KEY", CodexSourceHomes.SECRET);
			requireClass(variable, Optional.empty(), CodexLoginServices.runnerEnvironment(fileService, withKey),
				HostLogin.API_KEY, "environment:CODEX_API_KEY");

			Path signIn = CodexSourceHomes.create(root, "sign-in");
			CodexSourceHomes.ownerOnly(Files.writeString(signIn.resolve(".codex/auth.json"),
				CodexLoginServices.signIn()));
			requireClass(signIn, Optional.empty(), fileRunner, HostLogin.SUBSCRIPTION, ".codex/auth.json");

			Path keyring = CodexSourceHomes.create(root, "keyring");
			CodexSourceHomes.configuration(keyring.resolve(".codex"), "cli_auth_credentials_store = \"keyring\"\n");
			requireClass(keyring, Optional.empty(), CodexLoginServices.runnerEnvironment(signInKeyring, runnerHome),
				HostLogin.SUBSCRIPTION, "keyring:Codex Auth");
			requireClass(keyring, Optional.empty(), CodexLoginServices.runnerEnvironment(keyKeyring, runnerHome),
				HostLogin.API_KEY, "keyring:Codex Auth");

			Path gateway = CodexSourceHomes.create(root, "gateway");
			CodexSourceHomes.secret(gateway.resolve("key"));
			CodexSourceHomes.configuration(gateway.resolve(".codex"), """
				model_provider = "gateway"
				[model_providers.gateway]
				base_url = "https://gateway.example/v1"
				[model_providers.gateway.auth]
				command = "sh"
				args = ["-c", "cat ~/key"]
				""");
			requireClass(gateway, Optional.of(HostLogin.SUBSCRIPTION), fileRunner, HostLogin.SUBSCRIPTION,
				"model_providers.gateway");
		}
	}

	/**
	 * Checks the class of a source home's login once it opens, the class that the session states and the evidence.
	 *
	 * @param source      the source home
	 * @param declared    the declared class, or empty
	 * @param environment the runner's environment
	 * @param loginClass  the expected class
	 * @param part        a part that the evidence names
	 * @throws IOException if the source is refused
	 */
	private static void requireClass(Path source, Optional<HostLogin> declared, Map<String, String> environment,
		HostLogin loginClass, String part) throws IOException
	{
		CodexAuthentication authentication = CodexAuthentication.read(new AuthenticationOptions(Optional.of(source),
			declared, false), environment, Platform.current());
		try (CodexSessionLogin login = authentication.open(environment))
		{
			String name = source.getFileName().toString();
			requireThat(login.loginClass(), name + "Class").isEqualTo(loginClass);
			requireThat(login.evidence().loginClass(), name + "EvidenceClass").isEqualTo(loginClass);
			requireThat(login.evidence().parts(), name + "Parts").contains(part);
			Map<String, String> stated = login.environment();
			requireThat(Credentials.classify(source.resolve(".codex"), stated, JSON), name + "Stated").
				isEqualTo(loginClass);
			for (String secret : List.of(CodexSourceHomes.SECRET, CodexLoginServices.ACCESS_TOKEN,
				CodexLoginServices.REFRESH_TOKEN))
			{
				requireThat(login.evidence().toString(), name + "Evidence").doesNotContain(secret);
				requireThat(authentication.evidence().toString(), name + "ReadEvidence").doesNotContain(secret);
			}
		}
	}

	/**
	 * Verifies that a missing file, a file with both kinds of credential, a file with neither, keys of other types
	 * and a file that is not JSON all leave the login unknown.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void leavesOtherCredentialsUnknown() throws IOException
	{
		// Requirement: R-E7
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory("cat-codex-home-"));
			requireThat(Credentials.classify(home, Map.of(), JSON), "missing").isEqualTo(HostLogin.UNKNOWN);
			writeAuth(home, "{\"OPENAI_API_KEY\":\"placeholder\",\"tokens\":{\"id_token\":\"placeholder\"}}");
			requireThat(Credentials.classify(home, Map.of(), JSON), "both").isEqualTo(HostLogin.UNKNOWN);
			writeAuth(home, "{\"OPENAI_API_KEY\":null,\"tokens\":null}");
			requireThat(Credentials.classify(home, Map.of(), JSON), "neither").isEqualTo(HostLogin.UNKNOWN);
			writeAuth(home, "{\"OPENAI_API_KEY\":7,\"tokens\":\"placeholder\"}");
			requireThat(Credentials.classify(home, Map.of(), JSON), "wrongTypes").isEqualTo(HostLogin.UNKNOWN);
			writeAuth(home, "not json");
			requireThat(Credentials.classify(home, Map.of(), JSON), "notJson").isEqualTo(HostLogin.UNKNOWN);
		}
	}
}
