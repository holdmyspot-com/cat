package com.holdmyspot.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.PortablePaths;
import com.holdmyspot.cat.common.optimizer.campaign.PromptAuthorKind;
import com.holdmyspot.cat.common.optimizer.campaign.FailureKind;
import com.holdmyspot.cat.common.optimizer.campaign.HunkEvidence;
import com.holdmyspot.cat.common.optimizer.contract.DrawnTestCase;
import com.holdmyspot.cat.common.optimizer.contract.Contract;
import com.holdmyspot.cat.common.optimizer.contract.PromptTest;
import com.holdmyspot.cat.common.optimizer.inventory.FileContent;
import com.holdmyspot.cat.common.optimizer.inventory.FileEntry;
import com.holdmyspot.cat.common.optimizer.contract.Requirement;
import java.nio.charset.StandardCharsets;
import com.holdmyspot.cat.common.optimizer.inventory.FileInventory;
import com.holdmyspot.cat.common.optimizer.inventory.Hunk;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;

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
 * The prompt author's kinds of failure and the evidence for each changed hunk.
 *
 * @param kinds the kinds into which the prompt author grouped the supplied failures
 * @param hunks the evidence per changed hunk
 */
public record PromptAuthorManifest(List<PromptAuthorKind> kinds, List<HunkEvidence> hunks)
{
	/**
	 * Serializes drawn test cases into their citation identities. A shared mapper reuses its serializers, which a
	 * new mapper per identity would build again.
	 */
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * Creates a manifest.
	 *
	 * @param kinds the kinds into which the prompt author grouped the supplied failures
	 * @param hunks the evidence per changed hunk
	 * @throws NullPointerException if either argument or a collection element is null
	 */
	public PromptAuthorManifest
	{
		kinds = List.copyOf(kinds);
		hunks = List.copyOf(hunks);
	}

	/**
	 * Returns the citation identity of an exact supplied trial, including its cell and draw origin.
	 *
	 * @param testCase the failing test case
	 * @return its stable identifier
	 * @throws NullPointerException if {@code testCase} is null
	 */
	public static String drawnTestCaseId(DrawnTestCase testCase)
	{
		requireThat(testCase, "testCase").isNotNull();
		return Sha256.of(JSON.writeValueAsBytes(testCase)).hex();
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
		for (PromptAuthorKind kind : kinds)
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
	 * @param soundTests the test of each supplied failure whose test a test revision reported sound in the current
	 *   lineage, keyed by the failure identifier
	 * @return the manifest rejection reasons
	 */
	public List<String> violations(Set<String> received, List<Hunk> changed, Contract contract,
		FileInventory incumbent, Map<Path, FileContent> confirmedText, List<PromptAuthorConflict> conflicts,
		Map<String, String> soundTests)
	{
		List<String> violations = new ArrayList<>(hunkViolations(received, changed));
		violations.addAll(kindViolations(received));
		violations.addAll(testDefectViolations(soundTests));
		Map<String, FailureKind> classifications = new HashMap<>();
		for (PromptAuthorKind kind : kinds)
		{
			for (String failure : kind.failures())
				classifications.put(failure, kind.classification());
		}
		Set<String> cited = new HashSet<>();
		Set<String> requirementIds = new HashSet<>();
		for (Requirement requirement : contract.requirements())
			requirementIds.add(requirement.id());
		for (PromptAuthorConflict conflict : conflicts)
		{
			for (String failure : conflict.failures())
			{
				cited.add(failure);
				if (classifications.get(failure) != FailureKind.CONFLICT)
					violations.add("Failure " + failure + " cited by a conflict is not classified conflict");
			}
			for (String requirement : conflict.requirementIds())
			{
				if (!requirementIds.contains(requirement))
					violations.add("Unknown contract requirement cited by a conflict: " + requirement);
			}
			if (conflict.recommended() < 1 || conflict.recommended() > conflict.resolutions().size())
				violations.add("Conflict recommended position is outside its resolutions: " + conflict.recommended());
			for (PromptAuthorConflict.Quotation quotation : conflict.quotations())
			{
				Path path = incumbent.resolve(quotation.path()).orElse(quotation.path());
				FileContent content = confirmedText.get(path);
				if (!(incumbent.entries().get(path) instanceof FileEntry.RegularFile file) || content == null ||
					!file.contentHash().equals(content.hash()) ||
					!new String(content.bytes(), StandardCharsets.UTF_8).contains(quotation.text()))
				{
					violations.add("Conflict quotation is absent from the confirmed prompt system: " +
						PortablePaths.text(quotation.path()));
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
	 * Checks that each test defect kind names its fault and cites no failure of a test that a test revision reported
	 * sound.
	 *
	 * @param soundTests the test of each supplied failure whose test a test revision reported sound, keyed by the
	 *   failure identifier
	 * @return rejection reasons, empty if every test defect kind names its fault and concerns only unexamined tests
	 */
	private List<String> testDefectViolations(Map<String, String> soundTests)
	{
		List<String> violations = new ArrayList<>();
		for (PromptAuthorKind kind : kinds)
		{
			if (kind.classification() != FailureKind.TEST_DEFECT)
				continue;
			if (kind.fault().isBlank())
				violations.add("Kind '" + kind.name() + "' is classified test_defect but names no fault of the test");
			for (String failure : kind.failures())
			{
				String test = soundTests.get(failure);
				if (test != null)
				{
					violations.add("Failure " + failure + " is classified test_defect, but a test revision reported " +
						"its test " + test + " sound");
				}
			}
		}
		return violations;
	}

	/**
	 * Checks that the kinds partition the supplied failures and that each kind names only known hunks, at least one
	 * when it is classified routing or guidance.
	 *
	 * @param received the exact failure identifiers supplied to this prompt author
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
		for (PromptAuthorKind kind : kinds)
		{
			for (String failure : kind.failures())
			{
				if (!received.contains(failure))
					violations.add("Kind '" + kind.name() + "' names an unknown failure: " + failure);
				else if (!grouped.add(failure))
					repeated.add(failure);
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
		for (String failure : repeated)
			violations.add("Failure " + failure + " is in more than one kind");
		for (String failure : new TreeSet<>(received))
		{
			if (!grouped.contains(failure))
				violations.add("Failure " + failure + " is outside every kind");
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
			List<String> testIds = contract.tests().stream().map(PromptTest::id).toList();
			violations.add("Hunk at " + PortablePaths.text(evidence.path()) + " (working lines " + evidence.beforeStart() +
				".." + evidence.beforeEnd() + ", prompt author lines " + evidence.afterStart() + ".." + evidence.afterEnd() +
				") names the dissimilar case '" + evidence.dissimilar() + "', which is not a test of the confirmed " +
				"contract. Name the id of one of its tests: " + testIds);
		}
		return violations;
	}

	/**
	 * Checks completeness and citations, without judging the semantic quality of an edit.
	 *
	 * @param received the exact failure identifiers supplied to this prompt author
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
				violations.add("Manifest range contains no changed hunk: " + PortablePaths.text(evidence.path()));
			for (String failure : evidence.failures())
			{
				if (!received.contains(failure))
					violations.add("Unknown failure " + failure + " cited at " + PortablePaths.text(evidence.path()));
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
