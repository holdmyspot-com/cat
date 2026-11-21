package com.holdmyspot.cat.common.optimizer.cli;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.BoundedInput;
import com.holdmyspot.cat.common.PortablePaths;
import com.holdmyspot.cat.common.optimizer.campaign.Harness;
import com.holdmyspot.cat.common.optimizer.contract.DerivedSource;
import com.holdmyspot.cat.common.optimizer.contract.TrialProfile;
import com.holdmyspot.cat.common.optimizer.proposal.ProfileEvidence;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The profiles and project prompt sources observed by an installed evaluator.
 * <p>
 * <b>Thread Safety:</b> This class is immutable.
 *
 * @param harness        the installed evaluator's harness
 * @param profiles       the model inventory, ranking and staged prompt bindings
 * @param roleProfile    the evaluator's installed role profile
 * @param loaderFiles    the project-relative loader files present in the staged source
 * @param loaderIdentity the digest of those files and the user's loader settings
 * @param loadedSources  the prompt sources the evaluator loads in trials
 * @param runtime        the evaluator's native runtime identity
 * @param refusal        the reason the evaluator cannot load the target, or empty if it loads
 * @param observations   native loader differences and hook registrations, when present in the report
 */
public record EvaluatorMetadata(Harness harness, ProfileEvidence profiles, TrialProfile roleProfile,
	List<Path> loaderFiles, String loaderIdentity, List<DerivedSource> loadedSources, EvaluatorRuntime runtime,
	Optional<String> refusal, Optional<EvaluatorObservations> observations)
{
	private static final int FORMAT_VERSION = 2;
	private static final int MAXIMUM_REPORT_BYTES = 1_048_576;
	private static final String OBSERVATIONS = "observations";
	private static final Set<String> REPORT_FIELDS = Set.of("format_version", "harness", "profiles",
		"role_profile", "loader_files", "loader_identity", "loaded_sources", "runtime", "refusal");
	private static final Set<String> ROLE_FIELDS = Set.of("model", "effort", "tool_output_token_limit");
	private static final Set<String> SOURCE_FIELDS = Set.of("path", "rule");

	/**
	 * Creates a report.
	 *
	 * @param harness        the installed evaluator's harness
	 * @param profiles       the model inventory, ranking and staged prompt bindings
	 * @param roleProfile    the evaluator's installed role profile
	 * @param loaderFiles    the project-relative loader files present in the staged source
	 * @param loaderIdentity the digest of the inputs that determine the loader
	 * @param loadedSources  the prompt sources the evaluator loads in trials
	 * @param runtime        the evaluator's native runtime identity
	 * @param refusal        the reason the evaluator cannot load the target, or empty if it loads
	 * @param observations   native loader differences and hook registrations, when supplied
	 * @throws NullPointerException     if any argument or list element is null
	 * @throws IllegalArgumentException if the role profile is incomplete or the loader digest is invalid
	 */
	public EvaluatorMetadata
	{
		requireThat(harness, "harness").isNotNull();
		requireThat(profiles, "profiles").isNotNull();
		requireThat(roleProfile, "roleProfile").isNotNull();
		requireThat(roleProfile.isComplete(), "roleProfile.isComplete").isTrue();
		requireThat(loaderIdentity, "loaderIdentity").matches("[0-9a-f]{64}");
		requireThat(runtime, "runtime").isNotNull();
		requireThat(refusal, "refusal").isNotNull();
		requireThat(observations, "observations").isNotNull();
		loaderFiles = List.copyOf(loaderFiles);
		loadedSources = List.copyOf(loadedSources);
	}

	/**
	 * Creates a report with the original evaluator metadata fields only.
	 *
	 * @param harness the native harness
	 * @param profiles model inventory, ranking and bindings
	 * @param roleProfile the evaluator's role profile
	 * @param loaderFiles the staged loader files
	 * @param loaderIdentity the loader digest
	 * @param loadedSources the sources loaded in trials
	 * @param runtime the evaluator's native runtime
	 * @param refusal why the target cannot load, or empty if it loads
	 * @throws NullPointerException if any argument or list entry is null
	 * @throws IllegalArgumentException if the role or loader identity is invalid
	 */
	public EvaluatorMetadata(Harness harness, ProfileEvidence profiles, TrialProfile roleProfile,
		List<Path> loaderFiles, String loaderIdentity, List<DerivedSource> loadedSources, EvaluatorRuntime runtime,
		Optional<String> refusal)
	{
		this(harness, profiles, roleProfile, loaderFiles, loaderIdentity, loadedSources, runtime, refusal,
			Optional.empty());
	}

	/**
	 * Compares this observation against a frozen report, including the optional facts if that report recorded them.
	 *
	 * @param pinned the report written when the campaign derived or revised its proposal
	 * @return whether all recorded facts still hold
	 */
	boolean matchesPinned(EvaluatorMetadata pinned)
	{
		if (pinned.observations().isPresent() || observations.isEmpty())
			return equals(pinned);
		return new EvaluatorMetadata(harness, profiles, roleProfile, loaderFiles, loaderIdentity, loadedSources,
			runtime, refusal).equals(pinned);
	}

	/**
	 * Reads one complete report and closes its stream.
	 *
	 * @param json  the report mapper
	 * @param input the evaluator process's output stream
	 * @return the validated report
	 * @throws NullPointerException if any argument is null
	 * @throws IOException          if the stream cannot be read or does not contain one bounded report
	 */
	public static EvaluatorMetadata read(JsonMapper json, InputStream input) throws IOException
	{
		requireThat(json, "json").isNotNull();
		byte[] bytes = BoundedInput.readBytes(input, "evaluator output", MAXIMUM_REPORT_BYTES,
			"The evaluator metadata report");
		try
		{
			JsonNode root = json.readerFor(JsonNode.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
				DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readValue(bytes);
			return read(json, root);
		}
		catch (JacksonException | IllegalArgumentException e)
		{
			throw new IOException("Invalid evaluator metadata report", e);
		}
	}

	/**
	 * Reads a report stored as part of another document.
	 *
	 * @param json the report mapper
	 * @param root the stored report
	 * @return the validated report
	 * @throws IllegalArgumentException if the report is invalid
	 */
	static EvaluatorMetadata read(JsonMapper json, JsonNode root)
	{
		Set<String> expected = new HashSet<>(REPORT_FIELDS);
		Optional<EvaluatorObservations> observations = Optional.empty();
		if (root != null && root.has(OBSERVATIONS))
		{
			expected.add(OBSERVATIONS);
			JsonNode observed = root.path(OBSERVATIONS);
			if (!observed.isObject())
				throw new IllegalArgumentException("Expected evaluator observations");
			observations = Optional.of(json.treeToValue(observed, EvaluatorObservations.class));
		}
		requireFields(root, expected);
		if (!root.path("format_version").isInt() || root.path("format_version").intValue() != FORMAT_VERSION)
			throw new IllegalArgumentException("Unsupported evaluator metadata format");

		Harness harness = Harness.fromCommandName(text(root.path("harness"))).
			orElseThrow(() -> new IllegalArgumentException("Unknown evaluator harness"));
		JsonNode refusal = root.path("refusal");
		Optional<String> reason = Optional.empty();
		if (!refusal.isNull())
			reason = Optional.of(text(refusal));
		return new EvaluatorMetadata(harness, ProfileEvidence.read(root.path("profiles")),
			role(root.path("role_profile")), paths(root.path("loader_files")), text(root.path("loader_identity")),
			sources(root.path("loaded_sources")), json.treeToValue(root.path("runtime"), EvaluatorRuntime.class), reason,
			observations);
	}

	/**
	 * Writes one report, refusing oversized output before writing any bytes.
	 *
	 * @param json the report mapper
	 * @param out  the report stream
	 * @throws IOException if the encoded report exceeds its allowed size or the stream fails
	 */
	void write(JsonMapper json, PrintStream out) throws IOException
	{
		ObjectNode root = json.createObjectNode();
		write(json, root);
		byte[] encoded = json.writeValueAsBytes(root);
		if (encoded.length >= MAXIMUM_REPORT_BYTES)
			throw new IOException("Evaluator metadata exceeds the report size limit");
		out.write(encoded);
		out.write('\n');
		if (out.checkError())
			throw new IOException("Cannot write the evaluator metadata report");
	}

	/**
	 * Writes a report into the supplied JSON object.
	 *
	 * @param json the report mapper
	 * @param root the destination object
	 */
	void write(JsonMapper json, ObjectNode root)
	{
		root.put("format_version", FORMAT_VERSION).put("harness", harness.commandName()).
			put("loader_identity", loaderIdentity);
		profiles.write(root.putObject("profiles"));
		root.putObject("role_profile").put("model", roleProfile.model()).put("effort", roleProfile.effort()).
			put("tool_output_token_limit", roleProfile.toolOutputTokenLimit());
		ArrayNode loaderNodes = root.putArray("loader_files");
		for (Path path : loaderFiles)
			loaderNodes.add(PortablePaths.text(path));
		ArrayNode loadedNodes = root.putArray("loaded_sources");
		for (DerivedSource source : loadedSources)
			loadedNodes.addObject().put("path", source.path()).put("rule", source.rule());
		root.set("runtime", json.valueToTree(runtime));
		observations.ifPresent(value -> root.set(OBSERVATIONS, json.valueToTree(value)));
		if (refusal.isPresent())
			root.put("refusal", refusal.get());
		else
			root.putNull("refusal");
	}

	/**
	 * Reads the exact fields of the supplied object.
	 *
	 * @param node   the value
	 * @param fields the allowed and required fields
	 */
	private static void requireFields(JsonNode node, Set<String> fields)
	{
		if (node == null || !node.isObject() || !Set.copyOf(node.propertyNames()).equals(fields))
			throw new IllegalArgumentException("Unexpected evaluator metadata fields");
	}

	/**
	 * Returns a string value.
	 *
	 * @param node the value
	 * @return the nonblank text
	 */
	private static String text(JsonNode node)
	{
		if (!node.isString() || node.asString().isBlank())
			throw new IllegalArgumentException("Expected evaluator metadata text");
		return node.asString();
	}

	/**
	 * Reads the evaluator's role profile.
	 *
	 * @param node the profile object
	 * @return the role profile
	 */
	private static TrialProfile role(JsonNode node)
	{
		requireFields(node, ROLE_FIELDS);
		JsonNode limit = node.path("tool_output_token_limit");
		if (!limit.isInt())
			throw new IllegalArgumentException("Expected an evaluator role token limit");
		return new TrialProfile(text(node.path("model")), text(node.path("effort")), limit.intValue());
	}

	/**
	 * Reads the loader file paths.
	 *
	 * @param node the path array
	 * @return the project-relative paths
	 */
	private static List<Path> paths(JsonNode node)
	{
		if (!node.isArray())
			throw new IllegalArgumentException("Expected evaluator loader paths");
		List<Path> paths = new ArrayList<>();
		for (JsonNode value : node)
		{
			Path path = Path.of(text(value)).normalize();
			if (PortablePaths.isRooted(path) || path.toString().isEmpty() || path.startsWith(".."))
				throw new IllegalArgumentException("Expected a project-relative evaluator loader path");
			paths.add(path);
		}
		return paths;
	}

	/**
	 * Reads the evaluator's trial sources.
	 *
	 * @param node the source array
	 * @return the sources in loading order
	 */
	private static List<DerivedSource> sources(JsonNode node)
	{
		if (!node.isArray())
			throw new IllegalArgumentException("Expected evaluator loaded sources");
		List<DerivedSource> sources = new ArrayList<>();
		for (JsonNode value : node)
		{
			requireFields(value, SOURCE_FIELDS);
			sources.add(new DerivedSource(text(value.path("path")), text(value.path("rule"))));
		}
		return sources;
	}
}
