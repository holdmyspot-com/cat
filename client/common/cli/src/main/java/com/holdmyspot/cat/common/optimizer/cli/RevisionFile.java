package com.holdmyspot.cat.common.optimizer.cli;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.optimizer.campaign.CampaignEvent;
import com.holdmyspot.cat.common.optimizer.campaign.PendingDecision;
import com.holdmyspot.cat.common.optimizer.campaign.TestRevision;
import com.holdmyspot.cat.common.optimizer.proposal.DerivedProposal;
import com.holdmyspot.cat.common.optimizer.proposal.ProfileEvidence;
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
 * Persists the revised contract of a test revision, and what the revision decided, while the guard pilot of the
 * contract has not ended, so that the next {@code run} continues the pilot instead of asking the contract author again.
 * <p>
 * The revised contract is stored beside the confirmed proposal, which stays the confirmed contract until the revised
 * one is confirmed: {@link ProposalFile#revised} holds the contract, and this file holds the corrections and the
 * test revisions that cannot proceed. The directory of the revised contract's draft is one of the draft directories
 * that the held-out directory stores. Both files hold held-out content.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
final class RevisionFile
{
	/**
	 * The name of the file that holds the corrections and the test revisions that cannot proceed.
	 */
	static final String NAME = "revision.json";
	/**
	 * The version of the file's layout.
	 */
	private static final int FORMAT_VERSION = 1;
	private final Path file;
	private final ProposalFile proposal;
	private final JsonMapper json;
	private final FileReplacer replacer;

	/**
	 * A stored test revision.
	 *
	 * @param proposal the revised contract
	 * @param revisions the corrections, one per corrected test
	 * @param blocked the test revisions that cannot proceed
	 */
	record Stored(ProposalFile.Stored proposal, List<TestRevision> revisions,
		List<CampaignEvent.BlockTestRevision> blocked)
	{
		/**
		 * Creates a stored test revision.
		 *
		 * @param proposal the revised contract
		 * @param revisions the corrections, one per corrected test
		 * @param blocked the test revisions that cannot proceed
		 * @throws NullPointerException if any argument is null, or a list contains null
		 */
		Stored
		{
			requireThat(proposal, "proposal").isNotNull();
			revisions = List.copyOf(revisions);
			blocked = List.copyOf(blocked);
		}
	}

	/**
	 * Creates a revision file.
	 *
	 * @param heldOut the campaign's {@code held_out} directory
	 * @param json the operation-owned JSON mapper
	 * @param replacer replaces the files
	 * @throws NullPointerException if any argument is null
	 */
	RevisionFile(Path heldOut, JsonMapper json, FileReplacer replacer)
	{
		requireThat(heldOut, "heldOut").isNotNull();
		requireThat(json, "json").isNotNull();
		requireThat(replacer, "replacer").isNotNull();
		this.file = heldOut.resolve(NAME);
		this.proposal = ProposalFile.revised(heldOut, json, replacer);
		this.json = json;
		this.replacer = replacer;
	}

	/**
	 * Stores a revised contract and what its test revision decided, replacing any stored one.
	 *
	 * @param derived the revised contract, whose draft directory the held-out directory stores
	 * @param profiles the profile inventory, capability ranking and bindings that CAT read when the contract author ran
	 * @param revisions the corrections, one per corrected test
	 * @param blocked the test revisions that cannot proceed
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if the contract's draft directory does not lie in the held-out directory
	 * @throws IOException if a file cannot be written
	 */
	void write(DerivedProposal derived, ProfileEvidence profiles, List<TestRevision> revisions,
		List<CampaignEvent.BlockTestRevision> blocked) throws IOException
	{
		requireThat(revisions, "revisions").isNotNull();
		requireThat(blocked, "blocked").isNotNull();
		ObjectNode root = json.createObjectNode();
		root.put("format_version", FORMAT_VERSION);
		ArrayNode corrections = root.putArray("revisions");
		for (TestRevision revision : revisions)
		{
			corrections.addObject().put("lineage", revision.lineage()).put("test", revision.test()).
				put("before", revision.before()).put("after", revision.after()).put("reasons", revision.reasons());
		}
		ArrayNode blockedTests = root.putArray("blocked");
		for (CampaignEvent.BlockTestRevision block : blocked)
		{
			blockedTests.addObject().put("test", block.test()).put("reason", block.reason().name()).
				put("reasons", block.reasons()).put("proposed_change", block.proposedChange());
		}
		Path temporary = file.resolveSibling(NAME + ".tmp");
		Files.writeString(temporary, json.writerWithDefaultPrettyPrinter().writeValueAsString(root),
			StandardCharsets.UTF_8);
		replacer.replace(temporary, file);
		// The contract is stored last, and a stored test revision is the one whose contract is stored
		proposal.write(derived, profiles);
	}

	/**
	 * Reads the stored test revision.
	 *
	 * @return the test revision, or empty if none is stored
	 * @throws IOException if a file cannot be read or is not a test revision file of a readable version
	 */
	Optional<Stored> read() throws IOException
	{
		Optional<ProposalFile.Stored> contract = proposal.read();
		if (contract.isEmpty())
			return Optional.empty();
		if (!Files.exists(file))
			throw new IOException("A revised contract is stored without " + file + ".");
		JsonNode root = json.readTree(Files.readString(file, StandardCharsets.UTF_8));
		int version = root.path("format_version").asInt();
		if (version != FORMAT_VERSION)
		{
			throw new IOException(file + " is a version " + version + " test revision file, but this version of CAT " +
				"reads version " + FORMAT_VERSION + ".");
		}
		if (!root.path("revisions").isArray() || !root.path("blocked").isArray())
			throw new IOException(file + " is not a valid version " + version + " test revision file.");
		try
		{
			List<TestRevision> revisions = new ArrayList<>();
			for (JsonNode node : root.get("revisions"))
			{
				revisions.add(new TestRevision(node.path("lineage").asInt(), node.path("test").asString(),
					node.path("before").asString(), node.path("after").asString(), node.path("reasons").asString()));
			}
			List<CampaignEvent.BlockTestRevision> blocked = new ArrayList<>();
			for (JsonNode node : root.get("blocked"))
			{
				blocked.add(new CampaignEvent.BlockTestRevision(node.path("test").asString(),
					PendingDecision.TestRevisionBlocked.Reason.valueOf(node.path("reason").asString()),
					node.path("reasons").asString(), node.path("proposed_change").asString()));
			}
			return Optional.of(new Stored(contract.get(), revisions, blocked));
		}
		catch (IllegalArgumentException e)
		{
			throw new IOException(file + " holds an invalid test revision: " + e.getMessage(), e);
		}
	}

	/**
	 * Deletes the stored test revision, without touching any draft directory.
	 *
	 * @return {@code true} if a test revision was stored
	 * @throws IOException if a file cannot be deleted
	 */
	boolean delete() throws IOException
	{
		// The contract goes first, so an interrupted deletion leaves no test revision stored
		boolean stored = proposal.delete();
		Files.deleteIfExists(file);
		return stored;
	}
}
