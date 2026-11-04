package com.holdmyspot.cat.common.installer.trust;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.BoundedInput;
import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.installer.activation.HarnessCommand;
import com.holdmyspot.cat.common.installer.trust.TrustException.Reason;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The user's trust policy: the keys whose signatures make a release authentic, the one location that releases are
 * downloaded from, the versions that the installer may install, and the home directory of each harness that CAT is
 * installed in.
 * <p>
 * Only the user's explicit setup and installer upgrades write the policy, through {@link #write(Path, FileReplacer)}.
 * Routine updates only read it, and no argument or environment variable overrides any of its values.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class TrustPolicy
{
	/**
	 * The largest policy file that the installer reads: 1 MiB.
	 */
	private static final int MAXIMUM_SIZE = 1024 * 1024;
	/**
	 * The top-level fields of a policy. The policy is refused when it has another field.
	 */
	private static final Set<String> FIELDS = Set.of("format", "keys", "release_base_url", "allowed_versions",
		"harnesses");
	/**
	 * The fields of a key entry.
	 */
	private static final Set<String> KEY_FIELDS = Set.of("id", "ed25519_x509_base64");
	/**
	 * The fields of {@code allowed_versions}.
	 */
	private static final Set<String> ALLOWED_VERSION_FIELDS = Set.of("minimum", "maximum_major");
	/**
	 * The fields of a harness entry in a policy of format 1, which records no harness program.
	 */
	private static final Set<String> HARNESS_FIELDS = Set.of("home");
	/**
	 * The fields of a harness entry in a policy of format 2.
	 */
	private static final Set<String> COMMAND_HARNESS_FIELDS = Set.of("home", "command", "environment");
	/**
	 * The newest policy format, which records each harness's program.
	 */
	private static final int LATEST_FORMAT = 2;
	private final List<TrustedKey> keys;
	/**
	 * The URL below which each release's assets lie, in a directory named after its tag.
	 */
	private final URI releaseBaseUrl;
	/**
	 * The lowest version that the installer may install.
	 */
	private final ReleaseVersion minimum;
	/**
	 * The highest major version that the installer may install.
	 */
	private final int maximumMajor;
	/**
	 * Each harness's identifier, mapped to the home directory that CAT is installed in, in identifier order.
	 */
	private final SortedMap<String, Path> harnesses;
	/**
	 * Each harness's identifier, mapped to the harness program that setup found, in identifier order; empty in a
	 * policy of format 1.
	 */
	private final SortedMap<String, HarnessCommand> commands;
	/**
	 * The policy's file format: 1 without harness programs, 2 with one program per harness.
	 */
	private final int format;

	/**
	 * Creates a policy.
	 *
	 * @param keys           the keys whose signatures make a release authentic, with unique ids; when empty, no
	 *                       release verifies
	 * @param releaseBaseUrl the URL below which each release's assets lie: an absolute {@code https} URL with no user
	 *                       information, query or fragment and no trailing slash
	 * @param minimum        the lowest version that the installer may install
	 * @param maximumMajor   the highest major version that the installer may install
	 * @param harnesses      each harness's identifier, mapped to the absolute, normalized home directory that CAT is
	 *                       installed in
	 * @return the policy
	 * @throws NullPointerException if any of the arguments are null, or contain null
	 * @throws TrustException       if {@code maximumMajor} is negative, two keys have the same id,
	 *                              {@code releaseBaseUrl} breaks a rule above, a harness identifier is not a
	 *                              lowercase letter followed by at most 31 lowercase letters, digits or hyphens, or a
	 *                              home directory is
	 *                              not absolute or not normalized
	 */
	public static TrustPolicy of(List<TrustedKey> keys, URI releaseBaseUrl, ReleaseVersion minimum, int maximumMajor,
		Map<String, Path> harnesses) throws TrustException
	{
		requireThat(keys, "keys").isNotNull().doesNotContain(null);
		requireThat(releaseBaseUrl, "releaseBaseUrl").isNotNull();
		requireThat(minimum, "minimum").isNotNull();
		requireThat(harnesses, "harnesses").isNotNull();
		TrustJson json = policyJson();

		Set<String> ids = new HashSet<>();
		for (TrustedKey key : keys)
		{
			if (!ids.add(key.id()))
				throw json.refusal("The trust policy lists the key id \"" + key.id() + "\" more than once");
		}
		requireReleaseBaseUrl(json, releaseBaseUrl);
		if (maximumMajor < 0)
		{
			throw json.refusal("The trust policy's field \"allowed_versions.maximum_major\" must not be negative: " +
				maximumMajor);
		}
		for (Entry<String, Path> entry : harnesses.entrySet())
		{
			String id = entry.getKey();
			Path home = entry.getValue();
			requireThat(id, "id").isNotNull();
			requireThat(home, "home").isNotNull();
			if (!HarnessIdentifier.SYNTAX.matcher(id).matches())
			{
				throw json.refusal("The trust policy's harness identifier \"" + TrustText.excerpt(id) + "\" must be a " +
					"lowercase letter followed by at most 31 lowercase letters, digits or hyphens");
			}
			if (!home.isAbsolute() || !home.normalize().equals(home))
			{
				throw json.refusal("The trust policy's field \"harnesses." + id + ".home\" must be an absolute path " +
					"without . or .. names: \"" + TrustText.excerpt(home.toString()) + "\"");
			}
		}
		return new TrustPolicy(keys, releaseBaseUrl, minimum, maximumMajor, harnesses, Map.of(), 1);
	}

	/**
	 * Creates a policy that also records, for each harness, the program that routine operations run to register CAT
	 * with the harness. Routine operations run exactly that program with exactly that environment.
	 *
	 * @param keys           the keys whose signatures make a release authentic, with unique ids; when empty, no
	 *                       release verifies
	 * @param releaseBaseUrl the URL below which each release's assets lie: an absolute {@code https} URL with no user
	 *                       information, query or fragment and no trailing slash
	 * @param minimum        the lowest version that the installer may install
	 * @param maximumMajor   the highest major version that the installer may install
	 * @param harnesses      each harness's identifier, mapped to the absolute, normalized home directory that CAT is
	 *                       installed in
	 * @param commands       each harness's identifier, mapped to the harness program that setup found for it
	 * @return the policy
	 * @throws NullPointerException if any of the arguments are null, or contain null
	 * @throws TrustException       if {@code commands} does not name exactly the harnesses of {@code harnesses}, or
	 *                              for any reason that
	 *                              {@link #of(List, URI, ReleaseVersion, int, Map) the policy without programs} is
	 *                              refused
	 */
	public static TrustPolicy of(List<TrustedKey> keys, URI releaseBaseUrl, ReleaseVersion minimum, int maximumMajor,
		Map<String, Path> harnesses, Map<String, HarnessCommand> commands) throws TrustException
	{
		requireThat(commands, "commands").isNotNull();
		TrustPolicy policy = of(keys, releaseBaseUrl, minimum, maximumMajor, harnesses);
		if (!commands.keySet().equals(harnesses.keySet()))
		{
			throw policyJson().refusal("The trust policy must record a harness program for exactly its harnesses " +
				harnesses.keySet() + ", not for " + commands.keySet());
		}
		return new TrustPolicy(policy.keys, policy.releaseBaseUrl, policy.minimum, policy.maximumMajor,
			policy.harnesses, commands, LATEST_FORMAT);
	}

	/**
	 * Returns the reader of a policy's fields.
	 *
	 * @return the reader
	 */
	private static TrustJson policyJson()
	{
		return new TrustJson(Reason.MALFORMED_POLICY, "trust policy");
	}

	/**
	 * Refuses a release URL that could send the installer's requests anywhere other than one fixed HTTPS location.
	 *
	 * @param json the policy's field reader
	 * @param url  the URL
	 * @throws TrustException if the URL is not an absolute {@code https} URL with a host, or has user information, a
	 *                        query, a fragment or a trailing slash
	 */
	private static void requireReleaseBaseUrl(TrustJson json, URI url) throws TrustException
	{
		String rule;
		if (!"https".equals(url.getScheme()) || url.isOpaque() || url.getHost() == null)
			rule = "an absolute https URL with a host";
		else if (url.getRawUserInfo() != null)
			rule = "free of user information";
		else if (url.getRawQuery() != null)
			rule = "free of a query";
		else if (url.getRawFragment() != null)
			rule = "free of a fragment";
		else if (url.getRawPath().endsWith("/"))
			rule = "free of a trailing slash";
		else
			return;
		throw json.refusal("The trust policy's field \"release_base_url\" must be " + rule + ": \"" +
			TrustText.excerpt(url.toString()) + "\"");
	}

	/**
	 * Reads a policy file.
	 *
	 * @param file the policy file
	 * @return the policy
	 * @throws NullPointerException if {@code file} is null
	 * @throws TrustException       if the file breaks a rule of the policy format
	 * @throws IOException          if the file cannot be read or is larger than 1 MiB
	 */
	public static TrustPolicy read(Path file) throws TrustException, IOException
	{
		requireThat(file, "file").isNotNull();
		byte[] bytes = BoundedInput.readBytes(Files.newInputStream(file), file, MAXIMUM_SIZE, "The trust policy");
		return parse(bytes);
	}

	/**
	 * Parses a policy.
	 *
	 * @param bytes the policy's UTF-8 bytes
	 * @return the policy
	 * @throws TrustException if the bytes break a rule of the policy format
	 */
	private static TrustPolicy parse(byte[] bytes) throws TrustException
	{
		TrustJson json = policyJson();
		JsonNode root = json.parseObject(bytes);
		json.requireOnly(root, "", FIELDS);
		int format = json.requireFormat(root, LATEST_FORMAT);

		List<TrustedKey> keys = parseKeys(json, json.requiredArray(root, "", "keys"));
		String urlText = json.requiredString(root, "", "release_base_url");
		URI releaseBaseUrl;
		try
		{
			releaseBaseUrl = new URI(urlText);
		}
		catch (URISyntaxException _)
		{
			throw json.refusal("The trust policy's field \"release_base_url\" is not a URL: \"" +
				TrustText.excerpt(urlText) + "\"");
		}

		JsonNode allowed = json.requiredObject(root, "", "allowed_versions");
		json.requireOnly(allowed, "allowed_versions", ALLOWED_VERSION_FIELDS);
		ReleaseVersion minimum;
		try
		{
			minimum = ReleaseVersion.parse(json.requiredString(allowed, "allowed_versions", "minimum"));
		}
		catch (TrustException e)
		{
			if (e.reason() != Reason.MALFORMED_VERSION)
				throw e;
			throw json.refusal("The trust policy's field \"allowed_versions.minimum\" is invalid. " + e.getMessage());
		}
		int maximumMajor = (int) json.requiredInteger(allowed, "allowed_versions", "maximum_major", 0,
			Integer.MAX_VALUE);

		JsonNode harnessObject = json.requiredObject(root, "", "harnesses");
		Map<String, Path> harnesses = parseHarnesses(json, harnessObject, format);
		if (format == 1)
			return of(keys, releaseBaseUrl, minimum, maximumMajor, harnesses);
		return of(keys, releaseBaseUrl, minimum, maximumMajor, harnesses, parseCommands(json, harnessObject));
	}

	/**
	 * Parses the harness program of each harness entry of a policy of format 2.
	 *
	 * @param json   the policy's parser
	 * @param object the {@code harnesses} object, whose entries were already checked
	 * @return each harness's identifier, mapped to its program
	 * @throws TrustException if a program is not a non-empty array of strings whose first element is an absolute
	 *                        path, or an environment is not an object of string values
	 */
	private static Map<String, HarnessCommand> parseCommands(TrustJson json, JsonNode object) throws TrustException
	{
		Map<String, HarnessCommand> commands = new TreeMap<>();
		for (Entry<String, JsonNode> entry : object.properties())
		{
			String path = "harnesses." + TrustText.excerpt(entry.getKey());
			JsonNode array = json.requiredArray(entry.getValue(), path, "command");
			List<String> command = new ArrayList<>();
			for (int index = 0; index < array.size(); ++index)
			{
				if (!array.get(index).isString())
					throw json.refusal("The trust policy's field \"" + path + ".command\" must contain only strings");
				command.add(array.get(index).stringValue());
			}
			JsonNode environmentObject = json.requiredObject(entry.getValue(), path, "environment");
			Map<String, String> environment = new TreeMap<>();
			for (Entry<String, JsonNode> variable : environmentObject.properties())
			{
				if (!variable.getValue().isString())
				{
					throw json.refusal("The trust policy's field \"" + path + ".environment\" must contain only " +
						"strings");
				}
				environment.put(variable.getKey(), variable.getValue().stringValue());
			}
			try
			{
				commands.put(entry.getKey(), new HarnessCommand(command, environment));
			}
			catch (IllegalArgumentException _)
			{
				throw json.refusal("The trust policy's field \"" + path + ".command\" must start with the harness " +
					"program's absolute path");
			}
		}
		return commands;
	}

	/**
	 * Reads a policy's keys.
	 *
	 * @param json  the policy's field reader
	 * @param array the value of the {@code keys} field
	 * @return the keys, in the policy's order
	 * @throws TrustException if an entry is not a key entry, or its key is not an Ed25519 public key
	 */
	private static List<TrustedKey> parseKeys(TrustJson json, JsonNode array) throws TrustException
	{
		List<TrustedKey> keys = new ArrayList<>();
		for (int index = 0; index < array.size(); ++index)
		{
			String path = "keys[" + index + "]";
			JsonNode entry = array.get(index);
			if (!entry.isObject())
				throw json.refusal("The trust policy's " + path + " must be a JSON object");
			json.requireOnly(entry, path, KEY_FIELDS);
			keys.add(TrustedKey.decode(json.requiredString(entry, path, "id"),
				json.requiredString(entry, path, "ed25519_x509_base64")));
		}
		return keys;
	}

	/**
	 * Reads a policy's harness entries.
	 *
	 * @param json   the policy's field reader
	 * @param object the value of the {@code harnesses} field
	 * @param format the policy's format, which decides the fields that a harness entry may have
	 * @return each harness's identifier, mapped to its home directory
	 * @throws TrustException if an entry is not a harness entry, or its home is not a path
	 */
	private static Map<String, Path> parseHarnesses(TrustJson json, JsonNode object, int format)
		throws TrustException
	{
		Map<String, Path> harnesses = new TreeMap<>();
		for (Entry<String, JsonNode> entry : object.properties())
		{
			String path = "harnesses." + TrustText.excerpt(entry.getKey());
			JsonNode harness = entry.getValue();
			if (!harness.isObject())
				throw json.refusal("The trust policy's field \"" + path + "\" must be a JSON object");
			if (format == 1)
				json.requireOnly(harness, path, HARNESS_FIELDS);
			else
				json.requireOnly(harness, path, COMMAND_HARNESS_FIELDS);
			String home = json.requiredString(harness, path, "home");
			try
			{
				harnesses.put(entry.getKey(), Path.of(home));
			}
			catch (InvalidPathException _)
			{
				throw json.refusal("The trust policy's field \"" + path + ".home\" is not a path: \"" +
					TrustText.excerpt(home) + "\"");
			}
		}
		return harnesses;
	}

	/**
	 * Creates a policy.
	 *
	 * @param keys           the trusted keys
	 * @param releaseBaseUrl the URL below which each release's assets lie
	 * @param minimum        the lowest version that the installer may install
	 * @param maximumMajor   the highest major version that the installer may install
	 * @param harnesses      each harness's identifier, mapped to its home directory
	 * @param commands       each harness's identifier, mapped to the harness program that setup recorded for it
	 * @param format         the policy's format
	 */
	private TrustPolicy(List<TrustedKey> keys, URI releaseBaseUrl, ReleaseVersion minimum, int maximumMajor,
		Map<String, Path> harnesses, Map<String, HarnessCommand> commands, int format)
	{
		this.commands = new TreeMap<>(commands);
		this.format = format;
		this.keys = List.copyOf(keys);
		this.releaseBaseUrl = releaseBaseUrl;
		this.minimum = minimum;
		this.maximumMajor = maximumMajor;
		this.harnesses = new TreeMap<>(harnesses);
	}

	/**
	 * Returns the keys whose signatures make a release authentic.
	 *
	 * @return an unmodifiable list in the policy's order, which is empty when no release can verify
	 */
	public List<TrustedKey> keys()
	{
		return keys;
	}

	/**
	 * Returns the URL below which each release's assets lie, in a directory named after the release's tag.
	 *
	 * @return an absolute {@code https} URL with no trailing slash
	 */
	public URI releaseBaseUrl()
	{
		return releaseBaseUrl;
	}

	/**
	 * Returns the lowest version that the installer may install.
	 *
	 * @return the version
	 */
	public ReleaseVersion minimum()
	{
		return minimum;
	}

	/**
	 * Returns the highest major version that the installer may install.
	 *
	 * @return the major version
	 */
	public int maximumMajor()
	{
		return maximumMajor;
	}

	/**
	 * Returns the home directory of each harness that CAT is installed in.
	 *
	 * @return an unmodifiable map from each harness's identifier to its absolute, normalized home directory, in
	 * 	identifier order
	 */
	public Map<String, Path> harnesses()
	{
		return Collections.unmodifiableSortedMap(harnesses);
	}

	/**
	 * Returns the policy's file format.
	 *
	 * @return {@code 1} for a policy that records no harness programs, {@code 2} for one that records a program for
	 * 	each harness
	 */
	public int format()
	{
		return format;
	}

	/**
	 * Returns the harness program that setup found for a harness.
	 *
	 * @param harness the harness's identifier
	 * @return the program, or empty if the policy records none for {@code harness}
	 * @throws NullPointerException if {@code harness} is null
	 */
	public Optional<HarnessCommand> command(String harness)
	{
		requireThat(harness, "harness").isNotNull();
		return Optional.ofNullable(commands.get(harness));
	}

	/**
	 * Writes the policy to a file, replacing it atomically.
	 * <p>
	 * The policy is written to a new temporary file in the same directory, forced to the storage device, and then
	 * renamed over {@code file}, so a reader sees either the old policy or the complete new one, never a partial file.
	 * On failure, {@code file} is unchanged and the temporary file is removed.
	 *
	 * @param file     the policy file, whose directory exists
	 * @param replacer renames the temporary file over {@code file}
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the file cannot be written or replaced
	 */
	public void write(Path file, FileReplacer replacer) throws IOException
	{
		requireThat(file, "file").isNotNull();
		requireThat(replacer, "replacer").isNotNull();
		byte[] content = (TrustJson.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(toJson()) + "\n").
			getBytes(UTF_8);

		Path absolute = file.toAbsolutePath();
		Path temporary = Files.createTempFile(absolute.getParent(), "." + absolute.getFileName() + "-", ".tmp");
		try
		{
			try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
				StandardOpenOption.TRUNCATE_EXISTING))
			{
				ByteBuffer buffer = ByteBuffer.wrap(content);
				while (buffer.hasRemaining())
					channel.write(buffer);
				channel.force(true);
			}
			replacer.replace(temporary, absolute);
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
	}

	/**
	 * Returns the policy in its file format.
	 *
	 * @return the JSON object
	 */
	private ObjectNode toJson()
	{
		ObjectNode root = TrustJson.MAPPER.createObjectNode();
		root.put("format", format);
		ArrayNode keyArray = root.putArray("keys");
		for (TrustedKey key : keys)
		{
			keyArray.addObject().
				put("id", key.id()).
				put("ed25519_x509_base64", key.x509Base64());
		}
		root.put("release_base_url", releaseBaseUrl.toString());
		root.putObject("allowed_versions").
			put("minimum", minimum.toString()).
			put("maximum_major", maximumMajor);
		ObjectNode harnessObject = root.putObject("harnesses");
		for (Entry<String, Path> entry : harnesses.entrySet())
		{
			ObjectNode harness = harnessObject.putObject(entry.getKey());
			harness.put("home", entry.getValue().toString());
			HarnessCommand command = commands.get(entry.getKey());
			if (command == null)
				continue;
			ArrayNode commandArray = harness.putArray("command");
			for (String element : command.command())
				commandArray.add(element);
			ObjectNode environment = harness.putObject("environment");
			for (Entry<String, String> variable : new TreeMap<>(command.environment()).entrySet())
				environment.put(variable.getKey(), variable.getValue());
		}
		return root;
	}

	/**
	 * Indicates whether another object is a policy with the same values.
	 *
	 * @param object the other object
	 * @return {@code true} if {@code object} is an equal policy
	 */
	@Override
	public boolean equals(Object object)
	{
		return object instanceof TrustPolicy other && other.keys.equals(keys) &&
			other.releaseBaseUrl.equals(releaseBaseUrl) && other.minimum.equals(minimum) &&
			other.maximumMajor == maximumMajor && other.harnesses.equals(harnesses) && other.commands.equals(commands) &&
			other.format == format;
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(keys, releaseBaseUrl, minimum, maximumMajor, harnesses, commands, format);
	}

	@Override
	public String toString()
	{
		return "TrustPolicy{keys=" + keys + ", releaseBaseUrl=" + releaseBaseUrl + ", harnesses=" +
			harnesses.keySet() + "}";
	}
}
