package com.holdmyspot.cat.common.optimizer.cli;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.PortablePaths;
import com.holdmyspot.cat.common.optimizer.campaign.Harness;
import com.holdmyspot.cat.common.optimizer.campaign.Verdict;
import com.holdmyspot.cat.common.optimizer.contract.DerivedSource;
import com.holdmyspot.cat.common.optimizer.execution.AssertionReview.Finding;
import com.holdmyspot.cat.common.optimizer.execution.AssertionReview.Kind;
import com.holdmyspot.cat.common.optimizer.proposal.AssertionExampleCheck;
import com.holdmyspot.cat.common.optimizer.proposal.DerivedProposal;
import com.holdmyspot.cat.common.optimizer.proposal.DerivedProposal.ExampleJudgment;
import com.holdmyspot.cat.common.optimizer.proposal.DerivedProposal.TestReport;
import com.holdmyspot.cat.common.optimizer.proposal.DraftDirectory;
import com.holdmyspot.cat.common.optimizer.proposal.ProfileEvidence;
import com.holdmyspot.cat.common.optimizer.report.EnumNames;
import com.holdmyspot.cat.common.optimizer.store.FileCampaignStore;
import tools.jackson.databind.JsonNode;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Persists the contract author's latest proposal in the campaign's {@code held_out} directory so that
 * {@code approve} and {@code request-changes} can continue after the launcher exited.
 * <p>
 * The file holds the contract author's proposal file, which {@code ContractAuthorProtocol} validates again with its
 * draft directory when it is read, the draft directory that the held-out directory stores, the sources that the
 * contract author's session was observed loading, the profile inventory, capability ranking and bindings that CAT read
 * when the contract author ran, and the salt that CAT drew for the proposal. Confirmation therefore freezes the trial
 * profiles and the split into development and held-out test cases that the user saw.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
final class ProposalFile
{
	/**
	 * The file's name.
	 */
	static final String NAME = FileCampaignStore.PROPOSAL;
	/**
	 * The version of the proposal layout, including regression scenario citations, the variables of each prompt test
	 * and whether each is outcome-changing, the triggers of the requirements, the shipped capability
	 * ranking, the salt, the judge prompt's placeholders and the assertions of each prompt test, including what a
	 * failure of each assertion's built-in tool means, the derived prompt sources that CAT listed, and the proposal file
	 * whose prompt tests its stored draft directory holds.
	 */
	private static final int FORMAT_VERSION = 19;
	private static final String JUDGED_REPORTS = "judged_assertion_reports";
	/**
	 * The name of the file that holds the contract that a test revision corrected, while the revision's guard pilot
	 * has not ended.
	 */
	static final String REVISED_NAME = "revised_proposal.json";
	private final Path heldOut;
	private final Path file;
	private final boolean prunes;
	private final JsonMapper json;
	private final FileReplacer replacer;

	/**
	 * A stored proposal.
	 *
	 * @param derivation the contract author's proposal file, whose text already names the user's project and which the
	 *   caller validates again
	 * @param draft the stored draft directory that holds the proposal's prompt tests
	 * @param observedSources the prompt sources that the harness reported loading in the contract author session
	 * @param derivedSources the prompt files that CAT listed as loading although no harness event reports them
	 * @param profiles the profile inventory, capability ranking and bindings that CAT read when the contract author ran
	 * @param evaluators the staged loader and runtime evidence of the selected evaluators
	 * @param salt the salt that CAT drew for the proposal
	 * @param exampleChecks the decisions from mechanically checking the assertion examples
	 * @param judgedReports the judged assertion summaries by test identifier
	 */
	record Stored(String derivation, Path draft, String observedSources, List<DerivedSource> derivedSources,
		ProfileEvidence profiles, Map<Harness, EvaluatorMetadata> evaluators, long salt,
		List<AssertionExampleCheck> exampleChecks, Map<String, TestReport> judgedReports)
	{
		/**
		 * Creates a stored proposal.
		 *
		 * @param derivation the contract author's proposal file
		 * @param draft the stored draft directory that holds the proposal's prompt tests
		 * @param observedSources the prompt sources that the harness reported loading in the contract author session
		 * @param derivedSources the prompt files that CAT listed as loading although no harness event reports them
		 * @param profiles the profile inventory and bindings
		 * @param evaluators the staged loader and runtime evidence of the selected evaluators
		 * @param salt the salt that CAT drew for the proposal
		 * @param exampleChecks the decisions from mechanically checking the assertion examples
		 * @param judgedReports the judged assertion summaries by test identifier
		 * @throws NullPointerException if an argument, list element, or map key or value is null
		 */
		Stored
		{
			requireThat(derivation, "derivation").isNotNull();
			requireThat(draft, "draft").isNotNull();
			requireThat(observedSources, "observedSources").isNotNull();
			derivedSources = List.copyOf(derivedSources);
			exampleChecks = List.copyOf(exampleChecks);
			judgedReports = Map.copyOf(judgedReports);
			requireThat(profiles, "profiles").isNotNull();
			evaluators = Map.copyOf(evaluators);
			requireThat(evaluators.keySet(), "evaluators").isEqualTo(profiles.byEvaluator().keySet());
			for (Map.Entry<Harness, EvaluatorMetadata> entry : evaluators.entrySet())
			{
				EvaluatorMetadata report = entry.getValue();
				if (report.harness() != entry.getKey() || report.refusal().isPresent() ||
					!report.profiles().equals(profiles.byEvaluator().get(entry.getKey())))
				{
					throw new IllegalArgumentException("Inconsistent evidence for " + entry.getKey().productName());
				}
			}
		}

		/**
		 * Returns the validated proposal with the derived sources and assertion evidence summaries that CAT stored.
		 *
		 * @param validated the proposal that validating {@link #derivation()} returned
		 * @return the proposal
		 * @throws NullPointerException if {@code validated} is null
		 */
		DerivedProposal restore(DerivedProposal validated)
		{
			return validated.withDerivedSources(derivedSources).withExampleChecks(exampleChecks).
				withJudgedReports(judgedReports);
		}
	}

	/**
	 * Creates a proposal file.
	 *
	 * @param heldOut the campaign's {@code held_out} directory
	 * @param json the operation-owned JSON mapper
	 * @param replacer replaces the file
	 * @throws NullPointerException if any argument is null
	 */
	ProposalFile(Path heldOut, JsonMapper json, FileReplacer replacer)
	{
		this(heldOut, NAME, true, json, replacer);
	}

	/**
	 * Creates the file that holds a test revision's corrected contract beside the confirmed proposal. Writing it
	 * keeps every draft directory that the held-out directory stores, because one of them holds the confirmed
	 * contract, and the file never replaces the confirmed proposal.
	 *
	 * @param heldOut the campaign's {@code held_out} directory
	 * @param json the operation-owned JSON mapper
	 * @param replacer replaces the file
	 * @return the file
	 * @throws NullPointerException if any argument is null
	 */
	static ProposalFile revised(Path heldOut, JsonMapper json, FileReplacer replacer)
	{
		return new ProposalFile(heldOut, REVISED_NAME, false, json, replacer);
	}

	/**
	 * Creates a proposal file.
	 *
	 * @param heldOut the campaign's {@code held_out} directory
	 * @param name the file's name
	 * @param prunes {@code true} if writing the file removes every other draft directory that the held-out directory
	 *   stores
	 * @param json the operation-owned JSON mapper
	 * @param replacer replaces the file
	 * @throws NullPointerException if any argument is null
	 */
	private ProposalFile(Path heldOut, String name, boolean prunes, JsonMapper json, FileReplacer replacer)
	{
		requireThat(heldOut, "heldOut").isNotNull();
		requireThat(json, "json").isNotNull();
		requireThat(replacer, "replacer").isNotNull();
		this.heldOut = heldOut;
		this.file = heldOut.resolve(name);
		this.prunes = prunes;
		this.json = json;
		this.replacer = replacer;
	}

	/**
	 * Replaces the stored proposal, then removes every other draft directory that the held-out directory stores,
	 * unless the file sits beside the confirmed proposal.
	 *
	 * @param derived the proposal, whose draft directory the held-out directory stores
	 * @param profiles the profile inventory, capability ranking and bindings that CAT read when the contract author ran
	 * @param evaluators the staged loader and runtime evidence of the selected evaluators
	 * @throws NullPointerException if any of the arguments are null
	 * @throws IllegalArgumentException if the proposal's draft directory does not lie in the held-out directory
	 * @throws IOException if the file cannot be written or another draft directory cannot be removed
	 */
	void write(DerivedProposal derived, ProfileEvidence profiles, Map<Harness, EvaluatorMetadata> evaluators)
		throws IOException
	{
		requireThat(derived, "derived").isNotNull();
		requireThat(profiles, "profiles").isNotNull();
		Path draft = derived.draft();
		requireThat(draft.startsWith(heldOut), "draft.startsWith(heldOut)").isTrue();
		ObjectNode root = json.createObjectNode();
		root.put("format_version", FORMAT_VERSION);
		root.put("draft", PortablePaths.text(heldOut.relativize(draft)));
		root.put("observed_sources", derived.observedSources());
		ArrayNode derivedSources = root.putArray("derived_sources");
		for (DerivedSource source : derived.derivedSources())
			derivedSources.addObject().put("path", source.path()).put("rule", source.rule());
		root.put("derivation", derived.derivation());
		root.put("salt", derived.contract().salt());
		if (!derived.exampleChecks().isEmpty())
		{
			ArrayNode examples = root.putArray("example_checks");
			for (AssertionExampleCheck check : derived.exampleChecks())
			{
				examples.addObject().put("test", check.testId()).put("assertion", check.assertionId()).
					put("example", check.exampleId()).put("tool", check.tool()).put("passed", check.passed());
			}
		}
		writeJudgedReports(root, derived.judgedReports());
		profiles.write(root);
		if (!evaluators.isEmpty())
		{
			ArrayNode reports = root.putArray("evaluators");
			for (EvaluatorMetadata evaluator : new EnumMap<>(evaluators).values())
				evaluator.write(json, reports.addObject());
		}
		Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(temporary, json.writerWithDefaultPrettyPrinter().writeValueAsString(root),
			StandardCharsets.UTF_8);
		replacer.replace(temporary, file);
		// The stored proposal names the new draft directory only now, so the previous one stayed whole until then
		if (prunes)
			DraftDirectory.keepOnly(heldOut, draft);
	}

	/**
	 * Writes the proposal's judged assertion summaries if present.
	 *
	 * @param root the proposal document
	 * @param reports the judged reports by test identifier
	 */
	private static void writeJudgedReports(ObjectNode root, Map<String, TestReport> reports)
	{
		if (reports.isEmpty())
			return;
		ArrayNode stored = root.putArray(JUDGED_REPORTS);
		for (Map.Entry<String, TestReport> entry : new TreeMap<>(reports).entrySet())
		{
			ObjectNode report = stored.addObject().put("test", entry.getKey());
			ArrayNode examples = report.putArray("examples");
			for (ExampleJudgment example : entry.getValue().examples())
			{
				examples.addObject().put("assertion", example.assertionId()).put("example", example.exampleId()).
					put("label", EnumNames.lowerCase(example.label())).put("rationale", example.rationale());
			}
			ArrayNode findings = report.putArray("findings");
			for (Finding finding : entry.getValue().findings())
			{
				findings.addObject().put("assertion", finding.assertionId()).put("kind", EnumNames.lowerCase(finding.kind())).
					put("tool", finding.tool()).put("rationale", finding.rationale());
			}
		}
	}

	/**
	 * Deletes the stored proposal, without touching any draft directory.
	 *
	 * @return {@code true} if a proposal was stored
	 * @throws IOException if the file cannot be deleted
	 */
	boolean delete() throws IOException
	{
		return Files.deleteIfExists(file);
	}

	/**
	 * Reads the stored proposal.
	 *
	 * @return the proposal, or empty if no proposal is stored
	 * @throws IOException if the file cannot be read or is not a proposal file of a readable version
	 */
	Optional<Stored> read() throws IOException
	{
		if (!Files.exists(file))
			return Optional.empty();
		JsonNode root = json.readTree(Files.readString(file, StandardCharsets.UTF_8));
		int version = root.path("format_version").asInt();
		if (version != FORMAT_VERSION)
		{
			throw new IOException(file + " is a version " + version + " proposal file, but this version of CAT reads " +
				"version " + FORMAT_VERSION + ". Run `start` again to derive a new proposal.");
		}
		if (!root.path("derivation").isString() || !root.path("draft").isString() ||
			!root.path("observed_sources").isString() ||
			!root.path("derived_sources").isArray() || !root.path("salt").isIntegralNumber() ||
			!root.path("salt").canConvertToLong())
		{
			throw new IOException(file + " is not a valid version " + version + " proposal file.");
		}
		String derivation = root.get("derivation").asString();
		Path draft = heldOut.resolve(root.get("draft").asString()).normalize();
		if (!draft.startsWith(heldOut) || draft.equals(heldOut) || !Files.isDirectory(draft))
			throw new IOException(file + " names the draft directory " + draft + ", which the held-out directory does " +
				"not store.");
		List<DerivedSource> derivedSources = new ArrayList<>();
		try
		{
			for (JsonNode node : root.get("derived_sources"))
				derivedSources.add(new DerivedSource(node.path("path").asString(), node.path("rule").asString()));
			return Optional.of(new Stored(derivation, draft, root.get("observed_sources").asString(), derivedSources,
				ProfileEvidence.read(root), readEvaluators(root.path("evaluators")), root.get("salt").asLong(),
				readExampleChecks(root.path("example_checks")), readJudgedReports(root.path(JUDGED_REPORTS))));
		}
		catch (IllegalArgumentException | JacksonException e)
		{
			throw new IOException(file + " holds invalid derived sources, evaluator evidence, example checks or judged " +
				"assertion reports: " + e.getMessage(), e);
		}
	}

	/**
	 * Reads the mechanical decisions in their presentation order. Earlier proposals have no decisions.
	 *
	 * @param node the stored decisions, if present
	 * @return the mechanical results
	 */
	private static List<AssertionExampleCheck> readExampleChecks(JsonNode node)
	{
		if (node.isMissingNode())
			return List.of();
		if (!node.isArray())
			throw new IllegalArgumentException("Expected an array of assertion example checks.");
		List<AssertionExampleCheck> result = new ArrayList<>();
		for (JsonNode example : node)
		{
			JsonNode tool = example.path("tool");
			if (!example.path("test").isString() || !example.path("assertion").isString() ||
				!example.path("example").isString() || !(tool.isString() || tool.isNull()) ||
				!example.path("passed").isBoolean())
			{
				throw new IllegalArgumentException("Invalid assertion example check.");
			}
			result.add(new AssertionExampleCheck(example.get("test").asString(), example.get("assertion").asString(),
				example.get("example").asString(), tool.stringValue(), example.get("passed").booleanValue()));
		}
		return result;
	}

	/**
	 * Reads an optional array of judged assertion summaries. Earlier proposals have no summaries.
	 *
	 * @param node the stored reports, if present
	 * @return the reports by test identifier
	 * @throws IllegalArgumentException if the stored reports are malformed or a test appears twice
	 */
	private static Map<String, TestReport> readJudgedReports(JsonNode node)
	{
		if (node.isMissingNode())
			return Map.of();
		if (!node.isArray())
			throw new IllegalArgumentException("Expected an array of judged assertion reports.");
		Map<String, TestReport> reports = new HashMap<>();
		for (JsonNode report : node)
		{
			if (!report.isObject() || report.size() != 3 || !report.path("test").isString() ||
				!report.path("examples").isArray() || !report.path("findings").isArray())
			{
				throw new IllegalArgumentException("Invalid judged assertion report.");
			}
			String testId = report.get("test").asString();
			requireThat(testId, "testId").isNotBlank();
			List<ExampleJudgment> examples = new ArrayList<>();
			for (JsonNode example : report.get("examples"))
				examples.add(readJudgedExample(example));
			List<Finding> findings = new ArrayList<>();
			for (JsonNode finding : report.get("findings"))
				findings.add(readAssertionFinding(finding));
			if (reports.putIfAbsent(testId, new TestReport(examples, findings)) != null)
				throw new IllegalArgumentException("Repeated judged assertion report for " + testId);
		}
		return reports;
	}

	/**
	 * Reads a stored example label and rationale.
	 *
	 * @param example the example summary
	 * @return the judged example summary
	 * @throws IllegalArgumentException if the summary is malformed
	 */
	private static ExampleJudgment readJudgedExample(JsonNode example)
	{
		if (!example.isObject() || example.size() != 4 || !example.path("assertion").isString() ||
			!example.path("example").isString() || !example.path("label").isString() ||
			!example.path("rationale").isString())
		{
			throw new IllegalArgumentException("Invalid judged example.");
		}
		return new ExampleJudgment(example.get("assertion").asString(), example.get("example").asString(),
			EnumNames.parse(example.get("label").asString(), Verdict.class), example.get("rationale").asString());
	}

	/**
	 * Reads a stored assertion review finding.
	 *
	 * @param finding the finding summary
	 * @return the reviewer's finding
	 * @throws IllegalArgumentException if the finding is malformed
	 */
	private static Finding readAssertionFinding(JsonNode finding)
	{
		JsonNode tool = finding.path("tool");
		if (!finding.isObject() || finding.size() != 4 || !finding.path("assertion").isString() ||
			!finding.path("kind").isString() || !(tool.isNull() || tool.isString()) ||
			!finding.path("rationale").isString())
		{
			throw new IllegalArgumentException("Invalid assertion review finding.");
		}
		return new Finding(finding.get("assertion").asString(),
			EnumNames.parse(finding.get("kind").asString(), Kind.class), finding.get("rationale").asString(),
			tool.stringValue());
	}

	/**
	 * Restores the optional evaluator reports. Proposals written before shared evaluation have no report array.
	 *
	 * @param node the stored report array, if present
	 * @return the reports indexed by their evaluator
	 */
	private Map<Harness, EvaluatorMetadata> readEvaluators(JsonNode node)
	{
		if (node.isMissingNode())
			return Map.of();
		if (!node.isArray() || node.isEmpty() || node.size() > Harness.values().length)
			throw new IllegalArgumentException("Invalid evaluator reports");
		Map<Harness, EvaluatorMetadata> result = new EnumMap<>(Harness.class);
		for (JsonNode report : node)
		{
			EvaluatorMetadata metadata = EvaluatorMetadata.read(json, report);
			if (result.putIfAbsent(metadata.harness(), metadata) != null)
				throw new IllegalArgumentException("Repeated report for " + metadata.harness().productName());
		}
		return result;
	}
}
