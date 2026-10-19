package com.holdmyspot.cat.common.optimizer.proposal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.PortablePaths;
import com.holdmyspot.cat.common.optimizer.contract.Cell;
import com.holdmyspot.cat.common.optimizer.contract.Contract;
import com.holdmyspot.cat.common.optimizer.contract.TestWorkspaces;
import com.holdmyspot.cat.common.optimizer.contract.InvalidProposalException;
import com.holdmyspot.cat.common.optimizer.contract.PromptTest;
import com.holdmyspot.cat.common.optimizer.contract.PromptTestFiles;
import com.holdmyspot.cat.common.optimizer.contract.PromptTestLocations;
import com.holdmyspot.cat.common.optimizer.contract.ProposalSchema;
import com.holdmyspot.cat.common.optimizer.contract.ScenarioFormatException;
import com.holdmyspot.cat.common.optimizer.contract.Split;
import com.holdmyspot.cat.common.optimizer.contract.TestCase;
import com.holdmyspot.cat.common.optimizer.contract.TestValue;
import com.holdmyspot.cat.common.optimizer.contract.TestValueJson;
import com.holdmyspot.cat.common.optimizer.contract.TrialProfile;
import com.holdmyspot.cat.common.optimizer.contract.ValuesFile;
import com.holdmyspot.cat.common.optimizer.contract.ValuesFile.DraftTestCase;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.TreeSet;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The contract tools that a contract author session runs through the launcher while it writes the proposal file and
 * the draft directory: {@code contract check} and {@code contract workspace}.
 * <p>
 * Both read the context file that CAT wrote into the session's project, the proposal file and the draft directory,
 * and nothing in the campaign's held-out directory. They validate with a salt of {@code 0}, so the seeds that they give
 * the generator calls that state none differ from the seeds that CAT gives those calls when the session ends. Only
 * {@code contract workspace} writes, and only below the output directory that it receives.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 */
public final class ContractTools
{
	/**
	 * The salt that the tools validate with, which reveals nothing of the proposal's split.
	 */
	private static final long SALT = 0;
	/**
	 * Stands for the prompt sources that the harness reports loading when a session ends. The validation records
	 * them in the proposal without reading them, and the tools discard the proposal.
	 */
	private static final String NO_OBSERVED_SOURCES = "none";
	private final JsonMapper json;
	private final ProposalSchema schema;
	private final OutputFile outputFile;

	/**
	 * Creates the tools.
	 *
	 * @param json the operation-owned JSON mapper
	 * @throws NullPointerException if {@code json} is null
	 */
	public ContractTools(JsonMapper json)
	{
		requireThat(json, "json").isNotNull();
		this.json = json;
		this.schema = new ProposalSchema(json);
		this.outputFile = new OutputFile(json);
	}

	/**
	 * Applies to the proposal file and the draft directory every validation that CAT applies when the session ends,
	 * apart from the split of each prompt test's expected outcomes between the parts. Prints every problem, each with
	 * the file and line or the JSON path that it concerns, or, when there is none, the number of distinct expected
	 * outcomes of each prompt test.
	 *
	 * @param contextFile the context file that CAT wrote into the session's project
	 * @param out         receives the report
	 * @return 0 if the check found no problem, otherwise 1
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the context file cannot be read or is not a context file
	 */
	public int check(Path contextFile, PrintStream out) throws IOException
	{
		requireThat(out, "out").isNotNull();
		Session session = open(contextFile);
		List<String> problems = new ArrayList<>();
		Optional<Contract> contract = validate(session, problems);
		if (!problems.isEmpty())
		{
			out.println("`contract check` found " + problems.size() + " problem(s):");
			for (String problem : problems)
				out.println("- " + problem);
			return 1;
		}
		out.println("`contract check` found no problem.");
		if (contract.isPresent())
		{
			out.println();
			out.println("Distinct expected outcomes of each prompt test's test cases:");
			for (PromptTest test : contract.get().tests())
			{
				Set<String> outcomes = new HashSet<>();
				for (TestCase testCase : test.allTestCases())
					outcomes.add(Split.normalize(testCase.expectedOutcome()));
				out.println("- `" + test.id() + "`" + directory(test) + ": " + outcomes.size());
			}
		}
		return 0;
	}

	/**
	 * Writes the workspace files of one test case of a prompt test in the draft directory into a new directory: the
	 * workspace files that the test's patterns select, with the test case's values in place of the placeholders and its
	 * generator calls expanded. Prints each file's path, size in bytes and line count, and how its size compares with
	 * the tool-output limit of each trial profile of the prompt test. Those are the profiles of the test's cells if
	 * the proposal passes {@link #check(Path, PrintStream) contract check}, and every profile of the inventory
	 * otherwise.
	 *
	 * @param contextFile   the context file that CAT wrote into the session's project
	 * @param testDirectory the prompt test's directory, relative to the draft directory, as the proposal file names it
	 * @param testCase      the test case's number, counted from 1 in the test's values file
	 * @param output        the directory to write to, which must not exist and must lie outside the draft directory;
	 *                      its parent must exist
	 * @param out           receives the report
	 * @return 0 if the files were written, otherwise 1
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the context file cannot be read or is not a context file, or a file cannot be
	 *                              read or written
	 */
	public int workspace(Path contextFile, Path testDirectory, int testCase, Path output, PrintStream out)
		throws IOException
	{
		requireThat(testDirectory, "testDirectory").isNotNull();
		requireThat(output, "output").isNotNull();
		requireThat(out, "out").isNotNull();
		Session session = open(contextFile);
		Path directory = testDirectory.normalize();
		Path test = session.draft().resolve(directory).normalize();
		if (testDirectory.isAbsolute() || !test.startsWith(session.draft()) ||
			!Files.isRegularFile(test.resolve(PromptTestFiles.SCENARIO), LinkOption.NOFOLLOW_LINKS))
		{
			out.println("`" + testDirectory + "` names no prompt test in the draft directory `" +
				session.context().draft() + "`. Give the test's directory as the proposal file names it, relative " +
				"to the draft directory.");
			return 1;
		}
		Path target = output.toAbsolutePath().normalize();
		if (target.startsWith(session.draft()) || Files.exists(target, LinkOption.NOFOLLOW_LINKS))
		{
			out.println("`" + output + "` must be a new directory outside the draft directory `" +
				session.context().draft() + "`.");
			return 1;
		}

		List<String> problems = new ArrayList<>();
		Optional<Expansion> expansion = expansion(session, test, testCase, problems);
		if (expansion.isEmpty())
		{
			out.println("`contract workspace` wrote nothing:");
			for (String problem : problems)
				out.println("- " + problem);
			return 1;
		}
		PromptTestFiles files = expansion.get().files();
		TestCase values = expansion.get().testCase();
		Map<PromptTestFiles.WorkspaceEntry, String> targets = files.targets(values);
		Files.createDirectory(target);
		TestWorkspaces.copy(files, values, target);

		out.println("Wrote the workspace files of test case " + testCase + " of `" +
			PortablePaths.text(directory) + "` to `" + output + "`.");
		if (expansion.get().unseeded())
		{
			out.println("A generator call of the test case states no seed. CAT gives it one when the session ends, " +
				"so the content that it generates there differs from these files.");
		}
		Map<Long, List<String>> byLimit = limits(session, directory, out);
		out.println();
		if (targets.isEmpty())
		{
			out.println("The test case has no workspace files.");
			return 0;
		}
		out.println("Files:");
		for (String path : new TreeSet<>(targets.values()))
			out.println("- " + describe(target.resolve(path), path, byLimit.keySet()));
		return 0;
	}

	/**
	 * What a contract tool works on.
	 *
	 * @param context the context file
	 * @param project the session's project, which holds the context file
	 * @param draft   the draft directory
	 * @param check   validates the proposal file and the draft directory
	 */
	private record Session(ContractContext context, Path project, Path draft, ProposalCheck check)
	{
	}

	/**
	 * Reads a context file.
	 *
	 * @param contextFile the context file that CAT wrote into the session's project
	 * @return what the tools work on
	 * @throws NullPointerException if {@code contextFile} is null
	 * @throws IOException          if the file cannot be read or is not a context file
	 */
	private Session open(Path contextFile) throws IOException
	{
		requireThat(contextFile, "contextFile").isNotNull();
		Path file = contextFile.toAbsolutePath().normalize();
		ContractContext context = ContractContext.read(json, file);
		Path project = file.getParent();
		ProposalCheck check = new ProposalCheck(json, schema, new JudgeTemplate(json, context.role()),
			context.target(), context.reportsExitStatus(), context.recorded());
		return new Session(context, project, project.resolve(context.draft()).normalize(), check);
	}

	/**
	 * Validates the proposal file, or a test revision's result file, with the draft directory.
	 *
	 * @param session  what the tools work on
	 * @param problems receives every problem found
	 * @return the contract, or empty if there is a problem or a test revision's result revises nothing
	 * @throws IOException if a file of the draft directory cannot be read
	 */
	private Optional<Contract> validate(Session session, List<String> problems) throws IOException
	{
		ContractContext context = session.context();
		try
		{
			ObjectNode file = outputFile.read(session.project(), context.proposalFile());
			if (context.revision().isEmpty())
			{
				return Optional.of(session.check().check(file, session.project(), session.draft(),
					NO_OBSERVED_SOURCES, context.profiles(), SALT).contract());
			}
			ContractContext.Revision revision = context.revision().get();
			TestRevisionCheck check = new TestRevisionCheck(session.check(), revision.proposalFile(),
				revision.tests(), revision.test());
			if (!(check.validate(file, session.project(), session.draft(), NO_OBSERVED_SOURCES, context.profiles(),
				SALT, false) instanceof TestRevisionProtocol.Result.Revised revised))
			{
				return Optional.empty();
			}
			List<Path> changed = DraftDirectory.changedOutside(session.draft(), check.directory(),
				revision.sharedFiles());
			if (!changed.isEmpty())
			{
				problems.addAll(check.outsideProblems(context.draft(), changed));
				return Optional.empty();
			}
			return Optional.of(revised.proposal().contract());
		}
		catch (InvalidProposalException e)
		{
			problems.addAll(e.problems());
			return Optional.empty();
		}
	}

	/**
	 * Returns the directory of a prompt test, as the check's report shows it.
	 *
	 * @param test the prompt test
	 * @return the directory in parentheses after a space, or an empty string if the test has none
	 */
	private static String directory(PromptTest test)
	{
		if (test.draft().isEmpty())
			return "";
		return " (`" + PortablePaths.text(test.draft().get().directory()) + "`)";
	}

	/**
	 * One test case of a prompt test in the draft directory, ready to expand.
	 *
	 * @param files    the prompt test's files
	 * @param testCase the test case, whose generator calls all have a seed
	 * @param unseeded {@code true} if a generator call of the values file states no seed
	 */
	private record Expansion(PromptTestFiles files, TestCase testCase, boolean unseeded)
	{
	}

	/**
	 * Reads one test case of a prompt test in the draft directory.
	 *
	 * @param session  what the tools work on
	 * @param test     the prompt test's directory
	 * @param number   the test case's number, counted from 1
	 * @param problems receives every problem found
	 * @return the test case, or empty if there is a problem
	 * @throws IOException if a file cannot be read
	 */
	private Optional<Expansion> expansion(Session session, Path test, int number, List<String> problems)
		throws IOException
	{
		PromptTestFiles files;
		List<DraftTestCase> testCases;
		Path valuesFile = test.resolve(ValuesFile.NAME);
		String location = PortablePaths.text(session.project().relativize(valuesFile));
		try
		{
			Path relative = session.draft().relativize(test);
			Path root = session.draft().resolve(PromptTestLocations.rootOf(relative).orElse(relative));
			files = PromptTestFiles.readScenario(session.project(), session.draft(), root, test);
			try (BufferedReader in = Files.newBufferedReader(valuesFile, UTF_8))
			{
				testCases = ValuesFile.readDraft(json, in, location);
			}
		}
		catch (ScenarioFormatException e)
		{
			problems.add(e.getMessage());
			return Optional.empty();
		}
		if (number < 1 || number > testCases.size())
		{
			problems.add(location + ": the file holds " + testCases.size() + " test case(s), so it has no test case " +
				number);
			return Optional.empty();
		}

		ObjectNode drafted = testCases.get(number - 1).testCase();
		JsonNode valuesNode = drafted.get("values");
		boolean unseeded = false;
		for (JsonNode value : valuesNode)
		{
			JsonNode call = value.get("call");
			if (call != null && call.isObject() && !call.has("seed"))
				unseeded = true;
		}
		TestCase testCase;
		try
		{
			Map<String, TestValue> values = TestValueJson.readCalls(valuesNode, new SplittableRandom(SALT),
				new HashSet<>());
			testCase = new TestCase(values, drafted.path(TestCase.EXPECTED_OUTCOME).asString());
		}
		catch (IllegalArgumentException e)
		{
			problems.add(location + ": test case " + number + ": " + e.getMessage());
			return Optional.empty();
		}
		List<String> invalid = files.problems(testCase);
		if (!invalid.isEmpty())
		{
			for (String problem : invalid)
				problems.add(location + ": test case " + number + ": " + problem);
			return Optional.empty();
		}
		return Optional.of(new Expansion(files, testCase, unseeded));
	}

	/**
	 * Prints the tool-output limit of each trial profile of a prompt test.
	 *
	 * @param session   what the tools work on
	 * @param directory the prompt test's directory, relative to the draft directory
	 * @param out       receives the limits
	 * @return the labels of the profiles whose limit the harness states, by limit in bytes
	 * @throws IOException if a file of the draft directory cannot be read
	 */
	private Map<Long, List<String>> limits(Session session, Path directory, PrintStream out) throws IOException
	{
		Optional<List<TrialProfile>> cells = cellProfiles(session, directory);
		List<TrialProfile> profiles;
		out.println();
		if (cells.isPresent())
		{
			profiles = cells.get();
			out.println("Tool-output limit of each trial profile of the test:");
		}
		else
		{
			profiles = session.context().profiles().inventory().profiles();
			out.println("The proposal does not pass `contract check`, so the test's trial profiles are not known. " +
				"Tool-output limit of each profile of the inventory:");
		}
		Map<Long, List<String>> byLimit = new TreeMap<>();
		Set<String> unknown = new LinkedHashSet<>();
		for (TrialProfile profile : profiles)
		{
			Long bytes = session.context().toolOutputBytes().get(profile);
			if (bytes == null)
				unknown.add(profile.label());
			else
				byLimit.computeIfAbsent(bytes, _ -> new ArrayList<>()).add(profile.label());
		}
		for (Map.Entry<Long, List<String>> entry : byLimit.entrySet())
			out.println("- " + entry.getKey() + " bytes: " + labels(entry.getValue()));
		if (!unknown.isEmpty())
		{
			out.println("- not known, since the harness does not state it: " + labels(unknown));
		}
		return byLimit;
	}

	/**
	 * Returns the profiles of a prompt test's cells.
	 *
	 * @param session   what the tools work on
	 * @param directory the prompt test's directory, relative to the draft directory
	 * @return the profiles, or empty if the proposal has a problem or names no test with the directory
	 * @throws IOException if a file of the draft directory cannot be read
	 */
	private Optional<List<TrialProfile>> cellProfiles(Session session, Path directory) throws IOException
	{
		Optional<Contract> contract = validate(session, new ArrayList<>());
		if (contract.isEmpty())
			return Optional.empty();
		for (PromptTest test : contract.get().tests())
		{
			if (test.draft().isPresent() && test.draft().get().directory().equals(directory))
			{
				List<TrialProfile> profiles = new ArrayList<>();
				for (Cell cell : contract.get().cells())
				{
					if (cell.testId().equals(test.id()))
						profiles.add(cell.profile());
				}
				return Optional.of(profiles);
			}
		}
		return Optional.empty();
	}

	/**
	 * Returns profile labels as a comma-separated list.
	 *
	 * @param labels the labels
	 * @return the list
	 */
	private static String labels(Iterable<String> labels)
	{
		StringJoiner joiner = new StringJoiner(", ");
		for (String label : labels)
			joiner.add("`" + label + "`");
		return joiner.toString();
	}

	/**
	 * Describes a written file.
	 *
	 * @param file   the file
	 * @param path   the file's path in the workspace
	 * @param limits the tool-output limits of the test's trial profiles, in bytes
	 * @return the file's path, size, line count and how its size compares with each limit
	 * @throws IOException if the file cannot be read
	 */
	private static String describe(Path file, String path, Set<Long> limits) throws IOException
	{
		long size = Files.size(file);
		StringBuilder text = new StringBuilder(128).append('`').append(path).append("`: ").append(size).
			append(" bytes, ").append(lines(file)).append(" line(s)");
		for (long limit : limits)
		{
			text.append("; ");
			if (size > limit)
				text.append(size - limit).append(" bytes over");
			else if (size < limit)
				text.append(limit - size).append(" bytes under");
			else
				text.append("exactly");
			text.append(" the ").append(limit).append("-byte limit");
		}
		return text.toString();
	}

	/**
	 * Counts the lines of a file: its line feeds, plus one if it does not end with one.
	 *
	 * @param file the file
	 * @return the number of lines
	 * @throws IOException if the file cannot be read
	 */
	private static long lines(Path file) throws IOException
	{
		long count = 0;
		int last = '\n';
		byte[] buffer = new byte[8192];
		try (InputStream in = Files.newInputStream(file))
		{
			for (int read = in.read(buffer); read != -1; read = in.read(buffer))
			{
				for (int i = 0; i < read; ++i)
				{
					if (buffer[i] == '\n')
						++count;
				}
				if (read > 0)
					last = buffer[read - 1];
			}
		}
		if (last != '\n')
			++count;
		return count;
	}
}
