package io.github.cowwoc.cat.common.optimizer.cli;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.port.SessionResult;
import io.github.cowwoc.cat.common.optimizer.proposal.DerivedProposal;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;

/**
 * Persists the deriver's latest proposal in the campaign directory so that {@code confirm} and {@code revise} can
 * continue after the launcher exited.
 * <p>
 * The file holds the deriver's output, which {@code DeriverProtocol} validates again when it is read, and the sources
 * that the deriver's session was observed loading.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 */
final class ProposalFile
{
	/**
	 * The file's name.
	 */
	static final String NAME = "proposal.json";
	private static final int FORMAT_VERSION = 1;
	private final Path file;
	private final JsonMapper json;

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
	 * @throws NullPointerException if {@code derived} is null
	 * @throws IOException if the file cannot be written
	 */
	void write(DerivedProposal derived) throws IOException
	{
		requireThat(derived, "derived").isNotNull();
		ObjectNode root = json.createObjectNode();
		root.put("format_version", FORMAT_VERSION);
		root.put("observed_sources", derived.observedSources());
		root.put("derivation", derived.derivation());
		Path temporary = file.resolveSibling(NAME + ".tmp");
		Files.writeString(temporary, json.writerWithDefaultPrettyPrinter().writeValueAsString(root),
			StandardCharsets.UTF_8);
		Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	/**
	 * Reads the stored proposal as the deriver's session result, which the caller validates again.
	 *
	 * @return the result, or empty if no proposal is stored
	 * @throws IOException if the file cannot be read or is not a proposal file of this version
	 */
	Optional<SessionResult.Completed> read() throws IOException
	{
		if (!Files.exists(file))
			return Optional.empty();
		JsonNode root = json.readTree(Files.readString(file, StandardCharsets.UTF_8));
		if (root.path("format_version").asInt() != FORMAT_VERSION || !root.path("derivation").isString() ||
			!root.path("observed_sources").isString())
		{
			throw new IOException(file + " is not a version " + FORMAT_VERSION + " proposal file.");
		}
		Path none = file.getParent();
		return Optional.of(new SessionResult.Completed(file, List.of(root.get("derivation").asString()), none, null,
			null, null, List.of(), root.get("observed_sources").asString()));
	}
}
