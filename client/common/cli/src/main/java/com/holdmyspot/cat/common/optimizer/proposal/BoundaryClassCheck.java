package com.holdmyspot.cat.common.optimizer.proposal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.contract.Split;
import com.holdmyspot.cat.common.optimizer.contract.ValuesFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks the boundary classes of one prompt test's test cases: every test case has a class, test cases that share a
 * normalized expected outcome share a class, and every class can have a test case in each part of the split, so it
 * has at least 2 distinct expected outcomes, or one beside a committed outcome of the class.
 * <p>
 * No refusal names a part, so that a contract author who reads it cannot tell which test cases are held out.
 */
final class BoundaryClassCheck
{
	/**
	 * Prevents instantiation.
	 */
	private BoundaryClassCheck()
	{
	}

	/**
	 * One test case of a prompt test, as the check reads it.
	 *
	 * @param location        the test case's location, which starts the refusal of a test case without a class
	 * @param expectedOutcome the test case's expected outcome
	 * @param boundaryClass   the test case's boundary class, or an empty string if it has none
	 * @param committed       {@code true} if an earlier campaign committed the test case
	 */
	record Entry(String location, String expectedOutcome, String boundaryClass, boolean committed)
	{
		/**
		 * Creates a test case.
		 *
		 * @param location        the test case's location, which starts the refusal of a test case without a class
		 * @param expectedOutcome the test case's expected outcome
		 * @param boundaryClass   the test case's boundary class, or an empty string if it has none
		 * @param committed       {@code true} if an earlier campaign committed the test case
		 * @throws NullPointerException if any of the arguments are null
		 */
		Entry
		{
			requireThat(location, "location").isNotNull();
			requireThat(expectedOutcome, "expectedOutcome").isNotNull();
			requireThat(boundaryClass, "boundaryClass").isNotNull();
		}
	}

	/**
	 * Returns the problems of a prompt test's boundary classes.
	 *
	 * @param valuesFile the prompt test's values file, which starts each refusal that concerns a class
	 * @param testCases  the prompt test's test cases, committed ones included, in the values file's order
	 * @return the problems, in the order of the test cases that they concern
	 * @throws NullPointerException if any of the arguments are null
	 */
	static List<String> problems(String valuesFile, List<Entry> testCases)
	{
		requireThat(valuesFile, "valuesFile").isNotNull();
		requireThat(testCases, "testCases").isNotNull();
		List<String> problems = new ArrayList<>();
		Set<String> committedOutcomes = new LinkedHashSet<>();
		Map<String, Set<String>> classesByOutcome = new LinkedHashMap<>();
		Map<String, Set<String>> outcomesByClass = new LinkedHashMap<>();
		for (Entry testCase : testCases)
		{
			String outcome = Split.normalize(testCase.expectedOutcome());
			if (testCase.committed())
				committedOutcomes.add(outcome);
			String boundaryClass = testCase.boundaryClass();
			if (boundaryClass.isEmpty())
			{
				problems.add(testCase.location() + ": the test case has no " + ValuesFile.BOUNDARY_CLASS + ". Tag " +
					"every test case, committed ones included, with the kind of input boundary that it exercises, in " +
					"lowercase words joined by underscores");
				continue;
			}
			classesByOutcome.computeIfAbsent(outcome, _ -> new LinkedHashSet<>()).add(boundaryClass);
			outcomesByClass.computeIfAbsent(boundaryClass, _ -> new LinkedHashSet<>()).add(outcome);
		}
		for (Map.Entry<String, Set<String>> entry : classesByOutcome.entrySet())
		{
			if (entry.getValue().size() > 1)
			{
				problems.add(valuesFile + ": the test cases with the expected outcome \"" + entry.getKey() + "\" have " +
					"the boundary classes " + names(entry.getValue()) + ". Test cases that share an expected outcome, " +
					"ignoring case and spacing, share a class: give them one class, or write each expected outcome as " +
					"its own test case's result");
			}
		}
		for (Map.Entry<String, Set<String>> entry : outcomesByClass.entrySet())
		{
			String boundaryClass = entry.getKey();
			Set<String> outcomes = entry.getValue();
			if (committedOutcomes.containsAll(outcomes))
			{
				problems.add(valuesFile + ": the boundary class `" + boundaryClass + "` holds only test cases that " +
					"earlier campaigns committed. Add a new test case of the class whose expected outcome no committed " +
					"test case has, or tag its test cases with a wider class");
			}
			else if (outcomes.size() == 1)
			{
				problems.add(valuesFile + ": the boundary class `" + boundaryClass + "` has a single case: its test " +
					"cases share one expected outcome, ignoring case and spacing, and none of them is committed. Add a " +
					"test case of the class with a different expected outcome, writing the outcome as that test case's " +
					"own result, such as the day as well as the time, or tag its test cases with a wider class");
			}
		}
		return problems;
	}

	/**
	 * Returns boundary classes as a refusal lists them.
	 *
	 * @param classes two or more classes
	 * @return the classes, each in backticks, joined by commas and a final "and"
	 */
	private static String names(Set<String> classes)
	{
		List<String> quoted = classes.stream().map(name -> "`" + name + "`").toList();
		return String.join(", ", quoted.subList(0, quoted.size() - 1)) + " and " + quoted.getLast();
	}
}
