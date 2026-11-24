package com.holdmyspot.cat.claude.credential;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.holdmyspot.cat.common.BoundedInput;
import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.optimizer.port.HostLogin;
import com.holdmyspot.cat.common.runner.SecretFile;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * CAT's Claude credential file of a source home, {@code .cat/claude/.credentials.json}, which holds one long-lived
 * Claude token that the user saved: an Anthropic API key or a token that {@code claude setup-token} printed. Its
 * content is {@code {"format_version": 1, "token": "<token>"}}, at most {@value #MAXIMUM_BYTES} bytes, and the token's
 * prefix gives its class. The file is never a Claude configuration folder's login: Claude Code neither reads nor
 * writes it. The runner reads it, the {@code claude-credential} command saves, reports and removes it, and uninstall
 * removes the uninstalling user's own.
 * <p>
 * A save replaces the file whole: a reader in any process, such as a runner that a launch starts meanwhile, reads the
 * earlier file or the new one, never part of one.
 * <p>
 * No message, log entry or {@link #toString()} of this class holds the token.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class ClaudeCredentialFile
{
	/**
	 * The file's path relative to its source home, with {@code /} between its names on every platform, as records name
	 * the file.
	 */
	public static final String RELATIVE_NAME = ".cat/claude/.credentials.json";
	/**
	 * The file's path relative to its source home.
	 */
	public static final Path RELATIVE_PATH = Path.of(RELATIVE_NAME);
	/**
	 * The size of the largest file that is read. The whole document is read at once, which this bound keeps small.
	 */
	public static final int MAXIMUM_BYTES = 16 * 1024;
	/**
	 * The prefix of an Anthropic API key.
	 */
	private static final String API_KEY_PREFIX = "sk-ant-api";
	/**
	 * The prefix of a token that {@code claude setup-token} printed.
	 */
	private static final String SETUP_TOKEN_PREFIX = "sk-ant-oat";
	/**
	 * The fields of the file's format.
	 */
	private static final Set<String> FIELDS = Set.of("format_version", "token");
	/**
	 * The only version of the file's format, which a save writes and a read accepts.
	 */
	private static final int FORMAT_VERSION = 1;
	/**
	 * The two forms of token that the file accepts, as a refusal names them.
	 */
	private static final String ACCEPTED_FORMS = "an Anthropic API key, which starts with " + API_KEY_PREFIX +
		", or a token that claude setup-token printed, which starts with " + SETUP_TOKEN_PREFIX;
	private final Path file;
	private final String token;
	private final HostLogin loginClass;

	/**
	 * Creates a credential file's content.
	 *
	 * @param file       the file
	 * @param token      the file's token
	 * @param loginClass the token's class
	 */
	private ClaudeCredentialFile(Path file, String token, HostLogin loginClass)
	{
		this.file = file;
		this.token = token;
		this.loginClass = loginClass;
	}

	/**
	 * Returns the credential file of a source home. The caller's {@code CLAUDE_CONFIG_DIR} never moves it.
	 *
	 * @param home the source home
	 * @return the file
	 * @throws NullPointerException if {@code home} is null
	 */
	public static Path path(Path home)
	{
		requireThat(home, "home").isNotNull();
		return home.resolve(RELATIVE_PATH);
	}

	/**
	 * Reads the credential file of a source home.
	 *
	 * @param json reads the file
	 * @param home the source home
	 * @return the file's content, or empty if the file does not exist
	 * @throws NullPointerException       if any of the arguments are null
	 * @throws CredentialRefusedException if the file is not a regular file that its owner alone may use, is larger than
	 *                                    {@value #MAXIMUM_BYTES} bytes, is not a JSON object with exactly the fields
	 *                                    {@code format_version}, which is {@code 1}, and {@code token}, or holds a token
	 *                                    of neither accepted form. The reason names the file and the fault, and never
	 *                                    the token.
	 * @throws IOException                if the file cannot be read
	 */
	public static Optional<ClaudeCredentialFile> read(JsonMapper json, Path home)
		throws IOException, CredentialRefusedException
	{
		requireThat(json, "json").isNotNull();
		Path file = path(home);
		if (!Files.exists(file, NOFOLLOW_LINKS))
			return Optional.empty();
		Optional<String> problem = SecretFile.problem(file, SecretFile.read(file));
		if (problem.isPresent())
			throw new CredentialRefusedException(problem.get());
		if (Files.size(file) > MAXIMUM_BYTES)
		{
			throw refusal(file, "is larger than " + MAXIMUM_BYTES + " bytes, the most that the file may hold. Save the " +
				"token again with claude-credential set");
		}
		JsonNode document;
		try
		{
			document = json.readTree(BoundedInput.readUtf8(file, MAXIMUM_BYTES, "CAT's Claude credential file"));
		}
		catch (JacksonException _)
		{
			// Jackson's message can quote the file's content, which holds the token
			throw refusal(file, "is not valid JSON. Save the token again with claude-credential set");
		}
		if (!document.isObject())
		{
			throw refusal(file, "is not a JSON object with the fields format_version and token. Save the token again " +
				"with claude-credential set");
		}
		List<String> unknown = new ArrayList<>();
		for (String field : document.propertyNames())
		{
			if (!FIELDS.contains(field))
				unknown.add(field);
		}
		if (!unknown.isEmpty())
		{
			throw refusal(file, "holds the unknown field " + String.join(", ", unknown) + "; the file holds only " +
				"format_version and token. Save the token again with claude-credential set");
		}
		JsonNode version = document.path("format_version");
		if (!version.isIntegralNumber() || version.asLong() != FORMAT_VERSION)
		{
			throw refusal(file, "does not have format_version 1, the only format that this CAT reads. Save the token " +
				"again with claude-credential set");
		}
		JsonNode tokenNode = document.path("token");
		if (!tokenNode.isString() || tokenNode.stringValue().isBlank())
			throw refusal(file, "holds no token. It must hold " + ACCEPTED_FORMS + ". Save one with claude-credential set");
		String token = tokenNode.stringValue();
		Optional<HostLogin> loginClass = tokenClass(token);
		if (loginClass.isEmpty())
		{
			throw refusal(file, "holds a token that is not " + ACCEPTED_FORMS + ". Save one of them with " +
				"claude-credential set");
		}
		return Optional.of(new ClaudeCredentialFile(file, token, loginClass.get()));
	}

	/**
	 * Returns the class of a token, which its prefix gives.
	 *
	 * @param token the token
	 * @return {@code api_key} for an Anthropic API key, {@code subscription} for a {@code claude setup-token} token, or
	 * 	empty for a token of neither form
	 */
	private static Optional<HostLogin> tokenClass(String token)
	{
		if (token.startsWith(API_KEY_PREFIX))
			return Optional.of(HostLogin.API_KEY);
		if (token.startsWith(SETUP_TOKEN_PREFIX))
			return Optional.of(HostLogin.SUBSCRIPTION);
		return Optional.empty();
	}

	/**
	 * Saves a token in the credential file of a source home, replacing the file's earlier token. When
	 * {@code .cat/claude} is absent, it is created with access for its owner alone. The token is written into a new
	 * file in that folder, created with access for its owner alone, forced to disk and renamed over
	 * {@code .credentials.json}. Owner-only access is mode {@code 0700} for the folder and {@code 0600} for the file on
	 * a file system with POSIX permissions, and an access list of the owner alone on one with access lists.
	 *
	 * @param json     writes the file
	 * @param home     the existing source home
	 * @param token    the token, without surrounding whitespace
	 * @param replacer renames the new file over the earlier one
	 * @return the saved file, the token's class and whether an earlier file was replaced
	 * @throws NullPointerException       if any of the arguments are null
	 * @throws CredentialRefusedException if the token is empty, is of neither accepted form or would make the file
	 *                                    larger than {@value #MAXIMUM_BYTES} bytes, or if {@code .cat/claude} is a
	 *                                    symbolic link or not a folder, or {@code .credentials.json} is a symbolic link
	 *                                    or not a regular file. Nothing is written, and the reason names the path or
	 *                                    both accepted forms, never the token.
	 * @throws IOException                if the folder or the file cannot be created or written; the earlier file is
	 *                                    unchanged and no new file remains
	 */
	public static Saved save(JsonMapper json, Path home, String token, FileReplacer replacer)
		throws IOException, CredentialRefusedException
	{
		requireThat(json, "json").isNotNull();
		requireThat(home, "home").isNotNull();
		requireThat(token, "token").isNotNull();
		requireThat(replacer, "replacer").isNotNull();
		if (token.isBlank())
			throw new CredentialRefusedException("The token is empty. Save " + ACCEPTED_FORMS + ".");
		Optional<HostLogin> loginClass = tokenClass(token);
		if (loginClass.isEmpty())
			throw new CredentialRefusedException("The token is not " + ACCEPTED_FORMS + ". Save one of them.");
		ObjectNode document = json.createObjectNode();
		document.put("format_version", FORMAT_VERSION);
		document.put("token", token);
		byte[] content = (json.writeValueAsString(document) + "\n").getBytes(UTF_8);
		if (content.length > MAXIMUM_BYTES)
		{
			throw new CredentialRefusedException("The token is too long: CAT's Claude credential file holds at most " +
				MAXIMUM_BYTES + " bytes. Save " + ACCEPTED_FORMS + ".");
		}

		Path file = path(home);
		Path folder = file.getParent();
		boolean folderExists = existingFolder(folder);
		boolean replaced = folderExists && existingFile(file);
		if (!folderExists)
		{
			Path catFolder = Files.createDirectories(folder.getParent());
			Files.createDirectory(folder, SecretFile.ownerOnlyFolder(catFolder));
		}
		Path temporary = Files.createTempFile(folder, ".credentials-", ".tmp", SecretFile.ownerOnlyFile(folder));
		try
		{
			try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE))
			{
				ByteBuffer bytes = ByteBuffer.wrap(content);
				while (bytes.hasRemaining())
					channel.write(bytes);
				channel.force(true);
			}
			replacer.replace(temporary, file);
		}
		catch (IOException | RuntimeException e)
		{
			try
			{
				Files.deleteIfExists(temporary);
			}
			catch (IOException suppressed)
			{
				e.addSuppressed(suppressed);
			}
			throw e;
		}
		return new Saved(file, loginClass.get(), replaced);
	}

	/**
	 * Returns whether the folder of a credential file exists, refusing a path that is not a folder.
	 *
	 * @param folder the {@code .cat/claude} folder
	 * @return {@code true} if the folder exists, {@code false} if nothing is at its path
	 * @throws CredentialRefusedException if the path is a symbolic link or an entry other than a folder
	 * @throws IOException                if the path's attributes cannot be read
	 */
	private static boolean existingFolder(Path folder) throws IOException, CredentialRefusedException
	{
		BasicFileAttributes attributes;
		try
		{
			attributes = Files.readAttributes(folder, BasicFileAttributes.class, NOFOLLOW_LINKS);
		}
		catch (NoSuchFileException _)
		{
			return false;
		}
		if (attributes.isSymbolicLink())
		{
			throw new CredentialRefusedException(folder + " is a symbolic link, whose target anyone who may write " +
				"its folder can change. Replace the link with a folder that its owner alone may use, or remove it.");
		}
		if (!attributes.isDirectory())
		{
			throw new CredentialRefusedException(folder + " is not a folder. Move it away, so that the folder of " +
				"CAT's Claude credential file can take its place.");
		}
		return true;
	}

	/**
	 * Returns whether a credential file exists, refusing a path that is not a regular file.
	 *
	 * @param file the {@code .credentials.json} file
	 * @return {@code true} if the file exists, {@code false} if nothing is at its path
	 * @throws CredentialRefusedException if the path is a symbolic link or an entry other than a regular file
	 * @throws IOException                if the path's attributes cannot be read
	 */
	private static boolean existingFile(Path file) throws IOException, CredentialRefusedException
	{
		BasicFileAttributes attributes;
		try
		{
			attributes = Files.readAttributes(file, BasicFileAttributes.class, NOFOLLOW_LINKS);
		}
		catch (NoSuchFileException _)
		{
			return false;
		}
		if (attributes.isSymbolicLink())
		{
			throw new CredentialRefusedException(file + " is a symbolic link, whose target anyone who may write its " +
				"folder can change. Remove the link; claude-credential set then saves the token in a new file.");
		}
		if (!attributes.isRegularFile())
		{
			throw new CredentialRefusedException(file + " is not a regular file. Move it away, so that CAT's Claude " +
				"credential file can take its place.");
		}
		return true;
	}

	/**
	 * Removes the credential file of a source home, and the {@code .cat/claude} folder when it is then empty. A folder
	 * that holds other files stays.
	 *
	 * @param home the source home
	 * @return {@code true} if a file was removed, {@code false} if there was none
	 * @throws NullPointerException       if {@code home} is null
	 * @throws CredentialRefusedException if {@code .cat/claude} is a symbolic link or not a folder, or
	 *                                    {@code .credentials.json} is a symbolic link or not a regular file; nothing is
	 *                                    removed, and the reason names the path
	 * @throws IOException                if the file or the emptied folder cannot be removed
	 */
	public static boolean remove(Path home) throws IOException, CredentialRefusedException
	{
		Path file = path(home);
		Path folder = file.getParent();
		if (!existingFolder(folder))
			return false;
		boolean present = existingFile(file);
		if (present)
			Files.delete(file);
		try
		{
			Files.delete(folder);
		}
		catch (DirectoryNotEmptyException _)
		{
			// The folder keeps the other files that it holds
		}
		return present;
	}

	/**
	 * Returns the refusal of a credential file that a launch cannot use.
	 *
	 * @param file  the file
	 * @param fault what is wrong with the file, which never quotes the token
	 * @return the refusal
	 */
	private static CredentialRefusedException refusal(Path file, String fault)
	{
		return new CredentialRefusedException("CAT's Claude credential file " + file + " " + fault + ".");
	}

	/**
	 * Returns the file.
	 *
	 * @return the file
	 */
	public Path file()
	{
		return file;
	}

	/**
	 * Returns the file's token, which only a session's key file receives.
	 *
	 * @return the token
	 */
	public String token()
	{
		return token;
	}

	/**
	 * Returns the token's class, which its prefix gives.
	 *
	 * @return {@code api_key} for an Anthropic API key, or {@code subscription} for a {@code claude setup-token} token
	 */
	public HostLogin loginClass()
	{
		return loginClass;
	}

	@Override
	public String toString()
	{
		return "ClaudeCredentialFile{file=" + file + ", loginClass=" + loginClass + "}";
	}

	/**
	 * A token that {@link #save} saved.
	 *
	 * @param file       the credential file
	 * @param loginClass the token's class
	 * @param replaced   {@code true} if the file replaced an earlier credential file
	 */
	public record Saved(Path file, HostLogin loginClass, boolean replaced)
	{
		/**
		 * Creates a saved token's record.
		 *
		 * @param file       the credential file
		 * @param loginClass the token's class
		 * @param replaced   {@code true} if the file replaced an earlier credential file
		 * @throws NullPointerException if {@code file} or {@code loginClass} are null
		 */
		public Saved
		{
			requireThat(file, "file").isNotNull();
			requireThat(loginClass, "loginClass").isNotNull();
		}
	}
}
