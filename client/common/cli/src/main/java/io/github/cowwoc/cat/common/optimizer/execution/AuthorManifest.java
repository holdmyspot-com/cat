package io.github.cowwoc.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.FailureKind;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.json.JsonMapper;

/**
 * The author's classified failures and evidence for each changed hunk.
 *
 * @param classifications each supplied failure's identifier and classification
 * @param hunks the evidence per changed hunk
 */
public record AuthorManifest(Map<String, FailureKind> classifications, List<Evidence> hunks)
{
	/**
	 * Creates a manifest.
	 *
	 * @param classifications each supplied failure's identifier and classification
	 * @param hunks the evidence per changed hunk
	 * @throws NullPointerException if either argument or a collection element is null
	 * @throws IllegalArgumentException if a failure identifier is blank
	 */
	public AuthorManifest
	{
		classifications = Map.copyOf(classifications);
		for (String id : classifications.keySet())
			requireThat(id, "id").isNotBlank();
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
	 * Checks that each hunk's dissimilar case names a test of the confirmed contract.
	 *
	 * @param contract the confirmed contract
	 * @return a rejection reason for each hunk whose dissimilar case names no test of the contract
	 */
	private List<String> dissimilarViolations(Contract contract)
	{
		List<String> violations = new ArrayList<>();
		for (Evidence evidence : hunks)
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
	 * @return rejection reasons, empty if the manifest covers exactly the changes and supplied failures
	 */
	private List<String> hunkViolations(Set<String> received, List<Hunk> changed)
	{
		List<String> violations = new ArrayList<>();
		if (!classifications.keySet().equals(received))
			violations.add("Classifications must name every supplied failure and no unknown failure");
		Set<String> covered = new HashSet<>();
		for (Evidence evidence : hunks)
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

	/**
	 * The failing instances and generalization cases motivating one hunk.
	 *
	 * @param path the changed file's resolved path
	 * @param beforeStart the first annotated working line, from zero
	 * @param beforeEnd the end of the working range, exclusive
	 * @param afterStart the first annotated author line, from zero
	 * @param afterEnd the end of the author range, exclusive
	 * @param instances the supplied failing-instance identifiers
	 * @param analogous a case requiring the same guidance
	 * @param dissimilar the identifier of the contract test whose case must not receive the guidance
	 */
	public record Evidence(Path path, int beforeStart, int beforeEnd, int afterStart, int afterEnd,
		List<String> instances, String analogous, String dissimilar)
	{
		/**
		 * Creates evidence for one hunk.
		 *
		 * @param path the changed file's resolved path
		 * @param beforeStart the first working line
		 * @param beforeEnd the working range end
		 * @param afterStart the first author line
		 * @param afterEnd the author range end
		 * @param instances the supplied failing-instance identifiers
		 * @param analogous a case requiring the same guidance
		 * @param dissimilar the identifier of the contract test whose case is excluded from the guidance
		 * @throws NullPointerException if an argument or a collection element is null
		 * @throws IllegalArgumentException if a path is invalid, a range is invalid, the citations are empty,
		 *   or any text is blank
		 */
		public Evidence
		{
			FileInventory.requireInventoryPath(path, "path");
			requireThat(beforeStart, "beforeStart").isNotNegative();
			requireThat(beforeEnd, "beforeEnd").isGreaterThanOrEqualTo(beforeStart);
			requireThat(afterStart, "afterStart").isNotNegative();
			requireThat(afterEnd, "afterEnd").isGreaterThanOrEqualTo(afterStart);
			requireThat(instances, "instances").isNotEmpty();
			instances = List.copyOf(instances);
			for (String instance : instances)
				requireThat(instance, "instance").isNotBlank();
			requireThat(analogous, "analogous").isNotBlank();
			requireThat(dissimilar, "dissimilar").isNotBlank();
		}

		/**
		 * Indicates whether this annotation contains an actual hunk's replaced and replacement spans.
		 *
		 * @param hunk the actual hunk
		 * @return whether both complete spans are covered
		 */
		public boolean covers(Hunk hunk)
		{
			return path.equals(hunk.path()) && beforeStart <= hunk.beforeStart() && beforeEnd >= hunk.beforeEnd() &&
				afterStart <= hunk.afterStart() && afterEnd >= hunk.afterEnd();
		}
	}
}
