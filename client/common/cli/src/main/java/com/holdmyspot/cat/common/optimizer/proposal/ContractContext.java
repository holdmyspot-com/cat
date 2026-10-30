package com.holdmyspot.cat.common.optimizer.proposal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.PortablePaths;
import com.holdmyspot.cat.common.optimizer.contract.TestCase;
import com.holdmyspot.cat.common.optimizer.contract.TestValueJson;
import com.holdmyspot.cat.common.optimizer.contract.TrialProfile;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;
import com.holdmyspot.cat.common.optimizer.proposal.ProposalCheck.RecordedTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * What the contract tools need to validate a contract author's proposal file and draft directory inside the contract
 * author's session, without reading the campaign's held-out directory. CAT writes it into the session's project
 * beside the proposal file.
 * <p>
 * It holds no salt: the tools validate with a salt of {@code 0}, so that they never reveal which expected outcomes the
 * proposal's salt assigns to the held-out part.
 *
 * @param proposalFile      the name of the proposal file, or of a test revision's result file, relative to the project
 * @param draft             the name of the draft directory, relative to the project
 * @param target            the campaign's target, relative to the project
 * @param reportsExitStatus {@code true} if the harness reports the exit status of commands
 * @param role              the profile of the harness's role sessions, which the judge runs with
 * @param profiles          the profile inventory, its capability ranking and the bindings that CAT read
 * @param toolOutputBytes   the number of bytes of each tool output that a trial receives whole, for each profile of
 *                          the inventory whose limit the harness states
 * @param recorded          the recorded prompt tests of the target
 * @param revision          the confirmed contract that a test revision session examines a prompt test of, or empty
 *                          for a session that writes a proposal
 */
public record ContractContext(String proposalFile, String draft, Path target, boolean reportsExitStatus,
	TrialProfile role, ProfileEvidence profiles, Map<TrialProfile, Long> toolOutputBytes, List<RecordedTest> recorded,
	Optional<Revision> revision)
{
	/**
	 * The name of the file when the session's project does not already hold a file by that name.
	 */
	public static final String NAME = "cat-contract.json";
	/**
	 * The start of the file's name when the project already holds {@link #NAME}.
	 */
	private static final String STEM = "cat-contract-";
	/**
	 * The version of the file's layout.
	 */
	private static final int FORMAT_VERSION = 1;

	/**
	 * Creates a context.
	 *
	 * @param proposalFile      the name of the proposal file, relative to the project
	 * @param draft             the name of the draft directory, relative to the project
	 * @param target            the campaign's target, relative to the project
	 * @param reportsExitStatus {@code true} if the harness reports the exit status of commands
	 * @param role              the profile of the harness's role sessions, which the judge runs with
	 * @param profiles          the profile inventory, its capability ranking and the bindings that CAT read
	 * @param toolOutputBytes   the number of bytes of each tool output that a trial receives whole, for each profile
	 *                          of the inventory whose limit the harness states
	 * @param recorded          the recorded prompt tests of the target
	 * @param revision          the confirmed contract that a test revision session examines a prompt test of, or
	 *                          empty for a session that writes a proposal
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code target} is absolute, or {@code proposalFile} or {@code draft} are
	 *                                  blank
	 */
	public ContractContext
	{
		requireThat(proposalFile, "proposalFile").isNotBlank();
		requireThat(draft, "draft").isNotBlank();
		requireThat(target.isAbsolute(), "target.isAbsolute()").isFalse();
		requireThat(role, "role").isNotNull();
		requireThat(profiles, "profiles").isNotNull();
		toolOutputBytes = Map.copyOf(toolOutputBytes);
		recorded = List.copyOf(recorded);
		requireThat(revision, "revision").isNotNull();
	}

	/**
	 * The confirmed contract that a test revision session examines a prompt test of. It holds the expected outcomes
	 * of every confirmed test case but not the salt, so it reveals none of their parts.
	 *
	 * @param test         the identifier of the prompt test that the session examines
	 * @param proposalFile the confirmed proposal file
	 * @param tests        the confirmed prompt tests, each in the form that a completed proposal holds it
	 * @param sharedFiles  the hash of each file that the session's draft directory received outside the examined
	 *                     test's directory, by its path relative to the draft directory, which a correction leaves
	 *                     unchanged
	 */
	public record Revision(String test, ObjectNode proposalFile, ArrayNode tests, Map<Path, Sha256> sharedFiles)
	{
		/**
		 * Creates a revision.
		 *
		 * @param test         the identifier of the prompt test that the session examines
		 * @param proposalFile the confirmed proposal file
		 * @param tests        the confirmed prompt tests, each in the form that a completed proposal holds it
		 * @param sharedFiles  the hash of each file that the session's draft directory received outside the examined
		 *                     test's directory, by its path relative to the draft directory
		 * @throws NullPointerException     if any of the arguments are null
		 * @throws IllegalArgumentException if {@code test} is blank
		 */
		public Revision
		{
			requireThat(test, "test").isNotBlank();
			requireThat(proposalFile, "proposalFile").isNotNull();
			requireThat(tests, "tests").isNotNull();
			sharedFiles = Map.copyOf(sharedFiles);
		}
	}

	/**
	 * Returns the name of a session's context file: {@link #NAME}, or the first of {@code cat-contract-2.json},
	 * {@code cat-contract-3.json} and so on that the project does not hold.
	 *
	 * @param project the session's project as it was before CAT added the session's files
	 * @return the file name, relative to the project
	 * @throws NullPointerException if {@code project} is null
	 */
	public static String name(Path project)
	{
		requireThat(project, "project").isNotNull();
		String name = NAME;
		for (int suffix = 2; Files.exists(project.resolve(name), LinkOption.NOFOLLOW_LINKS); ++suffix)
			name = STEM + suffix + ".json";
		return name;
	}

	/**
	 * Writes this context to a file.
	 *
	 * @param json the JSON mapper
	 * @param file the file, which must not exist
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the file exists or cannot be written
	 */
	public void write(JsonMapper json, Path file) throws IOException
	{
		requireThat(json, "json").isNotNull();
		requireThat(file, "file").isNotNull();
		ObjectNode root = json.createObjectNode();
		root.put("format_version", FORMAT_VERSION);
		root.put("proposal_file", proposalFile);
		root.put("draft", draft);
		root.put("target", PortablePaths.text(target));
		root.put("reports_exit_status", reportsExitStatus);
		ObjectNode roleNode = root.putObject("role");
		roleNode.put("model", role.model());
		roleNode.put("effort", role.effort());
		profiles.write(root.putObject("profiles"));
		ArrayNode limits = root.putArray("tool_output_bytes");
		List<Map.Entry<TrialProfile, Long>> entries = new ArrayList<>(toolOutputBytes.entrySet());
		// Profiles in label order keep the file the same for the same inventory
		entries.sort(Comparator.comparing(entry -> entry.getKey().label()));
		for (Map.Entry<TrialProfile, Long> entry : entries)
		{
			ObjectNode limit = limits.addObject();
			limit.put("model", entry.getKey().model());
			limit.put("effort", entry.getKey().effort());
			limit.put("tool_output_token_limit", entry.getKey().toolOutputTokenLimit());
			limit.put("bytes", entry.getValue());
		}
		ArrayNode recordedNodes = root.putArray("recorded");
		for (RecordedTest test : recorded)
		{
			ObjectNode node = recordedNodes.addObject();
			node.put("citation", test.citation());
			node.put("directory", PortablePaths.text(test.directory()));
			ArrayNode committed = node.putArray("committed_test_cases");
			for (TestCase testCase : test.committed())
			{
				ObjectNode testCaseNode = committed.addObject();
				TestValueJson.write(testCase.values(), testCaseNode.putArray("values"));
				testCaseNode.put(TestCase.EXPECTED_OUTCOME, testCase.expectedOutcome());
			}
		}
		if (revision.isPresent())
		{
			ObjectNode revisionNode = root.putObject("revision");
			revisionNode.put("test", revision.get().test());
			revisionNode.set("confirmed_proposal", revision.get().proposalFile());
			revisionNode.set("confirmed_tests", revision.get().tests());
			ArrayNode sharedNodes = revisionNode.putArray("shared_files");
			// Paths in order keep the file the same for the same draft directory
			for (Map.Entry<Path, Sha256> entry : new TreeMap<>(revision.get().sharedFiles()).entrySet())
			{
				sharedNodes.addObject().put("path", PortablePaths.text(entry.getKey())).
					put("sha256", entry.getValue().hex());
			}
		}
		Files.writeString(file, json.writerWithDefaultPrettyPrinter().writeValueAsString(root), UTF_8,
			StandardOpenOption.CREATE_NEW);
	}

	/**
	 * Reads a context file.
	 *
	 * @param json the JSON mapper
	 * @param file the file
	 * @return the context
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IOException          if the file cannot be read or is not a context file of this version
	 */
	public static ContractContext read(JsonMapper json, Path file) throws IOException
	{
		requireThat(json, "json").isNotNull();
		requireThat(file, "file").isNotNull();
		JsonNode root;
		try
		{
			root = json.readTree(Files.readString(file, UTF_8));
		}
		catch (JacksonException e)
		{
			throw new IOException(file + " is not JSON: " + e.getOriginalMessage(), e);
		}
		if (root == null || root.path("format_version").asInt() != FORMAT_VERSION)
			throw new IOException(file + " is not a version " + FORMAT_VERSION + " contract context file");
		try
		{
			List<RecordedTest> recorded = new ArrayList<>();
			for (JsonNode node : root.path("recorded"))
			{
				List<TestCase> committed = new ArrayList<>();
				for (JsonNode testCase : node.path("committed_test_cases"))
				{
					committed.add(new TestCase(TestValueJson.read(testCase.get("values")),
						testCase.path(TestCase.EXPECTED_OUTCOME).asString()));
				}
				recorded.add(new RecordedTest(node.path("citation").asString(),
					Path.of(node.path("directory").asString()), committed));
			}
			Map<TrialProfile, Long> toolOutputBytes = new HashMap<>();
			for (JsonNode limit : root.path("tool_output_bytes"))
			{
				TrialProfile profile = TrialProfile.stored(limit.path("model").asString(),
					limit.path("effort").asString());
				int tokens = limit.path("tool_output_token_limit").asInt();
				if (tokens != 0)
					profile = profile.withToolOutputTokenLimit(tokens);
				toolOutputBytes.put(profile, limit.path("bytes").asLong());
			}
			JsonNode role = root.path("role");
			Optional<Revision> revision = Optional.empty();
			JsonNode revisionNode = root.get("revision");
			if (revisionNode != null)
			{
				if (!(revisionNode.get("confirmed_proposal") instanceof ObjectNode proposalFile) ||
					!(revisionNode.get("confirmed_tests") instanceof ArrayNode tests))
				{
					throw new IOException(file + " is not a valid contract context file: its revision lacks the " +
						"confirmed proposal or tests");
				}
				Map<Path, Sha256> sharedFiles = new HashMap<>();
				for (JsonNode shared : revisionNode.path("shared_files"))
				{
					sharedFiles.put(Path.of(shared.path("path").asString()),
						new Sha256(shared.path("sha256").asString()));
				}
				revision = Optional.of(new Revision(revisionNode.path("test").asString(), proposalFile, tests,
					sharedFiles));
			}
			return new ContractContext(root.path("proposal_file").asString(), root.path("draft").asString(),
				Path.of(root.path("target").asString()), root.path("reports_exit_status").asBoolean(),
				TrialProfile.stored(role.path("model").asString(), role.path("effort").asString()),
				ProfileEvidence.read(root.path("profiles")), toolOutputBytes, recorded, revision);
		}
		catch (IllegalArgumentException | NullPointerException e)
		{
			throw new IOException(file + " is not a valid contract context file: " + e.getMessage(), e);
		}
	}
}
