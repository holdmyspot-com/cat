package com.holdmyspot.cat.claude.installer.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.installer.activation.CommandResult;
import com.holdmyspot.cat.common.installer.activation.HarnessCli;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Stands in for Claude Code 2.1.318's {@code claude plugin} commands on one configuration directory, as a probe of that
 * version recorded them with a directory marketplace:
 * <ul>
 *   <li>{@code plugin marketplace add <folder>} succeeds and remembers the folder;</li>
 *   <li>{@code plugin install cat@cat} loads the plugin in place from the folder that {@code marketplace.json}'s
 *   source names and records it in {@code plugins/installed_plugins.json}, with the {@code installPath} of a cache copy
 *   whose name has {@code -} for {@code +}; when the plugin is already installed it changes nothing;</li>
 *   <li>{@code plugin update cat@cat} does the same when {@code marketplace.json} names another version, and otherwise
 *   answers that there is nothing to update.</li>
 * </ul>
 * Every command exits with status 0 unless the fake was told to refuse it.
 * <p>
 * <b>Thread Safety:</b> This class is not thread-safe.
 */
public final class FakeClaude implements HarnessCli
{
	private static final JsonMapper MAPPER = JsonMapper.builder().build();
	private final Path home;
	private final String refused;
	private final List<List<String>> calls = new ArrayList<>();
	private Path marketplace;
	private Path loadedFrom;

	/**
	 * Creates a fake program that runs every command.
	 *
	 * @param home the absolute configuration directory
	 * @throws NullPointerException if {@code home} is null
	 */
	public FakeClaude(Path home)
	{
		this(home, "");
	}

	/**
	 * Creates a fake program that refuses one subcommand.
	 *
	 * @param home    the absolute configuration directory
	 * @param refused the subcommand that fails with {@code ✘ network unavailable}, such as {@code update}, or empty
	 * @throws NullPointerException if any of the arguments are null
	 */
	public FakeClaude(Path home, String refused)
	{
		requireThat(home, "home").isNotNull();
		requireThat(refused, "refused").isNotNull();
		this.home = home;
		this.refused = refused;
	}

	@Override
	public CommandResult run(List<String> arguments) throws IOException
	{
		calls.add(List.copyOf(arguments));
		String subcommand = arguments.get(1);
		if (subcommand.equals("marketplace"))
			subcommand = arguments.get(2);
		if (subcommand.equals(refused))
			return new CommandResult(1, "✘ network unavailable\n");
		return switch (subcommand)
		{
			case "add" ->
			{
				marketplace = Path.of(arguments.get(3));
				yield new CommandResult(0, "✔ Successfully added marketplace: cat (declared in user settings)\n");
			}
			case "install" ->
			{
				if (installedVersion().isPresent())
					yield new CommandResult(0, "✔ Plugin \"cat@cat\" is already installed (scope: user)\n");
				load();
				yield new CommandResult(0, "✔ Successfully installed plugin: cat@cat (scope: user)\n");
			}
			case "update" ->
			{
				if (installedVersion().equals(Optional.of(marketplaceEntry().path("version").asString())))
					yield new CommandResult(0, "cat is read from its folder: nothing to update.\n");
				load();
				yield new CommandResult(0, "✔ Plugin \"cat\" updated. Restart to apply changes.\n");
			}
			default -> throw new AssertionError("Unexpected command: " + arguments);
		};
	}

	/**
	 * Returns the argument vectors that the program received.
	 *
	 * @return the calls in order
	 */
	public List<List<String>> calls()
	{
		return List.copyOf(calls);
	}

	/**
	 * Returns the folder that Claude Code loads the plugin from in place.
	 *
	 * @return the folder, or empty if no install or update loaded the plugin
	 */
	public Optional<Path> loadedFrom()
	{
		return Optional.ofNullable(loadedFrom);
	}

	/**
	 * Records an installed plugin, as Claude Code writes it, before the fake runs.
	 *
	 * @param home    the configuration directory
	 * @param version the installed version
	 * @throws IOException if the record cannot be written
	 */
	public static void recordInstalled(Path home, String version) throws IOException
	{
		Path record = home.resolve("plugins/installed_plugins.json");
		Files.createDirectories(record.getParent());
		Files.writeString(record, """
			{
			  "version": 2,
			  "plugins": {
			    "cat@cat": [
			      {
			        "scope": "user",
			        "installPath": "%s",
			        "version": "%s"
			      }
			    ]
			  }
			}
			""".formatted(home.resolve("plugins/cache/cat/cat").resolve(version.replace('+', '-')).toString().
			replace("\\", "\\\\"), version));
	}

	/**
	 * Loads the plugin that the marketplace manifest names and records its version.
	 *
	 * @throws IOException if a file cannot be read or written
	 */
	private void load() throws IOException
	{
		ObjectNode entry = marketplaceEntry();
		loadedFrom = marketplace.resolve(entry.path("source").asString()).normalize();
		recordInstalled(home, entry.path("version").asString());
	}

	/**
	 * Returns the version that {@code installed_plugins.json} records for the user scope.
	 *
	 * @return the version, or empty if the plugin is not installed
	 * @throws IOException if the record cannot be read
	 */
	private Optional<String> installedVersion() throws IOException
	{
		Path record = home.resolve("plugins/installed_plugins.json");
		if (Files.notExists(record))
			return Optional.empty();
		for (JsonNode element : MAPPER.readTree(Files.readString(record)).path("plugins").path("cat@cat"))
			return Optional.of(element.path("version").asString());
		return Optional.empty();
	}

	/**
	 * Returns the {@code cat} entry of the marketplace manifest in the added marketplace.
	 *
	 * @return the entry
	 * @throws IOException if the manifest cannot be read
	 */
	private ObjectNode marketplaceEntry() throws IOException
	{
		requireThat(marketplace, "marketplace").isNotNull();
		JsonNode root = MAPPER.readTree(Files.readString(marketplace.resolve(".claude-plugin/marketplace.json")));
		for (JsonNode entry : root.path("plugins"))
		{
			if (entry.path("name").asString().equals("cat"))
				return (ObjectNode) entry;
		}
		throw new AssertionError("marketplace.json has no cat entry: " + root);
	}
}
