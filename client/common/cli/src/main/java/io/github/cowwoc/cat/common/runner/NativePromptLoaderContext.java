package io.github.cowwoc.cat.common.runner;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.ArrayList;
import java.util.HashSet;

/**
 * Records the runner's effective, allowlisted native instruction-loader selectors before the first turn.
 *
 * @param schemaVersion the exact context schema version
 * @param runId the owning run ID
 * @param harness the native harness
 * @param observedNativeVersion the detected native version, or absent when unavailable
 * @param normalizedRunProjectRoot the copied project root
 * @param normalizedLaunchCwd the native process working directory
 * @param copiedProjectBaselineCommit the copied project's baseline commit
 * @param orderedInstructionRoots the effective instruction roots in search order
 * @param orderedSelectorFacts the effective native selector facts
 */
public record NativePromptLoaderContext(int schemaVersion,
	String runId,
	Harness harness,
	Optional<String> observedNativeVersion,
	Path normalizedRunProjectRoot,
	Path normalizedLaunchCwd,
	String copiedProjectBaselineCommit,
	List<Path> orderedInstructionRoots,
	List<SelectorFact> orderedSelectorFacts)
{
	/**
	 * Validates and snapshots the context.
	 */
	public NativePromptLoaderContext
	{
		if (schemaVersion != 1)
			throw new IllegalArgumentException("schemaVersion must be 1");
		checkText(runId, "runId");
		Objects.requireNonNull(harness, "harness");
		Objects.requireNonNull(observedNativeVersion, "observedNativeVersion");
		observedNativeVersion.ifPresent(value -> checkText(value, "observedNativeVersion"));
		checkAbsolute(normalizedRunProjectRoot);
		checkAbsolute(normalizedLaunchCwd);
		checkText(copiedProjectBaselineCommit, "copiedProjectBaselineCommit");
		orderedInstructionRoots = List.copyOf(orderedInstructionRoots);
		orderedInstructionRoots.forEach(NativePromptLoaderContext::checkAbsolute);
		orderedSelectorFacts = List.copyOf(orderedSelectorFacts);
	}

	/**
	 * Seals this context at its run-owned artifact path, validating an existing copy on restart.
	 *
	 * @param json the runner-owned JSON mapper
	 * @param destination the context artifact path
	 * @throws IOException if the artifact cannot be sealed or conflicts with this context
	 */
	public void seal(JsonMapper json, Path destination) throws IOException
	{
		Objects.requireNonNull(json, "json");
		Objects.requireNonNull(destination, "destination");
		if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS))
		{
			if (!equals(read(json, destination)))
				throw new IOException("Native loader context conflicts with sealed artifact: " + destination);
			return;
		}
		Path parent = destination.toAbsolutePath().normalize().getParent();
		if (parent == null)
			throw new IOException("Native loader context has no parent: " + destination);
		Files.createDirectories(parent);
		Path temporary = Files.createTempFile(parent, ".loader-context-", ".json");
		try
		{
			json.writeValue(temporary.toFile(), toJson(json));
			Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
		}
		finally
		{
			Files.deleteIfExists(temporary);
		}
	}

	/**
	 * Reads and strictly validates a sealed context artifact.
	 *
	 * @param json the runner-owned JSON mapper
	 * @param source the context artifact path
	 * @return the validated context
	 * @throws IOException if the artifact is missing or malformed
	 */
	public static NativePromptLoaderContext read(JsonMapper json, Path source) throws IOException
	{
		Objects.requireNonNull(json, "json");
		Objects.requireNonNull(source, "source");
		if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Native loader context is not a regular file: " + source);
		if (Files.size(source) > 1024 * 1024)
			throw new IOException("Native loader context exceeds 1 MiB: " + source);
		try
		{
			JsonNode root = json.readTree(source.toFile());
			checkFields(root, Set.of("schemaVersion", "runId", "harness", "observedNativeVersion",
				"normalizedRunProjectRoot", "normalizedLaunchCwd", "copiedProjectBaselineCommit",
				"orderedInstructionRoots", "orderedSelectorFacts"));
			List<Path> roots = new ArrayList<>();
			for (JsonNode item : array(root.path("orderedInstructionRoots")))
				roots.add(Path.of(text(item)));
			List<SelectorFact> facts = new ArrayList<>();
			for (JsonNode item : array(root.path("orderedSelectorFacts")))
				facts.add(readFact(item));
			JsonNode version = root.path("observedNativeVersion");
			Optional<String> observedVersion = Optional.empty();
			if (!version.isNull())
				observedVersion = Optional.of(text(version));
			JsonNode schema = root.path("schemaVersion");
			if (!schema.isIntegralNumber())
				throw new IllegalArgumentException("schemaVersion must be integral");
			return new NativePromptLoaderContext(schema.intValue(), text(root.path("runId")),
				Harness.valueOf(text(root.path("harness"))), observedVersion,
				Path.of(text(root.path("normalizedRunProjectRoot"))),
				Path.of(text(root.path("normalizedLaunchCwd"))),
				text(root.path("copiedProjectBaselineCommit")), roots, facts);
		}
		catch (RuntimeException e)
		{
			throw new IOException("Malformed native loader context: " + source, e);
		}
	}

	/**
	 * Converts the allowlisted fields to the sealed JSON representation.
	 *
	 * @param json the runner-owned JSON mapper
	 * @return the context JSON object
	 */
	private ObjectNode toJson(JsonMapper json)
	{
		ObjectNode root = json.createObjectNode();
		root.put("schemaVersion", schemaVersion);
		root.put("runId", runId);
		root.put("harness", harness.name());
		if (observedNativeVersion.isPresent())
			root.put("observedNativeVersion", observedNativeVersion.orElseThrow());
		else
			root.putNull("observedNativeVersion");
		root.put("normalizedRunProjectRoot", normalizedRunProjectRoot.toString());
		root.put("normalizedLaunchCwd", normalizedLaunchCwd.toString());
		root.put("copiedProjectBaselineCommit", copiedProjectBaselineCommit);
		ArrayNode roots = root.putArray("orderedInstructionRoots");
		orderedInstructionRoots.forEach(path -> roots.add(path.toString()));
		ArrayNode facts = root.putArray("orderedSelectorFacts");
		for (SelectorFact fact : orderedSelectorFacts)
		{
			ObjectNode item = facts.addObject();
			item.put("kind", fact.kind().name());
			item.put("status", fact.status().name());
			item.put("origin", fact.origin());
			if (fact.value().isEmpty())
			{
				item.putNull("value");
				continue;
			}
			SelectorValue value = fact.value().orElseThrow();
			switch (value)
			{
				case Names names ->
				{
					ArrayNode entries = item.putArray("value");
					names.values().forEach(entries::add);
				}
				case Paths paths ->
				{
					ArrayNode entries = item.putArray("value");
					paths.values().forEach(path -> entries.add(path.toString()));
				}
				case ByteCap cap -> item.put("value", cap.value());
				case ProjectInstructionMode mode -> item.put("value", mode.value().name());
				case Flag flag -> item.put("value", flag.value());
			}
		}
		return root;
	}

	/**
	 * Reads a selector fact from a strictly shaped JSON object.
	 *
	 * @param item the fact object
	 * @return the validated selector fact
	 */
	private static SelectorFact readFact(JsonNode item)
	{
		checkFields(item, Set.of("kind", "status", "origin", "value"));
		SelectorKind kind = SelectorKind.valueOf(text(item.path("kind")));
		SelectorStatus status = SelectorStatus.valueOf(text(item.path("status")));
		JsonNode raw = item.path("value");
		Optional<SelectorValue> value = Optional.empty();
		if (!raw.isNull())
			value = Optional.of(readValue(kind, raw));
		return new SelectorFact(kind, status, text(item.path("origin")), value);
	}

	/**
	 * Reads the value shape assigned to one selector kind.
	 *
	 * @param kind the selector kind
	 * @param raw the raw JSON value
	 * @return the typed value
	 */
	private static SelectorValue readValue(SelectorKind kind, JsonNode raw)
	{
		return switch (kind)
		{
			case CODEX_FALLBACK_FILENAMES, CLAUDE_EFFECTIVE_SETTING_SOURCES, CLAUDE_MD_EXCLUDES ->
			{
				List<String> names = new ArrayList<>();
				for (JsonNode item : array(raw))
					names.add(text(item));
				yield new Names(names);
			}
			case CODEX_ROOT_DETECTION_SELECTORS, CLAUDE_ADDED_DIRECTORIES ->
			{
				List<Path> paths = new ArrayList<>();
				for (JsonNode item : array(raw))
					paths.add(Path.of(text(item)));
				yield new Paths(paths);
			}
			case CODEX_DOCUMENT_BYTE_CAP ->
			{
				if (!raw.isIntegralNumber())
					throw new IllegalArgumentException("byte cap must be integral");
				yield new ByteCap(raw.longValue());
			}
			case CLAUDE_PROJECT_INSTRUCTION_MODE -> new ProjectInstructionMode(Mode.valueOf(text(raw)));
			case CLAUDE_MEMORY_LOADING, CLAUDE_EXTERNAL_IMPORT_APPROVAL ->
			{
				if (!raw.isBoolean())
					throw new IllegalArgumentException("flag must be boolean");
				yield new Flag(raw.booleanValue());
			}
		};
	}

	/**
	 * Rejects missing, unknown, or duplicate field names.
	 *
	 * @param value the JSON object
	 * @param expected the exact field set
	 */
	private static void checkFields(JsonNode value, Set<String> expected)
	{
		if (!(value instanceof ObjectNode object) || !new HashSet<>(object.propertyNames()).equals(expected))
			throw new IllegalArgumentException("unsupported native loader context fields");
	}

	/**
	 * Requires a JSON array.
	 *
	 * @param value the JSON value
	 * @return the validated array
	 */
	private static JsonNode array(JsonNode value)
	{
		if (!value.isArray())
			throw new IllegalArgumentException("expected array");
		return value;
	}

	/**
	 * Requires JSON text.
	 *
	 * @param value the JSON value
	 * @return the text value
	 */
	private static String text(JsonNode value)
	{
		if (!value.isString())
			throw new IllegalArgumentException("expected text");
		return value.asString();
	}

	/**
	 * The supported native harnesses.
	 */
	public enum Harness
	{
		/** Claude Code. */
		CLAUDE,
		/** Codex. */
		CODEX
	}

	/**
	 * Native selector identities with stable value shapes.
	 */
	public enum SelectorKind
	{
		/** Ordered Codex fallback basenames. */
		CODEX_FALLBACK_FILENAMES,
		/** Codex document byte cap. */
		CODEX_DOCUMENT_BYTE_CAP,
		/** Ordered Codex root-detection paths. */
		CODEX_ROOT_DETECTION_SELECTORS,
		/** Ordered Claude effective setting source identifiers. */
		CLAUDE_EFFECTIVE_SETTING_SOURCES,
		/** Ordered Claude exclude globs. */
		CLAUDE_MD_EXCLUDES,
		/** Claude project-instruction mode. */
		CLAUDE_PROJECT_INSTRUCTION_MODE,
		/** Ordered Claude additional instruction directories. */
		CLAUDE_ADDED_DIRECTORIES,
		/** Claude memory-loading flag. */
		CLAUDE_MEMORY_LOADING,
		/** Claude external-import approval. */
		CLAUDE_EXTERNAL_IMPORT_APPROVAL
	}

	/**
	 * Whether a selector was available at the runner boundary.
	 */
	public enum SelectorStatus
	{
		/** A value was observed. */
		KNOWN,
		/** The native boundary could not establish a value. */
		UNAVAILABLE,
		/** The selector does not apply to this harness. */
		NOT_APPLICABLE
	}

	/**
	 * One effective selector and its source.
	 *
	 * @param kind the constrained selector kind
	 * @param status the availability of the value
	 * @param origin the effective setting, generated config, CLI argument, or documented default
	 * @param value the typed value, present exactly when known
	 */
	public record SelectorFact(SelectorKind kind, SelectorStatus status, String origin,
		Optional<SelectorValue> value)
	{
		/**
		 * Validates the selector shape and availability.
		 */
		public SelectorFact
		{
			Objects.requireNonNull(kind, "kind");
			Objects.requireNonNull(status, "status");
			checkText(origin, "origin");
			Objects.requireNonNull(value, "value");
			if ((status == SelectorStatus.KNOWN) != value.isPresent())
				throw new IllegalArgumentException("value must be present exactly when selector is known");
			value.ifPresent(selected -> checkShape(kind, selected));
		}
	}

	/**
	 * A constrained native selector value.
	 */
	public sealed interface SelectorValue permits Names, Paths, ByteCap, ProjectInstructionMode, Flag
	{
	}

	/**
	 * Ordered basenames, identifiers, or globs.
	 *
	 * @param values the ordered text values
	 */
	public record Names(List<String> values) implements SelectorValue
	{
		/**
		 * Snapshots and validates the names.
		 */
		public Names
		{
			values = List.copyOf(values);
			values.forEach(value -> checkText(value, "selector name"));
		}
	}

	/**
	 * Ordered normalized absolute paths.
	 *
	 * @param values the ordered paths
	 */
	public record Paths(List<Path> values) implements SelectorValue
	{
		/**
		 * Snapshots and validates the paths.
		 */
		public Paths
		{
			values = List.copyOf(values);
			values.forEach(NativePromptLoaderContext::checkAbsolute);
		}
	}

	/**
	 * A positive native document byte cap.
	 *
	 * @param value the byte limit
	 */
	public record ByteCap(long value) implements SelectorValue
	{
		/**
		 * Validates the byte cap.
		 */
		public ByteCap
		{
			if (value < 1)
				throw new IllegalArgumentException("byte cap must be positive");
		}
	}

	/**
	 * Claude's documented project-instruction mode token.
	 *
	 * @param value the native mode
	 */
	public record ProjectInstructionMode(Mode value) implements SelectorValue
	{
		/**
		 * Validates the native mode.
		 */
		public ProjectInstructionMode
		{
			Objects.requireNonNull(value, "value");
		}
	}

	/**
	 * Claude project-instruction modes.
	 */
	public enum Mode
	{
		/** Native eager mode. */
		EAGER,
		/** Native lazy mode. */
		LAZY
	}

	/**
	 * A native boolean setting.
	 *
	 * @param value the setting
	 */
	public record Flag(boolean value) implements SelectorValue
	{
	}

	/**
	 * Validates the value type associated with a selector kind.
	 *
	 * @param kind the selector kind
	 * @param value the typed selector value
	 */
	private static void checkShape(SelectorKind kind, SelectorValue value)
	{
		boolean valid = switch (kind)
		{
			case CODEX_FALLBACK_FILENAMES, CLAUDE_EFFECTIVE_SETTING_SOURCES, CLAUDE_MD_EXCLUDES ->
				value instanceof Names;
			case CODEX_ROOT_DETECTION_SELECTORS, CLAUDE_ADDED_DIRECTORIES -> value instanceof Paths;
			case CODEX_DOCUMENT_BYTE_CAP -> value instanceof ByteCap;
			case CLAUDE_PROJECT_INSTRUCTION_MODE -> value instanceof ProjectInstructionMode;
			case CLAUDE_MEMORY_LOADING, CLAUDE_EXTERNAL_IMPORT_APPROVAL -> value instanceof Flag;
		};
		if (!valid)
			throw new IllegalArgumentException("selector value does not match kind " + kind);
	}

	/**
	 * Requires nonblank selector or identity text.
	 *
	 * @param value the text
	 * @param name the field name
	 */
	private static void checkText(String value, String name)
	{
		Objects.requireNonNull(value, name);
		if (value.isBlank())
			throw new IllegalArgumentException(name + " must not be blank");
	}

	/**
	 * Requires a normalized absolute native path.
	 *
	 * @param value the path
	 */
	private static void checkAbsolute(Path value)
	{
		Objects.requireNonNull(value, "path");
		if (!value.isAbsolute() || !value.equals(value.normalize()))
			throw new IllegalArgumentException("path must be normalized and absolute");
	}
}
