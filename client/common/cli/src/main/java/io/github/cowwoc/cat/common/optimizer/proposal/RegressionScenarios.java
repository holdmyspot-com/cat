package io.github.cowwoc.cat.common.optimizer.proposal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.Category;
import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.contract.Requirement;
import io.github.cowwoc.cat.common.optimizer.contract.TestDefinition;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;
import java.util.stream.Stream;

/**
 * Recorded regression scenarios available to a deriver, associated with the guidance each scenario exercises.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param project the project root used to resolve proposal source paths
 * @param scenarios the recorded scenarios, in citation order
 */
public record RegressionScenarios(Path project, List<Scenario> scenarios)
{
	private static final String SCENARIO = "scenario.md";
	private static final Path TEST_RESOURCES = Path.of("src/test/resources");
	private static final Path MAIN_RESOURCES = Path.of("src/main/resources");

	/**
	 * One recorded scenario and the guidance whose contract requires it.
	 *
	 * @param source the absolute guidance path
	 * @param citation the path a test definition must cite
	 * @param content the complete Markdown content
	 */
	public record Scenario(Path source, String citation, String content)
	{
		/**
		 * Creates a recorded scenario.
		 *
		 * @param source the absolute guidance path
		 * @param citation the non-blank citation path
		 * @param content the complete Markdown content
		 * @throws NullPointerException if any argument is null
		 * @throws IllegalArgumentException if {@code source} is relative or {@code citation} is blank
		 */
		public Scenario
		{
			requireThat(source, "source").isNotNull();
			requireThat(source.isAbsolute(), "source.isAbsolute()").isTrue();
			requireThat(citation, "citation").isNotBlank();
			requireThat(content, "content").isNotNull();
			source = source.normalize();
		}
	}

	/**
	 * Creates the available scenario evidence.
	 *
	 * @param project the project root used to resolve proposal source paths
	 * @param scenarios the recorded scenarios, in citation order
	 * @throws NullPointerException if any argument is null or {@code scenarios} contains null
	 */
	public RegressionScenarios
	{
		requireThat(project, "project").isNotNull();
		project = project.toAbsolutePath().normalize();
		scenarios = List.copyOf(scenarios);
	}

	/**
	 * Reads the scenarios of existing project guidance, user rules and guidance packaged as a module resource.
	 * <p>
	 * A {@code .cat/tests} scenario directory holds its scenarios as Markdown files directly in it or as
	 * {@code scenario.md} files in its child directories. Guidance packaged as a module resource,
	 * {@code <module>/src/main/resources/<top>/<path>.md}, keeps its scenarios in
	 * {@code <module>/src/test/resources/<path>/<case>/scenario.md}. Other Markdown files hold fixtures.
	 *
	 * @param project the project root
	 * @param home the user home resolved at the environment boundary
	 * @return the available scenarios, sorted by citation
	 * @throws NullPointerException if any argument is null
	 * @throws IOException if an existing scenario tree cannot be read
	 */
	public static RegressionScenarios read(Path project, Path home) throws IOException
	{
		requireThat(project, "project").isNotNull();
		requireThat(home, "home").isNotNull();
		Path root = project.toAbsolutePath().normalize();
		Path user = home.toAbsolutePath().normalize();
		List<Scenario> scenarios = new ArrayList<>();
		readRoot(root, root, true, scenarios);
		readModules(root, scenarios);
		if (!user.equals(root))
			readRoot(root, user, false, scenarios);
		scenarios.sort(Comparator.comparing(Scenario::citation));
		return new RegressionScenarios(root, scenarios);
	}

	/**
	 * Reads the Markdown scenarios in directories that map to existing guidance.
	 *
	 * @param project the project used to render citations
	 * @param root the root of the scenario and rules trees
	 * @param otherGuidance whether project-relative guidance outside the rules tree is supported
	 * @param scenarios receives the discovered scenarios
	 * @throws IOException if a scenario tree or file cannot be read
	 */
	private static void readRoot(Path project, Path root, boolean otherGuidance, List<Scenario> scenarios)
		throws IOException
	{
		Path tests = root.resolve(".cat/tests");
		if (Files.notExists(tests))
			return;
		try (Stream<Path> paths = Files.walk(tests))
		{
			for (Path directory : paths.filter(Files::isDirectory).sorted().toList())
			{
				Path relative = tests.relativize(directory);
				Path source = root.resolve(".cat/rules").resolve(relative + ".md");
				if (!Files.isRegularFile(source) && otherGuidance)
					source = root.resolve(relative + ".md");
				if (!Files.isRegularFile(source))
					continue;
				readDirectory(project, directory, source, scenarios);
			}
		}
	}

	/**
	 * Reads direct Markdown scenarios and nested {@code scenario.md} files for one guidance.
	 *
	 * @param project the project used to render citations
	 * @param directory the guidance's scenario directory
	 * @param source the guidance file
	 * @param scenarios receives the discovered scenarios
	 * @throws IOException if the directory or a scenario cannot be read
	 */
	private static void readDirectory(Path project, Path directory, Path source, List<Scenario> scenarios)
		throws IOException
	{
		try (Stream<Path> children = Files.walk(directory))
		{
			// A scenario.md deeper than a child directory belongs to the nested guidance that names its parent's parent
			for (Path file : children.filter(Files::isRegularFile).
				filter(path -> path.getFileName().toString().endsWith(".md") &&
					(path.getParent().equals(directory) || isCaseScenario(directory, path))).
				sorted().toList())
			{
				addScenario(project, source, file, scenarios);
			}
		}
	}

	/**
	 * Reads the scenarios of guidance packaged as a module resource from each module's test resources. Hidden
	 * directories, such as {@code .git} and {@code .cat}, hold no modules.
	 *
	 * @param project the project root
	 * @param scenarios receives the discovered scenarios
	 * @throws IOException if a module tree cannot be read
	 */
	private static void readModules(Path project, List<Scenario> scenarios) throws IOException
	{
		List<Path> testResources = new ArrayList<>();
		Files.walkFileTree(project, new SimpleFileVisitor<>()
		{
			@Override
			public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
			{
				if (!directory.equals(project) && directory.getFileName().toString().startsWith("."))
					return FileVisitResult.SKIP_SUBTREE;
				if (directory.endsWith(TEST_RESOURCES))
				{
					testResources.add(directory);
					return FileVisitResult.SKIP_SUBTREE;
				}
				return FileVisitResult.CONTINUE;
			}
		});
		for (Path tests : testResources)
		{
			Path main = tests.getParent().getParent().getParent().resolve(MAIN_RESOURCES);
			if (!Files.isDirectory(main))
				continue;
			List<Path> tops;
			try (Stream<Path> children = Files.list(main))
			{
				tops = children.filter(Files::isDirectory).sorted().toList();
			}
			try (Stream<Path> files = Files.walk(tests))
			{
				for (Path file : files.filter(path -> path.getFileName().toString().equals(SCENARIO)).sorted().toList())
				{
					// <path>/<case>/scenario.md names the guidance <top>/<path>.md
					Path relative = tests.relativize(file.getParent().getParent());
					if (relative.toString().isEmpty())
						continue;
					for (Path top : tops)
					{
						Path source = top.resolve(relative + ".md");
						if (Files.isRegularFile(source))
						{
							addScenario(project, source, file, scenarios);
							break;
						}
					}
				}
			}
		}
	}

	/**
	 * Indicates whether a file is the {@code scenario.md} of a case directory directly inside a scenario directory.
	 *
	 * @param directory the scenario directory
	 * @param file a file below it
	 * @return whether {@code file} is {@code <directory>/<case>/scenario.md}
	 */
	private static boolean isCaseScenario(Path directory, Path file)
	{
		return file.getFileName().toString().equals(SCENARIO) && file.getParent().getParent().equals(directory);
	}

	/**
	 * Adds one scenario, cited relative to the project when it lies inside it.
	 *
	 * @param project the project used to render citations
	 * @param source the guidance file
	 * @param file the scenario file
	 * @param scenarios receives the scenario
	 * @throws IOException if the scenario cannot be read
	 */
	private static void addScenario(Path project, Path source, Path file, List<Scenario> scenarios)
		throws IOException
	{
		String citation;
		if (file.startsWith(project))
			citation = project.relativize(file).toString();
		else
			citation = file.toString();
		// The complete scenario is needed together to construct its fixed turns and expected behaviour.
		scenarios.add(new Scenario(source, citation, Files.readString(file)));
	}

	/**
	 * Returns every scenario's source, exact citation and complete content for the deriver.
	 *
	 * @return the scenario material, or {@code none} when no scenario is recorded
	 */
	public String prompt()
	{
		if (scenarios.isEmpty())
			return "none";
		StringJoiner text = new StringJoiner("\n\n");
		for (Scenario scenario : scenarios)
		{
			text.add("Guidance source: " + scenario.source() + "\nCitation: " + scenario.citation() +
				"\n\n" + scenario.content());
		}
		return text.toString();
	}

	/**
	 * Returns a refusal reason for every scenario of a named prompt source that no test cites.
	 *
	 * @param contract the proposed contract
	 * @param sources the instruction files whose loading was derived from documented rules
	 * @return the missing-scenario problems, in citation order
	 * @throws NullPointerException if any argument is null or contains null
	 */
	public List<String> missingCitations(Contract contract, List<DerivedSource> sources)
	{
		requireThat(contract, "contract").isNotNull();
		requireThat(sources, "sources").isNotNull();
		Set<Path> paths = new LinkedHashSet<>();
		for (Requirement requirement : contract.requirements())
			addSources(paths, requirement.source());
		for (DerivedSource source : sources)
			addSources(paths, source.path());
		Set<String> cited = new LinkedHashSet<>();
		for (Category category : contract.categories())
		{
			for (TestDefinition test : category.tests())
				cited.addAll(test.regressionScenarios());
		}
		List<String> problems = new ArrayList<>();
		for (Scenario scenario : scenarios)
		{
			if (paths.contains(scenario.source()) && !cited.contains(scenario.citation()))
				problems.add("Recorded regression scenario has no citing test: " + scenario.citation());
		}
		return List.copyOf(problems);
	}

	/**
	 * Resolves each file named by a source, including both ends of a symbolic link.
	 *
	 * @param paths receives the normalized absolute paths
	 * @param source a path, or a link path followed by its target using {@code ->}
	 */
	private void addSources(Set<Path> paths, String source)
	{
		for (String part : source.split("->"))
			paths.add(project.resolve(part.strip()).normalize());
	}
}
