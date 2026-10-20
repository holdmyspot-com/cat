package com.holdmyspot.cat.common.optimizer.proposal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.PortablePaths;
import com.holdmyspot.cat.common.optimizer.contract.WorkspaceFile;
import com.holdmyspot.cat.common.optimizer.contract.PromptTestFiles;
import com.holdmyspot.cat.common.optimizer.contract.ScenarioFormatException;
import com.holdmyspot.cat.common.optimizer.contract.TestCase;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import tools.jackson.databind.json.JsonMapper;

/**
 * Recorded regression scenarios available to a contract author, associated with the guidance each scenario exercises.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param project the project root used to resolve proposal source paths
 * @param scenarios the recorded scenarios, in citation order
 */
public record RegressionScenarios(Path project, List<Scenario> scenarios)
{
	private static final String SCENARIO = PromptTestFiles.SCENARIO;
	private static final Path TEST_RESOURCES = Path.of("src/test/resources");
	private static final Path MAIN_RESOURCES = Path.of("src/main/resources");

	/**
	 * The format of a recorded scenario.
	 */
	public sealed interface Format
	{
	}

	/**
	 * A prompt test directory in the current format.
	 *
	 * @param files              the prompt test's scenario, workspace files and committed test cases
	 * @param textWorkspaceFiles each workspace file that is UTF-8 text, with its content and the file that it is copied
	 *                           from, in path order
	 */
	public record Current(PromptTestFiles files, List<WorkspaceFile> textWorkspaceFiles) implements Format
	{
		/**
		 * Creates a current-format scenario.
		 *
		 * @param files              the prompt test's scenario, workspace files and committed test cases
		 * @param textWorkspaceFiles each workspace file that is UTF-8 text, with its content and the file that it is copied
		 *                           from, in path order
		 * @throws NullPointerException if any of the arguments are null
		 */
		public Current
		{
			requireThat(files, "files").isNotNull();
			textWorkspaceFiles = List.copyOf(textWorkspaceFiles);
		}
	}

	/**
	 * A scenario written before the current format, which the contract author converts.
	 *
	 * @param problem why the scenario does not follow the current format
	 */
	public record Earlier(String problem) implements Format
	{
		/**
		 * Creates an earlier-format scenario.
		 *
		 * @param problem why the scenario does not follow the current format
		 * @throws NullPointerException     if {@code problem} is null
		 * @throws IllegalArgumentException if {@code problem} is blank
		 */
		public Earlier
		{
			requireThat(problem, "problem").isNotBlank();
		}
	}

	/**
	 * One recorded scenario and the guidance whose contract requires it.
	 *
	 * @param source   the absolute guidance path
	 * @param citation the path a prompt test must cite
	 * @param content  the complete Markdown content
	 * @param format   the scenario's format
	 */
	public record Scenario(Path source, String citation, String content, Format format)
	{
		/**
		 * Creates a recorded scenario.
		 *
		 * @param source   the absolute guidance path
		 * @param citation the non-blank citation path
		 * @param content  the complete Markdown content
		 * @param format   the scenario's format
		 * @throws NullPointerException     if any argument is null
		 * @throws IllegalArgumentException if {@code source} is relative or {@code citation} is blank
		 */
		public Scenario
		{
			requireThat(source, "source").isNotNull();
			requireThat(source.isAbsolute(), "source.isAbsolute()").isTrue();
			requireThat(citation, "citation").isNotBlank();
			requireThat(content, "content").isNotNull();
			requireThat(format, "format").isNotNull();
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
	 * {@code <module>/src/test/resources/<path>/<case>/scenario.md}. Other Markdown files hold workspace files.
	 * <p>
	 * A {@code <case>/scenario.md} that parses is a prompt test directory in the current format, read with its workspace
	 * files and values file, unless the project's Git index records a symbolic link in its
	 * {@value PromptTestFiles#WORKSPACE} directory where the checkout holds a regular file. Any other scenario is in an
	 * earlier format, kept with the reason for its conversion.
	 *
	 * @param json         the JSON mapper that reads values files
	 * @param project      the project root
	 * @param home         the user home resolved at the environment boundary
	 * @param trackedLinks the files, relative to {@code project}, that the project's Git index records as symbolic
	 *                     links
	 * @return the available scenarios, sorted by citation
	 * @throws NullPointerException if any argument is null
	 * @throws IOException          if an existing scenario tree cannot be read
	 */
	public static RegressionScenarios read(JsonMapper json, Path project, Path home, Set<Path> trackedLinks)
		throws IOException
	{
		requireThat(json, "json").isNotNull();
		requireThat(project, "project").isNotNull();
		requireThat(home, "home").isNotNull();
		requireThat(trackedLinks, "trackedLinks").isNotNull();
		Path root = project.toAbsolutePath().normalize();
		Path user = home.toAbsolutePath().normalize();
		List<Scenario> scenarios = new ArrayList<>();
		readRoot(json, root, root, true, scenarios);
		readModules(json, root, scenarios);
		refuseLinksCheckedOutAsFiles(root, trackedLinks, scenarios);
		if (!user.equals(root))
			readRoot(json, root, user, false, scenarios);
		scenarios.sort(Comparator.comparing(Scenario::citation));
		return new RegressionScenarios(root, scenarios);
	}

	/**
	 * Keeps for conversion each prompt test of the project whose {@value PromptTestFiles#WORKSPACE} directory holds a
	 * regular file where the project's Git index records a symbolic link. Git writes such a file, holding the link's
	 * target, when it checks out a link with {@code core.symlinks} false, and the prompt test would read the target's
	 * path as the file's content.
	 *
	 * @param project      the project root
	 * @param trackedLinks the files, relative to {@code project}, that the project's Git index records as symbolic
	 *                     links
	 * @param scenarios    the scenarios read from the project; each affected one is replaced
	 */
	private static void refuseLinksCheckedOutAsFiles(Path project, Set<Path> trackedLinks, List<Scenario> scenarios)
	{
		List<Path> linksAsFiles = new ArrayList<>();
		for (Path link : trackedLinks)
		{
			Path file = project.resolve(link);
			if (Files.exists(file, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(file))
				linksAsFiles.add(file);
		}
		for (int i = 0; i < scenarios.size(); ++i)
		{
			Scenario scenario = scenarios.get(i);
			if (!(scenario.format() instanceof Current current))
				continue;
			Path workspace = current.files().directory().resolve(PromptTestFiles.WORKSPACE);
			Optional<Path> linkAsFile = linksAsFiles.stream().filter(file -> file.startsWith(workspace)).findFirst();
			if (linkAsFile.isEmpty())
				continue;
			String path = cited(project, linkAsFile.get());
			scenarios.set(i, new Scenario(scenario.source(), scenario.citation(), scenario.content(),
				new Earlier(path + ": Git records a symbolic link here, but the checkout holds a regular file. " +
					"Enable symbolic links for Git: on Windows, turn on Developer Mode, then run `git config " +
					"core.symlinks true` and `git checkout -- " + path + "`.")));
		}
	}

	/**
	 * Reads the Markdown scenarios in directories that map to existing guidance.
	 *
	 * @param json the JSON mapper that reads values files
	 * @param project the project used to render citations
	 * @param root the root of the scenario and rules trees
	 * @param otherGuidance whether project-relative guidance outside the rules tree is supported
	 * @param scenarios receives the discovered scenarios
	 * @throws IOException if a scenario tree or file cannot be read
	 */
	private static void readRoot(JsonMapper json, Path project, Path root, boolean otherGuidance,
		List<Scenario> scenarios)
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
				readDirectory(json, project, tests, directory, source, scenarios);
			}
		}
	}

	/**
	 * Reads direct Markdown scenarios and nested {@code scenario.md} files for one guidance.
	 *
	 * @param json the JSON mapper that reads values files
	 * @param project the project used to render citations
	 * @param tests the test root that holds the scenario directory
	 * @param directory the guidance's scenario directory
	 * @param source the guidance file
	 * @param scenarios receives the discovered scenarios
	 * @throws IOException if the directory or a scenario cannot be read
	 */
	private static void readDirectory(JsonMapper json, Path project, Path tests, Path directory, Path source,
		List<Scenario> scenarios)
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
				addScenario(json, project, tests, source, file, scenarios);
			}
		}
	}

	/**
	 * Reads the scenarios of guidance packaged as a module resource from each module's test resources. Hidden
	 * directories, such as {@code .git} and {@code .cat}, hold no modules.
	 *
	 * @param json the JSON mapper that reads values files
	 * @param project the project root
	 * @param scenarios receives the discovered scenarios
	 * @throws IOException if a module tree cannot be read
	 */
	private static void readModules(JsonMapper json, Path project, List<Scenario> scenarios) throws IOException
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

			@Override
			public FileVisitResult visitFileFailed(Path file, IOException failure) throws IOException
			{
				// A build writing into the tree, such as a test runner replacing its reports, can delete an entry
				// after its directory was listed; a deleted entry holds no scenario
				if (failure instanceof NoSuchFileException)
					return FileVisitResult.CONTINUE;
				throw failure;
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
							addScenario(json, project, tests, source, file, scenarios);
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
	 * @param json      the JSON mapper that reads values files
	 * @param project   the project used to render citations
	 * @param tests     the test root that holds the scenario
	 * @param source    the guidance file
	 * @param file      the scenario file
	 * @param scenarios receives the scenario
	 * @throws IOException if the scenario, its workspace files or its values file cannot be read
	 */
	private static void addScenario(JsonMapper json, Path project, Path tests, Path source, Path file,
		List<Scenario> scenarios)
		throws IOException
	{
		String citation = cited(project, file);
		Format format;
		if (file.getFileName().toString().equals(SCENARIO))
		{
			try
			{
				PromptTestFiles files = PromptTestFiles.read(json, project, tests, file.getParent());
				List<WorkspaceFile> textWorkspaceFiles = new ArrayList<>();
				for (PromptTestFiles.WorkspaceEntry workspaceFile : files.workspaceFiles())
				{
					Path workspaceFileSource = files.source(workspaceFile);
					if (workspaceFile.link().isPresent())
					{
						textWorkspaceFiles.add(new WorkspaceFile(workspaceFile.path(), workspaceFile.link().get(),
							cited(project, workspaceFileSource), true));
					}
					else if (workspaceFile.text())
					{
						textWorkspaceFiles.add(new WorkspaceFile(workspaceFile.path(), Files.readString(workspaceFileSource),
							cited(project, workspaceFileSource)));
					}
				}
				format = new Current(files, textWorkspaceFiles);
			}
			catch (ScenarioFormatException e)
			{
				format = new Earlier(e.getMessage());
			}
		}
		else
		{
			format = new Earlier(citation + ": a prompt test is a directory that holds " + SCENARIO +
				", but this scenario is a file of its guidance's scenario directory");
		}
		// The complete scenario is needed together to construct its fixed turns and expected behaviour.
		scenarios.add(new Scenario(source, citation, Files.readString(file), format));
	}

	/**
	 * Returns the path of a file relative to the project when it lies inside it.
	 *
	 * @param project the project
	 * @param file    the file
	 * @return the path, relative to {@code project} when it lies inside it, and absolute otherwise
	 */
	private static String cited(Path project, Path file)
	{
		if (file.startsWith(project))
			return PortablePaths.text(project.relativize(file));
		return file.toString();
	}

	/**
	 * Returns the test cases that earlier campaigns committed to the values files of the cited scenarios.
	 *
	 * @param citations the citations of the scenarios that one prompt test covers
	 * @return the committed test cases, in citation order, each once
	 * @throws NullPointerException if {@code citations} is null
	 */
	public List<TestCase> committedTestCases(List<String> citations)
	{
		requireThat(citations, "citations").isNotNull();
		Set<TestCase> testCases = new LinkedHashSet<>();
		for (String citation : citations)
		{
			for (Scenario scenario : scenarios)
			{
				if (scenario.citation().equals(citation) && scenario.format() instanceof Current current)
					testCases.addAll(current.files().testCases());
			}
		}
		return List.copyOf(testCases);
	}

	/**
	 * Returns the text workspace files of the cited scenarios, which CAT adds to the prompt test that cites them.
	 *
	 * @param citations the citations of the scenarios that one prompt test covers
	 * @return the workspace files, each with its source, in citation order, each once
	 * @throws NullPointerException if {@code citations} is null
	 */
	public List<WorkspaceFile> recordedWorkspaceFiles(List<String> citations)
	{
		requireThat(citations, "citations").isNotNull();
		Set<WorkspaceFile> workspaceFiles = new LinkedHashSet<>();
		for (String citation : citations)
		{
			for (Scenario scenario : scenarios)
			{
				if (scenario.citation().equals(citation) && scenario.format() instanceof Current current)
					workspaceFiles.addAll(current.textWorkspaceFiles());
			}
		}
		return List.copyOf(workspaceFiles);
	}

	/**
	 * Returns the scenarios of the guidance that a campaign optimizes.
	 *
	 * @param target the absolute path of the campaign's target, a prompt file or a directory of prompt files
	 * @return the scenarios whose guidance is the target or lies inside it, in citation order
	 * @throws NullPointerException if {@code target} is null
	 */
	public RegressionScenarios of(Path target)
	{
		requireThat(target, "target").isNotNull();
		Path normalized = target.toAbsolutePath().normalize();
		List<Scenario> kept = new ArrayList<>();
		for (Scenario scenario : scenarios)
		{
			if (scenario.source().startsWith(normalized))
				kept.add(scenario);
		}
		return new RegressionScenarios(project, kept);
	}

	/**
	 * Returns a refusal reason for every scenario that no test cites.
	 *
	 * @param cited the citations of the proposal's prompt tests
	 * @return the missing-scenario problems, in citation order
	 * @throws NullPointerException if {@code cited} is null
	 */
	public List<String> missingCitations(Set<String> cited)
	{
		requireThat(cited, "cited").isNotNull();
		List<String> problems = new ArrayList<>();
		for (Scenario scenario : scenarios)
		{
			if (!cited.contains(scenario.citation()))
				problems.add("Recorded regression scenario has no citing test: " + scenario.citation());
		}
		return List.copyOf(problems);
	}
}
