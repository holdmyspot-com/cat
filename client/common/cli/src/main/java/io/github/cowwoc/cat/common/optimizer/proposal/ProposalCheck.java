package io.github.cowwoc.cat.common.optimizer.proposal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.Identifier;
import io.github.cowwoc.cat.common.PortablePaths;
import io.github.cowwoc.cat.common.optimizer.contract.Assertion;
import io.github.cowwoc.cat.common.optimizer.contract.BuiltInTool;
import io.github.cowwoc.cat.common.optimizer.contract.CapabilityRanking;
import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.contract.ExpectedBehavior;
import io.github.cowwoc.cat.common.optimizer.contract.FixtureFile;
import io.github.cowwoc.cat.common.optimizer.contract.InvalidProposalException;
import io.github.cowwoc.cat.common.optimizer.contract.MappedAssertion;
import io.github.cowwoc.cat.common.optimizer.contract.PromptTest;
import io.github.cowwoc.cat.common.optimizer.contract.PromptTestFiles;
import io.github.cowwoc.cat.common.optimizer.contract.PromptTestLocations;
import io.github.cowwoc.cat.common.optimizer.contract.ProposalSchema;
import io.github.cowwoc.cat.common.optimizer.contract.Scenario;
import io.github.cowwoc.cat.common.optimizer.contract.ScenarioFormatException;
import io.github.cowwoc.cat.common.optimizer.contract.TestCase;
import io.github.cowwoc.cat.common.optimizer.contract.TestValueJson;
import io.github.cowwoc.cat.common.optimizer.contract.TrialProfile;
import io.github.cowwoc.cat.common.optimizer.contract.TrialProfiles;
import io.github.cowwoc.cat.common.optimizer.contract.ValuesFile;
import io.github.cowwoc.cat.common.optimizer.contract.ValuesFile.DraftTestCase;
import io.github.cowwoc.cat.common.optimizer.contract.VariableDeclaration;
import io.github.cowwoc.cat.common.optimizer.inventory.FileContent;
import io.github.cowwoc.cat.common.optimizer.inventory.FileDelta;
import io.github.cowwoc.cat.common.optimizer.inventory.UnifiedDiff;
import io.github.cowwoc.cat.common.optimizer.report.EnumNames;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Validates a contract author's proposal file together with the prompt tests of its draft directory, and builds the
 * contract that they describe.
 * <p>
 * The proposal file names each prompt test by its directory in the draft directory. The prompt test's turns,
 * variables, expected behavior, time limit and assertions come from its {@value PromptTestFiles#SCENARIO}, its test
 * cases from its {@value ValuesFile#NAME} and its fixtures from the files that its patterns select. A recorded prompt
 * test of the target keeps its directory and receives the test cases that earlier campaigns committed; a new one lies
 * directly in the target's test directory and is named by its identifier.
 * <p>
 * Every problem names the file and line, or the JSON path of the proposal file, that it concerns. The session end, the
 * restoring of a stored proposal and the contract author's {@code contract check} all validate through this class.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 */
public final class ProposalCheck
{
	/**
	 * Matches a problem that concerns one prompt test of the completed proposal: its index, the rest of its JSON path
	 * and its message.
	 */
	private static final Pattern TEST_PROBLEM = Pattern.compile("^\\$\\.tests\\[(\\d+)]([^:]*): (.*)$",
		Pattern.DOTALL);
	/**
	 * Matches the JSON path of a test case of a completed prompt test: its index and the rest of the path.
	 */
	private static final Pattern TEST_CASE_PATH = Pattern.compile("^\\.test_cases\\[(\\d+)](.*)$");
	/**
	 * Matches the JSON path of an assertion of a completed prompt test: its index and the rest of the path.
	 */
	private static final Pattern ASSERTION_PATH = Pattern.compile("^\\.assertions\\[(\\d+)](.*)$");
	/**
	 * The fields of a completed prompt test whose values come from its {@value PromptTestFiles#SCENARIO}.
	 */
	private static final Set<String> SCENARIO_FIELDS = Set.of(".turns", ".variables", ".expected_behavior",
		".time_limit_seconds");
	/**
	 * The file name suffix of a scenario in an earlier format.
	 */
	private static final String MARKDOWN = ".md";
	private final JsonMapper json;
	private final ProposalSchema schema;
	private final JudgeTemplate judge;
	private final Path scenarioDirectory;
	private final boolean reportsExitStatus;
	/**
	 * The recorded prompt tests of the target, by their directory in the draft directory.
	 */
	private final Map<Path, RecordedTest> recorded;

	/**
	 * A recorded prompt test of the campaign's target.
	 *
	 * @param citation  the recorded scenario's citation, relative to the project
	 * @param directory the prompt test's directory in the draft directory, which is also its path relative to the
	 *                  project
	 * @param committed the test cases that earlier campaigns committed to it
	 */
	public record RecordedTest(String citation, Path directory, List<TestCase> committed)
	{
		/**
		 * Creates a recorded prompt test.
		 *
		 * @param citation  the recorded scenario's citation, relative to the project
		 * @param directory the prompt test's directory in the draft directory
		 * @param committed the test cases that earlier campaigns committed to it
		 * @throws NullPointerException     if any of the arguments are null
		 * @throws IllegalArgumentException if {@code citation} is blank or {@code directory} is absolute
		 */
		public RecordedTest
		{
			requireThat(citation, "citation").isNotBlank();
			requireThat(directory.isAbsolute(), "directory.isAbsolute()").isFalse();
			committed = List.copyOf(committed);
		}
	}

	/**
	 * Returns the recorded prompt tests of a target, each at the directory that a draft directory gives it.
	 *
	 * @param scenarios the recorded scenarios of the target
	 * @return the recorded prompt tests, in citation order
	 * @throws NullPointerException if {@code scenarios} is null
	 */
	public static List<RecordedTest> recorded(RegressionScenarios scenarios)
	{
		List<RecordedTest> tests = new ArrayList<>();
		for (Map.Entry<String, Path> entry : DraftDirectory.testDirectories(scenarios).entrySet())
		{
			tests.add(new RecordedTest(entry.getKey(), entry.getValue(),
				scenarios.committedTestCases(List.of(entry.getKey()))));
		}
		return tests;
	}

	/**
	 * Creates a validator.
	 *
	 * @param json              the operation-owned JSON mapper
	 * @param schema            the proposal schema
	 * @param judge             the judge fields that CAT adds to every proposal
	 * @param target            the campaign's target, relative to the project
	 * @param reportsExitStatus {@code true} if the harness reports the exit status of commands, so that a built-in
	 *                          check that needs it can be decided
	 * @param recorded          the recorded prompt tests of the target
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code target} is absolute
	 */
	public ProposalCheck(JsonMapper json, ProposalSchema schema, JudgeTemplate judge, Path target,
		boolean reportsExitStatus, List<RecordedTest> recorded)
	{
		requireThat(json, "json").isNotNull();
		requireThat(schema, "schema").isNotNull();
		requireThat(judge, "judge").isNotNull();
		requireThat(recorded, "recorded").isNotNull();
		this.json = json;
		this.schema = schema;
		this.judge = judge;
		this.scenarioDirectory = PromptTestLocations.scenarioDirectory(target);
		this.reportsExitStatus = reportsExitStatus;
		Map<Path, RecordedTest> byDirectory = new LinkedHashMap<>();
		for (RecordedTest test : recorded)
			byDirectory.put(test.directory(), test);
		this.recorded = byDirectory;
	}

	/**
	 * Validates a proposal file and its draft directory as a contract author session leaves them.
	 *
	 * @param proposalFile    the proposal file, which this method changes
	 * @param project         the session's project, which holds the draft directory; problems name files relative to
	 *                        it, and a new prompt test's directory must not exist in it
	 * @param draft           the draft directory
	 * @param observedSources the prompt sources that the harness reported loading in the contract author session
	 * @param profiles        the profile inventory, its shipped capability ranking and the bindings that CAT read
	 *                        when the contract author ran
	 * @param salt            the salt that CAT drew for the proposal, which splits each test's outcome-changing
	 *                        combinations into the development part and the held-out part, and from which the seeds
	 *                        of the generator calls derive
	 * @return the proposal, without derived sources and without its prompt tests' changes
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws InvalidProposalException if the proposal file violates its schema, supplies a judge field, the trial
	 *                                  profiles or the derived sources that CAT owns, names a directory that is not a
	 *                                  recorded prompt test or a new one in the target's test directory, leaves out a
	 *                                  recorded prompt test, names a prompt test whose files are missing or malformed,
	 *                                  whose scenario has no expected behavior or whose assertions it does not map
	 *                                  one to one, declares an assertion whose built-in tool needs exit statuses that
	 *                                  the harness does not report, or breaks a rule of the contract model. The
	 *                                  exception lists every problem found.
	 * @throws UncheckedIOException     if a file cannot be read
	 */
	public DerivedProposal check(ObjectNode proposalFile, Path project, Path draft, String observedSources,
		ProfileEvidence profiles, long salt) throws InvalidProposalException
	{
		requireThat(project, "project").isNotNull();
		return check(proposalFile, project, draft, observedSources, profiles, salt, true, Map.of());
	}

	/**
	 * Validates a proposal file and a draft directory that holds the files of only some of its prompt tests, as a
	 * test revision session leaves them: the other prompt tests are given in the form that a completed proposal holds
	 * them.
	 *
	 * @param proposalFile    the proposal file, which this method changes
	 * @param project         the session's project, which holds the draft directory; problems name files relative to
	 *                        it, and a new prompt test's directory must not exist in it
	 * @param draft           the draft directory
	 * @param observedSources the prompt sources that the harness reported loading in the contract author session
	 * @param profiles        the profile inventory, its shipped capability ranking and the bindings that CAT read
	 *                        when the contract author ran
	 * @param salt            the salt that CAT drew for the proposal
	 * @param fixed           the completed form of each prompt test whose files the draft directory need not hold, by
	 *                        its directory, as {@link ProposalSchema#toJson(Contract)} writes it
	 * @return the proposal, without derived sources and without its prompt tests' changes
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws InvalidProposalException if the proposal breaks a rule that
	 *                                  {@link #check(ObjectNode, Path, Path, String, ProfileEvidence, long)} lists
	 * @throws UncheckedIOException     if a file cannot be read
	 */
	public DerivedProposal check(ObjectNode proposalFile, Path project, Path draft, String observedSources,
		ProfileEvidence profiles, long salt, Map<Path, ObjectNode> fixed) throws InvalidProposalException
	{
		requireThat(project, "project").isNotNull();
		requireThat(fixed, "fixed").isNotNull();
		return check(proposalFile, project, draft, observedSources, profiles, salt, true, fixed);
	}

	/**
	 * Validates a stored proposal file and its stored draft directory again.
	 *
	 * @param proposalFile    the proposal file, which this method changes
	 * @param draft           the stored draft directory
	 * @param observedSources the prompt sources that the harness reported loading in the contract author session
	 * @param profiles        the profile inventory, its shipped capability ranking and the bindings that CAT read
	 *                        when the contract author ran
	 * @param salt            the salt that CAT drew for the proposal
	 * @return the proposal, without derived sources and without its prompt tests' changes
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws InvalidProposalException if the proposal breaks a rule that
	 *                                  {@link #check(ObjectNode, Path, Path, String, ProfileEvidence, long)} lists,
	 *                                  apart from a new prompt test whose directory the project holds by now
	 * @throws UncheckedIOException     if a file cannot be read
	 */
	public DerivedProposal restore(ObjectNode proposalFile, Path draft, String observedSources,
		ProfileEvidence profiles, long salt) throws InvalidProposalException
	{
		requireThat(draft, "draft").isNotNull();
		return check(proposalFile, draft, draft, observedSources, profiles, salt, false, Map.of());
	}

	/**
	 * Validates a proposal file and its draft directory.
	 *
	 * @param proposalFile     the proposal file, which this method changes
	 * @param project          the directory that problems name files relative to
	 * @param draft            the draft directory
	 * @param observedSources  the prompt sources that the harness reported loading in the contract author session
	 * @param profiles         the profile inventory, its shipped capability ranking and the bindings
	 * @param salt             the salt that CAT drew for the proposal
	 * @param newMustBeAbsent  {@code true} if a new prompt test's directory must not exist in {@code project}
	 * @param fixed            the completed form of each prompt test whose files the draft directory need not hold,
	 *                         by its directory
	 * @return the proposal
	 * @throws InvalidProposalException if the proposal is invalid
	 */
	private DerivedProposal check(ObjectNode proposalFile, Path project, Path draft, String observedSources,
		ProfileEvidence profiles, long salt, boolean newMustBeAbsent, Map<Path, ObjectNode> fixed)
		throws InvalidProposalException
	{
		requireThat(proposalFile, "proposalFile").isNotNull();
		requireThat(draft, "draft").isNotNull();
		requireThat(observedSources, "observedSources").isNotNull();
		requireThat(profiles, "profiles").isNotNull();
		String derivation = json.writerWithDefaultPrettyPrinter().writeValueAsString(proposalFile);

		List<String> problems = new ArrayList<>();
		Findings findings = new Findings(problems);
		JsonNode scopeNode = proposalFile.remove(Findings.SCOPE_CONDITIONS);
		JsonNode conflictsNode = proposalFile.remove(Findings.CONFLICTS);
		List<Conflict> conflicts = findings.conflicts(conflictsNode);
		List<ScopeCondition> scopeConditions = findings.scopeConditions(scopeNode);
		List<TrialProfile> restriction = findings.profileRestriction(
			proposalFile.remove(Findings.PROFILE_RESTRICTION));
		List<TrialProfile> described = findings.certificationProfiles(
			proposalFile.remove(Findings.CERTIFICATION_PROFILES));
		CapabilityRanking ranking = findings.capabilityRanking(proposalFile.remove(Findings.CAPABILITY_RANKING)).
			orElse(profiles.ranking());
		if (proposalFile.remove(ProposalSchema.TRIAL_PROFILES) != null)
		{
			problems.add("$." + ProposalSchema.TRIAL_PROFILES +
				": CAT computes the trial profiles, so the contract author must leave them out");
		}
		if (proposalFile.remove(Findings.DERIVED_SOURCES) != null)
		{
			problems.add("$." + Findings.DERIVED_SOURCES +
				": CAT computes the derived sources, so the contract author must leave them out");
		}
		if (proposalFile.get("judge") instanceof ObjectNode judgeNode)
		{
			for (String field : JudgeTemplate.OWNED_FIELDS)
			{
				if (judgeNode.has(field))
				{
					problems.add("$.judge." + field +
						": CAT supplies this field, so the contract author must leave it out");
				}
			}
			judgeNode.remove(JudgeTemplate.OWNED_FIELDS);
			judge.complete(judgeNode);
		}

		List<String> violations = schema.proposalFileViolations(proposalFile);
		problems.addAll(violations);
		// The files of a prompt test whose item satisfies the schema are read even when another part of the proposal
		// file breaks it, so that every problem is reported at once
		DraftAssembly assembled = new DraftAssembly(project, draft, newMustBeAbsent, fixed, problems).
			assemble(proposalFile, invalidTests(violations));
		// Read from the raw file so that a missing recorded prompt test is reported even when the schema rejects it
		problems.addAll(missingRecorded(proposalFile));
		if (assembled == null || !violations.isEmpty())
			throw new InvalidProposalException(problems);

		Contract contract = null;
		try
		{
			contract = schema.parse(json.writeValueAsString(assembled.proposal()), (requirements, tests) ->
					TrialProfiles.compute(profiles.inventory(), ranking, profiles.bindingsOf(requirements),
						restriction, described, requirements, tests), salt, new SplittableRandom(salt),
				assembled.committed(), assembled.fixtures());
			problems.addAll(unreportedExitStatuses(contract));
			findings.checkRequirements(scopeNode, Findings.SCOPE_CONDITIONS, contract);
			findings.checkRequirements(conflictsNode, Findings.CONFLICTS, contract);
		}
		catch (InvalidProposalException e)
		{
			for (String problem : e.problems())
				problems.add(assembled.locate(problem));
		}
		if (!problems.isEmpty())
			throw new InvalidProposalException(problems);
		return new DerivedProposal(contract, List.of(), scopeConditions, conflicts, observedSources, derivation, draft,
			Map.of());
	}

	/**
	 * Returns the prompt tests of a proposal file whose items break its schema.
	 *
	 * @param violations the proposal file's schema violations
	 * @return the index of each item of {@code tests} that a violation concerns
	 */
	private static Set<Integer> invalidTests(List<String> violations)
	{
		Set<Integer> invalid = new HashSet<>();
		for (String violation : violations)
		{
			Matcher matcher = TEST_PROBLEM.matcher(violation);
			if (matcher.matches())
				invalid.add(Integer.parseInt(matcher.group(1)));
		}
		return invalid;
	}

	/**
	 * Returns a problem for each recorded prompt test that no prompt test of a proposal file names.
	 *
	 * @param proposalFile the proposal file, which may violate its schema
	 * @return the problems, in the order of the recorded prompt tests
	 */
	private List<String> missingRecorded(ObjectNode proposalFile)
	{
		Set<Path> named = new HashSet<>();
		JsonNode tests = proposalFile.get("tests");
		if (tests != null && tests.isArray())
		{
			for (JsonNode test : tests)
			{
				JsonNode directory = test.get("directory");
				if (directory != null && directory.isString())
					named.add(Path.of(directory.asString()).normalize());
			}
		}
		List<String> problems = new ArrayList<>();
		for (RecordedTest test : recorded.values())
		{
			if (!named.contains(test.directory()))
			{
				problems.add("$.tests: no item names the recorded prompt test `" +
					PortablePaths.text(test.directory()) + "`, recorded at `" + test.citation() + "`. Every recorded " +
					"prompt test of the target stays in the proposal; add an item with its directory.");
			}
		}
		return problems;
	}

	/**
	 * Returns a problem for each assertion whose built-in tool needs exit statuses when the harness does not report
	 * them.
	 *
	 * @param contract the proposed contract
	 * @return the problems, empty if the harness reports exit statuses or no check needs them
	 */
	private List<String> unreportedExitStatuses(Contract contract)
	{
		List<String> problems = new ArrayList<>();
		if (reportsExitStatus)
			return problems;
		for (PromptTest test : contract.tests())
		{
			for (MappedAssertion mapped : test.assertions())
			{
				Assertion assertion = mapped.assertion();
				if (assertion.builtInTool().map(BuiltInTool::needsExitStatus).orElse(false))
				{
					problems.add("test '" + test.id() + "', assertion '" + assertion.id() + "': this harness does " +
						"not report the exit status of commands, so `tool: " + assertion.tool() + "` cannot be " +
						"decided. Use another built-in tool or let the judge decide the assertion.");
				}
			}
		}
		return problems;
	}

	/**
	 * Writes into each prompt test's {@value ValuesFile#NAME} in a draft directory the seed and the version that
	 * validation gave each generator call that stated neither, so that reading the draft again gives the same test
	 * cases. A values file whose calls all state their seed is left unchanged.
	 *
	 * @param contract the contract that {@link #check(ObjectNode, Path, Path, String, ProfileEvidence, long)} returned
	 *                 for the draft
	 * @param draft    the draft directory
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if a values file cannot be read or written
	 */
	public void writeSeeds(Contract contract, Path draft) throws IOException
	{
		requireThat(contract, "contract").isNotNull();
		requireThat(draft, "draft").isNotNull();
		for (PromptTest test : contract.tests())
		{
			if (test.draft().isEmpty())
				continue;
			Path valuesFile = draft.resolve(test.draft().get().directory()).resolve(ValuesFile.NAME);
			if (!hasUnseededCall(valuesFile))
				continue;
			StringWriter text = new StringWriter();
			ValuesFile.write(json, test.allTestCases(), text);
			Files.writeString(valuesFile, text.toString(), UTF_8);
		}
	}

	/**
	 * Indicates whether a values file in a draft directory has a generator call that states no seed.
	 *
	 * @param valuesFile the values file
	 * @return {@code true} if a call states no seed
	 * @throws IOException if the file cannot be read
	 */
	private boolean hasUnseededCall(Path valuesFile) throws IOException
	{
		List<DraftTestCase> testCases;
		try (BufferedReader in = Files.newBufferedReader(valuesFile, UTF_8))
		{
			testCases = ValuesFile.readDraft(json, in, valuesFile.toString());
		}
		catch (ScenarioFormatException e)
		{
			// Validation read the same file before
			throw new AssertionError(e);
		}
		for (DraftTestCase testCase : testCases)
		{
			for (JsonNode value : testCase.testCase().get("values"))
			{
				JsonNode call = value.get("call");
				if (call != null && !call.has("seed"))
					return true;
			}
		}
		return false;
	}

	/**
	 * Returns how each prompt test of a contract relates to the prompt tests that a project records.
	 *
	 * @param contract the contract that {@link #check(ObjectNode, Path, Path, String, ProfileEvidence, long)} returned
	 * @param draft    the contract's draft directory
	 * @param project  the project whose recorded prompt tests the draft started from
	 * @return the change of each prompt test, by its identifier, in contract order
	 * @throws NullPointerException if any of the arguments are null
	 * @throws UncheckedIOException if a file cannot be read
	 */
	public Map<String, DraftChange> changes(Contract contract, Path draft, Path project)
	{
		requireThat(contract, "contract").isNotNull();
		requireThat(draft, "draft").isNotNull();
		requireThat(project, "project").isNotNull();
		Map<String, DraftChange> changes = new LinkedHashMap<>();
		try
		{
			for (PromptTest test : contract.tests())
			{
				if (test.draft().isEmpty())
					continue;
				Path directory = test.draft().get().directory();
				RecordedTest recordedTest = recorded.get(directory);
				if (recordedTest == null)
				{
					changes.put(test.id(), new DraftChange(DraftChange.Kind.CREATED, "", false));
					continue;
				}
				List<FileDelta> deltas = deltas(recordedTest, draft, project);
				if (deltas.isEmpty())
				{
					changes.put(test.id(), new DraftChange(DraftChange.Kind.RECORDED, "", false));
					continue;
				}
				// The proposal lists the values file's test cases, so a difference of the file would repeat them
				Path valuesFile = directory.resolve(ValuesFile.NAME);
				List<FileDelta> shown = deltas.stream().filter(delta -> !delta.path().equals(valuesFile)).toList();
				changes.put(test.id(), new DraftChange(DraftChange.Kind.EDITED, UnifiedDiff.of(shown),
					shown.size() < deltas.size()));
			}
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
		return changes;
	}

	/**
	 * Returns the differences between a recorded prompt test's files in the project and in a draft directory. A
	 * scenario in an earlier format is compared with the {@value PromptTestFiles#SCENARIO} that converts it.
	 *
	 * @param test    the recorded prompt test
	 * @param draft   the draft directory
	 * @param project the project
	 * @return the differences, by path
	 * @throws IOException if a file cannot be read
	 */
	private static List<FileDelta> deltas(RecordedTest test, Path draft, Path project) throws IOException
	{
		Path directory = test.directory();
		Set<Path> paths = new TreeSet<>();
		Map<Path, Path> before = new HashMap<>();
		if (test.citation().endsWith(MARKDOWN) && !test.citation().endsWith("/" + PromptTestFiles.SCENARIO) &&
			!test.citation().equals(PromptTestFiles.SCENARIO))
		{
			// A scenario in an earlier format becomes the scenario of a new directory
			Path scenario = directory.resolve(PromptTestFiles.SCENARIO);
			before.put(scenario, project.resolve(test.citation()));
			paths.add(scenario);
		}
		else
		{
			Set<Path> trees = new TreeSet<>();
			trees.add(directory);
			trees.addAll(PromptTestLocations.sharedFixtures(project, directory.resolve(PromptTestFiles.SCENARIO)));
			trees.addAll(PromptTestLocations.sharedFixtures(draft, directory.resolve(PromptTestFiles.SCENARIO)));
			for (Path tree : trees)
			{
				for (Path file : files(project, tree))
				{
					before.put(file, project.resolve(file));
					paths.add(file);
				}
			}
		}
		Set<Path> drafted = new TreeSet<>();
		drafted.add(directory);
		drafted.addAll(PromptTestLocations.sharedFixtures(draft, directory.resolve(PromptTestFiles.SCENARIO)));
		for (Path tree : drafted)
			paths.addAll(files(draft, tree));

		List<FileDelta> deltas = new ArrayList<>();
		for (Path path : paths)
		{
			FileContent old = content(before.get(path));
			FileContent current = content(draft.resolve(path));
			if (old == null && current == null)
				continue;
			if (old != null && old.equals(current))
				continue;
			deltas.add(new FileDelta(path, old, current, Set.of()));
		}
		return deltas;
	}

	/**
	 * Returns the regular files below a directory.
	 *
	 * @param root      the directory that {@code directory} is relative to
	 * @param directory the directory, relative to {@code root}
	 * @return each file, relative to {@code root}, or an empty list if the directory does not exist
	 * @throws IOException if the directory cannot be read
	 */
	private static List<Path> files(Path root, Path directory) throws IOException
	{
		Path absolute = root.resolve(directory);
		if (!Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS))
			return List.of();
		try (Stream<Path> walk = Files.walk(absolute))
		{
			return walk.filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)).
				map(root::relativize).toList();
		}
	}

	/**
	 * Reads a regular file.
	 *
	 * @param file the file, or {@code null}
	 * @return the content, or {@code null} if {@code file} is null or not a regular file
	 * @throws IOException if the file cannot be read
	 */
	private static FileContent content(Path file) throws IOException
	{
		if (file == null || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
			return null;
		return new FileContent(Files.readAllBytes(file));
	}

	/**
	 * The prompt tests of one proposal file read from a draft directory.
	 */
	private final class DraftAssembly
	{
		private final Path project;
		private final Path draft;
		private final boolean newMustBeAbsent;
		/**
		 * The completed form of each prompt test whose files the draft directory need not hold, by its directory.
		 */
		private final Map<Path, ObjectNode> fixed;
		private final List<String> problems;
		/**
		 * The files of each prompt test, by its index in the proposal file.
		 */
		private final Map<Integer, TestFiles> testFiles = new HashMap<>();
		private final Map<String, List<TestCase>> committed = new HashMap<>();
		private final Map<String, List<FixtureFile>> fixtures = new HashMap<>();
		private ObjectNode proposal;

		/**
		 * Creates a reader.
		 *
		 * @param project         the directory that problems name files relative to
		 * @param draft           the draft directory
		 * @param newMustBeAbsent {@code true} if a new prompt test's directory must not exist in {@code project}
		 * @param fixed           the completed form of each prompt test whose files the draft directory need not
		 *                        hold, by its directory
		 * @param problems        receives each problem
		 */
		DraftAssembly(Path project, Path draft, boolean newMustBeAbsent, Map<Path, ObjectNode> fixed, List<String> problems)
		{
			this.project = project;
			this.draft = draft;
			this.newMustBeAbsent = newMustBeAbsent;
			this.fixed = fixed;
			this.problems = problems;
		}

		/**
		 * Returns the proposal with each prompt test completed from its files.
		 *
		 * @return the proposal
		 */
		ObjectNode proposal()
		{
			return proposal;
		}

		/**
		 * Returns the test cases that earlier campaigns committed to the recorded prompt tests.
		 *
		 * @return the test cases, by prompt test identifier
		 */
		Map<String, List<TestCase>> committed()
		{
			return committed;
		}

		/**
		 * Returns the text fixtures of each prompt test, each with its file in the draft directory as its source, which
		 * is also the file that delivery writes.
		 *
		 * @return the fixtures, by prompt test identifier
		 */
		Map<String, List<FixtureFile>> fixtures()
		{
			return fixtures;
		}

		/**
		 * Completes each prompt test of a proposal file from its files.
		 *
		 * @param proposalFile a proposal file
		 * @param invalid      the indexes of the items of {@code tests} that break the proposal file's schema, which
		 *                     are left out
		 * @return this reader, or {@code null} if a problem was added or {@code tests} is not an array
		 */
		DraftAssembly assemble(ObjectNode proposalFile, Set<Integer> invalid)
		{
			if (!(proposalFile.get("tests") instanceof ArrayNode items))
				return null;
			int problemsBefore = problems.size();
			ObjectNode completed = proposalFile.deepCopy();
			ArrayNode tests = completed.putArray("tests");
			Set<String> ids = new HashSet<>();
			for (int i = 0; i < items.size(); ++i)
			{
				if (invalid.contains(i))
					continue;
				ObjectNode test = test((ObjectNode) items.get(i), i, ids);
				if (test != null)
					tests.add(test);
			}
			if (problems.size() > problemsBefore)
				return null;
			proposal = completed;
			return this;
		}

		/**
		 * Completes one prompt test of a proposal file from its files.
		 *
		 * @param item  the proposal file's item
		 * @param index the item's index
		 * @param ids   the identifiers of the prompt tests before it; receives this one's
		 * @return the completed prompt test, or {@code null} if a problem was added
		 */
		private ObjectNode test(ObjectNode item, int index, Set<String> ids)
		{
			String location = "$.tests[" + index + "]";
			String text = item.get("directory").asString();
			String at = location + ".directory";
			Path directory = directory(text, at);
			if (directory == null)
				return null;
			ObjectNode fixedTest = fixed.get(directory);
			if (fixedTest != null)
			{
				if (!ids.add(fixedTest.get("id").asString()))
				{
					problems.add(at + ": another prompt test has the identifier `" + fixedTest.get("id").asString() +
						"`");
					return null;
				}
				return fixedTest(fixedTest);
			}
			RecordedTest recordedTest = recorded.get(directory);
			String id;
			if (recordedTest != null)
				id = DraftDirectory.snakeCase(directory.getFileName().toString());
			else
			{
				id = directory.getFileName().toString();
				if (!newDirectory(directory, id, at))
					return null;
			}
			if (!ids.add(id))
			{
				problems.add(at + ": another prompt test has the identifier `" + id + "`, which is the last name of " +
					"its directory in lowercase words joined by underscores");
				return null;
			}

			Path testDirectory = draft.resolve(directory);
			Path scenarioFile = testDirectory.resolve(PromptTestFiles.SCENARIO);
			if (!Files.isRegularFile(scenarioFile, LinkOption.NOFOLLOW_LINKS))
			{
				problems.add(display(scenarioFile) + ": the file is missing. Each prompt test's directory in the " +
					"draft directory holds its " + PromptTestFiles.SCENARIO + ".");
				return null;
			}
			PromptTestFiles files;
			String content;
			try
			{
				Path root = draft.resolve(PromptTestLocations.rootOf(directory).orElseThrow());
				files = PromptTestFiles.readScenario(project, root, testDirectory);
				content = Files.readString(scenarioFile, UTF_8);
			}
			catch (ScenarioFormatException e)
			{
				problems.add(e.getMessage());
				return null;
			}
			catch (CharacterCodingException _)
			{
				problems.add(display(scenarioFile) + ": the file is not UTF-8 text");
				return null;
			}
			catch (IOException e)
			{
				throw new UncheckedIOException(e);
			}
			Scenario scenario = files.scenario();
			if (scenario.expectedBehavior().isEmpty())
			{
				problems.add(display(scenarioFile) + ": the scenario has no ## Expected Behavior section. Add one " +
					"between ## Scenario and ## Assertions with a description of what a compliant reply does and its " +
					"time_limit_seconds.");
				return null;
			}

			List<TestCase> committedTestCases = List.of();
			if (recordedTest != null)
				committedTestCases = recordedTest.committed();
			TestFiles filesOfTest = new TestFiles(display(testDirectory), display(scenarioFile),
				display(testDirectory.resolve(ValuesFile.NAME)), new ArrayList<>(), new ArrayList<>());
			ArrayNode testCases = testCases(testDirectory, committedTestCases, filesOfTest);
			ArrayNode assertions = assertions(item, location, scenario, filesOfTest);
			if (testCases == null || assertions == null)
				return null;
			Optional<List<FixtureFile>> readFixtures = textFixtures(files);
			if (readFixtures.isEmpty())
				return null;
			List<FixtureFile> textFixtures = readFixtures.get();

			ObjectNode test = json.createObjectNode();
			test.put("id", id);
			test.set("risk_rank", item.get("risk_rank"));
			test.set("requirement_ids", item.get("requirement_ids"));
			test.set("absent_triggers", item.get("absent_triggers"));
			test.set("applicability", item.get("applicability"));
			ArrayNode turns = test.putArray("turns");
			scenario.turns().forEach(turns::add);
			ExpectedBehavior expected = scenario.expectedBehavior().get();
			test.put("time_limit_seconds", expected.timeLimit().toSeconds());
			test.put("expected_behavior", expected.description());
			test.set("uncovered_interactions", item.get("uncovered_interactions"));
			ArrayNode citations = test.putArray("regression_scenarios");
			if (recordedTest != null)
				citations.add(recordedTest.citation());
			ObjectNode draftNode = test.putObject("draft");
			draftNode.put("directory", PortablePaths.text(directory));
			draftNode.put("scenario", content);
			ArrayNode variables = test.putArray("variables");
			for (VariableDeclaration variable : scenario.variables())
			{
				ObjectNode variableNode = variables.addObject();
				variableNode.put("name", variable.name());
				variableNode.put("description", variable.description());
			}
			test.putArray("fixtures");
			test.set("test_cases", testCases);
			test.set("assertions", assertions);

			if (!committedTestCases.isEmpty())
				committed.put(id, committedTestCases);
			if (!textFixtures.isEmpty())
				fixtures.put(id, textFixtures);
			testFiles.put(index, filesOfTest);
			return test;
		}

		/**
		 * Returns a prompt test whose completed form the caller supplied, in the form that the other tests take: its
		 * committed test cases and its fixtures, which come from files, move to {@link #committed()} and
		 * {@link #fixtures()}.
		 *
		 * @param completed the prompt test in the form that a completed proposal holds it
		 * @return the prompt test without its committed test cases and fixtures
		 */
		private ObjectNode fixedTest(ObjectNode completed)
		{
			ObjectNode test = completed.deepCopy();
			String id = test.get("id").asString();
			List<TestCase> committedTestCases = new ArrayList<>();
			for (JsonNode testCase : test.remove(ProposalSchema.COMMITTED_TEST_CASES))
			{
				committedTestCases.add(new TestCase(TestValueJson.read(testCase.get("values")),
					testCase.get(TestCase.EXPECTED_OUTCOME).asString()));
			}
			if (!committedTestCases.isEmpty())
				committed.put(id, committedTestCases);
			List<FixtureFile> testFixtures = new ArrayList<>();
			JsonNode recordedFixtures = test.remove(ProposalSchema.RECORDED_FIXTURES);
			if (recordedFixtures != null)
			{
				for (JsonNode fixture : recordedFixtures)
				{
					testFixtures.add(new FixtureFile(fixture.get("path").asString(), fixture.get("content").asString(),
						fixture.get("source").asString()));
				}
			}
			if (!testFixtures.isEmpty())
				fixtures.put(id, testFixtures);
			return test;
		}

		/**
		 * Reads a prompt test's directory in the draft directory.
		 *
		 * @param text the directory as the proposal file states it
		 * @param at   the JSON path of the directory
		 * @return the directory, relative to the draft directory, or {@code null} if a problem was added
		 */
		private Path directory(String text, String at)
		{
			Path directory = Path.of(text);
			if (text.contains("\\") || directory.isAbsolute() || !directory.normalize().equals(directory) ||
				directory.startsWith("..") || text.endsWith("/"))
			{
				problems.add(at + ": `" + text + "` is not a directory relative to the draft directory, with / " +
					"separators and no . or .. names");
				return null;
			}
			if (PromptTestLocations.rootOf(directory).isEmpty())
			{
				problems.add(at + ": `" + text + "` lies outside the target's test directory `" +
					PortablePaths.text(scenarioDirectory) + "`");
				return null;
			}
			return directory;
		}

		/**
		 * Checks the directory of a prompt test that the project does not record.
		 *
		 * @param directory the directory, relative to the draft directory
		 * @param id        the prompt test's identifier, the directory's last name
		 * @param at        the JSON path of the directory
		 * @return {@code true} if the directory is valid; otherwise a problem was added
		 */
		private boolean newDirectory(Path directory, String id, String at)
		{
			String text = PortablePaths.text(directory);
			if (!scenarioDirectory.equals(directory.getParent()))
			{
				problems.add(at + ": `" + text + "` is neither a recorded prompt test of the target nor a directory " +
					"directly in the target's test directory `" + PortablePaths.text(scenarioDirectory) + "`");
				return false;
			}
			if (!Identifier.SNAKE_CASE.matcher(id).matches())
			{
				problems.add(at + ": the last name of a new prompt test's directory is its identifier, in lowercase " +
					"words joined by underscores, but `" + id + "` is not");
				return false;
			}
			if (newMustBeAbsent && Files.exists(project.resolve(directory), LinkOption.NOFOLLOW_LINKS))
			{
				problems.add(at + ": the project already holds `" + text + "`, which is not a recorded prompt test " +
					"of the target. Choose another directory for the new prompt test.");
				return false;
			}
			return true;
		}

		/**
		 * Reads the test cases of a prompt test's {@value ValuesFile#NAME}, leaving out those that earlier campaigns
		 * committed, which CAT adds.
		 *
		 * @param testDirectory the prompt test's directory
		 * @param committed     the test cases that earlier campaigns committed to it
		 * @param files         receives the line of each test case that the result holds
		 * @return the test cases, or {@code null} if a problem was added
		 */
		private ArrayNode testCases(Path testDirectory, List<TestCase> committed, TestFiles files)
		{
			Path valuesFile = testDirectory.resolve(ValuesFile.NAME);
			if (!Files.isRegularFile(valuesFile, LinkOption.NOFOLLOW_LINKS))
			{
				problems.add(files.values() + ": the file is missing. Each prompt test's directory holds its test " +
					"cases in " + ValuesFile.NAME + ".");
				return null;
			}
			List<DraftTestCase> drafted;
			try (BufferedReader in = Files.newBufferedReader(valuesFile, UTF_8))
			{
				drafted = ValuesFile.readDraft(json, in, files.values());
			}
			catch (ScenarioFormatException e)
			{
				problems.add(relativize(e.getMessage()));
				return null;
			}
			catch (IOException e)
			{
				throw new UncheckedIOException(e);
			}
			ArrayNode testCases = json.createArrayNode();
			for (int i = 0; i < drafted.size(); ++i)
			{
				DraftTestCase testCase = drafted.get(i);
				if (isCommitted(testCase.testCase(), committed))
					continue;
				testCases.add(testCase.testCase());
				files.testCases().add(new TestCaseLine(testCase.line(), i + 1));
			}
			return testCases;
		}

		/**
		 * Indicates whether a test case of a values file is one that an earlier campaign committed.
		 *
		 * @param testCase  the test case as the values file holds it
		 * @param committed the committed test cases
		 * @return {@code true} if the test case states the seed of each call and equals a committed one
		 */
		private static boolean isCommitted(ObjectNode testCase, List<TestCase> committed)
		{
			if (committed.isEmpty())
				return false;
			try
			{
				return committed.contains(new TestCase(TestValueJson.read(testCase.get("values")),
					testCase.get(TestCase.EXPECTED_OUTCOME).asString()));
			}
			catch (IllegalArgumentException _)
			{
				// A new test case may leave out its calls' seeds
				return false;
			}
		}

		/**
		 * Completes the assertions of a prompt test from its scenario.
		 *
		 * @param item         the proposal file's item
		 * @param location     the item's JSON path
		 * @param scenario     the prompt test's scenario
		 * @param files        the prompt test's files, which receive the identifier of each assertion
		 * @return the assertions, in the item's order, or {@code null} if a problem was added
		 */
		private ArrayNode assertions(ObjectNode item, String location, Scenario scenario, TestFiles files)
		{
			String scenarioFile = files.scenario();
			int problemsBefore = problems.size();
			Map<String, Assertion> declared = new LinkedHashMap<>();
			for (Assertion assertion : scenario.assertions())
				declared.put(assertion.id(), assertion);
			Set<String> mapped = new HashSet<>();
			ArrayNode assertions = json.createArrayNode();
			JsonNode items = item.get("assertions");
			for (int k = 0; k < items.size(); ++k)
			{
				String id = items.get(k).get("id").asString();
				String at = location + ".assertions[" + k + "].id";
				Assertion assertion = declared.get(id);
				if (assertion == null)
				{
					problems.add(at + ": " + scenarioFile + " declares no assertion `" + id + "`. Declared: " +
						declared.keySet());
					continue;
				}
				if (!mapped.add(id))
				{
					problems.add(at + ": the assertion `" + id + "` has another item. Map each assertion of " +
						scenarioFile + " to one requirement.");
					continue;
				}
				ObjectNode full = assertions.addObject();
				files.assertionIds().add(id);
				full.put("id", id);
				full.put("kind", EnumNames.lowerCase(assertion.kind()));
				full.put("tool", assertion.tool());
				full.put("on_tool_failure", EnumNames.lowerCase(assertion.onToolFailure()));
				full.put("statement", assertion.statement());
				full.put("requirement_id", items.get(k).get("requirement_id").asString());
			}
			for (String id : declared.keySet())
			{
				if (!mapped.contains(id))
				{
					problems.add(location + ".assertions: " + scenarioFile + " declares the assertion `" + id +
						"`, which no item maps to the requirement that it checks");
				}
			}
			if (problems.size() > problemsBefore)
				return null;
			return assertions;
		}

		/**
		 * Reads the text fixtures that a prompt test's patterns select.
		 *
		 * @param files the prompt test's files
		 * @return the fixtures, each with its file relative to the draft directory as its source, or empty if a problem
		 *   was added
		 */
		private Optional<List<FixtureFile>> textFixtures(PromptTestFiles files)
		{
			List<FixtureFile> textFixtures = new ArrayList<>();
			for (PromptTestFiles.Fixture fixture : files.fixtures())
			{
				if (!fixture.text())
					continue;
				Path source = files.source(fixture);
				try
				{
					textFixtures.add(new FixtureFile(fixture.path(), Files.readString(source, UTF_8),
						PortablePaths.text(draft.relativize(source))));
				}
				catch (IllegalArgumentException e)
				{
					problems.add(display(source) + ": " + e.getMessage());
					return Optional.empty();
				}
				catch (IOException e)
				{
					throw new UncheckedIOException(e);
				}
			}
			return Optional.of(textFixtures);
		}

		/**
		 * Returns a problem of the completed proposal with the file and line, or the JSON path of the proposal
		 * file, that it concerns.
		 *
		 * @param problem a problem whose location is a JSON path of the completed proposal
		 * @return the problem
		 */
		String locate(String problem)
		{
			Matcher matcher = TEST_PROBLEM.matcher(problem);
			if (!matcher.matches())
				return problem;
			int index = Integer.parseInt(matcher.group(1));
			String path = matcher.group(2);
			String message = matcher.group(3);
			TestFiles files = testFiles.get(index);
			// A prompt test given in its completed form has no files to name
			if (files == null)
				return problem;
			String location = "$.tests[" + index + "]";
			if (path.isEmpty())
				return location + " (" + files.directory() + "): " + message;
			if (path.equals(".id"))
				return location + ".directory: " + message;
			Matcher testCase = TEST_CASE_PATH.matcher(path);
			if (testCase.matches())
			{
				TestCaseLine line = files.testCases().get(Integer.parseInt(testCase.group(1)));
				return files.values() + ":" + line.line() + ": test case " + line.number() + testCase.group(2) + ": " +
					message;
			}
			Matcher assertion = ASSERTION_PATH.matcher(path);
			if (assertion.matches())
			{
				int k = Integer.parseInt(assertion.group(1));
				return location + ".assertions[" + k + "]" + assertion.group(2) + " (" + files.scenario() +
					", assertion `" + files.assertionIds().get(k) + "`): " + message;
			}
			for (String field : SCENARIO_FIELDS)
			{
				if (path.startsWith(field))
					return files.scenario() + ": " + message;
			}
			return location + path + ": " + message;
		}

		/**
		 * Returns a path as problems name it: relative to the project when it lies inside it.
		 *
		 * @param file the absolute path
		 * @return the path's text
		 */
		private String display(Path file)
		{
			if (file.startsWith(project))
				return PortablePaths.text(project.relativize(file));
			return file.toString();
		}

		/**
		 * Replaces the absolute path of the project in a message with paths relative to it.
		 *
		 * @param message the message
		 * @return the message
		 */
		private String relativize(String message)
		{
			return message.replace(project.toString() + project.getFileSystem().getSeparator(), "");
		}
	}

	/**
	 * The files of one prompt test, as problems name them.
	 *
	 * @param directory    the prompt test's directory
	 * @param scenario     its {@value PromptTestFiles#SCENARIO}
	 * @param values       its {@value ValuesFile#NAME}
	 * @param testCases    the line and number of each test case that the completed prompt test holds, in order
	 * @param assertionIds the identifier of each assertion that the completed prompt test holds, in order
	 */
	private record TestFiles(String directory, String scenario, String values, List<TestCaseLine> testCases,
		List<String> assertionIds)
	{
	}

	/**
	 * Where a test case lies in its values file.
	 *
	 * @param line   the line on which it starts
	 * @param number its position in the file, starting at 1
	 */
	private record TestCaseLine(int line, int number)
	{
	}
}
