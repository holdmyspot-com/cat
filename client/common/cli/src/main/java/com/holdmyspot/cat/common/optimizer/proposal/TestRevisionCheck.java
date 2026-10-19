package com.holdmyspot.cat.common.optimizer.proposal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.PortablePaths;
import com.holdmyspot.cat.common.optimizer.campaign.PendingDecision.TestRevisionBlocked;
import com.holdmyspot.cat.common.optimizer.contract.InvalidProposalException;
import com.holdmyspot.cat.common.optimizer.contract.Part;
import com.holdmyspot.cat.common.optimizer.contract.PromptTest;
import com.holdmyspot.cat.common.optimizer.contract.Split;
import com.holdmyspot.cat.common.optimizer.contract.TestCase;
import com.holdmyspot.cat.common.optimizer.contract.TestValueJson;
import com.holdmyspot.cat.common.optimizer.proposal.TestRevisionProtocol.Result;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Validates the result file of a session that examines one prompt test of a confirmed contract (a test revision),
 * together with the files of a corrected test in the session's draft directory.
 * <p>
 * A corrected test's item replaces the examined test's item in the confirmed proposal file, and the result is
 * validated as a proposal whose other prompt tests are the confirmed ones. A corrected test must keep the examined
 * test's directory, requirements and absent triggers, and none of its test cases may repeat a confirmed one. The
 * session end and the contract tools' {@code contract check} both validate through this class.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 */
final class TestRevisionCheck
{
	/**
	 * The result of a session that corrected the test.
	 */
	static final String REVISED = "revised";
	private static final String SOUND = "sound";
	private static final String UNPROVABLE = "unprovable";
	private static final String REQUIREMENT_CHANGE = "requirement_change";
	private final ProposalCheck check;
	private final ObjectNode proposalFile;
	/**
	 * The confirmed prompt tests other than the examined one, each in its completed form, by directory.
	 */
	private final Map<Path, ObjectNode> fixed = new HashMap<>();
	private final String test;
	private final Path directory;
	private final List<String> requirementIds;
	private final List<String> absentTriggers;
	private final List<TestCase> confirmedTestCases;

	/**
	 * Creates a validator.
	 *
	 * @param check        validates proposal files against the target's recorded prompt tests
	 * @param proposalFile the confirmed proposal file
	 * @param tests        the confirmed prompt tests, each in the form that a completed proposal holds it
	 * @param test         the identifier of the examined prompt test
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code tests} holds no test with the identifier {@code test} or a test
	 *                                  without a draft directory
	 */
	TestRevisionCheck(ProposalCheck check, ObjectNode proposalFile, ArrayNode tests, String test)
	{
		requireThat(check, "check").isNotNull();
		requireThat(proposalFile, "proposalFile").isNotNull();
		requireThat(tests, "tests").isNotNull();
		requireThat(test, "test").isNotBlank();
		this.check = check;
		this.proposalFile = proposalFile.deepCopy();
		this.test = test;
		ObjectNode examined = null;
		for (JsonNode node : tests)
		{
			JsonNode draft = node.get("draft");
			if (draft == null)
				throw new IllegalArgumentException("The confirmed test " + node.get("id") + " has no draft directory");
			if (node.get("id").asString().equals(test))
				examined = (ObjectNode) node;
			else
				fixed.put(Path.of(draft.get("directory").asString()), ((ObjectNode) node).deepCopy());
		}
		if (examined == null)
			throw new IllegalArgumentException("The confirmed contract has no test " + test);
		this.directory = Path.of(examined.get("draft").get("directory").asString());
		this.requirementIds = strings(examined.get("requirement_ids"));
		this.absentTriggers = strings(examined.get("absent_triggers"));
		List<TestCase> testCases = new ArrayList<>();
		for (String name : List.of("committed_test_cases", "test_cases"))
		{
			for (JsonNode testCase : examined.path(name))
			{
				testCases.add(new TestCase(TestValueJson.read(testCase.get("values")),
					testCase.get(TestCase.EXPECTED_OUTCOME).asString()));
			}
		}
		this.confirmedTestCases = List.copyOf(testCases);
	}

	/**
	 * Returns the strings of a JSON array.
	 *
	 * @param array the array
	 * @return the strings
	 */
	private static List<String> strings(JsonNode array)
	{
		List<String> strings = new ArrayList<>();
		for (JsonNode element : array)
			strings.add(element.asString());
		return strings;
	}

	/**
	 * Returns the examined prompt test's directory.
	 *
	 * @return the directory, relative to the draft directories
	 */
	Path directory()
	{
		return directory;
	}

	/**
	 * Returns the problems of a corrected test that changed files outside the examined test's directory.
	 *
	 * @param draftName the name of the session's draft directory, relative to its project
	 * @param changed   the changed files, relative to the draft directory
	 * @return a problem for each file
	 */
	List<String> outsideProblems(String draftName, List<Path> changed)
	{
		String testDirectory = PortablePaths.text(directory);
		List<String> problems = new ArrayList<>();
		for (Path file : changed)
		{
			problems.add(draftName + "/" + PortablePaths.text(file) + ": a correction changes only the files in " +
				"the test's directory `" + draftName + "/" + testDirectory + "`; restore this file as the session " +
				"received it");
		}
		return problems;
	}

	/**
	 * Validates a test revision's result.
	 *
	 * @param output          the result file's object, which this method does not change
	 * @param project         the session's project, which holds the draft directory; problems name files relative
	 *                        to it
	 * @param draft           the session's draft directory, which holds the examined prompt test's files
	 * @param observedSources the prompt sources that the harness reported loading when the contract author ran
	 * @param profiles        the profile evidence that the confirmed proposal was derived with
	 * @param salt            the salt that validates the corrected contract
	 * @param split           {@code true} to refuse a corrected test with fewer than
	 *                        {@value Proposal#MINIMUM_OUTCOMES} distinct expected outcomes in a part of the split that
	 *                        {@code salt} draws
	 * @return the outcome; a corrected test's proposal has the session's draft directory, which holds only the
	 *   examined prompt test, and no derived sources
	 * @throws InvalidProposalException if the object has an unknown {@code result}, blank {@code reasons}, a
	 *                                  {@code test} that does not match its {@code result}, or a
	 *                                  {@code proposed_change} that does not match it; or if the corrected test
	 *                                  changes the examined test's directory, requirements or absent triggers,
	 *                                  repeats one of its test cases, lacks distinct expected outcomes in a part of
	 *                                  the split, or makes the contract invalid. The exception lists every problem
	 *                                  found.
	 */
	Result validate(ObjectNode output, Path project, Path draft, String observedSources, ProfileEvidence profiles,
		long salt, boolean split) throws InvalidProposalException
	{
		List<String> problems = new ArrayList<>();
		String kind = text(output, "result");
		String reasons = text(output, "reasons");
		String proposedChange = text(output, "proposed_change");
		JsonNode corrected = output.get("test");
		if (reasons == null)
			problems.add("$.reasons: state the rule that the test breaks or follows, and the evidence for it");
		Result outcome = null;
		switch (kind)
		{
			case REVISED ->
			{
				requireNull(proposedChange, "proposed_change", kind, problems);
				if (corrected instanceof ObjectNode item)
				{
					Optional<DerivedProposal> revised = revised(item, project, draft, observedSources, profiles, salt,
						split, problems);
					if (revised.isPresent() && reasons != null)
						outcome = new Result.Revised(revised.get(), reasons);
				}
				else
				{
					problems.add("$.test: a revised result holds the corrected test's item, as the proposal file's " +
						"tests hold it");
				}
			}
			case SOUND ->
			{
				requireNull(corrected, "test", kind, problems);
				requireNull(proposedChange, "proposed_change", kind, problems);
				if (reasons != null)
					outcome = new Result.Sound(reasons);
			}
			case UNPROVABLE ->
			{
				requireNull(corrected, "test", kind, problems);
				if (reasons != null)
				{
					outcome = new Result.Blocked(TestRevisionBlocked.Reason.UNPROVABLE, reasons,
						Optional.ofNullable(proposedChange).orElse(""));
				}
			}
			case REQUIREMENT_CHANGE ->
			{
				requireNull(corrected, "test", kind, problems);
				if (proposedChange == null)
				{
					problems.add("$.proposed_change: a requirement_change result states the requirement change as " +
						"feedback on the confirmed contract");
				}
				else if (reasons != null)
				{
					outcome = new Result.Blocked(TestRevisionBlocked.Reason.REQUIREMENT_CHANGE, reasons,
						proposedChange);
				}
			}
			case null, default -> problems.add("$.result: must be one of " + REVISED + ", " + SOUND + ", " +
				UNPROVABLE + " or " + REQUIREMENT_CHANGE);
		}
		if (!problems.isEmpty())
			throw new InvalidProposalException(problems);
		return outcome;
	}

	/**
	 * Returns a non-blank string property.
	 *
	 * @param output the session's output
	 * @param name   the property's name
	 * @return the property's value, or {@code null} if it is absent, not a string or blank
	 */
	private static String text(ObjectNode output, String name)
	{
		JsonNode value = output.get(name);
		if (value == null || !value.isString() || value.asString().isBlank())
			return null;
		return value.asString();
	}

	/**
	 * Adds a problem if a property that the result's kind leaves out has a value.
	 *
	 * @param value    the property's value, or {@code null}
	 * @param name     the property's name
	 * @param kind     the result's kind
	 * @param problems the problems found so far
	 */
	private static void requireNull(Object value, String name, String kind, List<String> problems)
	{
		if (value != null && !(value instanceof JsonNode node && node.isNull()))
			problems.add("$." + name + ": must be null when the result is " + kind);
	}

	/**
	 * Replaces the examined test's item with a corrected one in the confirmed proposal file and validates the result
	 * with the corrected test's files.
	 *
	 * @param corrected       the corrected test's item
	 * @param project         the session's project
	 * @param draft           the session's draft directory
	 * @param observedSources the prompt sources that the harness reported loading when the contract author ran
	 * @param profiles        the profile evidence that the confirmed proposal was derived with
	 * @param salt            the salt that validates the corrected contract
	 * @param split           {@code true} to check the corrected test's expected outcomes in each part of the split
	 * @param problems        the problems found so far, which this method adds to
	 * @return the revised proposal, or empty if it has a problem
	 */
	private Optional<DerivedProposal> revised(ObjectNode corrected, Path project, Path draft, String observedSources,
		ProfileEvidence profiles, long salt, boolean split, List<String> problems)
	{
		String directoryText = PortablePaths.text(directory);
		JsonNode correctedDirectory = corrected.get("directory");
		if (correctedDirectory == null || !correctedDirectory.isString() ||
			!correctedDirectory.asString().equals(directoryText))
		{
			problems.add("$.test.directory: a revision keeps the directory `" + directoryText + "`");
			return Optional.empty();
		}

		ObjectNode spliced = proposalFile.deepCopy();
		ArrayNode items = (ArrayNode) spliced.get("tests");
		int index = -1;
		for (int i = 0; i < items.size(); ++i)
		{
			if (items.get(i).path("directory").asString().equals(directoryText))
			{
				items.set(i, corrected.deepCopy());
				index = i;
			}
		}
		if (index == -1)
			throw new AssertionError("The confirmed proposal file has no item for " + directoryText);
		DerivedProposal revised;
		try
		{
			revised = check.check(spliced, project, draft, observedSources, profiles, salt, fixed);
		}
		catch (InvalidProposalException e)
		{
			String location = "$.tests[" + index + "]";
			for (String problem : e.problems())
			{
				if (problem.startsWith(location))
					problems.add("$.test" + problem.substring(location.length()));
				else
					problems.add(problem);
			}
			return Optional.empty();
		}

		PromptTest after = revised.contract().findTest(test).orElseThrow();
		List<String> changes = new ArrayList<>();
		if (!after.requirementIds().equals(requirementIds))
		{
			changes.add("$.test.requirement_ids: a revision keeps the requirements " + requirementIds + " that the " +
				"user confirmed. A test that needs other requirements is a requirement_change result");
		}
		if (!after.absentTriggers().equals(absentTriggers))
		{
			changes.add("$.test.absent_triggers: a revision keeps the absent triggers " + absentTriggers +
				" that the user confirmed");
		}
		for (TestCase testCase : after.testCases())
		{
			if (confirmedTestCases.contains(testCase))
			{
				changes.add(PortablePaths.text(project.relativize(draft.resolve(directory))) + "/values.json: the " +
					"test case whose expected outcome is `" + testCase.expectedOutcome() + "` repeats a test case of " +
					"the confirmed test; write new test cases");
			}
		}
		if (split)
		{
			Split parts = revised.contract().split();
			int development = parts.outcomeCount(after, Part.DEVELOPMENT);
			int heldOut = parts.outcomeCount(after, Part.HELD_OUT);
			if (development < Proposal.MINIMUM_OUTCOMES || heldOut < Proposal.MINIMUM_OUTCOMES)
			{
				changes.add(PortablePaths.text(project.relativize(draft.resolve(directory))) + "/values.json: the " +
					"test has " + development + " distinct expected outcomes in the development part and " + heldOut +
					" in the held-out part, but each part needs at least " + Proposal.MINIMUM_OUTCOMES +
					"; write test cases with more expected outcomes");
			}
		}
		if (!changes.isEmpty())
		{
			problems.addAll(changes);
			return Optional.empty();
		}
		return Optional.of(revised);
	}
}
