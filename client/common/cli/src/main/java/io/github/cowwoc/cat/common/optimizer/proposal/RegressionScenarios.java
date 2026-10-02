package io.github.cowwoc.cat.common.optimizer.proposal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.Category;
import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.contract.Requirement;
import io.github.cowwoc.cat.common.optimizer.contract.TestDefinition;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
	 * Reads scenarios beside existing project guidance and user rules. Markdown files directly in a guidance's
	 * scenario directory and {@code scenario.md} files in child directories are scenarios; other nested Markdown
	 * files may hold their fixtures.
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
			for (Path file : children.filter(Files::isRegularFile).
				filter(path -> path.getFileName().toString().endsWith(".md") &&
					(path.getParent().equals(directory) || path.getFileName().toString().equals("scenario.md"))).
				sorted().toList())
			{
				String citation;
				if (file.startsWith(project))
					citation = project.relativize(file).toString();
				else
					citation = file.toString();
				// The complete scenario is needed together to construct its fixed turns and expected behaviour.
				scenarios.add(new Scenario(source, citation, Files.readString(file)));
			}
		}
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
