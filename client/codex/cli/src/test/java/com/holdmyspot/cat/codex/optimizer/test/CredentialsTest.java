package com.holdmyspot.cat.codex.optimizer.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.codex.optimizer.Credentials;
import com.holdmyspot.cat.codex.runner.CodexAuthentication;
import com.holdmyspot.cat.codex.runner.test.CodexSourceHomes;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.port.HostLogin;
import com.holdmyspot.cat.common.runner.AuthenticationOptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
	 * Verifies that a source home's class is {@code api_key} for an API-key {@code auth.json} and the declared class for
	 * a provider with a base URL, and that the runner's own login file, which holds a ChatGPT sign-in, does not change
	 * it; and that the class that the source's session states in {@code CAT_HOST_LOGIN} is that class.
	 *
	 * @throws IOException if the fixture cannot be written or the source is refused
	 */
	@Test
	public void classifiesSourceHomeLogin() throws IOException
	{
		// Requirement: R-I15
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-source-class-")).toRealPath();
			Path runner = CodexSourceHomes.create(root, "runner");
			CodexSourceHomes.signInLogin(runner.resolve(".codex"));
			Map<String, String> environment = Map.of("PATH", "", Platform.current().homeVariable(), runner.toString());

			Path apiKey = CodexSourceHomes.create(root, "api-key");
			CodexSourceHomes.apiKeyLogin(apiKey.resolve(".codex"));
			CodexAuthentication fromKey = CodexAuthentication.read(new AuthenticationOptions(Optional.of(apiKey),
				Optional.empty(), false), environment, Platform.current());
			requireThat(fromKey.loginClass(), "apiKeyClass").isEqualTo(HostLogin.API_KEY);
			requireThat(fromKey.environment().get("CAT_HOST_LOGIN"), "apiKeyStated").isEqualTo("api_key");

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
			CodexAuthentication fromGateway = CodexAuthentication.read(new AuthenticationOptions(Optional.of(gateway),
				Optional.of(HostLogin.SUBSCRIPTION), false), environment, Platform.current());
			requireThat(fromGateway.loginClass(), "gatewayClass").isEqualTo(HostLogin.SUBSCRIPTION);
			requireThat(fromGateway.environment().get("CAT_HOST_LOGIN"), "gatewayStated").isEqualTo("subscription");
			requireThat(Credentials.classify(gateway.resolve(".codex"), fromGateway.environment(), JSON),
				"classified").isEqualTo(HostLogin.SUBSCRIPTION);
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
