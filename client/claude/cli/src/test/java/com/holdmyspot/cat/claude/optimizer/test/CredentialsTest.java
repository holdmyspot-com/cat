package com.holdmyspot.cat.claude.optimizer.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.claude.optimizer.Credentials;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.port.HostLogin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

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
	 * @param directory the configuration directory
	 * @param content the file's content
	 * @throws IOException if the file cannot be written
	 */
	private static void writeCredentials(Path directory, String content) throws IOException
	{
		Files.writeString(directory.resolve(".credentials.json"), content, UTF_8);
	}

	/**
	 * Verifies that an {@code claudeAiOauth} object marks a subscription login.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void classifiesSubscriptionLogin() throws IOException
	{
		// Requirement: R-E7
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path configuration = cleanup.add(Files.createTempDirectory("cat-claude-config-"));
			writeCredentials(configuration, "{\"claudeAiOauth\":{\"accessToken\":\"placeholder\"," +
				"\"subscriptionType\":\"placeholder\"},\"organizationUuid\":\"placeholder\"}");
			requireThat(Credentials.classify(configuration, Map.of(), JSON), "login").isEqualTo(HostLogin.SUBSCRIPTION);
		}
	}

	/**
	 * Verifies that an API key or auth token in the environment selects an API key even when the file holds a
	 * subscription login.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void environmentApiKeyOverridesFile() throws IOException
	{
		// Requirement: R-E7
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path configuration = cleanup.add(Files.createTempDirectory("cat-claude-config-"));
			writeCredentials(configuration, "{\"claudeAiOauth\":{\"accessToken\":\"placeholder\"}}");
			requireThat(Credentials.classify(configuration, Map.of("ANTHROPIC_API_KEY", "placeholder"), JSON), "key").
				isEqualTo(HostLogin.API_KEY);
			requireThat(Credentials.classify(configuration, Map.of("ANTHROPIC_AUTH_TOKEN", "placeholder"), JSON),
				"token").isEqualTo(HostLogin.API_KEY);
		}
	}

	/**
	 * Verifies that an OAuth token in the environment marks a subscription login without a credential file.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void environmentOauthTokenIsSubscription() throws IOException
	{
		// Requirement: R-E7
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path configuration = cleanup.add(Files.createTempDirectory("cat-claude-config-"));
			requireThat(Credentials.classify(configuration, Map.of("CLAUDE_CODE_OAUTH_TOKEN", "placeholder"), JSON),
				"login").isEqualTo(HostLogin.SUBSCRIPTION);
		}
	}

	/**
	 * Verifies that a missing file, a file without the subscription object, a subscription key of another type and
	 * a file that is not JSON all leave the login unknown.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void leavesOtherCredentialsUnknown() throws IOException
	{
		// Requirement: R-E7
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path configuration = cleanup.add(Files.createTempDirectory("cat-claude-config-"));
			requireThat(Credentials.classify(configuration, Map.of(), JSON), "missing").isEqualTo(HostLogin.UNKNOWN);
			writeCredentials(configuration, "{\"other\":{}}");
			requireThat(Credentials.classify(configuration, Map.of(), JSON), "otherKey").isEqualTo(HostLogin.UNKNOWN);
			writeCredentials(configuration, "{\"claudeAiOauth\":\"placeholder\"}");
			requireThat(Credentials.classify(configuration, Map.of(), JSON), "wrongType").isEqualTo(HostLogin.UNKNOWN);
			writeCredentials(configuration, "not json");
			requireThat(Credentials.classify(configuration, Map.of(), JSON), "notJson").isEqualTo(HostLogin.UNKNOWN);
		}
	}

	/**
	 * Verifies that a login in the login store that {@code CLAUDE_SECURESTORAGE_CONFIG_DIR} names marks a
	 * subscription, although the configuration directory holds no login: a run's session has its own configuration
	 * directory and the user's login in the store.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void loginStoreSubscriptionIsSubscription() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path configuration = cleanup.add(Files.createTempDirectory("cat-claude-config-"));
			Path store = cleanup.add(Files.createTempDirectory("cat-claude-store-"));
			writeCredentials(store, "{\"claudeAiOauth\":{\"accessToken\":\"placeholder\"}}");
			Map<String, String> environment = Map.of("CLAUDE_SECURESTORAGE_CONFIG_DIR", store.toString());
			requireThat(Credentials.classify(configuration, environment, JSON), "login").
				isEqualTo(HostLogin.SUBSCRIPTION);
		}
	}

	/**
	 * Verifies that an API key in the environment selects an API key even when the login store holds a
	 * subscription login.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void environmentApiKeyOverridesStore() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path configuration = cleanup.add(Files.createTempDirectory("cat-claude-config-"));
			Path store = cleanup.add(Files.createTempDirectory("cat-claude-store-"));
			writeCredentials(store, "{\"claudeAiOauth\":{\"accessToken\":\"placeholder\"}}");
			Map<String, String> environment = Map.of("CLAUDE_SECURESTORAGE_CONFIG_DIR", store.toString(),
				"ANTHROPIC_API_KEY", "placeholder");
			requireThat(Credentials.classify(configuration, environment, JSON), "login").isEqualTo(HostLogin.API_KEY);
		}
	}

	/**
	 * Verifies that a login store that is empty, names no directory, or is unset leaves the classification to the
	 * configuration directory, and that a store without a login does not hide the configuration directory's login.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void emptyLoginStoreKeepsFileClassification() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path configuration = cleanup.add(Files.createTempDirectory("cat-claude-config-"));
			Path emptyStore = cleanup.add(Files.createTempDirectory("cat-claude-store-"));
			Path missingStore = emptyStore.resolve("missing");
			requireThat(Credentials.classify(configuration, Map.of("CLAUDE_SECURESTORAGE_CONFIG_DIR", ""), JSON),
				"emptyVariableWithoutLogin").isEqualTo(HostLogin.UNKNOWN);
			requireThat(Credentials.classify(configuration,
				Map.of("CLAUDE_SECURESTORAGE_CONFIG_DIR", emptyStore.toString()), JSON), "storeWithoutLogin").
				isEqualTo(HostLogin.UNKNOWN);
			requireThat(Credentials.classify(configuration,
				Map.of("CLAUDE_SECURESTORAGE_CONFIG_DIR", missingStore.toString()), JSON), "missingStore").
				isEqualTo(HostLogin.UNKNOWN);

			writeCredentials(configuration, "{\"claudeAiOauth\":{\"accessToken\":\"placeholder\"}}");
			requireThat(Credentials.classify(configuration, Map.of("CLAUDE_SECURESTORAGE_CONFIG_DIR", ""), JSON),
				"emptyVariableWithLogin").isEqualTo(HostLogin.SUBSCRIPTION);
			requireThat(Credentials.classify(configuration,
				Map.of("CLAUDE_SECURESTORAGE_CONFIG_DIR", emptyStore.toString()), JSON), "storeWithoutLoginAndFile").
				isEqualTo(HostLogin.SUBSCRIPTION);
			requireThat(Credentials.classify(configuration, Map.of(), JSON), "unset").
				isEqualTo(HostLogin.SUBSCRIPTION);
		}
	}
}
