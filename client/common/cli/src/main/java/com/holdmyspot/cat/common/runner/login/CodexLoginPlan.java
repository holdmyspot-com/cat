package com.holdmyspot.cat.common.runner.login;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * What a runner asks the login service to serve a session: the source home's login that the service reads and serves
 * as a bearer token, chosen by the runner from the source's {@code config.toml} as Codex chooses for the user's own
 * run.
 * <p>
 * The description of an instance holds no secret.
 * <p>
 * <b>Thread Safety:</b> This interface's implementations are immutable and thread-safe.
 */
public sealed interface CodexLoginPlan
{
	/**
	 * Returns the plan as the runner sends it.
	 *
	 * @param json the mapper that creates the object
	 * @return the plan's JSON object
	 * @throws NullPointerException if {@code json} is null
	 */
	ObjectNode toJson(JsonMapper json);

	/**
	 * Reads a plan that a runner sent.
	 *
	 * @param node the plan's JSON object
	 * @return the plan
	 * @throws NullPointerException if {@code node} is null
	 * @throws IOException          if the object is not a plan
	 */
	static CodexLoginPlan fromJson(JsonNode node) throws IOException
	{
		requireThat(node, "node").isNotNull();
		String kind = node.path("kind").asString("");
		return switch (kind)
		{
			case "stored" -> new Stored(path(node, "source_home"), path(node, "codex_home"),
				Store.of(node.path("store").asString("")));
			case "command" -> Command.fromJson(node);
			case "forward" ->
			{
				Map<String, String> headers = new LinkedHashMap<>();
				for (Map.Entry<String, JsonNode> header : node.path("headers").properties())
					headers.put(header.getKey(), header.getValue().asString(""));
				Optional<String> bearer = Optional.ofNullable(node.path("bearer").asString(null));
				Optional<Command> bearerCommand = Optional.empty();
				if (node.path("bearer_command").isObject())
					bearerCommand = Optional.of(Command.fromJson(node.path("bearer_command")));
				URI baseUrl;
				try
				{
					baseUrl = new URI(node.path("base_url").asString(""));
				}
				catch (URISyntaxException e)
				{
					throw new IOException("The login plan's base_url is not a URL", e);
				}
				yield new Forward(baseUrl, headers, bearer, bearerCommand);
			}
			default -> throw new IOException("The login plan's kind is not known: " + kind);
		};
	}

	/**
	 * Returns a plan's absolute path.
	 *
	 * @param node the plan
	 * @param name the path's name
	 * @return the path
	 * @throws IOException if the plan does not name an absolute path
	 */
	private static Path path(JsonNode node, String name) throws IOException
	{
		String value = node.path(name).asString("");
		try
		{
			Path path = Path.of(value);
			if (!value.isBlank() && path.isAbsolute())
				return path;
		}
		catch (InvalidPathException e)
		{
			throw new IOException("The login plan's " + name + " is not a path", e);
		}
		throw new IOException("The login plan's " + name + " is not an absolute path");
	}

	/**
	 * Where Codex keeps a login, as {@code cli_auth_credentials_store} names it.
	 */
	enum Store
	{
		/**
		 * {@code auth.json} in the Codex folder.
		 */
		FILE,
		/**
		 * The operating system's keyring.
		 */
		KEYRING,
		/**
		 * The keyring when it holds the login, otherwise {@code auth.json}.
		 */
		AUTO;

		/**
		 * Returns the store that a setting names.
		 *
		 * @param value the setting's value, empty for Codex's default
		 * @return the store
		 * @throws IOException if the value names no store that the service reads
		 */
		public static Store of(String value) throws IOException
		{
			return switch (value)
			{
				case "", "file" -> FILE;
				case "keyring" -> KEYRING;
				case "auto" -> AUTO;
				default -> throw new IOException("The login plan's store is not known: " + value);
			};
		}

		/**
		 * Returns the store's value of {@code cli_auth_credentials_store}.
		 *
		 * @return the value
		 */
		public String setting()
		{
			return switch (this)
			{
				case FILE -> "file";
				case KEYRING -> "keyring";
				case AUTO -> "auto";
			};
		}
	}

	/**
	 * A login that Codex stores for the source home: a ChatGPT sign-in, or an API key that the keyring holds.
	 *
	 * @param sourceHome the source home, which a refusal names
	 * @param codexHome  the source's Codex folder, whose canonical path names its keyring item
	 * @param store      where the source keeps its login
	 */
	record Stored(Path sourceHome, Path codexHome, Store store) implements CodexLoginPlan
	{
		/**
		 * Creates a plan.
		 *
		 * @param sourceHome the source home
		 * @param codexHome  the source's Codex folder
		 * @param store      where the source keeps its login
		 * @throws NullPointerException     if any of the arguments are null
		 * @throws IllegalArgumentException if a path is not absolute
		 */
		public Stored
		{
			requireThat(sourceHome, "sourceHome").isNotNull().isAbsolute();
			requireThat(codexHome, "codexHome").isNotNull().isAbsolute();
			requireThat(store, "store").isNotNull();
		}

		@Override
		public ObjectNode toJson(JsonMapper json)
		{
			requireThat(json, "json").isNotNull();
			ObjectNode node = json.createObjectNode();
			node.put("kind", "stored");
			node.put("source_home", sourceHome.toString());
			node.put("codex_home", codexHome.toString());
			node.put("store", store.setting());
			return node;
		}
	}

	/**
	 * A source provider's own token command, or a {@code gateway_oauth} provider's {@code credential_export} command,
	 * which the server alone runs with the source home as its home folder and whose output it keeps for the interval.
	 *
	 * @param program         the command's program
	 * @param arguments       the program's arguments
	 * @param directory       the folder that the command runs in, or empty for the source home
	 * @param timeoutMillis   how long the command may run, in milliseconds
	 * @param refreshInterval how long the command's output serves, in milliseconds; {@code 0} runs it for each request
	 * @param home            the source home, the command's home folder
	 */
	record Command(String program, List<String> arguments, Optional<Path> directory, long timeoutMillis,
		long refreshInterval, Path home) implements CodexLoginPlan
	{
		/**
		 * Creates a plan.
		 *
		 * @param program         the command's program
		 * @param arguments       the program's arguments
		 * @param directory       the folder that the command runs in, or empty for the source home
		 * @param timeoutMillis   how long the command may run, in milliseconds
		 * @param refreshInterval how long the command's output serves, in milliseconds
		 * @param home            the source home
		 * @throws NullPointerException     if any of the arguments are null
		 * @throws IllegalArgumentException if {@code program} is blank, {@code timeoutMillis} is not positive,
		 *                                  {@code refreshInterval} is negative or {@code home} is not absolute
		 */
		public Command
		{
			requireThat(program, "program").isNotBlank();
			arguments = List.copyOf(arguments);
			requireThat(directory, "directory").isNotNull();
			requireThat(timeoutMillis, "timeoutMillis").isPositive();
			requireThat(refreshInterval, "refreshInterval").isNotNegative();
			requireThat(home, "home").isNotNull().isAbsolute();
		}

		/**
		 * Reads a command plan.
		 *
		 * @param node the plan's JSON object
		 * @return the plan
		 * @throws IOException if the object is not a command plan
		 */
		private static Command fromJson(JsonNode node) throws IOException
		{
			List<String> arguments = new ArrayList<>();
			for (JsonNode argument : node.path("args"))
				arguments.add(argument.asString(""));
			Optional<Path> directory = Optional.empty();
			if (!node.path("cwd").asString("").isBlank())
				directory = Optional.of(path(node, "cwd"));
			String program = node.path("command").asString("");
			long timeout = node.path("timeout_ms").asLong(0);
			long interval = node.path("refresh_interval_ms").asLong(-1);
			if (program.isBlank() || timeout <= 0 || interval < 0)
				throw new IOException("The login plan's command is incomplete");
			return new Command(program, arguments, directory, timeout, interval, path(node, "home"));
		}

		@Override
		public ObjectNode toJson(JsonMapper json)
		{
			requireThat(json, "json").isNotNull();
			ObjectNode node = json.createObjectNode();
			node.put("kind", "command");
			node.put("command", program);
			ArrayNode args = node.putArray("args");
			for (String argument : arguments)
				args.add(argument);
			directory.ifPresent(folder -> node.put("cwd", folder.toString()));
			node.put("timeout_ms", timeoutMillis);
			node.put("refresh_interval_ms", refreshInterval);
			node.put("home", home.toString());
			return node;
		}

		@Override
		public String toString()
		{
			// The arguments may name a secret's file, but hold no secret themselves
			return "Command{program=" + program + ", arguments=" + arguments + ", directory=" + directory +
				", timeoutMillis=" + timeoutMillis + ", refreshInterval=" + refreshInterval + ", home=" + home + "}";
		}
	}

	/**
	 * A provider whose secret travels in headers, which the server's forwarder adds to each request that it passes on
	 * to the provider's own base URL, together with the provider's bearer token.
	 *
	 * @param baseUrl       the provider's own base URL
	 * @param headers       the headers that the forwarder adds, with their values
	 * @param bearer        the provider's bearer token, or empty
	 * @param bearerCommand the command whose output is the provider's bearer token, or empty
	 */
	record Forward(URI baseUrl, Map<String, String> headers, Optional<String> bearer, Optional<Command> bearerCommand)
		implements CodexLoginPlan
	{
		/**
		 * Creates a plan.
		 *
		 * @param baseUrl       the provider's own base URL
		 * @param headers       the headers that the forwarder adds
		 * @param bearer        the provider's bearer token, or empty
		 * @param bearerCommand the command whose output is the provider's bearer token, or empty
		 * @throws NullPointerException     if any of the arguments are null
		 * @throws IllegalArgumentException if {@code baseUrl} is not an absolute HTTP or HTTPS URL, or both a bearer and
		 *                                  a command are given
		 */
		public Forward
		{
			requireThat(baseUrl, "baseUrl").isNotNull();
			String scheme = String.valueOf(baseUrl.getScheme());
			if (!scheme.equals("http") && !scheme.equals("https") || baseUrl.getHost() == null)
				throw new IllegalArgumentException("baseUrl must be an absolute HTTP or HTTPS URL");
			headers = Map.copyOf(headers);
			requireThat(bearer, "bearer").isNotNull();
			requireThat(bearerCommand, "bearerCommand").isNotNull();
			if (bearer.isPresent() && bearerCommand.isPresent())
				throw new IllegalArgumentException("bearer and bearerCommand may not both be present");
		}

		@Override
		public ObjectNode toJson(JsonMapper json)
		{
			requireThat(json, "json").isNotNull();
			ObjectNode node = json.createObjectNode();
			node.put("kind", "forward");
			node.put("base_url", baseUrl.toString());
			ObjectNode headerNode = node.putObject("headers");
			for (Map.Entry<String, String> header : headers.entrySet())
				headerNode.put(header.getKey(), header.getValue());
			bearer.ifPresent(token -> node.put("bearer", token));
			bearerCommand.ifPresent(command -> node.set("bearer_command", command.toJson(json)));
			return node;
		}

		@Override
		public String toString()
		{
			return "Forward{baseUrl=" + baseUrl + ", headers=" + headers.keySet() + ", bearer=" + bearer.isPresent() +
				", bearerCommand=" + bearerCommand + "}";
		}
	}
}
