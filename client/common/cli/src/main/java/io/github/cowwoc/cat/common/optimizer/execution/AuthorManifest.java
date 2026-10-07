package io.github.cowwoc.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.AuthorKind;
import io.github.cowwoc.cat.common.optimizer.campaign.FailureKind;
import io.github.cowwoc.cat.common.optimizer.campaign.HunkEvidence;
import io.github.cowwoc.cat.common.optimizer.contract.TrialInstance;
import io.github.cowwoc.cat.common.optimizer.contract.Category;
import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.contract.TestDefinition;
import io.github.cowwoc.cat.common.optimizer.inventory.FileContent;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry;
import io.github.cowwoc.cat.common.optimizer.contract.Requirement;
import java.nio.charset.StandardCharsets;
import io.github.cowwoc.cat.common.optimizer.inventory.FileInventory;
import io.github.cowwoc.cat.common.optimizer.inventory.Hunk;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.databind.json.JsonMapper;

/**
 * The author's kinds of failure and the evidence for each changed hunk.
 *
 * @param kinds the kinds into which the author grouped the supplied failures
 * @param hunks the evidence per changed hunk
 */
public record AuthorManifest(List<AuthorKind> kinds, List<HunkEvidence> hunks)
{
	/**
	 * Creates a manifest.
	 *
	 * @param kinds the kinds into which the author grouped the supplied failures
	 * @param hunks the evidence per changed hunk
	 * @throws NullPointerException if either argument or a collection element is null
	 */
	public AuthorManifest
	{
		kinds = List.copyOf(kinds);
		hunks = List.copyOf(hunks);
	}

	/**
	 * Returns the citation identity of an exact supplied trial, including its cell and draw origin.
	 *
	 * @param instance the failing instance
	 * @return its stable identifier
	 * @throws NullPointerException if {@code instance} is null
	 */
	public static String instanceId(TrialInstance instance)
	{
		requireThat(instance, "instance").isNotNull();
		return Sha256.of(JsonMapper.builder().build().writeValueAsBytes(instance)).hex();
	}

	/**
	 * Indicates whether an attempt with this manifest offers a candidate.
	 *
	 * @param changed the actual changed hunks
	 * @return {@code true} if the attempt changed a file and classified at least one kind routing or guidance
	 */
	public boolean producesCandidate(List<Hunk> changed)
	{
		if (changed.isEmpty())
			return false;
		for (AuthorKind kind : kinds)
		{
			if (kind.isCorrectable())
				return true;
		}
		return false;
	}

	/**
	 * Checks manifest citations against supplied failures, confirmed requirements and tests, and confirmed file
	 * content.
	 *
	 * @param received the supplied failure identifiers
	 * @param changed the actual changed hunks
	 * @param contract the confirmed requirements
	 * @param incumbent the confirmed snapshot's inventory
	 * @param confirmedText the confirmed file bytes, keyed by resolved path
	 * @param conflicts the reported conflicts in presentation order
	 * @return the manifest rejection reasons
	 */
	public List<String> violations(Set<String> received, List<Hunk> changed, Contract contract,
		FileInventory incumbent, Map<Path, FileContent> confirmedText, List<AuthorConflict> conflicts)
	{
		List<String> violations = new ArrayList<>(hunkViolations(received, changed));
		violations.addAll(kindViolations(received));
		Map<String, FailureKind> classifications = new HashMap<>();
		for (AuthorKind kind : kinds)
		{
			for (String instance : kind.instances())
				classifications.put(instance, kind.classification());
		}
		Set<String> cited = new HashSet<>();
		Set<String> requirementIds = new HashSet<>();
		for (Requirement requirement : contract.requirements())
			requirementIds.add(requirement.id());
		for (AuthorConflict conflict : conflicts)
		{
			for (String instance : conflict.instances())
			{
				cited.add(instance);
				if (classifications.get(instance) != FailureKind.CONFLICT)
					violations.add("Failure " + instance + " cited by a conflict is not classified conflict");
			}
			for (String requirement : conflict.requirementIds())
			{
				if (!requirementIds.contains(requirement))
					violations.add("Unknown contract requirement cited by a conflict: " + requirement);
			}
			if (conflict.recommended() < 1 || conflict.recommended() > conflict.resolutions().size())
				violations.add("Conflict recommended position is outside its resolutions: " + conflict.recommended());
			for (AuthorConflict.Quotation quotation : conflict.quotations())
			{
				Path path = incumbent.resolve(quotation.path()).orElse(quotation.path());
				FileContent content = confirmedText.get(path);
				if (!(incumbent.entries().get(path) instanceof FileEntry.RegularFile file) || content == null ||
					!file.contentHash().equals(content.hash()) ||
					!new String(content.bytes(), StandardCharsets.UTF_8).contains(quotation.text()))
				{
					violations.add("Conflict quotation is absent from the confirmed prompt system: " + quotation.path());
				}
			}
		}
		for (Map.Entry<String, FailureKind> classification : classifications.entrySet())
		{
			if (classification.getValue() == FailureKind.CONFLICT && !cited.contains(classification.getKey()))
				violations.add("Failure " + classification.getKey() + " is classified conflict but no conflict cites it");
		}
		violations.addAll(dissimilarViolations(contract));
		return List.copyOf(violations);
	}

	/**
	 * Checks that the kinds partition the supplied failures and that each kind names only known hunks, at least one
	 * when it is classified routing or guidance.
	 *
	 * @param received the exact failure identifiers supplied to this author
	 * @return rejection reasons, empty if every supplied failure is in exactly one kind and every kind names the hunks
	 *   its classification requires
	 */
	private List<String> kindViolations(Set<String> received)
	{
		List<String> violations = new ArrayList<>();
		Set<String> hunkIds = new HashSet<>();
		for (HunkEvidence evidence : hunks)
		{
			if (!hunkIds.add(evidence.id()))
				violations.add("Duplicate hunk id: " + evidence.id());
		}

		Set<String> grouped = new HashSet<>();
		Set<String> repeated = new LinkedHashSet<>();
		for (AuthorKind kind : kinds)
		{
			for (String instance : kind.instances())
			{
				if (!received.contains(instance))
					violations.add("Kind '" + kind.name() + "' names an unknown failure: " + instance);
				else if (!grouped.add(instance))
					repeated.add(instance);
			}
			if (kind.isCorrectable() && kind.hunks().isEmpty())
			{
				violations.add("Kind '" + kind.name() + "' is classified " +
					kind.classification().name().toLowerCase(Locale.ROOT) + " but names no hunk that addresses it");
			}
			for (String hunk : kind.hunks())
			{
				if (!hunkIds.contains(hunk))
					violations.add("Kind '" + kind.name() + "' names an unknown hunk: " + hunk);
			}
		}
		for (String instance : repeated)
			violations.add("Failure " + instance + " is in more than one kind");
		for (String instance : new TreeSet<>(received))
		{
			if (!grouped.contains(instance))
				violations.add("Failure " + instance + " is outside every kind");
		}
		return violations;
	}

	/**
	 * Checks that each hunk's dissimilar case names a test of the confirmed contract.
	 *
	 * @param contract the confirmed contract
	 * @return a rejection reason for each hunk whose dissimilar case names no test of the contract
	 */
	private List<String> dissimilarViolations(Contract contract)
	{
		List<String> violations = new ArrayList<>();
		for (HunkEvidence evidence : hunks)
		{
			if (contract.findTest(evidence.dissimilar()).isPresent())
				continue;
			List<String> testIds = new ArrayList<>();
			for (Category category : contract.categories())
			{
				for (TestDefinition test : category.tests())
					testIds.add(test.id());
			}
			violations.add("Hunk at " + evidence.path() + " (working lines " + evidence.beforeStart() + ".." +
				evidence.beforeEnd() + ", author lines " + evidence.afterStart() + ".." + evidence.afterEnd() +
				") names the dissimilar case '" + evidence.dissimilar() + "', which is not a test of the confirmed " +
				"contract. Name the id of one of its tests: " + testIds);
		}
		return violations;
	}

	/**
	 * Checks completeness and citations, without judging the semantic quality of an edit.
	 *
	 * @param received the exact failure identifiers supplied to this author
	 * @param changed the actual changed hunks
	 * @return rejection reasons, empty if the manifest covers exactly the changes and cites only supplied failures
	 */
	private List<String> hunkViolations(Set<String> received, List<Hunk> changed)
	{
		List<String> violations = new ArrayList<>();
		Set<String> covered = new HashSet<>();
		for (HunkEvidence evidence : hunks)
		{
			boolean matched = false;
			for (Hunk hunk : changed)
			{
				if (!evidence.covers(hunk))
					continue;
				matched = true;
				if (!covered.add(hunk.id()))
					violations.add("Duplicate evidence for hunk: " + hunk.id());
			}
			if (!matched)
				violations.add("Manifest range contains no changed hunk: " + evidence.path());
			for (String instance : evidence.instances())
			{
				if (!received.contains(instance))
					violations.add("Unknown failing instance " + instance + " cited at " + evidence.path());
			}
		}
		for (Hunk hunk : changed)
		{
			if (!covered.contains(hunk.id()))
				violations.add("Uncited changed hunk: " + hunk.id());
		}
		return List.copyOf(violations);
	}
}
