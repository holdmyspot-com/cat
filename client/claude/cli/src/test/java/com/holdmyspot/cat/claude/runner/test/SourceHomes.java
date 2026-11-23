package com.holdmyspot.cat.claude.runner.test;

import com.holdmyspot.cat.claude.runner.ClaudeAuthentication;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.runner.AuthenticationOptions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes source homes for tests: home folders whose {@code .claude} folder holds the Claude authentication that a
 * nested session takes. Each file that holds a secret is readable by its owner alone unless a test says otherwise, as
 * a user's own Claude Code writes it.
 */
public final class SourceHomes
{
	/**
	 * The text of every fixture secret, such as an API key or a token. A test that scans a message, a record or a
	 * file for a leaked secret looks for this text.
	 */
	public static final String SECRET = "sk-ant-api03-fixture";

	/**
	 * Prevents construction.
	 */
	private SourceHomes()
	{
	}

	/**
	 * Creates a home folder with an empty {@code .claude} folder.
	 *
	 * @param parent the folder that receives the home
	 * @param name   the home's name
	 * @return the home folder
	 * @throws IOException if the folders cannot be created
	 */
	public static Path create(Path parent, String name) throws IOException
	{
		Path home = Files.createDirectories(parent.resolve(name));
		Files.createDirectories(home.resolve(".claude"));
		return home;
	}

	/**
	 * Writes the {@code settings.json} of a home's {@code .claude} folder, readable by its owner alone.
	 *
	 * @param home     the home folder
	 * @param settings the file's JSON text
	 * @return the file
	 * @throws IOException if the file cannot be written
	 */
	public static Path settings(Path home, String settings) throws IOException
	{
		return ownerOnly(Files.writeString(home.resolve(".claude/settings.json"), settings));
	}

	/**
	 * Writes the stored login of a home's {@code .claude} folder, {@code .credentials.json}, readable by its owner
	 * alone, as Claude Code's {@code /login} writes it.
	 *
	 * @param home the home folder
	 * @return the file
	 * @throws IOException if the file cannot be written
	 */
	public static Path storedLogin(Path home) throws IOException
	{
		return ownerOnly(Files.writeString(home.resolve(".claude/.credentials.json"), """
			{"claudeAiOauth": {"accessToken": "%s", "refreshToken": "%s"}}
			""".formatted(SECRET, SECRET)));
	}

	/**
	 * Writes a file that holds the fixture secret, readable by its owner alone, such as the key file that an
	 * {@code apiKeyHelper} prints.
	 *
	 * @param file the file
	 * @return {@code file}
	 * @throws IOException if the file cannot be written
	 */
	public static Path secret(Path file) throws IOException
	{
		Files.createDirectories(file.getParent());
		return ownerOnly(Files.writeString(file, SECRET));
	}

	/**
	 * Returns the authentication of a source home, below a folder, whose only credential is an {@code apiKeyHelper} that
	 * names no file, for a test whose subject is not the credential.
	 *
	 * @param parent the folder that receives the source home
	 * @return the authentication
	 * @throws IOException if the source home cannot be written or read
	 */
	public static ClaudeAuthentication keyHelper(Path parent) throws IOException
	{
		Path home = create(parent, "key-helper-source");
		settings(home, """
			{"apiKeyHelper": "printf key"}
			""");
		return ClaudeAuthentication.read(JsonMapper.builder().build(), new AuthenticationOptions(Optional.of(home),
			Optional.empty(), false), Map.of(), Platform.current());
	}

	/**
	 * Lets a file's owner alone read and write it.
	 *
	 * @param file the file
	 * @return {@code file}
	 * @throws IOException if the permissions cannot be set
	 */
	public static Path ownerOnly(Path file) throws IOException
	{
		return Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
	}
}
