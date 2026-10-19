package com.holdmyspot.cat.common.optimizer.cli;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.PortableFiles;
import com.holdmyspot.cat.common.PortablePaths;
import com.holdmyspot.cat.common.optimizer.contract.DerivedSource;
import com.holdmyspot.cat.common.optimizer.proposal.DerivedProposal;
import com.holdmyspot.cat.common.optimizer.proposal.DraftDirectory;
import com.holdmyspot.cat.common.optimizer.proposal.ProfileEvidence;
import com.holdmyspot.cat.common.optimizer.store.FileCampaignStore;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Persists the contract author's latest proposal in the campaign's {@code held_out} directory so that {@code confirm}
 * and {@code revise} can continue after the launcher exited.
 * <p>
 * The file holds the contract author's proposal file, which {@code ContractAuthorProtocol} validates again with its
 * draft directory when it is read, the draft directory that the held-out directory stores, the sources that the
 * contract author's session was observed loading, the profile inventory, capability ranking and bindings that CAT read
 * when the contract author ran, and the salt that CAT drew for the proposal. Confirmation therefore freezes the trial
 * profiles and the split into development and held-out test cases that the user saw.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 */
final class ProposalFile
{
	/**
	 * The file's name.
	 */
	static final String NAME = FileCampaignStore.PROPOSAL;
	/**
	 * The version of the proposal layout, including regression scenario citations, the variables of each prompt test
	 * and whether each is outcome-changing, the triggers of the requirements, the shipped capability
	 * ranking, the salt, the judge prompt's placeholders and the assertions of each prompt test, including what a
	 * failure of each assertion's built-in tool means, the derived prompt sources that CAT listed, and the proposal file
	 * whose prompt tests its stored draft directory holds.
	 */
	private static final int FORMAT_VERSION = 19;
	private final Path heldOut;
	private final Path file;
	private final JsonMapper json;

	/**
	 * A stored proposal.
	 *
	 * @param derivation the contract author's proposal file, whose text already names the user's project and which the
	 *   caller validates again
	 * @param draft the stored draft directory that holds the proposal's prompt tests
	 * @param observedSources the prompt sources that the harness reported loading in the contract author session
	 * @param derivedSources the prompt files that CAT listed as loading although no harness event reports them
	 * @param profiles the profile inventory, capability ranking and bindings that CAT read when the contract author ran
	 * @param salt the salt that CAT drew for the proposal
	 */
	record Stored(String derivation, Path draft, String observedSources, List<DerivedSource> derivedSources,
		ProfileEvidence profiles, long salt)
	{
		/**
		 * Creates a stored proposal.
		 *
		 * @param derivation the contract author's proposal file
		 * @param draft the stored draft directory that holds the proposal's prompt tests
		 * @param observedSources the prompt sources that the harness reported loading in the contract author session
		 * @param derivedSources the prompt files that CAT listed as loading although no harness event reports them
		 * @param profiles the profile inventory and bindings
		 * @param salt the salt that CAT drew for the proposal
		 * @throws NullPointerException if any of the arguments are null, or {@code derivedSources} contains null
		 */
		Stored
		{
			requireThat(derivation, "derivation").isNotNull();
			requireThat(draft, "draft").isNotNull();
			requireThat(observedSources, "observedSources").isNotNull();
			derivedSources = List.copyOf(derivedSources);
			requireThat(profiles, "profiles").isNotNull();
		}

		/**
		 * Returns the stored proposal after a validation of its output, with the derived sources that CAT listed.
		 *
		 * @param validated the proposal that validating {@link #derivation()} returned
		 * @return the proposal
		 * @throws NullPointerException if {@code validated} is null
		 */
		DerivedProposal restore(DerivedProposal validated)
		{
			return validated.withDerivedSources(derivedSources);
		}
	}

	/**
	 * Creates a proposal file.
	 *
	 * @param heldOut the campaign's {@code held_out} directory
	 * @param json the operation-owned JSON mapper
	 * @throws NullPointerException if any argument is null
	 */
	ProposalFile(Path heldOut, JsonMapper json)
	{
		requireThat(heldOut, "heldOut").isNotNull();
		requireThat(json, "json").isNotNull();
		this.heldOut = heldOut;
		this.file = heldOut.resolve(NAME);
		this.json = json;
	}

	/**
	 * Replaces the stored proposal, then removes every other draft directory that the held-out directory stores.
	 *
	 * @param derived the proposal, whose draft directory the held-out directory stores
	 * @param profiles the profile inventory, capability ranking and bindings that CAT read when the contract author ran
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if the proposal's draft directory does not lie in the held-out directory
	 * @throws IOException if the file cannot be written or another draft directory cannot be removed
	 */
	void write(DerivedProposal derived, ProfileEvidence profiles) throws IOException
	{
		requireThat(derived, "derived").isNotNull();
		requireThat(profiles, "profiles").isNotNull();
		Path draft = derived.draft();
		requireThat(draft.startsWith(heldOut), "draft.startsWith(heldOut)").isTrue();
		ObjectNode root = json.createObjectNode();
		root.put("format_version", FORMAT_VERSION);
		root.put("draft", PortablePaths.text(heldOut.relativize(draft)));
		root.put("observed_sources", derived.observedSources());
		ArrayNode derivedSources = root.putArray("derived_sources");
		for (DerivedSource source : derived.derivedSources())
			derivedSources.addObject().put("path", source.path()).put("rule", source.rule());
		root.put("derivation", derived.derivation());
		root.put("salt", derived.contract().salt());
		profiles.write(root);
		Path temporary = file.resolveSibling(NAME + ".tmp");
		Files.writeString(temporary, json.writerWithDefaultPrettyPrinter().writeValueAsString(root),
			StandardCharsets.UTF_8);
		PortableFiles.replace(temporary, file);
		// The stored proposal names the new draft directory only now, so the previous one stayed whole until then
		DraftDirectory.keepOnly(heldOut, draft);
	}

	/**
	 * Reads the stored proposal.
	 *
	 * @return the proposal, or empty if no proposal is stored
	 * @throws IOException if the file cannot be read or is not a proposal file of a readable version
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
				"version " + FORMAT_VERSION + ". Run `start` again to derive a new proposal.");
		}
		if (!root.path("derivation").isString() || !root.path("draft").isString() ||
			!root.path("observed_sources").isString() ||
			!root.path("derived_sources").isArray() || !root.path("salt").isIntegralNumber() ||
			!root.path("salt").canConvertToLong())
		{
			throw new IOException(file + " is not a valid version " + version + " proposal file.");
		}
		String derivation = root.get("derivation").asString();
		Path draft = heldOut.resolve(root.get("draft").asString()).normalize();
		if (!draft.startsWith(heldOut) || draft.equals(heldOut) || !Files.isDirectory(draft))
			throw new IOException(file + " names the draft directory " + draft + ", which the held-out directory does " +
				"not store.");
		List<DerivedSource> derivedSources = new ArrayList<>();
		ProfileEvidence profiles;
		try
		{
			for (JsonNode node : root.get("derived_sources"))
				derivedSources.add(new DerivedSource(node.path("path").asString(), node.path("rule").asString()));
			profiles = ProfileEvidence.read(root);
		}
		catch (IllegalArgumentException e)
		{
			throw new IOException(file + " holds invalid derived sources or trial profiles: " + e.getMessage(), e);
		}
		return Optional.of(new Stored(derivation, draft, root.get("observed_sources").asString(), derivedSources, profiles,
			root.get("salt").asLong()));
	}
}
