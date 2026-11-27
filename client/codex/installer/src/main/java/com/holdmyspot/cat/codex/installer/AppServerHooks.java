package com.holdmyspot.cat.codex.installer;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.installer.activation.HarnessCli;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Asks Codex's {@code codex app-server} which hooks Codex finds for a folder, as its {@code hooks/list} method lists
 * them.
 * <p>
 * The server reads one JSON-RPC message per line on its standard input and writes one per line on its standard
 * output, without the {@code jsonrpc} member. The client sends {@code initialize} with the experimental API, which
 * {@code hooks/list} belongs to, waits for its response, sends the {@code initialized} notification and then
 * {@code hooks/list} for the folder, skipping the notifications that the server sends meanwhile. It then closes the
 * server's input, on which the server exits. Codex 0.172.0 answers as the schema's {@code HooksListResponse}: for each
 * folder, the hooks with their key, source, plugin, declaring file and {@code currentHash}, which joins the hash of the
 * hook's declaration and the hash of the plugin's content with a slash. A Codex that keeps no content hash, such as
 * codex-cli 0.160.0+41, lists the hash of the hook's declaration alone.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
final class AppServerHooks
{
	/**
	 * The longest that the conversation with the server may take, from its start to the listing.
	 */
	private static final Duration TIME_LIMIT = Duration.ofMinutes(1);
	/**
	 * The longest that the server may take to exit after its input closes.
	 */
	private static final Duration EXIT_LIMIT = Duration.ofSeconds(10);
	/**
	 * The longest line of the server's output that the client reads.
	 */
	private static final int MAXIMUM_LINE_BYTES = 16 * 1024 * 1024;
	/**
	 * The identifier of the {@code initialize} request.
	 */
	private static final int INITIALIZE = 1;
	/**
	 * The identifier of the {@code hooks/list} request.
	 */
	private static final int LIST = 2;
	/**
	 * A {@code currentHash}: the declaration hash, followed by a slash and the content hash unless Codex keeps none.
	 */
	private static final Pattern CURRENT_HASH = Pattern.compile("(sha256:[0-9a-f]{64})(?:/(sha256:[0-9a-f]{64}))?");
	/**
	 * Writes the client's messages and reads the server's.
	 */
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * Prevents instantiation.
	 */
	private AppServerHooks()
	{
	}

	/**
	 * Lists the hooks that Codex finds for a folder.
	 *
	 * @param cli    runs the Codex program
	 * @param folder the folder whose hooks Codex lists
	 * @return the listed hooks whose hashes have the form that Codex 0.172.0 writes, in the order that Codex lists them
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code folder} is relative
	 * @throws IOException              if the server cannot be started, exits or refuses before it lists the hooks, or
	 *                                  does not list them within a minute
	 */
	static List<Hook> list(HarnessCli cli, Path folder) throws IOException
	{
		requireThat(cli, "cli").isNotNull();
		requireThat(folder, "folder").isAbsolute();
		try (Process process = cli.start(List.of("app-server")))
		{
			FutureTask<List<Hook>> conversation = new FutureTask<>(() -> converse(process, folder));
			Thread.ofPlatform().daemon().name("codex-app-server").start(conversation);
			try
			{
				return conversation.get(TIME_LIMIT.toMillis(), TimeUnit.MILLISECONDS);
			}
			catch (TimeoutException _)
			{
				throw new IOException("Codex's app-server did not list the hooks within " + TIME_LIMIT.toMinutes() +
					" minute");
			}
			catch (InterruptedException _)
			{
				Thread.currentThread().interrupt();
				throw new InterruptedIOException("Interrupted while Codex's app-server listed the hooks");
			}
			catch (ExecutionException e)
			{
				throw rethrown(e.getCause());
			}
			finally
			{
				conversation.cancel(true);
				stop(process);
			}
		}
	}

	/**
	 * Returns the failure of the conversation as an exception that {@link #list} throws, rethrowing an unchecked one.
	 *
	 * @param cause the failure
	 * @return the failure, if it is an {@code IOException}
	 */
	private static IOException rethrown(Throwable cause)
	{
		return switch (cause)
		{
			case IOException io -> io;
			case RuntimeException unchecked -> throw unchecked;
			case Error error -> throw error;
			default -> new IOException(cause);
		};
	}

	/**
	 * Holds the conversation with the server.
	 *
	 * @param process the server's process
	 * @param folder  the folder whose hooks Codex lists
	 * @return the listed hooks
	 * @throws IOException if the server exits or refuses before it lists the hooks, or its output is not JSON
	 */
	private static List<Hook> converse(Process process, Path folder) throws IOException
	{
		try (OutputStream input = process.getOutputStream())
		{
			return converse(input, new BufferedInputStream(process.getInputStream()), folder);
		}
	}

	/**
	 * Holds the conversation with the server through its standard input and output.
	 *
	 * @param input  the server's input
	 * @param output the server's output
	 * @param folder the folder whose hooks Codex lists
	 * @return the listed hooks
	 * @throws IOException if the server exits or refuses before it lists the hooks, or its output is not JSON
	 */
	private static List<Hook> converse(OutputStream input, InputStream output, Path folder) throws IOException
	{
		ObjectNode initialize = request(INITIALIZE, "initialize");
		ObjectNode params = initialize.putObject("params");
		params.putObject("clientInfo").put("name", "cat-installer").put("version", "1");
		params.putObject("capabilities").put("experimentalApi", true);
		send(input, initialize);
		response(output, INITIALIZE);

		ObjectNode initialized = JSON.createObjectNode();
		initialized.put("method", "initialized");
		send(input, initialized);

		ObjectNode list = request(LIST, "hooks/list");
		list.putObject("params").putArray("cwds").add(folder.toString());
		send(input, list);
		return hooks(response(output, LIST));
	}

	/**
	 * Returns a request without its parameters.
	 *
	 * @param id     the request's identifier
	 * @param method the request's method
	 * @return the request
	 */
	private static ObjectNode request(int id, String method)
	{
		ObjectNode request = JSON.createObjectNode();
		request.put("id", id);
		request.put("method", method);
		return request;
	}

	/**
	 * Writes a message to the server as one line.
	 *
	 * @param input   the server's input
	 * @param message the message
	 * @throws IOException if the server's input is closed
	 */
	private static void send(OutputStream input, ObjectNode message) throws IOException
	{
		input.write((JSON.writeValueAsString(message) + "\n").getBytes(UTF_8));
		input.flush();
	}

	/**
	 * Reads the server's output up to the response to a request, skipping its notifications.
	 *
	 * @param output the server's output
	 * @param id     the request's identifier
	 * @return the response's result
	 * @throws IOException if the server exits before it responds, answers with an error, or writes a line that is not
	 *                     a JSON object
	 */
	private static JsonNode response(InputStream output, int id) throws IOException
	{
		while (true)
		{
			String line = readLine(output);
			if (line == null)
				throw new IOException("Codex's app-server exited before it answered");
			if (line.isBlank())
				continue;
			JsonNode message;
			try
			{
				message = JSON.readTree(line);
			}
			catch (JacksonException e)
			{
				throw new IOException("Codex's app-server wrote a line that is not JSON: " + e.getOriginalMessage(), e);
			}
			if (!message.isObject())
				throw new IOException("Codex's app-server wrote a message that is not a JSON object");
			JsonNode messageId = message.path("id");
			if (!messageId.isNumber() || messageId.asInt() != id || message.has("method"))
				continue;
			JsonNode error = message.path("error");
			if (!error.isMissingNode())
			{
				throw new IOException("Codex's app-server refused the request: " +
					error.path("message").asString(error.toString()));
			}
			return message.path("result");
		}
	}

	/**
	 * Reads one line of the server's output.
	 *
	 * @param output the server's output
	 * @return the line without its terminator, or null if the output ended before a line began
	 * @throws IOException if the output cannot be read, or the line is longer than the client reads
	 */
	private static String readLine(InputStream output) throws IOException
	{
		ByteArrayOutputStream line = new ByteArrayOutputStream();
		for (int value = output.read(); value != '\n'; value = output.read())
		{
			if (value == -1)
			{
				if (line.size() == 0)
					return null;
				break;
			}
			if (line.size() == MAXIMUM_LINE_BYTES)
				throw new IOException("Codex's app-server wrote a line longer than " + MAXIMUM_LINE_BYTES + " bytes");
			line.write(value);
		}
		String text = line.toString(UTF_8);
		if (text.endsWith("\r"))
			return text.substring(0, text.length() - 1);
		return text;
	}

	/**
	 * Returns the hooks of a {@code hooks/list} result.
	 *
	 * @param result the result
	 * @return the hooks whose fields have the form that Codex writes
	 */
	private static List<Hook> hooks(JsonNode result)
	{
		List<Hook> hooks = new ArrayList<>();
		for (JsonNode entry : result.path("data"))
		{
			for (JsonNode hook : entry.path("hooks"))
			{
				JsonNode key = hook.path("key");
				JsonNode source = hook.path("source");
				JsonNode pluginId = hook.path("pluginId");
				JsonNode sourcePath = hook.path("sourcePath");
				Matcher hash = CURRENT_HASH.matcher(hook.path("currentHash").asString(""));
				if (!key.isString() || !source.isString() || !pluginId.isString() || !sourcePath.isString() ||
					!hash.matches())
				{
					continue;
				}
				hooks.add(new Hook(key.stringValue(), source.stringValue(), pluginId.stringValue(),
					sourcePath.stringValue(), hash.group(1), hash.group(2)));
			}
		}
		return List.copyOf(hooks);
	}

	/**
	 * Closes the server's input, on which it exits, and ends it if it does not.
	 *
	 * @param process the server's process
	 */
	private static void stop(Process process)
	{
		try
		{
			process.getOutputStream().close();
		}
		catch (IOException _)
		{
			// The server's input is closed already
		}
		try
		{
			if (!process.waitFor(EXIT_LIMIT.toMillis(), TimeUnit.MILLISECONDS))
				process.destroyForcibly();
		}
		catch (InterruptedException _)
		{
			process.destroyForcibly();
			Thread.currentThread().interrupt();
		}
		try
		{
			process.getInputStream().close();
		}
		catch (IOException _)
		{
			// The server's output is closed already
		}
	}

	/**
	 * A hook that {@code hooks/list} lists.
	 *
	 * @param key             the hook's key in {@code config.toml}
	 * @param source          where Codex found the hook, such as {@code plugin} or {@code project}
	 * @param pluginId        the plugin that declares the hook
	 * @param sourcePath      the file that declares the hook, as Codex names it
	 * @param declarationHash the hash that Codex computes for the hook's declaration
	 * @param contentHash     the hash that Codex computes for the content of the plugin that declares the hook, or
	 *                        null if Codex lists none
	 */
	record Hook(String key, String source, String pluginId, String sourcePath, String declarationHash,
		String contentHash)
	{
	}
}
