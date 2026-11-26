package com.holdmyspot.cat.codex.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.BoundedInput;
import com.holdmyspot.cat.common.CommandLineArguments;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.runner.login.CodexLoginClient;
import com.holdmyspot.cat.common.runner.login.LoginRefusal;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The token command of a session's model provider that asks the request server's login service for the session's
 * current token. The session's key file names the service's socket and holds the session's credential, which the
 * service checks; the command prints the token that the service answers, and nothing else.
 * <p>
 * Codex starts the command itself, outside the command sandbox, as it starts {@link CodexKeyFileCommand}, and with the
 * same runtime and module path.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class CodexTokenCommand
{
	/**
	 * The most bytes of a key file that the command reads.
	 */
	private static final int MAXIMUM_KEY_FILE_BYTES = 65_536;

	/**
	 * Prevents construction.
	 */
	private CodexTokenCommand()
	{
	}

	/**
	 * Returns the content of a key file that names the login service's socket and a session's credential.
	 *
	 * @param socket     the service's socket
	 * @param credential the session's credential
	 * @return the content, as UTF-8 JSON
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code credential} is blank
	 */
	public static byte[] keyFileContent(Path socket, String credential)
	{
		requireThat(socket, "socket").isNotNull();
		requireThat(credential, "credential").isNotBlank();
		ObjectNode content = JsonMapper.builder().build().createObjectNode();
		content.put("socket", socket.toString());
		content.put("credential", credential);
		return content.toString().getBytes(UTF_8);
	}

	/**
	 * Returns the command that asks the login service for the token of the session whose key file it names.
	 *
	 * @param keyFile    the absolute path of the key file, which is the last argument
	 * @param platform   the platform that the command runs on
	 * @param javaHome   the home folder of the runtime that runs the runner
	 * @param modulePath the module path that the runner's program was started with, or {@code null} if its modules
	 *                   are in the runtime
	 * @return the invocation
	 * @throws NullPointerException if any of the arguments other than {@code modulePath} are null
	 */
	public static CodexKeyFileCommand.Invocation invocation(Path keyFile, Platform platform, Path javaHome,
		String modulePath)
	{
		requireThat(keyFile, "keyFile").isNotNull();
		return CodexKeyFileCommand.bundled(CodexTokenCommand.class, List.of(keyFile.toString()), platform, javaHome,
			modulePath);
	}

	/**
	 * Returns the command that asks the login service for a session's token, for the runtime and platform that this
	 * program runs on.
	 *
	 * @param keyFile the absolute path of the key file
	 * @return the invocation
	 * @throws NullPointerException if {@code keyFile} is null
	 */
	public static CodexKeyFileCommand.Invocation invocation(Path keyFile)
	{
		return invocation(keyFile, Platform.current(), Path.of(System.getProperty("java.home")),
			System.getProperty("jdk.module.path"));
	}

	/**
	 * Prints the token of the session whose key file the argument names to standard output, and nothing else.
	 *
	 * @param args the arguments
	 * @param output receives the token
	 * @param errors receives the reason for a failure, which holds no token
	 * @return {@code 0} if the token was printed, otherwise {@code 1}
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static int run(String[] args, OutputStream output, PrintStream errors)
	{
		requireThat(args, "args").isNotNull();
		requireThat(output, "output").isNotNull();
		requireThat(errors, "errors").isNotNull();
		if (args.length != 1)
		{
			errors.println("Usage: " + CodexTokenCommand.class.getName() + " <key file>");
			return 1;
		}
		Path keyFile = Path.of(args[0]);
		JsonNode content;
		try
		{
			content = JsonMapper.builder().build().readTree(BoundedInput.readUtf8(keyFile, MAXIMUM_KEY_FILE_BYTES,
				"key file"));
		}
		catch (IOException e)
		{
			errors.println("Cannot read the key file " + keyFile + ": " + e.getClass().getSimpleName());
			return 1;
		}
		catch (JacksonException _)
		{
			errors.println("The key file " + keyFile + " does not name the request server's login service");
			return 1;
		}
		String socket = content.path("socket").asString("");
		String credential = content.path("credential").asString("");
		if (socket.isBlank() || credential.isBlank())
		{
			errors.println("The key file " + keyFile + " does not name the request server's login service");
			return 1;
		}
		String token;
		try
		{
			token = CodexLoginClient.token(Path.of(socket), credential);
		}
		catch (LoginRefusal e)
		{
			// A refusal's reason holds no token
			errors.println(e.getMessage());
			return 1;
		}
		catch (IOException e)
		{
			errors.println("The request server's login service at " + socket + " cannot be reached: " +
				e.getClass().getSimpleName() + ". The request server that started the session has ended; start the " +
				"session again.");
			return 1;
		}
		try
		{
			output.write(token.getBytes(UTF_8));
			output.flush();
		}
		catch (IOException e)
		{
			errors.println("Cannot print the token: " + e.getClass().getSimpleName());
			return 1;
		}
		return 0;
	}

	/**
	 * Prints the token of the session whose key file the argument names to standard output, and nothing else. Exits
	 * with status 1 and a message on standard error, which holds no token, if the service refuses or is gone.
	 *
	 * @param args the key file's path
	 */
	public static void main(String[] args)
	{
		int status = run(CommandLineArguments.recover(args), System.out, System.err);
		if (status != 0)
			System.exit(status);
	}
}
