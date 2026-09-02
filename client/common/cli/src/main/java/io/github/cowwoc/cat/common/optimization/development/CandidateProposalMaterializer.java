package io.github.cowwoc.cat.common.optimization.development;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.Fingerprints;
import io.github.cowwoc.cat.common.RecordStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Applies target-only proposals to an immutable champion and publishes one atomic candidate set.
 */
public final class CandidateProposalMaterializer
{
	private static final int SCHEMA_VERSION = 1;
	private static final String MANIFEST_NAME = "candidate-set.json";
	private static final Set<String> MANIFEST_FIELDS = Set.of("schema_version", "input_sha256", "candidates");
	private static final Set<String> CANDIDATE_FIELDS = Set.of("candidate_id", "sha256");

	/**
	 * Prevents construction of this static materialization owner.
	 */
	private CandidateProposalMaterializer()
	{
	}

	/**
	 * Publishes every proposed target replacement as a complete immutable candidate bundle.
	 *
	 * @param json the bundle JSON codec
	 * @param championReference the immutable champion reference
	 * @param proposal the bounded target-only proposal
	 * @param destinationRoot the initially absent candidate-set root
	 * @return the published candidates in proposal order
	 * @throws IOException if the champion changed, a proposal is redundant, or publication cannot complete atomically
	 */
	public static List<DevelopmentComparisonOperation.CandidateReference> materialize(JsonMapper json,
		DevelopmentComparisonOperation.CandidateReference championReference,
		HarnessCandidateGenerator.Proposal proposal, Path destinationRoot) throws IOException
	{
		Objects.requireNonNull(json, "json must not be null");
		Objects.requireNonNull(championReference, "championReference must not be null");
		Objects.requireNonNull(proposal, "proposal must not be null");
		Path destination = Objects.requireNonNull(destinationRoot, "destinationRoot must not be null").
			toAbsolutePath().normalize();
		Path parent = destination.getParent();
		if (parent == null)
			throw new IOException("Candidate-set destination has no parent: " + destination);
		CandidateBundle.Candidate champion = CandidateBundle.read(json, championReference.bundleRoot());
		if (!champion.sha256().equals(championReference.sha256()))
			throw new IOException("Champion bundle changed before candidate materialization");
		if (proposal.candidates().isEmpty())
			throw new IOException("Candidate proposal contains no candidate");
		String inputFingerprint = inputFingerprint(json, championReference, champion, proposal);
		if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS))
			return readPublished(json, destination, proposal.candidates(), inputFingerprint);

		Files.createDirectories(parent);
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path staging = cleanup.add(Files.createTempDirectory(parent, ".candidate-set-"));
			Path stagedBundles = Files.createDirectories(staging.resolve("bundles"));
			Path sources = Files.createDirectories(staging.resolve("sources"));
			Set<String> identifiers = new HashSet<>();
			String championPrimary = Files.readString(champion.primary().source());
			List<PublishedCandidate> published = new ArrayList<>(proposal.candidates().size());
			for (HarnessCandidateGenerator.CandidateProposal candidate : proposal.candidates())
			{
				if (!identifiers.add(candidate.identifier()))
					throw new IOException("Duplicate materialized candidate identifier: " + candidate.identifier());
				if (candidate.replacement().equals(championPrimary))
					throw new IOException("Candidate does not change the target prompt: " + candidate.identifier());
				Path source = Files.createDirectories(sources.resolve(candidate.identifier()));
				copyChampion(champion, source);
				Files.writeString(source.resolve(champion.primary().relativePath()), candidate.replacement());
				CandidateBundle.Candidate materialized = CandidateBundle.materialize(json, source,
					stagedBundles.resolve(candidate.identifier()),
					champion.primary().relativePath(), additionalRules(champion), champion.routeReplacements());
				published.add(new PublishedCandidate(candidate.identifier(), materialized.sha256()));
			}
			RecordStore.write(json, stagedBundles.resolve(MANIFEST_NAME),
				manifest(json, inputFingerprint, published));
			TemporaryDirectories.deleteRecursively(sources);
			Files.move(stagedBundles, destination, StandardCopyOption.ATOMIC_MOVE);
			return readPublished(json, destination, proposal.candidates(), inputFingerprint);
		}
	}

	/**
	 * Computes the complete retry identity for one candidate publication.
	 *
	 * @param json the JSON codec
	 * @param championReference the immutable champion reference
	 * @param champion the verified champion bundle
	 * @param proposal the exact proposed replacements
	 * @return the canonical input fingerprint
	 * @throws IOException if canonical JSON cannot be encoded
	 */
	private static String inputFingerprint(JsonMapper json,
		DevelopmentComparisonOperation.CandidateReference championReference, CandidateBundle.Candidate champion,
		HarnessCandidateGenerator.Proposal proposal) throws IOException
	{
		ObjectNode identity = json.createObjectNode();
		identity.put("champion_id", championReference.identifier());
		identity.put("champion_sha256", champion.sha256());
		identity.put("primary_rule", champion.primary().relativePath());
		ArrayNode candidates = identity.putArray("candidates");
		for (HarnessCandidateGenerator.CandidateProposal candidate : proposal.candidates())
		{
			ObjectNode item = candidates.addObject();
			item.put("candidate_id", candidate.identifier());
			item.put("rationale", candidate.rationale());
			item.put("replacement", candidate.replacement());
		}
		return Fingerprints.ofUtf8(json.writeValueAsString(identity));
	}

	/**
	 * Creates the retained candidate-set manifest.
	 *
	 * @param json the JSON codec
	 * @param inputFingerprint the publication input identity
	 * @param candidates the published candidate identities
	 * @return the manifest document
	 */
	private static ObjectNode manifest(JsonMapper json, String inputFingerprint, List<PublishedCandidate> candidates)
	{
		ObjectNode result = json.createObjectNode();
		result.put("schema_version", SCHEMA_VERSION);
		result.put("input_sha256", inputFingerprint);
		ArrayNode values = result.putArray("candidates");
		for (PublishedCandidate candidate : candidates)
		{
			ObjectNode value = values.addObject();
			value.put("candidate_id", candidate.identifier());
			value.put("sha256", candidate.fingerprint());
		}
		return result;
	}

	/**
	 * Copies every champion rule into one candidate source tree.
	 *
	 * @param champion the validated champion
	 * @param sourceRoot the candidate source root
	 * @throws IOException if a source changes or cannot be copied
	 */
	private static void copyChampion(CandidateBundle.Candidate champion, Path sourceRoot) throws IOException
	{
		for (CandidateBundle.Entry entry : champion.entries())
		{
			Path destination = sourceRoot.resolve(entry.relativePath()).normalize();
			if (!destination.startsWith(sourceRoot))
				throw new IOException("Champion rule escapes candidate source: " + entry.relativePath());
			Files.createDirectories(destination.getParent());
			Files.copy(entry.source(), destination);
		}
	}

	/**
	 * Returns every non-primary champion rule.
	 *
	 * @param champion the champion
	 * @return the additional canonical rule paths
	 */
	private static List<String> additionalRules(CandidateBundle.Candidate champion)
	{
		return champion.entries().stream().map(CandidateBundle.Entry::relativePath).
			filter(path -> !path.equals(champion.primary().relativePath())).toList();
	}

	/**
	 * Reads the atomically published bundles and creates their stable references.
	 *
	 * @param json the bundle JSON codec
	 * @param destination the published candidate-set root
	 * @param proposals the proposals in caller order
	 * @param inputFingerprint the expected publication input identity
	 * @return candidate references in proposal order
	 * @throws IOException if a published bundle is invalid
	 */
	private static List<DevelopmentComparisonOperation.CandidateReference> readPublished(JsonMapper json,
		Path destination, List<HarnessCandidateGenerator.CandidateProposal> proposals, String inputFingerprint)
		throws IOException
	{
		if (!Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Candidate-set destination is not a directory: " + destination);
		Path manifestPath = destination.resolve(MANIFEST_NAME);
		JsonNode parsed = RecordStore.read(json, manifestPath);
		if (!(parsed instanceof ObjectNode manifest) || !fields(manifest).equals(MANIFEST_FIELDS) ||
			manifest.path("schema_version").asInt(-1) != SCHEMA_VERSION)
		{
			throw new IOException("Candidate-set manifest is malformed: " + manifestPath);
		}
		if (!manifest.path("input_sha256").isString() ||
			!manifest.path("input_sha256").asString().equals(inputFingerprint))
		{
			throw new IOException("Candidate-set input differs from retained identity: " + destination);
		}
		if (!(manifest.path("candidates") instanceof ArrayNode retained) || retained.size() != proposals.size())
			throw new IOException("Candidate-set manifest has an unexpected candidate count: " + manifestPath);

		List<DevelopmentComparisonOperation.CandidateReference> result = new ArrayList<>(proposals.size());
		for (int index = 0; index < proposals.size(); ++index)
		{
			HarnessCandidateGenerator.CandidateProposal proposal = proposals.get(index);
			JsonNode node = retained.get(index);
			if (!(node instanceof ObjectNode value) || !fields(value).equals(CANDIDATE_FIELDS) ||
				!value.path("candidate_id").isString() ||
				!value.path("candidate_id").asString().equals(proposal.identifier()) ||
				!value.path("sha256").isString())
			{
				throw new IOException("Candidate-set manifest entry is malformed: " + manifestPath);
			}
			Path bundle = destination.resolve(proposal.identifier());
			CandidateBundle.Candidate candidate = CandidateBundle.read(json, bundle);
			if (!candidate.sha256().equals(value.path("sha256").asString()))
				throw new IOException("Published candidate differs from retained manifest: " + bundle);
			if (!Files.readString(candidate.primary().source()).equals(proposal.replacement()))
				throw new IOException("Published candidate replacement differs from retained proposal: " + bundle);
			result.add(new DevelopmentComparisonOperation.CandidateReference(proposal.identifier(), bundle,
				candidate.sha256()));
		}
		return List.copyOf(result);
	}

	/**
	 * Returns an object's exact field set.
	 *
	 * @param object the object
	 * @return the field set
	 */
	private static Set<String> fields(ObjectNode object)
	{
		return new HashSet<>(object.propertyNames());
	}

	/**
	 * Holds one published candidate identity before its directory is atomically moved.
	 *
	 * @param identifier the candidate identifier
	 * @param fingerprint the candidate bundle fingerprint
	 */
	private record PublishedCandidate(String identifier, String fingerprint)
	{
	}
}
