package com.holdmyspot.cat.codex.optimizer.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.codex.optimizer.Credentials;
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
	 * Verifies that an API key in the environment selects an API key even when the file holds a subscription login.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void environmentApiKeyOverridesFile() throws IOException
	{
		// Requirement: R-E7
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path home = cleanup.add(Files.createTempDirectory("cat-codex-home-"));
			writeAuth(home, "{\"OPENAI_API_KEY\":null,\"tokens\":{\"id_token\":\"placeholder\"}}");
			requireThat(Credentials.classify(home, Map.of("CODEX_API_KEY", "placeholder"), JSON), "codexKey").
				isEqualTo(HostLogin.API_KEY);
			requireThat(Credentials.classify(home, Map.of("OPENAI_API_KEY", "placeholder"), JSON), "openaiKey").
				isEqualTo(HostLogin.API_KEY);
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
