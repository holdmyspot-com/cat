package com.holdmyspot.cat.claude.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static org.testng.Assert.expectThrows;

import com.holdmyspot.cat.claude.runner.ClaudeAuthentication;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.PortableFiles;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.AuthenticationOptions;
import com.holdmyspot.cat.common.runner.policy.SessionRefusedException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests {@link ClaudeAuthentication}.
 */
public final class ClaudeAuthenticationTest
{
	/**
	 * Reads the fixtures' settings files.
	 */
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * Creates the test.
	 */
	public ClaudeAuthenticationTest()
	{
	}

	/**
	 * Verifies that a source home whose CAT credential file, {@code .cat/claude/.credentials.json}, its group may read is
	 * refused, naming the file and the permission to clear, without quoting the file.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void checksCatCredentialFile() throws IOException
	{
		// Requirement: R-I14
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("source-secrets-")).toRealPath();
			Path source = SourceHomes.create(root, "source");
			Path file = SourceHomes.catCredential(source, SourceHomes.SECRET);
			SourceHomes.readableByOthers(file);

			requireRefusal(source, file);
		}
	}

	/**
	 * Verifies that a file that the source's {@code apiKeyHelper} names by an absolute path, or by a path under
	 * {@code ~/}, which names the source home, is checked, and is refused when its group may read it.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void checksFilesThatKeyHelperNames() throws IOException
	{
		// Requirement: R-I14
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("source-secrets-")).toRealPath();

			Path absoluteSource = SourceHomes.create(root, "absolute");
			Path absoluteKey = SourceHomes.secret(root.resolve("keys/absolute-key"));
			SourceHomes.readableByOthers(absoluteKey);
			SourceHomes.settings(absoluteSource, """
				{"apiKeyHelper": %s}
				""".formatted(JSON.writeValueAsString("cat " + absoluteKey)));
			requireRefusal(absoluteSource, absoluteKey);

			Path tildeSource = SourceHomes.create(root, "tilde");
			Path tildeKey = SourceHomes.secret(tildeSource.resolve("secrets/key"));
			SourceHomes.readableByOthers(tildeKey);
			SourceHomes.settings(tildeSource, """
				{"apiKeyHelper": "cat ~/secrets/key"}
				""");
			requireRefusal(tildeSource, tildeKey);
		}
	}

	/**
	 * Verifies that a source {@code settings.json} whose {@code env} holds a token is checked and refused when its group
	 * may read it, while one that holds no token, here only an {@code apiKeyHelper} whose key file only its owner may
	 * read, is accepted at mode {@code 0644}.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void checksSettingsThatHoldToken() throws IOException
	{
		// Requirement: R-I14
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("source-secrets-")).toRealPath();

			Path tokenSource = SourceHomes.create(root, "token");
			Path tokenSettings = SourceHomes.settings(tokenSource, """
				{"env": {"ANTHROPIC_API_KEY": "%s"}}
				""".formatted(SourceHomes.SECRET));
			SourceHomes.readableByOthers(tokenSettings);
			requireRefusal(tokenSource, tokenSettings);

			Path helperSource = SourceHomes.create(root, "helper");
			SourceHomes.secret(helperSource.resolve("key"));
			Path helperSettings = SourceHomes.settings(helperSource, """
				{"apiKeyHelper": "cat ~/key"}
				""");
			PortableFiles.setMode(helperSettings, PosixFilePermissions.fromString("rw-r--r--"));
			ClaudeAuthentication authentication = ClaudeAuthentication.read(JSON, named(helperSource),
				environment(root), Platform.current(), SourceHomes.PLUGIN);
			requireThat(authentication.secretFiles(), "secretFiles").isEqualTo(List.of(
				helperSource.resolve("key")));
		}
	}

	/**
	 * Requires a source home's authentication to be refused because of one secret file.
	 *
	 * @param source the source home
	 * @param file   the file whose permissions the refusal must name
	 * @throws IOException if the source home cannot be read
	 */
	private static void requireRefusal(Path source, Path file) throws IOException
	{
		SessionRefusedException refusal = expectThrows(SessionRefusedException.class, () ->
			ClaudeAuthentication.read(JSON, named(source), environment(source.getParent()), Platform.current(),
				SourceHomes.PLUGIN));
		requireThat(refusal.getMessage(), "message").contains(file.toString()).doesNotContain(SourceHomes.SECRET);
		for (String reason : SourceHomes.readableByOthersReasons())
			requireThat(refusal.getMessage(), "message").contains(reason);
	}

	/**
	 * Returns the choice of a launch that names a source home and declares no class of login.
	 *
	 * @param source the source home
	 * @return the choice
	 */
	private static AuthenticationOptions named(Path source)
	{
		return new AuthenticationOptions(Optional.of(source), Optional.empty(), false);
	}

	/**
	 * Returns a runner's environment whose home folder holds no Claude authentication.
	 *
	 * @param root the test's folder
	 * @return the environment
	 */
	private static Map<String, String> environment(Path root)
	{
		return Map.of("PATH", "", Platform.current().homeVariable(), root.resolve("runner").toString());
	}
}
