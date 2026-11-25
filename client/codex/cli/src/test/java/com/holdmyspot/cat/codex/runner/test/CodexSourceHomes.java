package com.holdmyspot.cat.codex.runner.test;

import com.holdmyspot.cat.common.Platform;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

/**
 * Writes source homes for tests: home folders whose {@code .codex} folder holds the Codex authentication that a nested
 * session takes. Each file that holds a secret is readable by its owner alone unless a test says otherwise, as a user's
 * own Codex writes it.
 */
public final class CodexSourceHomes
{
	/**
	 * The text of every fixture secret, such as an API key, a token or a sign-in. A test that scans a message, a
	 * record or a file for a leaked secret looks for this text.
	 */
	public static final String SECRET = "sk-proj-fixture-codex";

	/**
	 * Prevents construction.
	 */
	private CodexSourceHomes()
	{
	}

	/**
	 * Creates a home folder with an empty {@code .codex} folder.
	 *
	 * @param parent the folder that receives the home
	 * @param name   the home's name
	 * @return the home folder
	 * @throws IOException if the folders cannot be created
	 */
	public static Path create(Path parent, String name) throws IOException
	{
		Path home = Files.createDirectories(parent.resolve(name));
		Files.createDirectories(home.resolve(".codex"));
		return home;
	}

	/**
	 * Writes the {@code auth.json} of a Codex folder that holds an API key, readable by its owner alone, as
	 * {@code codex login --with-api-key} writes it.
	 *
	 * @param folder the Codex folder
	 * @return the file
	 * @throws IOException if the file cannot be written
	 */
	public static Path apiKeyLogin(Path folder) throws IOException
	{
		return ownerOnly(Files.writeString(Files.createDirectories(folder).resolve("auth.json"), """
			{"auth_mode": "apikey", "OPENAI_API_KEY": "%s"}
			""".formatted(SECRET)));
	}

	/**
	 * Writes the {@code auth.json} of a Codex folder that holds a ChatGPT sign-in, readable by its owner alone, as
	 * {@code codex login} writes it.
	 *
	 * @param folder the Codex folder
	 * @return the file
	 * @throws IOException if the file cannot be written
	 */
	public static Path signInLogin(Path folder) throws IOException
	{
		return ownerOnly(Files.writeString(Files.createDirectories(folder).resolve("auth.json"), """
			{"auth_mode": "chatgpt", "OPENAI_API_KEY": null,
			 "tokens": {"id_token": "%s", "access_token": "%s", "refresh_token": "%s", "account_id": "account"}}
			""".formatted(SECRET, SECRET, SECRET)));
	}

	/**
	 * Writes the {@code config.toml} of a Codex folder.
	 *
	 * @param folder        the Codex folder
	 * @param configuration the file's TOML text
	 * @return the file
	 * @throws IOException if the file cannot be written
	 */
	public static Path configuration(Path folder, String configuration) throws IOException
	{
		return Files.writeString(Files.createDirectories(folder).resolve("config.toml"), configuration);
	}

	/**
	 * Writes a file that holds the fixture secret, readable by its owner alone, such as the key file that a
	 * provider's token command prints.
	 *
	 * @param file the file
	 * @return the file
	 * @throws IOException if the file cannot be written
	 */
	public static Path secret(Path file) throws IOException
	{
		return ownerOnly(Files.writeString(file, SECRET));
	}

	/**
	 * Returns the environment of a runner whose own home folder is a home, with an API-key login in its
	 * {@code .codex} folder, for a test whose subject is not the session's credential.
	 *
	 * @param root the folder that receives the home folder
	 * @return the environment
	 * @throws IOException if the home folder cannot be written
	 */
	public static Map<String, String> runnerEnvironment(Path root) throws IOException
	{
		Path home = create(root, "runner-home");
		apiKeyLogin(home.resolve(".codex"));
		return Map.of("PATH", "", Platform.current().homeVariable(), home.toString());
	}

	/**
	 * Makes a file readable and writable by its owner alone, on a file system that has POSIX permissions.
	 *
	 * @param file the file
	 * @return the file
	 * @throws IOException if the permissions cannot be set
	 */
	public static Path ownerOnly(Path file) throws IOException
	{
		if (Files.getFileStore(file).supportsFileAttributeView("posix"))
			Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
		return file;
	}
}
