package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
	 * Returns the test root that holds a prompt test directory: the {@code .cat/tests} directory or a module's
	 * {@code src/test/resources} directory that the path lies in.
	 *
	 * @param normalized a normalized prompt test directory, relative to the project
	 * @return the test root, relative to the project, or an empty value if no test root holds the directory
	 */
	private static Optional<Path> testRoot(Path normalized)
	{
		if (normalized.startsWith(TESTS))
			return Optional.of(TESTS);
		int count = normalized.getNameCount();
		// <module>/src/test/resources/<path>, where <module> may be empty
		for (int i = 0; i + 2 < count; ++i)
		{
			if (normalized.getName(i).toString().equals("src") && normalized.getName(i + 1).toString().equals("test") &&
				normalized.getName(i + 2).toString().equals("resources"))
			{
				return Optional.of(normalized.subpath(0, i + 3));
			}
		}
		return Optional.empty();
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
	 * the directory of a {@value PromptTestFiles#SCENARIO} or the file of a scenario in an earlier format, and each
	 * {@value PromptTestFiles#FIXTURES} directory inside the test root that a cited {@value PromptTestFiles#SCENARIO}'s
	 * fixture patterns name, and the target's prompt test configuration, which names the trial plugins.
	 *
	 * @param contract the contract
	 * @param target   the campaign target, relative to the project
	 * @param project  the project directory, which the cited scenarios are read from
	 * @return the excluded paths, relative to the project
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code target} is absolute
	 * @throws IOException              if a cited {@value PromptTestFiles#SCENARIO} cannot be read
	 */
	public static Set<Path> excluded(Contract contract, Path target, Path project) throws IOException
	{
		requireThat(project, "project").isNotNull();
		Set<Path> excluded = new LinkedHashSet<>();
		for (Location location : of(contract, target).values())
			excluded.add(location.directory());
		excluded.add(PromptConfiguration.location(target));
		for (PromptTest test : contract.tests())
		{
			for (String citation : test.regressionScenarios())
			{
				Path scenario = Path.of(citation).normalize();
				if (scenario.isAbsolute())
					continue;
				if (scenario.getFileName().toString().equals(PromptTestFiles.SCENARIO) && scenario.getParent() != null)
				{
					excluded.add(scenario.getParent());
					excluded.addAll(sharedFixtures(project, scenario));
				}
				else
					excluded.add(scenario);
			}
		}
		return excluded;
	}

	/**
	 * Returns the {@value PromptTestFiles#FIXTURES} directories outside a scenario's directory that its fixture
	 * patterns name inside its test root.
	 *
	 * @param project  the project directory
	 * @param scenario a {@value PromptTestFiles#SCENARIO}, normalized and relative to the project
	 * @return the directories, relative to the project, or an empty set if the file does not exist or its
	 *   {@code ## Fixtures} section cannot be read
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code scenario} is absolute
	 * @throws IOException              if the file cannot be read
	 */
	public static Set<Path> sharedFixtures(Path project, Path scenario) throws IOException
	{
		requireThat(project, "project").isNotNull();
		requireThat(scenario.isAbsolute(), "scenario.isAbsolute()").isFalse();
		Path file = project.resolve(scenario);
		if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
			return Set.of();
		List<String> patterns;
		try
		{
			patterns = Scenario.fixturePatterns(Files.readString(file));
		}
		catch (ScenarioFormatException _)
		{
			// Reading the prompt test reports the malformed section; staging leaves out the scenario's directory alone
			return Set.of();
		}
		Path directory = scenario.getParent();
		Optional<Path> testRoot = testRoot(directory);
		if (testRoot.isEmpty())
			return Set.of();
		Set<Path> shared = new LinkedHashSet<>();
		for (String pattern : patterns)
		{
			Path base = FixturePattern.parse(pattern).base(directory);
			if (base.startsWith(testRoot.get()) && !base.startsWith(directory))
				shared.add(base);
		}
		return shared;
	}
}
