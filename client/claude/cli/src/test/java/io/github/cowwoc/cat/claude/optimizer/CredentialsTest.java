package io.github.cowwoc.cat.claude.optimizer;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.optimizer.port.HostLogin;
import io.github.cowwoc.cat.common.TemporaryDirectories;

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
}
