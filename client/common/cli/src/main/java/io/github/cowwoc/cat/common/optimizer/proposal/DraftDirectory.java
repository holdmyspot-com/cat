package io.github.cowwoc.cat.common.optimizer.proposal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.contract.PromptTestFiles;
import io.github.cowwoc.cat.common.optimizer.contract.PromptTestLocations;

import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The directory in which a contract author writes the prompt tests of its proposal as files, each at its path relative
 * to the project: the prompt test's {@value PromptTestFiles#SCENARIO}, its {@value PromptTestFiles#FIXTURES}
 * directory and its values file, and the shared fixtures that its patterns select.
 * <p>
 * Before a contract author session, CAT seeds a new draft directory with the recorded prompt tests of the target, or
 * copies the latest proposal's draft directory for a revision, or copies one prompt test for a test revision. When
 * the session ends with a valid proposal, CAT stores the draft directory in the campaign's held-out directory, because
 * its values files hold the expected outcomes of both parts.
 * <p>
 * Only directories and regular files are copied; a symbolic link is left out, so that a copy never reads or writes
 * outside the directory it copies.
 */
public final class DraftDirectory
{
	/**
	 * The name of the draft directory when the session's project does not already hold a file by that name.
	 */
	public static final String NAME = "cat-draft";
	/**
	 * The start of the draft directory's name when the project already holds {@link #NAME}.
	 */
	private static final String STEM = "cat-draft-";
	/**
	 * The directory of the held-out directory that holds the stored draft directories.
	 */
	private static final String DRAFTS = "drafts";
	/**
	 * The file name suffix of a scenario in an earlier format.
	 */
	private static final String MARKDOWN = ".md";

	/**
	 * Prevents construction.
	 */
	private DraftDirectory()
	{
	}

	/**
	 * Returns the name of a session's draft directory: {@link #NAME}, or the first of {@code cat-draft-2},
	 * {@code cat-draft-3} and so on that the project does not hold, so that the session never writes over a project
	 * file.
	 *
	 * @param project the session's project as it was before CAT added the session's files
	 * @return the directory name, relative to the project
	 * @throws NullPointerException if {@code project} is null
	 */
	public static String name(Path project)
	{
		requireThat(project, "project").isNotNull();
		String name = NAME;
		for (int suffix = 2; Files.exists(project.resolve(name), LinkOption.NOFOLLOW_LINKS); ++suffix)
			name = STEM + suffix;
		return name;
	}

	/**
	 * Returns the directory of each recorded prompt test of the target in a draft directory. A prompt test in its own
	 * directory keeps that directory. A scenario in an earlier format, a Markdown file directly in the target's test
	 * directory, becomes the {@value PromptTestFiles#SCENARIO} of a directory beside it named after the file in
	 * lowercase words joined by underscores, followed by {@code _2}, {@code _3} and so on if a recorded prompt test or
	 * another file of the project already has that name. A scenario outside the project, such as one of the user's
	 * rules, has no directory.
	 *
	 * @param scenarios the recorded scenarios of the target
	 * @return each test directory relative to the project, by the citation of its recorded scenario, in citation order
	 * @throws NullPointerException if {@code scenarios} is null
	 */
	public static Map<String, Path> testDirectories(RegressionScenarios scenarios)
	{
		requireThat(scenarios, "scenarios").isNotNull();
		Map<String, Path> directories = new LinkedHashMap<>();
		Set<Path> taken = new HashSet<>();
		for (RegressionScenarios.Scenario scenario : scenarios.scenarios())
		{
			Path citation = Path.of(scenario.citation());
			if (!citation.isAbsolute() && isCaseScenario(citation))
			{
				directories.put(scenario.citation(), citation.getParent());
				taken.add(citation.getParent());
			}
		}
		Map<String, Path> ordered = new LinkedHashMap<>();
		for (RegressionScenarios.Scenario scenario : scenarios.scenarios())
		{
			Path citation = Path.of(scenario.citation());
			if (citation.isAbsolute())
				continue;
			Path directory = directories.get(scenario.citation());
			if (directory == null)
			{
				directory = convertedDirectory(scenarios.project(), citation, taken);
				taken.add(directory);
			}
			ordered.put(scenario.citation(), directory);
		}
		return ordered;
	}

	/**
	 * Indicates whether a citation names the {@value PromptTestFiles#SCENARIO} of a prompt test directory.
	 *
	 * @param citation the citation, relative to the project
	 * @return {@code true} if the file is a {@value PromptTestFiles#SCENARIO} inside a directory
	 */
	private static boolean isCaseScenario(Path citation)
	{
		return citation.getFileName().toString().equals(PromptTestFiles.SCENARIO) && citation.getParent() != null;
	}

	/**
	 * Returns the directory into which a scenario in an earlier format is converted.
	 *
	 * @param project  the project
	 * @param citation the scenario's file, relative to the project
	 * @param taken    the directories that other prompt tests already have
	 * @return the directory, relative to the project
	 */
	private static Path convertedDirectory(Path project, Path citation, Set<Path> taken)
	{
		String file = citation.getFileName().toString();
		if (file.endsWith(MARKDOWN))
			file = file.substring(0, file.length() - MARKDOWN.length());
		String stem = file.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
		if (stem.isEmpty())
			stem = "scenario";
		Path parent = citation.getParent();
		Path directory = resolve(parent, stem);
		for (int suffix = 2; taken.contains(directory) ||
			Files.exists(project.resolve(directory), LinkOption.NOFOLLOW_LINKS); ++suffix)
		{
			directory = resolve(parent, stem + "_" + suffix);
		}
		return directory;
	}

	/**
	 * Resolves a name against a directory that may be absent.
	 *
	 * @param parent the directory, or {@code null} for the project root
	 * @param name   the name
	 * @return the path
	 */
	private static Path resolve(Path parent, String name)
	{
		if (parent == null)
			return Path.of(name);
		return parent.resolve(name);
	}

	/**
	 * Copies the recorded prompt tests of the target into a new draft directory, each at its directory as
	 * {@link #testDirectories(RegressionScenarios)} returns it, with the shared fixtures that its patterns select.
	 *
	 * @param scenarios the recorded scenarios of the target
	 * @param draft     the draft directory, which need not exist
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if a file cannot be read or written
	 */
	public static void seed(RegressionScenarios scenarios, Path draft) throws IOException
	{
		requireThat(draft, "draft").isNotNull();
		Map<String, Path> directories = testDirectories(scenarios);
		Files.createDirectories(draft);
		Path project = scenarios.project();
		for (RegressionScenarios.Scenario scenario : scenarios.scenarios())
		{
			Path directory = directories.get(scenario.citation());
			if (directory == null)
				continue;
			Path citation = Path.of(scenario.citation());
			if (isCaseScenario(citation))
				copyTest(project, directory, draft);
			else
			{
				Path converted = Files.createDirectories(draft.resolve(directory));
				Files.writeString(converted.resolve(PromptTestFiles.SCENARIO), scenario.content());
			}
		}
	}

	/**
	 * Copies a draft directory into a new one, for a revision of its proposal.
	 *
	 * @param source the draft directory to copy
	 * @param draft  the new draft directory, which need not exist
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if a file cannot be read or written
	 */
	public static void copy(Path source, Path draft) throws IOException
	{
		requireThat(source, "source").isNotNull();
		requireThat(draft, "draft").isNotNull();
		copyTree(source, draft);
	}

	/**
	 * Copies one prompt test into a draft directory: its directory and the shared fixtures that its patterns select.
	 *
	 * @param source        the directory that holds the prompt test at its path, such as the project or another
	 *                      draft directory
	 * @param testDirectory the prompt test's directory, relative to {@code source}
	 * @param draft         the draft directory, which need not exist
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code testDirectory} is absolute
	 * @throws IOException              if a file cannot be read or written
	 */
	public static void copyTest(Path source, Path testDirectory, Path draft) throws IOException
	{
		requireThat(source, "source").isNotNull();
		requireThat(testDirectory.isAbsolute(), "testDirectory.isAbsolute()").isFalse();
		requireThat(draft, "draft").isNotNull();
		copyTree(source.resolve(testDirectory), draft.resolve(testDirectory));
		Path scenario = testDirectory.normalize().resolve(PromptTestFiles.SCENARIO);
		for (Path shared : PromptTestLocations.sharedFixtures(source, scenario))
			copyTree(source.resolve(shared), draft.resolve(shared));
	}

	/**
	 * Copies the directories and regular files below a directory, keeping a file that the target already holds.
	 *
	 * @param source the directory to copy, which need not exist
	 * @param target the copy
	 * @throws IOException if a file cannot be read or written
	 */
	private static void copyTree(Path source, Path target) throws IOException
	{
		if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS))
			return;
		List<Path> paths;
		try (Stream<Path> walk = Files.walk(source))
		{
			paths = walk.toList();
		}
		for (Path path : paths)
		{
			BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
				LinkOption.NOFOLLOW_LINKS);
			Path copy = target.resolve(source.relativize(path).toString());
			if (attributes.isDirectory())
				Files.createDirectories(copy);
			else if (attributes.isRegularFile() && !Files.exists(copy, LinkOption.NOFOLLOW_LINKS))
				Files.copy(path, copy);
		}
	}

	/**
	 * Moves a draft directory into a held-out directory under a name that no stored draft directory has, so that a
	 * draft directory stored earlier stays whole until the proposal that names the new one replaces the stored
	 * proposal.
	 *
	 * @param draft   the draft directory
	 * @param heldOut the campaign's held-out directory
	 * @return the stored draft directory
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the directory cannot be moved
	 */
	public static Path store(Path draft, Path heldOut) throws IOException
	{
		requireThat(draft, "draft").isNotNull();
		requireThat(heldOut, "heldOut").isNotNull();
		Path drafts = Files.createDirectories(heldOut.resolve(DRAFTS));
		Path stored = drafts.resolve("1");
		for (int number = 2; Files.exists(stored, LinkOption.NOFOLLOW_LINKS); ++number)
			stored = drafts.resolve(String.valueOf(number));
		try
		{
			Files.move(draft, stored);
		}
		catch (DirectoryNotEmptyException _)
		{
			// A directory moves to another file store only by copying its entries
			copyTree(draft, stored);
			TemporaryDirectories.deleteRecursively(draft);
		}
		return stored;
	}

	/**
	 * Removes every draft directory stored in a held-out directory except one.
	 *
	 * @param heldOut the campaign's held-out directory
	 * @param kept    the stored draft directory to keep
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if a directory cannot be removed
	 */
	public static void keepOnly(Path heldOut, Path kept) throws IOException
	{
		requireThat(heldOut, "heldOut").isNotNull();
		requireThat(kept, "kept").isNotNull();
		Path drafts = heldOut.resolve(DRAFTS);
		if (!Files.isDirectory(drafts))
			return;
		List<Path> stored;
		try (Stream<Path> children = Files.list(drafts))
		{
			stored = children.toList();
		}
		for (Path directory : stored)
		{
			if (!directory.equals(kept))
				TemporaryDirectories.deleteRecursively(directory);
		}
	}
}
