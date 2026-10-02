package io.github.cowwoc.cat.common.optimizer.cli;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.ProfileCatalog;
import io.github.cowwoc.cat.common.optimizer.contract.TrialProfile;
import io.github.cowwoc.cat.common.optimizer.port.SessionResult;
import io.github.cowwoc.cat.common.optimizer.proposal.DerivedProposal;
import io.github.cowwoc.cat.common.optimizer.proposal.ProfileEvidence;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Persists the deriver's latest proposal in the campaign directory so that {@code confirm} and {@code revise} can
 * continue after the launcher exited.
 * <p>
 * The file holds the deriver's output, which {@code DeriverProtocol} validates again when it is read, the sources
 * that the deriver's session was observed loading, and the profile inventory and bindings that CAT read when the
 * deriver ran. Confirmation therefore freezes the trial profiles that the user saw.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 */
final class ProposalFile
{
	/**
	 * The file's name.
	 */
	static final String NAME = "proposal.json";
	/**
	 * The version of the proposal layout, including regression scenario citations in each test definition.
	 */
	private static final int FORMAT_VERSION = 4;
	private final Path file;
	private final JsonMapper json;

	/**
	 * A stored proposal.
	 *
	 * @param result the deriver's output as a session result, which the caller validates again
	 * @param profiles the profile inventory and bindings that CAT read when the deriver ran
	 */
	record Stored(SessionResult.Completed result, ProfileEvidence profiles)
	{
		/**
		 * Creates a stored proposal.
		 *
		 * @param result the deriver's output as a session result
		 * @param profiles the profile inventory and bindings
		 * @throws NullPointerException if any of the arguments are null
		 */
		Stored
		{
			requireThat(result, "result").isNotNull();
			requireThat(profiles, "profiles").isNotNull();
		}
	}

	/**
	 * Creates a proposal file.
	 *
	 * @param campaign the campaign's directory
	 * @param json the operation-owned JSON mapper
	 * @throws NullPointerException if any argument is null
	 */
	ProposalFile(Path campaign, JsonMapper json)
	{
		requireThat(campaign, "campaign").isNotNull();
		requireThat(json, "json").isNotNull();
		this.file = campaign.resolve(NAME);
		this.json = json;
	}

	/**
	 * Replaces the stored proposal.
	 *
	 * @param derived the proposal
	 * @param profiles the profile inventory and bindings that CAT read when the deriver ran
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException if the file cannot be written
	 */
	void write(DerivedProposal derived, ProfileEvidence profiles) throws IOException
	{
		requireThat(derived, "derived").isNotNull();
		requireThat(profiles, "profiles").isNotNull();
		ObjectNode root = json.createObjectNode();
		root.put("format_version", FORMAT_VERSION);
		root.put("observed_sources", derived.observedSources());
		root.put("derivation", derived.derivation());
		root.put("inventory_source", profiles.inventory().source());
		ArrayNode inventory = root.putArray("inventory");
		for (TrialProfile profile : profiles.inventory().profiles())
			inventory.add(profileNode(profile));
		ArrayNode bindings = root.putArray("bindings");
		for (Map.Entry<String, TrialProfile> entry : profiles.bindings().entrySet().stream().
			sorted(Map.Entry.comparingByKey()).toList())
		{
			bindings.add(profileNode(entry.getValue()).put("file", entry.getKey()));
		}
		Path temporary = file.resolveSibling(NAME + ".tmp");
		Files.writeString(temporary, json.writerWithDefaultPrettyPrinter().writeValueAsString(root),
			StandardCharsets.UTF_8);
		Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	/**
	 * Returns the JSON form of a profile.
	 *
	 * @param profile the profile
	 * @return an object with its model and effort
	 */
	private ObjectNode profileNode(TrialProfile profile)
	{
		ObjectNode node = json.createObjectNode();
		node.put("model", profile.model());
		node.put("effort", profile.effort());
		return node;
	}

	/**
	 * Reads the stored proposal.
	 *
	 * @return the proposal, or empty if no proposal is stored
	 * @throws IOException if the file cannot be read or is not a proposal file of this version
	 */
	Optional<Stored> read() throws IOException
	{
		if (!Files.exists(file))
			return Optional.empty();
		JsonNode root = json.readTree(Files.readString(file, StandardCharsets.UTF_8));
		int version = root.path("format_version").asInt();
		if (version != FORMAT_VERSION)
		{
			throw new IOException(file + " is a version " + version + " proposal file, but this version of CAT reads " +
				"only version " + FORMAT_VERSION + ". Run `start` again to derive a new proposal.");
		}
		if (!root.path("derivation").isString() || !root.path("observed_sources").isString() ||
			!root.path("inventory_source").isString() || !root.path("inventory").isArray() ||
			!root.path("bindings").isArray())
		{
			throw new IOException(file + " is not a valid version " + FORMAT_VERSION + " proposal file.");
		}
		ProfileEvidence profiles;
		try
		{
			List<TrialProfile> inventory = new ArrayList<>();
			for (JsonNode node : root.get("inventory"))
				inventory.add(profile(node));
			Map<String, TrialProfile> bindings = new LinkedHashMap<>();
			for (JsonNode node : root.get("bindings"))
				bindings.put(node.path("file").asString(), profile(node));
			profiles = new ProfileEvidence(new ProfileCatalog(root.get("inventory_source").asString(), inventory),
				bindings);
		}
		catch (IllegalArgumentException e)
		{
			throw new IOException(file + " holds invalid trial profiles: " + e.getMessage(), e);
		}
		// The deriver protocol reads only the responses and the prompt sources, so this file stands in for the
		// session's transcript and the campaign directory for its effects directory
		SessionResult.Completed result = new SessionResult.Completed(file, List.of(root.get("derivation").asString()),
			file.getParent(), null, null, null, List.of(), root.get("observed_sources").asString(), List.of());
		return Optional.of(new Stored(result, profiles));
	}

	/**
	 * Reads a profile.
	 *
	 * @param node an object with string {@code model} and {@code effort} properties
	 * @return the profile
	 * @throws IllegalArgumentException if a property is missing or invalid
	 */
	private static TrialProfile profile(JsonNode node)
	{
		if (!node.path("model").isString() || !node.path("effort").isString())
			throw new IllegalArgumentException("A profile needs string \"model\" and \"effort\" properties: " + node);
		return new TrialProfile(node.get("model").asString(), node.get("effort").asString());
	}
}
