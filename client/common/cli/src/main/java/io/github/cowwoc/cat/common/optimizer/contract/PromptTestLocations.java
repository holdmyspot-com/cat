package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Where a campaign's prompt tests are written as regression scenarios: each prompt test's directory, which holds its
 * {@value PromptTestFiles#SCENARIO} and {@value ValuesFile#NAME}, relative to the project.
 */
public final class PromptTestLocations
{
	/**
	 * The directory of the project's rules.
	 */
	private static final Path RULES = Path.of(".cat/rules");
	/**
	 * The directory of the project's regression scenarios.
	 */
	private static final Path TESTS = Path.of(".cat/tests");
	/**
	 * The file name suffix of guidance that maps to a scenario directory.
	 */
	private static final String MARKDOWN = ".md";

	/**
	 * Prevents construction.
	 */
	private PromptTestLocations()
	{
	}

	/**
	 * Where a prompt test lies.
	 *
	 * @param directory the prompt test's directory, relative to the project
	 * @param created   {@code true} if delivery creates the directory, or {@code false} if it edits the regression
	 *                  scenario that the prompt test was derived from
	 */
	public record Location(Path directory, boolean created)
	{
		/**
		 * Creates a location.
		 *
		 * @param directory the prompt test's directory, relative to the project
		 * @param created   {@code true} if delivery creates the directory, or {@code false} if it edits the regression
		 *                  scenario that the prompt test was derived from
		 * @throws NullPointerException     if {@code directory} is null
		 * @throws IllegalArgumentException if {@code directory} is absolute
		 */
		public Location
		{
			requireThat(directory.isAbsolute(), "directory.isAbsolute()").isFalse();
		}
	}

	/**
	 * Returns the directory in which the regression scenarios of a guidance file are read. A rule
	 * {@code .cat/rules/<path>.md} maps to {@code .cat/tests/<path>}, guidance packaged as a module resource
	 * {@code <module>/src/main/resources/<top>/<path>.md} maps to {@code <module>/src/test/resources/<path>}, and other
	 * guidance {@code <path>.md} maps to {@code .cat/tests/<path>}. A target that does not end in {@code .md} keeps its
	 * whole name, and its scenarios are not read back as regression scenarios.
	 *
	 * @param target the guidance file, relative to the project
	 * @return the scenario directory, relative to the project
	 * @throws NullPointerException     if {@code target} is null
	 * @throws IllegalArgumentException if {@code target} is absolute
	 */
	public static Path scenarioDirectory(Path target)
	{
		requireThat(target.isAbsolute(), "target.isAbsolute()").isFalse();
		Path normalized = target.normalize();
		if (normalized.startsWith(RULES) && normalized.getNameCount() > RULES.getNameCount())
			return TESTS.resolve(withoutSuffix(RULES.relativize(normalized)));

		int count = normalized.getNameCount();
		// <module>/src/main/resources/<top>/<path>.md, where <module> may be empty
		for (int i = 0; i + 4 < count; ++i)
		{
			if (normalized.getName(i).toString().equals("src") && normalized.getName(i + 1).toString().equals("main") &&
				normalized.getName(i + 2).toString().equals("resources"))
			{
				Path testResources = Path.of("src", "test", "resources");
				if (i > 0)
					testResources = normalized.subpath(0, i).resolve(testResources);
				return testResources.resolve(withoutSuffix(normalized.subpath(i + 4, count)));
			}
		}
		return TESTS.resolve(withoutSuffix(normalized));
	}

	/**
	 * Removes the Markdown suffix from a path.
	 *
	 * @param path a path
	 * @return {@code path} without a trailing {@code .md}
	 */
	private static Path withoutSuffix(Path path)
	{
		String text = path.toString();
		if (text.endsWith(MARKDOWN))
			return Path.of(text.substring(0, text.length() - MARKDOWN.length()));
		return path;
	}

	/**
	 * Returns the location of each prompt test of a contract. A prompt test that alone cites exactly one
	 * {@value PromptTestFiles#SCENARIO} directly inside a case directory of the target's scenario directory is edited
	 * there. Every other prompt test is created in the target's scenario directory under its identifier.
	 *
	 * @param contract the contract
	 * @param target   the campaign target, relative to the project
	 * @return each prompt test's identifier and location, in contract order
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code target} is absolute
	 */
	public static Map<String, Location> of(Contract contract, Path target)
	{
		requireThat(contract, "contract").isNotNull();
		Path scenarioDirectory = scenarioDirectory(target);
		Map<String, Integer> citers = new HashMap<>();
		for (PromptTest test : contract.tests())
		{
			for (String citation : test.regressionScenarios())
				citers.merge(citation, 1, Integer::sum);
		}

		Map<String, Location> locations = new LinkedHashMap<>();
		for (PromptTest test : contract.tests())
		{
			Location location = new Location(scenarioDirectory.resolve(test.id()), true);
			if (test.regressionScenarios().size() == 1)
			{
				String citation = test.regressionScenarios().getFirst();
				Path scenario = Path.of(citation);
				if (!scenario.isAbsolute() && citers.get(citation) == 1 &&
					scenario.getFileName().toString().equals(PromptTestFiles.SCENARIO) &&
					scenario.getParent() != null && scenarioDirectory.equals(scenario.getParent().getParent()))
				{
					location = new Location(scenario.getParent(), false);
				}
			}
			locations.put(test.id(), location);
		}
		return locations;
	}

	/**
	 * Returns the paths that staging leaves out of a candidate so that a trial cannot read the prompt tests: the
	 * directory of each prompt test and each regression scenario that a prompt test cites inside the project, which is
	 * the directory of a {@value PromptTestFiles#SCENARIO} or the file of a scenario in an earlier format.
	 *
	 * @param contract the contract
	 * @param target   the campaign target, relative to the project
	 * @return the excluded paths, relative to the project
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code target} is absolute
	 */
	public static Set<Path> excluded(Contract contract, Path target)
	{
		Set<Path> excluded = new LinkedHashSet<>();
		for (Location location : of(contract, target).values())
			excluded.add(location.directory());
		for (PromptTest test : contract.tests())
		{
			for (String citation : test.regressionScenarios())
			{
				Path scenario = Path.of(citation).normalize();
				if (scenario.isAbsolute())
					continue;
				if (scenario.getFileName().toString().equals(PromptTestFiles.SCENARIO) && scenario.getParent() != null)
					excluded.add(scenario.getParent());
				else
					excluded.add(scenario);
			}
		}
		return excluded;
	}
}
