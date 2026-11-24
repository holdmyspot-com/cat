package com.holdmyspot.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.evidence.ActivityFile;
import com.holdmyspot.cat.common.optimizer.evidence.SessionEvidence;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns the files of a prompt test's assertion example into the trial record that the assertion reads.
 */
public final class AssertionExamples
{
	private static final Pattern INVALID_FILE_NAME = Pattern.compile("[/\\\\:*?\"<>|\\p{Cntrl}]");

	/**
	 * Chooses the case that supplies the example's placeholder values.
	 *
	 * @param draftCases    the test cases, in the order the author wrote them
	 * @param boundaryClass the passing example's boundary class, or null for a failing example
	 * @return the first written case of the class, or the first written case for a failing example
	 * @throws NullPointerException if {@code draftCases} is null
	 * @throws IllegalArgumentException if no case matches the example
	 */
	public static TestCase selectCase(List<TestCase> draftCases, String boundaryClass)
	{
		requireThat(draftCases, "draftCases").isNotNull();
		for (TestCase testCase : draftCases)
		{
			if (boundaryClass == null || boundaryClass.equals(testCase.boundaryClass()))
				return testCase;
		}
		if (boundaryClass == null)
			throw new IllegalArgumentException("No draft test case for a failing assertion example");
		throw new IllegalArgumentException("No draft test case for assertion example of class " + boundaryClass);
	}

	/**
	 * Renders one assertion example. The caller keeps the scratch area and any default initial project available while
	 * using the record.
	 *
	 * @param json                  the process mapper
	 * @param turns                 the number of turns of the draft scenario
	 * @param testCase              the written case selected for the example
	 * @param exampleFiles          the regular files of the example, keyed by paths relative to its directory
	 * @param exampleDirectories    the directories of the example, including any empty directories, as relative paths
	 * @param defaultInitialProject the staged trial workspace of the case, or null if the example has an initial project
	 * @param scratch               a private, empty directory for rendered project files
	 * @return the normalized evidence available to a trial's assertion tools
	 * @throws NullPointerException     if a required argument is null
	 * @throws IllegalArgumentException if {@code turns} is negative
	 * @throws IOException              if the example is malformed or its files cannot be read or written
	 */
	public static TrialRecord render(JsonMapper json, int turns, TestCase testCase, Map<String, Path> exampleFiles,
		Set<String> exampleDirectories, Path defaultInitialProject, Path scratch) throws IOException
	{
		requireThat(json, "json").isNotNull();
		requireThat(turns, "turns").isGreaterThanOrEqualTo(0);
		requireThat(testCase, "testCase").isNotNull();
		requireThat(exampleFiles, "exampleFiles").isNotNull();
		requireThat(exampleDirectories, "exampleDirectories").isNotNull();
		requireThat(scratch, "scratch").isNotNull();
		if (!Files.isDirectory(scratch, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Assertion example scratch directory is missing or is a link: " + scratch);
		Map<String, String> textByName = PromptTest.textByName(testCase);
		List<List<String>> replies = replies(json, exampleFiles.get("replies.json"), turns, textByName);
		Path activitySource = exampleFiles.get("activity.json");
		SessionEvidence activity;
		if (activitySource == null)
			activity = new SessionEvidence(List.of());
		else
			activity = ActivityFile.read(json, activitySource, text -> Placeholders.fillScenario(text, textByName));
		Path initial = defaultInitialProject;
		if (exampleDirectories.contains("initial"))
			initial = snapshot("initial", scratch, exampleFiles, exampleDirectories, testCase, textByName);
		else if (initial == null || !Files.isDirectory(initial, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Missing staged initial project for assertion example: " + initial);
		Path project = initial;
		if (exampleDirectories.contains("project"))
			project = snapshot("project", scratch, exampleFiles, exampleDirectories, testCase, textByName);
		return new RecordedTrial(replies, activity, initial, project);
	}

	/**
	 * Reads one list of messages per turn. Substitution happens in each parsed message so that a value is ordinary
	 * message text even when it contains JSON punctuation.
	 *
	 * @param json       the process mapper
	 * @param source     the example's replies file, or null if the example omits it
	 * @param turns      the number of turns in the scenario
	 * @param textByName the case's text substitutions
	 * @return the replies in turn order, with an empty list for any omitted turn
	 * @throws IOException if the replies cannot be read or do not match the scenario's turns
	 */
	private static List<List<String>> replies(JsonMapper json, Path source, int turns, Map<String, String> textByName)
		throws IOException
	{
		List<List<String>> replies = new ArrayList<>(turns);
		if (source != null)
		{
			try (InputStream input = openRegularFile(source))
			{
				JsonNode document = json.readTree(input);
				if (document == null || !document.isArray() || document.size() > turns)
					throw new IOException("Expected at most " + turns + " reply arrays in " + source);
				for (JsonNode turn : document)
				{
					if (!turn.isArray())
						throw new IOException("Expected an array of messages for each turn in " + source);
					List<String> messages = new ArrayList<>();
					for (JsonNode message : turn)
					{
						if (!message.isString())
							throw new IOException("Expected a text message in " + source);
						messages.add(Placeholders.fillScenario(message.stringValue(), textByName));
					}
					replies.add(messages);
				}
			}
			catch (JacksonException e)
			{
				throw new IOException("Invalid replies in " + source, e);
			}
		}
		while (replies.size() < turns)
			replies.add(List.of());
		return replies;
	}

	/**
	 * Writes an explicit project snapshot, creating its directories and substituting its file names and text.
	 *
	 * @param name               the example's initial or final project directory name
	 * @param scratch            the example's private scratch directory
	 * @param exampleFiles       the example's regular files, keyed by example-relative path
	 * @param exampleDirectories the example's directories, as example-relative paths
	 * @param testCase           the case providing placeholder values
	 * @param textByName         the case's text substitutions
	 * @return the resulting project root
	 * @throws IOException if a file cannot be rendered
	 */
	private static Path snapshot(String name, Path scratch, Map<String, Path> exampleFiles,
		Set<String> exampleDirectories, TestCase testCase, Map<String, String> textByName) throws IOException
	{
		Path root = Files.createDirectory(scratch.resolve(name));
		String prefix = name + "/";
		for (String directory : exampleDirectories.stream().filter(path -> path.startsWith(prefix)).sorted().toList())
		{
			Path target = target(root, directory.substring(prefix.length()), textByName);
			Files.createDirectories(target.getParent());
			Files.createDirectory(target);
		}
		for (Map.Entry<String, Path> file : exampleFiles.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList())
		{
			if (!file.getKey().startsWith(prefix))
				continue;
			Path target = target(root, file.getKey().substring(prefix.length()), textByName);
			Files.createDirectories(target.getParent());
			try (InputStream input = openRegularFile(file.getValue()))
			{
				byte[] bytes = input.readAllBytes();
				String text = PromptTestFiles.utf8(bytes);
				if (text == null)
					Files.write(target, bytes, StandardOpenOption.CREATE_NEW);
				else
					TestWorkspaces.writeText(text, target, testCase.values(), testCase.expectedOutcome());
			}
		}
		return root;
	}

	/**
	 * Substitutes each component separately so that a case value cannot add a new component or navigate to a parent.
	 *
	 * @param root       the destination snapshot root
	 * @param name       the portable path from the input inventory
	 * @param textByName the case's text substitutions
	 * @return the rendered path within the snapshot
	 * @throws IOException if any name component is invalid
	 */
	private static Path target(Path root, String name, Map<String, String> textByName) throws IOException
	{
		Path target = root;
		for (String component : name.split("/", -1))
		{
			String rendered = Placeholders.fillScenario(component, textByName);
			if (rendered.isEmpty() || rendered.equals(".") || rendered.equals("..") ||
				INVALID_FILE_NAME.matcher(rendered).find())
				throw new IOException("Invalid rendered assertion example path: " + name);
			try
			{
				target = target.resolve(rendered);
			}
			catch (InvalidPathException e)
			{
				throw new IOException("Invalid rendered assertion example path: " + name, e);
			}
		}
		return target;
	}

	/**
	 * Opens an example file checked by the inventory reader without following a link at its path.
	 *
	 * @param source the inventoried source file
	 * @return a stream owned by the caller
	 * @throws IOException if the file is no longer regular or cannot be opened
	 */
	private static InputStream openRegularFile(Path source) throws IOException
	{
		if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS))
			throw new IOException("Expected a regular assertion example file: " + source);
		return Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS);
	}

	/**
	 * Prevents instantiation.
	 */
	private AssertionExamples()
	{
	}
}
