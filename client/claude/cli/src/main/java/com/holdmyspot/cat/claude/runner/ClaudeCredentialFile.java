package com.holdmyspot.cat.claude.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import com.holdmyspot.cat.common.BoundedInput;
import com.holdmyspot.cat.common.optimizer.port.HostLogin;
import com.holdmyspot.cat.common.runner.SecretFile;
import com.holdmyspot.cat.common.runner.policy.SessionRefusedException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * CAT's Claude credential file of a source home, {@code .cat/claude/.credentials.json}, which holds one long-lived
 * Claude token that the user saved: an Anthropic API key or a token that {@code claude setup-token} printed. Its
 * content is {@code {"format_version": 1, "token": "<token>"}}, at most {@value #MAXIMUM_BYTES} bytes, and the token's
 * prefix gives its class. The file is never a Claude configuration folder's login: Claude Code neither reads nor
 * writes it.
 * <p>
 * No message, log entry or {@link #toString()} of this class holds the token.
 * <p>
 * <b>Thread Safety:</b> This class is immutable.
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
	 * @throws NullPointerException    if any of the arguments are null
	 * @throws SessionRefusedException if the file is not a regular file that its owner alone may use, is larger than
	 *                                 {@value #MAXIMUM_BYTES} bytes, is not a JSON object with exactly the fields
	 *                                 {@code format_version}, which is {@code 1}, and {@code token}, or holds a token of
	 *                                 neither accepted form. The reason names the file and the fault, and never the
	 *                                 token.
	 * @throws IOException             if the file cannot be read
	 */
	public static Optional<ClaudeCredentialFile> read(JsonMapper json, Path home) throws IOException
	{
		requireThat(json, "json").isNotNull();
		Path file = path(home);
		if (!Files.exists(file, NOFOLLOW_LINKS))
			return Optional.empty();
		Optional<String> problem = SecretFile.problem(file, SecretFile.read(file));
		if (problem.isPresent())
			throw new SessionRefusedException("CAT does not start the session: " + problem.get());
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
		if (!version.isIntegralNumber() || version.asLong() != 1)
		{
			throw refusal(file, "does not have format_version 1, the only format that this CAT reads. Save the token " +
				"again with claude-credential set");
		}
		JsonNode tokenNode = document.path("token");
		if (!tokenNode.isString() || tokenNode.stringValue().isBlank())
			throw refusal(file, "holds no token. It must hold " + ACCEPTED_FORMS + ". Save one with claude-credential set");
		String token = tokenNode.stringValue();
		HostLogin loginClass;
		if (token.startsWith(API_KEY_PREFIX))
			loginClass = HostLogin.API_KEY;
		else if (token.startsWith(SETUP_TOKEN_PREFIX))
			loginClass = HostLogin.SUBSCRIPTION;
		else
		{
			throw refusal(file, "holds a token that is neither " + ACCEPTED_FORMS + ". Save one of them with " +
				"claude-credential set");
		}
		return Optional.of(new ClaudeCredentialFile(file, token, loginClass));
	}

	/**
	 * Returns the refusal of a launch whose credential file is unusable.
	 *
	 * @param file  the file
	 * @param fault what is wrong with the file, which never quotes the token
	 * @return the refusal
	 */
	private static SessionRefusedException refusal(Path file, String fault)
	{
		return new SessionRefusedException("CAT does not start the session: CAT's Claude credential file " + file + " " +
			fault + ".");
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
}
