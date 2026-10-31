package com.holdmyspot.cat.common.optimizer.store;

import com.holdmyspot.cat.common.PortablePaths;
import com.holdmyspot.cat.common.optimizer.execution.PromptAuthorConflict;
import com.holdmyspot.cat.common.optimizer.report.EnumNames;
import static com.holdmyspot.cat.common.optimizer.store.JsonFields.VERSION;
import static com.holdmyspot.cat.common.optimizer.store.JsonFields.bool;
import static com.holdmyspot.cat.common.optimizer.store.JsonFields.elements;
import static com.holdmyspot.cat.common.optimizer.store.JsonFields.field;
import static com.holdmyspot.cat.common.optimizer.store.JsonFields.intValue;
import static com.holdmyspot.cat.common.optimizer.store.JsonFields.integer;
import static com.holdmyspot.cat.common.optimizer.store.JsonFields.nullable;
import static com.holdmyspot.cat.common.optimizer.store.JsonFields.requireObject;
import static com.holdmyspot.cat.common.optimizer.store.JsonFields.stringValue;
import static com.holdmyspot.cat.common.optimizer.store.JsonFields.text;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.campaign.Allocation;
import com.holdmyspot.cat.common.optimizer.campaign.AllocationRule;
import com.holdmyspot.cat.common.optimizer.campaign.PromptAuthorAttempt;
import com.holdmyspot.cat.common.optimizer.campaign.AttemptResult;
import com.holdmyspot.cat.common.optimizer.campaign.PromptAuthorDiagnosis;
import com.holdmyspot.cat.common.optimizer.campaign.PromptAuthorKind;
import com.holdmyspot.cat.common.optimizer.campaign.HunkEvidence;
import com.holdmyspot.cat.common.optimizer.campaign.FailureKind;
import com.holdmyspot.cat.common.optimizer.campaign.OptimizationEvidence;
import com.holdmyspot.cat.common.optimizer.campaign.PromptSize;
import com.holdmyspot.cat.common.optimizer.campaign.TrialInputs;
import com.holdmyspot.cat.common.optimizer.campaign.Minimality;
import com.holdmyspot.cat.common.optimizer.evidence.CommandSummary;
import com.holdmyspot.cat.common.optimizer.inventory.FileDelta;
import com.holdmyspot.cat.common.optimizer.inventory.FileContent;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import com.holdmyspot.cat.common.optimizer.campaign.Campaign;
import com.holdmyspot.cat.common.optimizer.campaign.CampaignEvent;
import com.holdmyspot.cat.common.optimizer.campaign.Header;
import com.holdmyspot.cat.common.optimizer.campaign.Candidate;
import com.holdmyspot.cat.common.optimizer.campaign.CellCounts;
import com.holdmyspot.cat.common.optimizer.campaign.CommandCounts;
import com.holdmyspot.cat.common.optimizer.campaign.ConcurrencyWindows;
import com.holdmyspot.cat.common.optimizer.campaign.CertificationAttempt;
import com.holdmyspot.cat.common.optimizer.campaign.CertificationProcedure;
import com.holdmyspot.cat.common.optimizer.campaign.Pairing;
import com.holdmyspot.cat.common.optimizer.campaign.Pairing.UnpairedTrial;
import com.holdmyspot.cat.common.optimizer.campaign.DeliveryRequest;
import com.holdmyspot.cat.common.optimizer.campaign.Development;
import com.holdmyspot.cat.common.optimizer.campaign.TestCaseOutcomes;
import com.holdmyspot.cat.common.optimizer.campaign.Predecessor;
import com.holdmyspot.cat.common.optimizer.campaign.Revert;
import com.holdmyspot.cat.common.optimizer.campaign.Diagnosis;
import com.holdmyspot.cat.common.optimizer.campaign.DiagnosticRerun;
import com.holdmyspot.cat.common.optimizer.campaign.FailedAssertion;
import com.holdmyspot.cat.common.optimizer.campaign.FailureGroup;
import com.holdmyspot.cat.common.optimizer.campaign.Failure;
import com.holdmyspot.cat.common.optimizer.campaign.Harness;
import com.holdmyspot.cat.common.optimizer.campaign.Impact;
import com.holdmyspot.cat.common.optimizer.campaign.IncumbentBaseline;
import com.holdmyspot.cat.common.optimizer.campaign.InfrastructureAttempt;
import com.holdmyspot.cat.common.optimizer.campaign.Lifecycle;
import com.holdmyspot.cat.common.optimizer.campaign.Lineage;
import com.holdmyspot.cat.common.optimizer.campaign.LogicalTrial;
import com.holdmyspot.cat.common.optimizer.campaign.MeasurementCondition;
import com.holdmyspot.cat.common.optimizer.campaign.MeasurementIdentity;
import com.holdmyspot.cat.common.optimizer.campaign.Outcome;
import com.holdmyspot.cat.common.optimizer.campaign.PairCounts;
import com.holdmyspot.cat.common.optimizer.campaign.PendingDecision;
import com.holdmyspot.cat.common.optimizer.campaign.PromotionRun;
import com.holdmyspot.cat.common.optimizer.campaign.Purpose;
import com.holdmyspot.cat.common.optimizer.campaign.RegressionTestCase;
import com.holdmyspot.cat.common.optimizer.campaign.ReproductionRound;
import com.holdmyspot.cat.common.optimizer.campaign.Round;
import com.holdmyspot.cat.common.optimizer.campaign.ApproachOrder;
import com.holdmyspot.cat.common.optimizer.campaign.ApproachAssignment;
import com.holdmyspot.cat.common.optimizer.campaign.PromptAuthorApproach;
import com.holdmyspot.cat.common.optimizer.campaign.RunFailure;
import com.holdmyspot.cat.common.optimizer.campaign.SequenceGroup;
import com.holdmyspot.cat.common.optimizer.campaign.Throttle;
import com.holdmyspot.cat.common.optimizer.campaign.TrialFiles;
import com.holdmyspot.cat.common.optimizer.campaign.TrialKey;
import com.holdmyspot.cat.common.optimizer.campaign.TrialState;
import com.holdmyspot.cat.common.optimizer.campaign.Triage;
import com.holdmyspot.cat.common.optimizer.campaign.TriedTestCase;
import com.holdmyspot.cat.common.optimizer.campaign.SoundTest;
import com.holdmyspot.cat.common.optimizer.campaign.TestRevision;
import com.holdmyspot.cat.common.optimizer.campaign.TestRevisionRequest;
import com.holdmyspot.cat.common.optimizer.campaign.TestRevisions;
import com.holdmyspot.cat.common.optimizer.campaign.Verdict;
import com.holdmyspot.cat.common.optimizer.campaign.Window;
import com.holdmyspot.cat.common.optimizer.campaign.WorkUnit;
import com.holdmyspot.cat.common.optimizer.contract.AddedTestCase;
import com.holdmyspot.cat.common.optimizer.contract.AddedTestCases;
import com.holdmyspot.cat.common.optimizer.contract.Contract;
import com.holdmyspot.cat.common.optimizer.contract.DrawStream;
import com.holdmyspot.cat.common.optimizer.contract.InvalidProposalException;
import com.holdmyspot.cat.common.optimizer.contract.ProposalSchema;
import com.holdmyspot.cat.common.optimizer.contract.DrawnTestCase;
import com.holdmyspot.cat.common.optimizer.contract.TestCase;
import com.holdmyspot.cat.common.optimizer.contract.TestValueJson;
import com.holdmyspot.cat.common.optimizer.contract.TrialPlugin;
import com.holdmyspot.cat.common.optimizer.inventory.AuthorityWarning;
import com.holdmyspot.cat.common.optimizer.inventory.Limit;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;
import com.holdmyspot.cat.common.optimizer.port.HostLogin;
import com.holdmyspot.cat.common.optimizer.port.RateLimitWindow;
import com.holdmyspot.cat.common.optimizer.port.SessionRole;
import com.holdmyspot.cat.common.optimizer.port.TokenUsage;
import com.holdmyspot.cat.common.optimizer.statistics.Comparison;
import com.holdmyspot.cat.common.optimizer.statistics.ComparisonBoundaries;
import com.holdmyspot.cat.common.optimizer.statistics.DecisionBoundaries;
import com.holdmyspot.cat.common.optimizer.statistics.SignificanceLevel;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Converts campaign snapshots and trial records to and from JSON trees.
 * <p>
 * Every document starts with {@code format_version}. Keys are snake_case and constants are written by name. Maps are
 * written with sorted keys, except a {@link Round}, whose cell order is part of its state. Contracts retain each
 * test's recorded regression scenario citations.
 * <p>
 * Counts, rounds, review points, trial keys, sequences and certification procedures are keyed by cell, and the
 * observed model and reasoning are recorded per cell. Big numbers and instants are written as strings so that they
 * round-trip exactly. Version-43 to version-45 documents are upgraded on read: version 43 lacks the saved approach
 * rotation, so existing rounds keep their nine-approach rotation; version 44 lacks the paired-rerun roles of promotion
 * runs, which its runs never use; and versions 43 to 45 lack the throttle's peak window. An upgraded snapshot's peak
 * window is 1 while a rejection's backoffs are counted, and otherwise the campaign's ceiling, because its window cap
 * may hold a window that had just dropped. An upgraded trial session's peak window is its window cap, the level at
 * which that throttle stopped growth. Other unsupported versions are refused with a message that asks the user to
 * start a new campaign.
 * <p>
 * A campaign snapshot written before it recorded the concurrency window of each unit of work has no
 * {@code concurrency} object; every unit then starts at its starting window, and its earlier single window is
 * ignored.
 * <p>
 * Reading reports a document that does not describe a valid value with an {@link IllegalArgumentException} or a
 * {@link NullPointerException}. Reading ignores a measurement condition that CAT recorded in the current format but no
 * longer does, and a pending decision that reported only such conditions as changed.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 */
final class CampaignJson
{
	/**
	 * The version of the layout that this class writes.
	 */
	private static final int FORMAT_VERSION = 46;
	/**
	 * The names of measurement conditions that CAT no longer records. A document written while CAT recorded one still
	 * loads: reading drops the condition.
	 */
	private static final Set<String> RETIRED_CONDITIONS = Set.of("model_provider", "host_tools", "external_sources");
	private static final String TOKEN_USAGE = "token_usage";
	private static final String COMMANDS = "commands";
	/**
	 * The key of a campaign's concurrency windows.
	 */
	private static final String CONCURRENCY = "concurrency";
	private static final String SLOWEST_COMMAND = "slowest_command_ms";
	private static final String EMPTY_RUNNING_RESULTS = "empty_running_results";
	private static final String FAILED_ASSERTIONS = "failed_assertions";
	private static final Comparator<SequenceGroup> GROUP_ORDER = Comparator.comparing(SequenceGroup::cell).
		thenComparing(SequenceGroup::purpose).thenComparing(group -> group.snapshot().hex());
	private final JsonMapper json;
	private final ProposalSchema schema;

	/**
	 * Creates a converter.
	 *
	 * @param json the JSON mapper
	 * @throws NullPointerException if {@code json} is null
	 */
	CampaignJson(JsonMapper json)
	{
		requireThat(json, "json").isNotNull();
		this.json = json;
		this.schema = new ProposalSchema(json);
	}

	/**
	 * Converts a campaign to a snapshot document.
	 *
	 * @param campaign the campaign
	 * @return the document
	 */
	ObjectNode writeCampaign(Campaign campaign)
	{
		ObjectNode root = json.createObjectNode();
		root.put(VERSION, FORMAT_VERSION);
		root.set("header", writeHeader(campaign.header()));
		root.put("ceiling", campaign.ceiling());
		ArrayNode lineages = root.putArray("lineages");
		for (Lineage lineage : campaign.lineages())
			lineages.add(writeLineage(lineage));
		root.set("identity", writeIdentity(campaign.identity()));
		root.set("incumbent", writeCandidate(campaign.incumbent()));
		root.put("candidates", campaign.candidates());
		root.put("prompt_author_attempts", campaign.promptAuthorAttempts());
		root.set("development", writeDevelopment(campaign.development()));
		root.set("optimization", writeOptimization(campaign.optimization()));
		IncumbentBaseline baseline = campaign.baseline();
		if (baseline == null)
			root.putNull("baseline");
		else
		{
			ObjectNode node = root.putObject("baseline");
			node.put("index", baseline.index());
			node.set("identity", writeIdentity(baseline.identity()));
			node.set("counts", writeCounts(baseline.counts()));
		}
		ArrayNode attempts = root.putArray("attempts");
		for (CertificationAttempt attempt : campaign.attempts())
			attempts.add(writeAttempt(attempt));
		ArrayNode trials = root.putArray("trials");
		for (LogicalTrial trial : campaign.trials())
			trials.add(writeTrial(trial));
		ArrayNode sequences = root.putArray("sequences");
		Map<SequenceGroup, Long> sortedSequences = new TreeMap<>(GROUP_ORDER);
		sortedSequences.putAll(campaign.sequences());
		for (Entry<SequenceGroup, Long> entry : sortedSequences.entrySet())
		{
			ObjectNode node = sequences.addObject();
			node.put("cell", entry.getKey().cell());
			node.put("purpose", EnumNames.lowerCase(entry.getKey().purpose()));
			node.put("snapshot", entry.getKey().snapshot().hex());
			node.put("last_sequence", entry.getValue());
		}
		root.put("evidence_revision", campaign.evidenceRevision());
		root.set(CONCURRENCY, writeWindows(campaign.windows()));
		root.set("throttle", writeThrottle(campaign.throttle()));
		ArrayNode pending = root.putArray("pending_decisions");
		for (PendingDecision decision : campaign.pendingDecisions())
			pending.add(writeDecision(decision));
		root.put("revision_requested", campaign.revisionRequested());
		ArrayNode answered = root.putArray("answered_warnings");
		for (AuthorityWarning warning : campaign.answeredWarnings())
			writeWarning(answered.addObject(), warning);
		root.set("test_revisions", writeTestRevisions(campaign.testRevisions()));
		if (campaign.plugins().isEmpty())
			root.putNull("plugins");
		else
		{
			ArrayNode plugins = root.putArray("plugins");
			for (TrialPlugin plugin : campaign.plugins().get())
				writePlugin(plugins.addObject(), plugin);
		}
		putConstant(root, "outcome", campaign.outcome());
		DeliveryRequest delivery = campaign.delivery();
		if (delivery == null)
			root.putNull("delivery");
		else
		{
			ObjectNode node = root.putObject("delivery");
			node.set("candidate", writeCandidate(delivery.candidate()));
			node.put("attempt", delivery.attempt());
			node.put("label", EnumNames.lowerCase(delivery.label()));
		}
		return root;
	}

	/**
	 * Converts a snapshot document to a campaign.
	 *
	 * @param root the document
	 * @return the campaign
	 * @throws IllegalArgumentException if the document has a different format version or does not describe a valid
	 *   campaign
	 * @throws NullPointerException if a required value is missing
	 */
	Campaign readCampaign(JsonNode root)
	{
		requireVersion(root);
		List<Lineage> lineages = new ArrayList<>();
		for (JsonNode node : elements(root, "lineages"))
			lineages.add(readLineage(node));
		IncumbentBaseline baseline = null;
		JsonNode baselineNode = nullable(root, "baseline");
		if (baselineNode != null)
		{
			baseline = new IncumbentBaseline(integer(baselineNode, "index"),
				readIdentity(field(baselineNode, "identity")), readCounts(field(baselineNode, "counts")));
		}
		List<CertificationAttempt> attempts = new ArrayList<>();
		for (JsonNode node : elements(root, "attempts"))
			attempts.add(readAttempt(node));
		List<LogicalTrial> trials = new ArrayList<>();
		for (JsonNode node : elements(root, "trials"))
			trials.add(readTrial(node));
		Map<SequenceGroup, Long> sequences = new HashMap<>();
		for (JsonNode node : elements(root, "sequences"))
		{
			SequenceGroup group = new SequenceGroup(text(node, "cell"), constant(node, "purpose", Purpose.class),
				sha256(node, "snapshot"));
			if (sequences.put(group, longInteger(node, "last_sequence")) != null)
				throw new IllegalArgumentException("sequences: duplicate group " + group);
		}
		ConcurrencyWindows windows = ConcurrencyWindows.EMPTY;
		if (root.has(CONCURRENCY))
			windows = readWindows(field(root, CONCURRENCY));
		List<PendingDecision> pending = new ArrayList<>();
		for (JsonNode node : elements(root, "pending_decisions"))
		{
			if (!changesOnlyRetiredConditions(node))
				pending.add(readDecision(node));
		}
		List<AuthorityWarning> answered = new ArrayList<>();
		for (JsonNode node : elements(root, "answered_warnings"))
			answered.add(readWarning(node));
		DeliveryRequest delivery = null;
		JsonNode deliveryNode = nullable(root, "delivery");
		if (deliveryNode != null)
		{
			delivery = new DeliveryRequest(readCandidate(field(deliveryNode, "candidate")),
				integer(deliveryNode, "attempt"), constant(deliveryNode, "label", Outcome.class));
		}
		int ceiling = Campaign.DEFAULT_CEILING;
		if (root.has("ceiling"))
			ceiling = integer(root, "ceiling");
		Optional<List<TrialPlugin>> plugins = Optional.empty();
		if (nullable(root, "plugins") != null)
		{
			List<TrialPlugin> recorded = new ArrayList<>();
			for (JsonNode node : elements(root, "plugins"))
				recorded.add(readPlugin(node));
			plugins = Optional.of(recorded);
		}
		return new Campaign(readHeader(field(root, "header")), ceiling, lineages,
			readIdentity(field(root, "identity")), readCandidate(field(root, "incumbent")),
			integer(root, "candidates"), integer(root, "prompt_author_attempts"),
			readDevelopment(field(root, "development")), baseline, attempts, trials, sequences,
			longInteger(root, "evidence_revision"), windows,
			readThrottle(field(root, "throttle")), pending, bool(root, "revision_requested"),
			nullableConstant(root, "outcome", Outcome.class), delivery, readOptimization(field(root, "optimization")),
			answered, readTestRevisions(field(root, "test_revisions")), plugins);
	}

	/**
	 * Writes a trial plugin. Paths are written relative to the project root, or to the plugin directory for a mapping's
	 * target, with {@code /} between names. The {@code repository} key is left out when the manifest states none.
	 *
	 * @param node the object to write to
	 * @param plugin the trial plugin
	 */
	private static void writePlugin(ObjectNode node, TrialPlugin plugin)
	{
		node.put("name", plugin.name());
		node.put("base_digest", plugin.baseDigest());
		if (plugin.repository() != null)
			node.put("repository", plugin.repository());
		ObjectNode source = node.putObject("source");
		switch (plugin.source())
		{
			case TrialPlugin.Build build ->
			{
				source.put("type", "build");
				ArrayNode command = source.putArray("command");
				for (String argument : build.command())
					command.add(argument);
				source.put("output", PortablePaths.text(build.output()));
			}
			case TrialPlugin.Installed _ -> source.put("type", "installed");
		}
		ArrayNode mappings = node.putArray("mappings");
		for (TrialPlugin.Mapping mapping : plugin.mappings())
		{
			ObjectNode element = mappings.addObject();
			element.put("source", PortablePaths.text(mapping.source()));
			element.put("target", PortablePaths.text(mapping.target()));
		}
	}

	/**
	 * Reads a trial plugin.
	 *
	 * @param node the plugin's document
	 * @return the trial plugin
	 * @throws IllegalArgumentException if the document does not describe a valid trial plugin
	 */
	private static TrialPlugin readPlugin(JsonNode node)
	{
		JsonNode sourceNode = field(node, "source");
		String type = text(sourceNode, "type");
		TrialPlugin.BaseSource source = switch (type)
		{
			case "build" -> new TrialPlugin.Build(readStrings(sourceNode, "command"),
				Path.of(text(sourceNode, "output")));
			case "installed" -> new TrialPlugin.Installed();
			default -> throw new IllegalArgumentException("plugins.source.type: unknown type " + type);
		};
		List<TrialPlugin.Mapping> mappings = new ArrayList<>();
		for (JsonNode element : elements(node, "mappings"))
			mappings.add(new TrialPlugin.Mapping(Path.of(text(element, "source")), Path.of(text(element, "target"))));
		// A plugin recorded without a repository is not CAT. Its manifest stated none, or the campaign started before
		// CAT recorded it; reading the base copy instead would make loading a campaign depend on campaign storage
		String repository = null;
		if (node.has("repository"))
			repository = text(node, "repository");
		return new TrialPlugin(text(node, "name"), text(node, "base_digest"), source, mappings, repository);
	}

	/**
	 * Writes retained prompt author diagnoses and removed hunks.
	 *
	 * @param evidence the durable descriptive evidence
	 * @return its document
	 */
	private ObjectNode writeOptimization(OptimizationEvidence evidence)
	{
		ObjectNode node = json.createObjectNode();
		ObjectNode approaches = node.putObject("approaches");
		for (Entry<Integer, ApproachAssignment> entry : new TreeMap<>(evidence.approaches()).entrySet())
		{
			ObjectNode assignment = approaches.putObject(entry.getKey().toString());
			assignment.put("approach", EnumNames.lowerCase(entry.getValue().approach()));
			assignment.put("source", EnumNames.lowerCase(entry.getValue().source()));
		}
		ObjectNode commands = node.putObject(COMMANDS);
		for (Purpose purpose : Purpose.values())
		{
			Map<Integer, CommandCounts> byAttempt = evidence.commands().get(purpose);
			if (byAttempt == null)
				continue;
			ObjectNode purposeNode = commands.putObject(EnumNames.lowerCase(purpose));
			for (Entry<Integer, CommandCounts> entry : new TreeMap<>(byAttempt).entrySet())
			{
				ObjectNode counts = purposeNode.putObject(entry.getKey().toString());
				counts.put("sessions", entry.getValue().sessions());
				counts.put("slow_known", entry.getValue().slowKnown());
				counts.put("slow", entry.getValue().slow());
				counts.put("empty_running_known", entry.getValue().emptyRunningKnown());
				counts.put("empty_running", entry.getValue().emptyRunning());
			}
		}
		ObjectNode usage = node.putObject(TOKEN_USAGE);
		for (SessionRole role : SessionRole.values())
		{
			List<TokenUsage> sessions = evidence.tokenUsage().get(role);
			if (sessions != null)
				usage.set(EnumNames.lowerCase(role), writeUsage(sessions));
		}

		ArrayNode promptAuthors = node.putArray("prompt_authors");
		for (PromptAuthorDiagnosis diagnosis : evidence.promptAuthors())
		{
			ObjectNode promptAuthor = promptAuthors.addObject();
			promptAuthor.put("number", diagnosis.number());
			promptAuthor.put("lineage", diagnosis.lineage());
			promptAuthor.put("cell", diagnosis.cell());
			promptAuthor.put("snapshot", diagnosis.snapshot().hex());
			promptAuthor.put("transcript", diagnosis.transcript().toString());
			promptAuthor.put("effects", diagnosis.effects().toString());
			writeKinds(promptAuthor.putArray("kinds"), diagnosis.kinds());
			ArrayNode hunks = promptAuthor.putArray("hunks");
			for (HunkEvidence hunk : diagnosis.hunks())
			{
				ObjectNode element = hunks.addObject();
				element.put("id", hunk.id());
				element.put("path", PortablePaths.text(hunk.path()));
				element.put("before_start", hunk.beforeStart());
				element.put("before_end", hunk.beforeEnd());
				element.put("after_start", hunk.afterStart());
				element.put("after_end", hunk.afterEnd());
				writeStrings(element.putArray("failures"), hunk.failures());
				element.put("analogous", hunk.analogous());
				element.put("dissimilar", hunk.dissimilar());
			}
			ArrayNode rejections = promptAuthor.putArray("rejections");
			for (String reason : diagnosis.rejections())
				rejections.add(reason);
		}
		ArrayNode removed = node.putArray("removed");
		for (String hunk : evidence.removed())
			removed.add(hunk);
		ObjectNode sizes = node.putObject("prompt_sizes");
		for (Map.Entry<Sha256, PromptSize> entry : orderedSnapshots(evidence.promptSizes()).entrySet())
		{
			ObjectNode size = sizes.putObject(entry.getKey().hex());
			size.put("bytes", entry.getValue().bytes());
			size.put("words", entry.getValue().words());
			size.put("unavailable", entry.getValue().unavailable());
		}
		ArrayNode inputs = node.putArray("trial_inputs");
		for (Map.Entry<MeasurementIdentity, Map<Sha256, Map<String, TrialInputs>>> identity :
			evidence.trialInputs().entrySet())
		{
			ObjectNode observation = inputs.addObject();
			observation.set("identity", writeIdentity(identity.getKey()));
			ObjectNode snapshots = observation.putObject("snapshots");
			for (Map.Entry<Sha256, Map<String, TrialInputs>> snapshot : orderedSnapshots(identity.getValue()).entrySet())
			{
				ObjectNode cells = snapshots.putObject(snapshot.getKey().hex());
				for (Map.Entry<String, TrialInputs> entry : new TreeMap<>(snapshot.getValue()).entrySet())
				{
					ObjectNode cell = cells.putObject(entry.getKey());
					cell.put("outcomes", entry.getValue().outcomes());
					ArrayNode tokens = cell.putArray("tokens");
					for (long token : entry.getValue().tokens())
						tokens.add(token);
				}
			}
		}
		ArrayNode results = node.putArray("attempts");
		for (AttemptResult result : evidence.attempts())
		{
			ObjectNode attempt = results.addObject();
			attempt.put("round", result.round());
			attempt.put("working", result.working().hex());
			attempt.put("number", result.number());
			attempt.put("cell", result.cell());
			attempt.put("snapshot", result.snapshot().hex());
			attempt.set("runs", writePromotionRuns(result.runs()));
			attempt.put("changed_lines", result.changedLines());
			attempt.put("accepted", result.accepted());
			attempt.put("winner", result.winner());
		}
		return node;
	}

	/**
	 * Writes the failure kinds that a prompt author classified.
	 *
	 * @param array the empty array that receives the kinds
	 * @param kinds the kinds
	 */
	private static void writeKinds(ArrayNode array, List<PromptAuthorKind> kinds)
	{
		for (PromptAuthorKind kind : kinds)
		{
			ObjectNode element = array.addObject();
			element.put("name", kind.name());
			writeStrings(element.putArray("failures"), kind.failures());
			element.put("classification", EnumNames.lowerCase(kind.classification()));
			writeStrings(element.putArray("hunks"), kind.hunks());
			element.put("fault", kind.fault());
		}
	}

	/**
	 * Reads the failure kinds that a prompt author classified.
	 *
	 * @param node the object whose {@code kinds} array holds the kinds
	 * @return the kinds
	 */
	private static List<PromptAuthorKind> readKinds(JsonNode node)
	{
		List<PromptAuthorKind> kinds = new ArrayList<>();
		for (JsonNode kind : elements(node, "kinds"))
		{
			kinds.add(new PromptAuthorKind(text(kind, "name"), readStrings(kind, "failures"),
				constantValue(field(kind, "classification"), "classification", FailureKind.class),
				readStrings(kind, "hunks"), text(kind, "fault")));
		}
		return kinds;
	}

	/**
	 * Converts the requested, completed and declined test revisions to a document.
	 *
	 * @param revisions the test revisions
	 * @return the document
	 */
	private ObjectNode writeTestRevisions(TestRevisions revisions)
	{
		ObjectNode node = json.createObjectNode();
		ArrayNode requests = node.putArray("requests");
		for (TestRevisionRequest request : revisions.requests())
		{
			ObjectNode element = requests.addObject();
			element.put("test", request.test());
			element.put("trigger", EnumNames.lowerCase(request.trigger()));
			writeKinds(element.putArray("kinds"), request.kinds());
			writeStrings(element.putArray("failure_ids"), List.copyOf(request.failures().keySet()));
			element.set("failures", writeFailures(List.copyOf(request.failures().values())));
		}
		ArrayNode completed = node.putArray("revisions");
		for (TestRevision revision : revisions.revisions())
		{
			ObjectNode element = completed.addObject();
			element.put("lineage", revision.lineage());
			element.put("test", revision.test());
			element.put("before", revision.before());
			element.put("after", revision.after());
			element.put("reasons", revision.reasons());
		}
		ArrayNode sound = node.putArray("sound");
		for (SoundTest report : revisions.sound())
		{
			ObjectNode element = sound.addObject();
			element.put("lineage", report.lineage());
			element.put("test", report.test());
			element.put("reasons", report.reasons());
		}
		return node;
	}

	/**
	 * Converts a document to the requested, completed and declined test revisions.
	 *
	 * @param node the document
	 * @return the test revisions
	 * @throws IllegalArgumentException if a request's failure ids and failures differ in number
	 */
	private static TestRevisions readTestRevisions(JsonNode node)
	{
		List<TestRevisionRequest> requests = new ArrayList<>();
		for (JsonNode element : elements(node, "requests"))
		{
			List<String> ids = readStrings(element, "failure_ids");
			List<Failure> values = readFailures(element, "failures");
			if (ids.size() != values.size())
			{
				throw new IllegalArgumentException("test_revisions.requests: " + ids.size() + " failure ids but " +
					values.size() + " failures");
			}
			Map<String, Failure> failures = new LinkedHashMap<>();
			for (int i = 0; i < ids.size(); ++i)
				failures.put(ids.get(i), values.get(i));
			requests.add(new TestRevisionRequest(text(element, "test"),
				constant(element, "trigger", TestRevisionRequest.Trigger.class), readKinds(element), failures));
		}
		List<TestRevision> revisions = new ArrayList<>();
		for (JsonNode element : elements(node, "revisions"))
		{
			revisions.add(new TestRevision(integer(element, "lineage"), text(element, "test"),
				text(element, "before"), text(element, "after"), text(element, "reasons")));
		}
		List<SoundTest> sound = new ArrayList<>();
		for (JsonNode element : elements(node, "sound"))
			sound.add(new SoundTest(integer(element, "lineage"), text(element, "test"), text(element, "reasons")));
		return new TestRevisions(requests, revisions, sound);
	}

	/**
	 * Reads retained prompt author diagnoses and removed hunks.
	 *
	 * @param node the document
	 * @return the durable evidence
	 */
	private static OptimizationEvidence readOptimization(JsonNode node)
	{
		List<PromptAuthorDiagnosis> promptAuthors = new ArrayList<>();
		for (JsonNode promptAuthor : elements(node, "prompt_authors"))
		{
			List<PromptAuthorKind> kinds = readKinds(promptAuthor);
			List<HunkEvidence> hunks = new ArrayList<>();
			for (JsonNode hunk : elements(promptAuthor, "hunks"))
			{
				hunks.add(new HunkEvidence(text(hunk, "id"), pathValue(field(hunk, "path"), "path"),
					integer(hunk, "before_start"), integer(hunk, "before_end"), integer(hunk, "after_start"),
					integer(hunk, "after_end"), readStrings(hunk, "failures"), text(hunk, "analogous"),
					text(hunk, "dissimilar")));
			}
			promptAuthors.add(new PromptAuthorDiagnosis(integer(promptAuthor, "number"), integer(promptAuthor,
				"lineage"), text(promptAuthor, "cell"), sha256(promptAuthor, "snapshot"), kinds, hunks,
				readStrings(promptAuthor, "rejections"), Path.of(text(promptAuthor, "transcript")),
				Path.of(text(promptAuthor, "effects"))));
		}
		Map<Sha256, PromptSize> sizes = new HashMap<>();
		for (Entry<String, JsonNode> entry : object(node, "prompt_sizes").properties())
		{
			JsonNode size = entry.getValue();
			sizes.put(new Sha256(entry.getKey()), new PromptSize(nullableLong(size, "bytes"),
				nullableLong(size, "words"), nullableText(size, "unavailable")));
		}
		Map<MeasurementIdentity, Map<Sha256, Map<String, TrialInputs>>> inputs = new HashMap<>();
		for (JsonNode observation : elements(node, "trial_inputs"))
		{
			Map<Sha256, Map<String, TrialInputs>> snapshots = new HashMap<>();
			for (Entry<String, JsonNode> snapshot : object(observation, "snapshots").properties())
			{
				requireThat(snapshot.getValue().isObject(), "trial_inputs").isTrue();
				Map<String, TrialInputs> cells = new HashMap<>();
				for (Entry<String, JsonNode> entry : snapshot.getValue().properties())
				{
					List<Long> tokens = new ArrayList<>();
					for (JsonNode token : elements(entry.getValue(), "tokens"))
						tokens.add(longValue(token, "token"));
					cells.put(entry.getKey(), new TrialInputs(longInteger(entry.getValue(), "outcomes"), tokens));
				}
				snapshots.put(new Sha256(snapshot.getKey()), cells);
			}
			inputs.put(readIdentity(field(observation, "identity")), snapshots);
		}
		List<AttemptResult> results = new ArrayList<>();
		for (JsonNode attempt : elements(node, "attempts"))
		{
			results.add(new AttemptResult(integer(attempt, "round"), sha256(attempt, "working"),
				integer(attempt, "number"), text(attempt, "cell"), sha256(attempt, "snapshot"),
				readPromotionRuns(attempt, "runs"), integer(attempt, "changed_lines"), bool(attempt, "accepted"),
				bool(attempt, "winner")));
		}
		Map<Integer, ApproachAssignment> approaches = new TreeMap<>();
		for (Entry<String, JsonNode> entry : object(node, "approaches").properties())
		{
			approaches.put(Integer.parseInt(entry.getKey()), new ApproachAssignment(
				constant(entry.getValue(), "approach", PromptAuthorApproach.class),
				constant(entry.getValue(), "source", ApproachAssignment.Source.class)));
		}
		return new OptimizationEvidence(promptAuthors, readStrings(node, "removed"), sizes, inputs, results,
			readRoleUsage(node), approaches, readCommandCounts(node));
	}

	/**
	 * Reads how the shell commands of completed trial sessions ran. A snapshot written before sessions recorded their
	 * commands has no counts.
	 *
	 * @param node the optimization evidence
	 * @return the counts by purpose and certification attempt
	 */
	private static Map<Purpose, Map<Integer, CommandCounts>> readCommandCounts(JsonNode node)
	{
		Map<Purpose, Map<Integer, CommandCounts>> commands = new EnumMap<>(Purpose.class);
		if (!node.has(COMMANDS))
			return commands;
		for (Entry<String, JsonNode> purpose : object(node, COMMANDS).properties())
		{
			Map<Integer, CommandCounts> byAttempt = new HashMap<>();
			requireObject(purpose.getValue(), COMMANDS);
			for (Entry<String, JsonNode> entry : purpose.getValue().properties())
			{
				JsonNode counts = entry.getValue();
				byAttempt.put(Integer.parseInt(entry.getKey()), new CommandCounts(longInteger(counts, "sessions"),
					longInteger(counts, "slow_known"), longInteger(counts, "slow"),
					longInteger(counts, "empty_running_known"), longInteger(counts, "empty_running")));
			}
			commands.put(EnumNames.parse(purpose.getKey(), Purpose.class), byAttempt);
		}
		return commands;
	}

	/**
	 * Orders snapshot-keyed measurements by their hexadecimal identities.
	 *
	 * @param values the measurements
	 * @param <T> the measured value type
	 * @return the ordered entries
	 */
	private static <T> Map<Sha256, T> orderedSnapshots(Map<Sha256, T> values)
	{
		Map<Sha256, T> ordered = new TreeMap<>(Comparator.comparing(Sha256::hex));
		ordered.putAll(values);
		return ordered;
	}

	/**
	 * Reads an optional integer count, preserving unavailable measurements.
	 *
	 * @param node the object
	 * @param name the property
	 * @return the count, or {@code null}
	 */
	private static Long nullableLong(JsonNode node, String name)
	{
		JsonNode value = nullable(node, name);
		if (value == null)
			return null;
		return longValue(value, name);
	}

	/**
	 * Reads an array of required strings.
	 *
	 * @param node the containing object
	 * @param name the array property
	 * @return its strings
	 */
	private static List<String> readStrings(JsonNode node, String name)
	{
		List<String> values = new ArrayList<>();
		for (JsonNode element : elements(node, name))
			values.add(stringValue(element, name));
		return List.copyOf(values);
	}

	/**
	 * Appends strings to an array.
	 *
	 * @param array the array
	 * @param values the strings, in order
	 */
	private static void writeStrings(ArrayNode array, List<String> values)
	{
		for (String value : values)
			array.add(value);
	}

	/**
	 * Writes the assertions that a trial failed, each with its behavior.
	 *
	 * @param node the containing object
	 * @param failures the failed assertions
	 */
	private static void putFailedAssertions(ObjectNode node, List<FailedAssertion> failures)
	{
		ArrayNode array = node.putArray(FAILED_ASSERTIONS);
		for (FailedAssertion failure : failures)
			array.addObject().put("assertion_id", failure.assertionId()).put("behavior", failure.behavior());
	}

	/**
	 * Reads the assertions that a trial failed, each with its behavior.
	 *
	 * @param node the containing object
	 * @return the failed assertions
	 */
	private static List<FailedAssertion> readFailedAssertions(JsonNode node)
	{
		List<FailedAssertion> failures = new ArrayList<>();
		for (JsonNode element : elements(node, FAILED_ASSERTIONS))
			failures.add(new FailedAssertion(text(element, "assertion_id"), text(element, "behavior")));
		return List.copyOf(failures);
	}

	/**
	 * Reads only the header of a snapshot document.
	 *
	 * @param root the document
	 * @return the header
	 * @throws IllegalArgumentException if the document has a different format version or does not describe a valid
	 *   header
	 * @throws NullPointerException if a required value is missing
	 */
	Header readSnapshotHeader(JsonNode root)
	{
		requireVersion(root);
		return readHeader(field(root, "header"));
	}

	/**
	 * Converts a trial record to a document.
	 *
	 * @param record the record
	 * @return the document
	 */
	ObjectNode writeRecord(TrialRecord record)
	{
		ObjectNode root = json.createObjectNode();
		root.put(VERSION, FORMAT_VERSION);
		root.set("key", writeKey(record.key()));
		root.set("test_case", writeTestCase(record.testCase()));
		root.set("identity", writeIdentity(record.identity()));
		root.set("allocation", writeAllocation(record.allocation()));
		root.set("infrastructure_attempts", writeInfrastructureAttempts(record.infrastructureAttempts()));
		ArrayNode sessions = root.putArray("sessions");
		for (TrialSession session : record.sessions())
			sessions.add(writeSession(session));
		CampaignEvent.Complete outcome = record.outcome();
		if (outcome == null)
			root.putNull("outcome");
		else
		{
			ObjectNode node = root.putObject("outcome");
			node.put("verdict", EnumNames.lowerCase(outcome.verdict()));
			node.put("rationale", outcome.rationale());
			putFailedAssertions(node, outcome.failedAssertions());
			node.set("triage", writeTriage(outcome.triage()));
			node.set("observed", writeIdentity(outcome.observed()));
			node.put("input_tokens", outcome.inputTokens());
			node.set("responses", writeResponses(outcome.responses()));
			node.set("files", writeFiles(outcome.files()));
		}
		return root;
	}

	/**
	 * Converts a document to a trial record.
	 *
	 * @param root the document
	 * @return the record
	 * @throws IllegalArgumentException if the document has a different format version or does not describe a valid
	 *   record
	 * @throws NullPointerException if a required value is missing
	 */
	TrialRecord readRecord(JsonNode root)
	{
		requireVersion(root);
		TrialKey key = readKey(field(root, "key"));
		CampaignEvent.Complete outcome = null;
		JsonNode outcomeNode = nullable(root, "outcome");
		if (outcomeNode != null)
		{
			outcome = new CampaignEvent.Complete(key, constant(outcomeNode, "verdict", Verdict.class),
				text(outcomeNode, "rationale"), readFailedAssertions(outcomeNode), readTriage(outcomeNode),
				readIdentity(field(outcomeNode, "observed")), nullableLong(outcomeNode, "input_tokens"),
				readResponses(outcomeNode), readFiles(outcomeNode));
		}
		List<TrialSession> sessions = new ArrayList<>();
		for (JsonNode session : elements(root, "sessions"))
			sessions.add(readSession(session));
		return new TrialRecord(key, readTestCase(field(root, "test_case")), readIdentity(field(root, "identity")),
			readAllocation(field(root, "allocation")), readInfrastructureAttempts(root), outcome, sessions);
	}

	/**
	 * Writes the record of a finished trial session.
	 *
	 * @param session the session's record
	 * @return the document
	 */
	private ObjectNode writeSession(TrialSession session)
	{
		ObjectNode node = json.createObjectNode();
		node.put("started_at", session.startedAt().toString());
		node.put("ended_at", session.endedAt().toString());
		node.put("concurrency", session.concurrency());
		node.set("throttle", writeThrottle(session.throttle()));
		ArrayNode windows = node.putArray("rate_limit_windows");
		for (RateLimitWindow window : session.windows())
		{
			ObjectNode element = windows.addObject();
			element.put("utilization", window.utilization());
			if (window.resetsAt() == null)
				element.putNull("resets_at");
			else
				element.put("resets_at", window.resetsAt().toString());
		}
		putConstant(node, "login", session.login());
		if (session.usage() == null)
			node.putNull(TOKEN_USAGE);
		else
			node.set(TOKEN_USAGE, writeTokenUsage(session.usage()));
		Duration slowest = session.commands().slowest();
		if (slowest == null)
			node.putNull(SLOWEST_COMMAND);
		else
			node.put(SLOWEST_COMMAND, slowest.toMillis());
		Integer emptyRunning = session.commands().emptyRunningResults();
		if (emptyRunning == null)
			node.putNull(EMPTY_RUNNING_RESULTS);
		else
			node.put(EMPTY_RUNNING_RESULTS, emptyRunning.intValue());
		return node;
	}

	/**
	 * Reads the record of a finished trial session. A record written before sessions recorded how their commands ran
	 * has neither command value, so both are unknown.
	 *
	 * @param node the document
	 * @return the session's record
	 */
	private static TrialSession readSession(JsonNode node)
	{
		List<RateLimitWindow> windows = new ArrayList<>();
		for (JsonNode window : elements(node, "rate_limit_windows"))
			windows.add(new RateLimitWindow(floating(window, "utilization"), nullableInstant(window, "resets_at")));
		TokenUsage usage = null;
		JsonNode usageNode = nullable(node, TOKEN_USAGE);
		if (usageNode != null)
			usage = readTokenUsage(usageNode);
		return new TrialSession(instant(node, "started_at"), instant(node, "ended_at"), integer(node, "concurrency"),
			readThrottle(field(node, "throttle")), windows, constant(node, "login", HostLogin.class), usage,
			readCommands(node));
	}

	/**
	 * Reads how a finished trial session's commands ran.
	 *
	 * @param node the session's document
	 * @return the summary, with each value that is absent or {@code null} unknown
	 */
	private static CommandSummary readCommands(JsonNode node)
	{
		Duration slowest = null;
		if (node.has(SLOWEST_COMMAND) && !node.get(SLOWEST_COMMAND).isNull())
			slowest = Duration.ofMillis(longValue(node.get(SLOWEST_COMMAND), SLOWEST_COMMAND));
		Integer emptyRunning = null;
		if (node.has(EMPTY_RUNNING_RESULTS) && !node.get(EMPTY_RUNNING_RESULTS).isNull())
			emptyRunning = intValue(node.get(EMPTY_RUNNING_RESULTS), EMPTY_RUNNING_RESULTS);
		return new CommandSummary(slowest, emptyRunning);
	}

	/**
	 * Writes concurrency windows, each unit of work's window in unit order.
	 *
	 * @param windows the windows
	 * @return the document
	 */
	private ObjectNode writeWindows(ConcurrencyWindows windows)
	{
		ObjectNode node = json.createObjectNode();
		ArrayNode units = node.putArray("windows");
		List<Entry<WorkUnit, Window>> entries = new ArrayList<>(windows.windows().entrySet());
		entries.sort(Comparator.comparing((Entry<WorkUnit, Window> entry) -> entry.getKey().purpose()).
			thenComparingInt(entry -> entry.getKey().series()).
			thenComparingInt(entry -> entry.getKey().stage()).
			thenComparing(entry -> entry.getKey().part()));
		for (Entry<WorkUnit, Window> entry : entries)
		{
			ObjectNode unit = units.addObject();
			unit.put("purpose", EnumNames.lowerCase(entry.getKey().purpose()));
			unit.put("series", entry.getKey().series());
			unit.put("stage", entry.getKey().stage());
			unit.put("part", entry.getKey().part());
			unit.put("size", entry.getValue().size());
			unit.put("passes", entry.getValue().passes());
		}
		node.put("halvings", windows.halvings());
		node.put("completions", windows.completions());
		return node;
	}

	/**
	 * Reads concurrency windows.
	 *
	 * @param node the document
	 * @return the windows
	 */
	private static ConcurrencyWindows readWindows(JsonNode node)
	{
		Map<WorkUnit, Window> windows = new HashMap<>();
		for (JsonNode unit : elements(node, "windows"))
		{
			WorkUnit key = new WorkUnit(constant(unit, "purpose", Purpose.class), integer(unit, "series"),
				integer(unit, "stage"), text(unit, "part"));
			if (windows.put(key, new Window(integer(unit, "size"), integer(unit, "passes"))) != null)
				throw new IllegalArgumentException("concurrency.windows: duplicate unit " + key);
		}
		return new ConcurrencyWindows(windows, integer(node, "halvings"), integer(node, "completions"));
	}

	/**
	 * Writes a throttle state.
	 *
	 * @param throttle the state
	 * @return the document
	 */
	private ObjectNode writeThrottle(Throttle throttle)
	{
		ObjectNode node = json.createObjectNode();
		node.put("hold_until", throttle.holdUntil().toString());
		node.put("window_cap", throttle.windowCap());
		node.put("backoffs", throttle.backoffs());
		node.put("peak_window", throttle.peakWindow());
		return node;
	}

	/**
	 * Reads a throttle state.
	 *
	 * @param node the document
	 * @return the state
	 */
	private static Throttle readThrottle(JsonNode node)
	{
		return new Throttle(instant(node, "hold_until"), integer(node, "window_cap"), integer(node, "backoffs"),
			integer(node, "peak_window"));
	}

	/**
	 * Writes the full token counts of finished sessions in completion order.
	 *
	 * @param sessions reported counts
	 * @return the counts
	 */
	private ArrayNode writeUsage(List<TokenUsage> sessions)
	{
		ArrayNode array = json.createArrayNode();
		for (TokenUsage usage : sessions)
			array.add(writeTokenUsage(usage));
		return array;
	}

	/**
	 * Writes the full token counts that a harness reported for one session.
	 *
	 * @param usage the reported counts
	 * @return the document
	 */
	private ObjectNode writeTokenUsage(TokenUsage usage)
	{
		ObjectNode node = json.createObjectNode();
		node.put("input", usage.input());
		node.put("cached_input", usage.cachedInput());
		node.put("output", usage.output());
		node.put("reasoning_output", usage.reasoningOutput());
		return node;
	}

	/**
	 * Reads the counts reported by finished sessions of each role.
	 *
	 * @param node the optimization evidence
	 * @return reported counts by role
	 */
	private static Map<SessionRole, List<TokenUsage>> readRoleUsage(JsonNode node)
	{
		Map<SessionRole, List<TokenUsage>> usage = new EnumMap<>(SessionRole.class);
		JsonNode roles = object(node, TOKEN_USAGE);
		for (Entry<String, JsonNode> entry : roles.properties())
		{
			SessionRole role = EnumNames.parse(entry.getKey(), SessionRole.class);
			usage.put(role, readUsage(roles, entry.getKey()));
		}
		return usage;
	}

	/**
	 * Reads full reported counts without inferring counts from outcome input tokens.
	 *
	 * @param node the containing document
	 * @param key the array's key
	 * @return the reported counts, in completion order
	 */
	private static List<TokenUsage> readUsage(JsonNode node, String key)
	{
		List<TokenUsage> sessions = new ArrayList<>();
		for (JsonNode usage : elements(node, key))
			sessions.add(readTokenUsage(usage));
		return sessions;
	}

	/**
	 * Reads the full token counts that a harness reported for one session.
	 *
	 * @param node the document
	 * @return the reported counts
	 */
	private static TokenUsage readTokenUsage(JsonNode node)
	{
		return new TokenUsage(longInteger(node, "input"), longInteger(node, "cached_input"),
			longInteger(node, "output"), longInteger(node, "reasoning_output"));
	}

	/**
	 * Converts replies to arrays of assistant messages in turn order.
	 *
	 * @param responses each turn's messages in order, ending with its final response
	 * @return the document
	 */
	private ArrayNode writeResponses(List<List<String>> responses)
	{
		ArrayNode node = json.createArrayNode();
		for (List<String> messages : responses)
		{
			ArrayNode turn = node.addArray();
			for (String message : messages)
				turn.add(message);
		}
		return node;
	}

	/**
	 * Reads the ordered assistant messages of each turn's reply.
	 *
	 * @param node the document that holds the replies
	 * @return the replies in turn order
	 * @throws IllegalArgumentException if a reply is not an array or a message is not a string
	 */
	private static List<List<String>> readResponses(JsonNode node)
	{
		List<List<String>> responses = new ArrayList<>();
		for (JsonNode turn : elements(node, "responses"))
		{
			if (!turn.isArray())
				throw new IllegalArgumentException("responses: each turn must be an array of assistant messages");

			List<String> messages = new ArrayList<>();
			for (JsonNode message : turn)
				messages.add(stringValue(message, "responses"));
			responses.add(List.copyOf(messages));
		}
		return List.copyOf(responses);
	}

	/**
	 * Converts where a trial session's harness events and file changes were retained to a document.
	 *
	 * @param files the paths, or {@code null} if the trial is not judged
	 * @return the document, or a JSON null if {@code files} is null
	 */
	private JsonNode writeFiles(TrialFiles files)
	{
		if (files == null)
			return json.nullNode();
		ObjectNode node = json.createObjectNode();
		node.put("transcript", files.transcript().toString());
		node.put("effects", files.effects().toString());
		node.put("initial_project", files.initialProject().toString());
		return node;
	}

	/**
	 * Reads where a trial session's harness events and file changes were retained.
	 *
	 * @param node the object that holds the {@code files} field
	 * @return the paths, or {@code null} if the trial is not judged
	 */
	private static TrialFiles readFiles(JsonNode node)
	{
		JsonNode files = nullable(node, "files");
		if (files == null)
			return null;
		return new TrialFiles(Path.of(text(files, "transcript")), Path.of(text(files, "effects")),
			Path.of(text(files, "initial_project")));
	}

	/**
	 * Converts a campaign header to a document.
	 *
	 * @param header the header
	 * @return the document
	 */
	private ObjectNode writeHeader(Header header)
	{
		ObjectNode node = json.createObjectNode();
		node.put("id", header.id());
		node.put("root", header.root().toString());
		node.put("target", PortablePaths.text(header.target()));
		node.put("harness", EnumNames.lowerCase(header.harness()));
		node.put("lifecycle", EnumNames.lowerCase(header.lifecycle()));
		node.put("goal", header.goal());
		return node;
	}

	/**
	 * Converts a document to a campaign header.
	 *
	 * @param node the document
	 * @return the header
	 */
	private static Header readHeader(JsonNode node)
	{
		return new Header(text(node, "id"), Path.of(text(node, "root")), Path.of(text(node, "target")),
			constant(node, "harness", Harness.class), constant(node, "lifecycle", Lifecycle.class),
			nullableText(node, "goal"));
	}

	/**
	 * Converts a contract lineage to a document.
	 *
	 * @param lineage the lineage
	 * @return the document
	 */
	private ObjectNode writeLineage(Lineage lineage)
	{
		ObjectNode node = json.createObjectNode();
		node.put("number", lineage.number());
		node.set("contract", json.readTree(schema.toJson(lineage.contract())));
		node.put("contract_hash", lineage.contractHash().hex());
		node.set("development_stream", writeStream(lineage.development()));
		node.set("baseline_stream", writeStream(lineage.baseline()));
		ArrayNode regressionSet = node.putArray("regression_set");
		for (RegressionTestCase regression : lineage.regressionSet())
		{
			ObjectNode element = regressionSet.addObject();
			element.set("test_case", writeTestCase(regression.testCase()));
			putConstant(element, "working_verdict", regression.working());
			element.put("settled", regression.settled());
		}
		ArrayNode unavailable = node.putArray("unavailable");
		for (Outcome outcome : Outcome.values())
		{
			if (lineage.unavailable().contains(outcome))
				unavailable.add(EnumNames.lowerCase(outcome));
		}
		ArrayNode added = node.putArray("added_test_cases");
		for (AddedTestCase testCase : lineage.added().testCases())
			added.add(writeAddedTestCase(testCase));
		return node;
	}

	/**
	 * Converts a development test case that a reproduction added to a document.
	 *
	 * @param testCase the added test case
	 * @return the document
	 */
	private ObjectNode writeAddedTestCase(AddedTestCase testCase)
	{
		ObjectNode element = json.createObjectNode();
		element.put("attempt", testCase.attempt());
		element.put("test_id", testCase.testId());
		element.set("test_case", writeValues(testCase.testCase()));
		return element;
	}

	/**
	 * Converts a document to a development test case that a reproduction added.
	 *
	 * @param element the document
	 * @return the added test case
	 */
	private static AddedTestCase readAddedTestCase(JsonNode element)
	{
		return new AddedTestCase(integer(element, "attempt"), text(element, "test_id"),
			readValues(field(element, "test_case")));
	}

	/**
	 * Converts a test case's values and expected outcome to a document.
	 *
	 * @param testCase the test case
	 * @return the document
	 */
	private ObjectNode writeValues(TestCase testCase)
	{
		ObjectNode node = json.createObjectNode();
		TestValueJson.write(testCase.values(), node.putArray("values"));
		node.put(TestCase.EXPECTED_OUTCOME, testCase.expectedOutcome());
		return node;
	}

	/**
	 * Converts a document to a test case's values and expected outcome.
	 *
	 * @param node the document
	 * @return the test case
	 */
	private static TestCase readValues(JsonNode node)
	{
		return new TestCase(TestValueJson.read(field(node, "values")), text(node, TestCase.EXPECTED_OUTCOME));
	}

	/**
	 * Converts an attempt's failure groups to a document.
	 *
	 * @param groups the groups
	 * @return the document
	 */
	private ArrayNode writeGroups(List<FailureGroup> groups)
	{
		ArrayNode array = json.createArrayNode();
		for (FailureGroup group : groups)
		{
			ObjectNode node = array.addObject();
			node.put("cell", group.cellId());
			node.put("test_id", group.testId());
			node.put("assertion_id", group.failed().assertionId());
			node.put("behavior", group.failed().behavior());
			node.put("formal_failures", group.formalFailures());
			node.put("round_limit", group.roundLimit());
			ArrayNode rounds = node.putArray("rounds");
			for (ReproductionRound round : group.rounds())
			{
				ObjectNode roundNode = rounds.addObject();
				roundNode.put("cell", round.cellId());
				ArrayNode testCases = roundNode.putArray("test_cases");
				for (TriedTestCase tried : round.testCases())
				{
					ObjectNode triedNode = testCases.addObject();
					triedNode.set("test_case", writeAddedTestCase(tried.testCase()));
					triedNode.put("trials", tried.trials());
					triedNode.put("failures", tried.failures());
				}
			}
			putConstant(node, "status", group.status());
		}
		return array;
	}

	/**
	 * Converts a document to an attempt's failure groups.
	 *
	 * @param node the attempt's document
	 * @return the groups
	 */
	private static List<FailureGroup> readGroups(JsonNode node)
	{
		List<FailureGroup> groups = new ArrayList<>();
		for (JsonNode element : elements(node, "groups"))
		{
			List<ReproductionRound> rounds = new ArrayList<>();
			for (JsonNode roundNode : elements(element, "rounds"))
			{
				List<TriedTestCase> testCases = new ArrayList<>();
				for (JsonNode tried : elements(roundNode, "test_cases"))
				{
					testCases.add(new TriedTestCase(readAddedTestCase(field(tried, "test_case")),
						integer(tried, "trials"), integer(tried, "failures")));
				}
				rounds.add(new ReproductionRound(text(roundNode, "cell"), testCases));
			}
			groups.add(new FailureGroup(text(element, "cell"), text(element, "test_id"),
				new FailedAssertion(text(element, "assertion_id"), text(element, "behavior")),
				integer(element, "formal_failures"), integer(element, "round_limit"), rounds,
				constant(element, "status", FailureGroup.Status.class)));
		}
		return groups;
	}

	/**
	 * Converts a document to a contract lineage.
	 *
	 * @param node the document
	 * @return the lineage
	 */
	private Lineage readLineage(JsonNode node)
	{
		JsonNode contractNode = field(node, "contract");
		Contract contract;
		try
		{
			contract = schema.parse(json.writeValueAsString(contractNode));
		}
		catch (InvalidProposalException e)
		{
			throw new IllegalArgumentException("contract: " + e.getMessage(), e);
		}
		List<RegressionTestCase> regressionSet = new ArrayList<>();
		for (JsonNode element : elements(node, "regression_set"))
		{
			regressionSet.add(new RegressionTestCase(readTestCase(field(element, "test_case")),
				nullableConstant(element, "working_verdict", Verdict.class), bool(element, "settled")));
		}
		Set<Outcome> unavailable = EnumSet.noneOf(Outcome.class);
		for (JsonNode element : elements(node, "unavailable"))
			unavailable.add(constantValue(element, "unavailable", Outcome.class));
		List<AddedTestCase> added = new ArrayList<>();
		for (JsonNode element : elements(node, "added_test_cases"))
			added.add(readAddedTestCase(element));
		return new Lineage(integer(node, "number"), contract, sha256(node, "contract_hash"),
			readStream(field(node, "development_stream")), readStream(field(node, "baseline_stream")), regressionSet,
			unavailable, new AddedTestCases(added));
	}

	/**
	 * Converts the development state to a document.
	 *
	 * @param development the state
	 * @return the document
	 */
	private ObjectNode writeDevelopment(Development development)
	{
		ObjectNode node = json.createObjectNode();
		node.set("working", writeCandidate(development.working()));
		node.put("series", development.series());
		node.set("counts", writeCounts(development.counts()));
		node.set("failures", writeFailures(development.failures()));
		Diagnosis diagnosis = development.diagnosis();
		if (diagnosis == null)
			node.putNull("diagnosis");
		else
		{
			ObjectNode element = node.putObject("diagnosis");
			element.set("pending", writeTestCases(diagnosis.pending()));
			element.put("passed_reruns", diagnosis.passedReruns());
			putKey(element, "rerun", diagnosis.rerun());
			element.put("reproduced", diagnosis.reproduced());
		}
		Round round = development.round();
		if (round == null)
			node.putNull("round");
		else
			node.set("round", writeRound(round));
		PromptAuthorAttempt promptAuthor = development.promptAuthor();
		if (promptAuthor == null)
			node.putNull("prompt_author");
		else
			node.set("prompt_author", writePromptAuthorAttempt(promptAuthor));
		ObjectNode reviewPoints = node.putObject("review_points");
		for (Entry<String, Integer> entry : new TreeMap<>(development.reviewPoints()).entrySet())
			reviewPoints.put(entry.getKey(), entry.getValue());
		ArrayNode outcomes = node.putArray("test_case_outcomes");
		for (TestCaseOutcomes outcome : development.testCaseOutcomes())
		{
			ObjectNode element = outcomes.addObject();
			element.set("test_case", writeTestCase(outcome.testCase()));
			element.put("passes", outcome.passes());
			element.put("outcomes", outcome.outcomes());
		}
		Predecessor predecessor = development.predecessor();
		if (predecessor == null)
			node.putNull("predecessor");
		else
		{
			ObjectNode element = node.putObject("predecessor");
			element.set("development", writeDevelopment(predecessor.development()));
			element.set("round", writeRound(predecessor.round()));
			element.put("cell", predecessor.cell());
			ArrayNode regressions = element.putArray("regression_set");
			for (RegressionTestCase regression : predecessor.regressionSet())
			{
				ObjectNode testCase = regressions.addObject();
				testCase.set("test_case", writeTestCase(regression.testCase()));
				putConstant(testCase, "working_verdict", regression.working());
				testCase.put("settled", regression.settled());
			}
		}
		ArrayNode reverts = node.putArray("reverts");
		for (Revert revert : development.reverts())
		{
			ObjectNode element = reverts.addObject();
			element.set("withdrawn", writeCandidate(revert.withdrawn()));
			element.put("cell", revert.cell());
			element.put("upper_bound", revert.upperBound());
			element.put("predecessor_lower_bound", revert.predecessorLowerBound());
			element.set("development", writeDevelopment(revert.development()));
		}
		return node;
	}

	/**
	 * Writes the frozen prompt author policy and all reserved or validated attempts of a round.
	 *
	 * @param round the round
	 * @return its document
	 */
	private ObjectNode writeRound(Round round)
	{
		ObjectNode node = json.createObjectNode();
		node.put("weak", round.weak());
		node.put("rank_approaches", round.rankApproaches());
		node.put("rotation_size", round.rotationSize());
		ObjectNode orders = node.putObject("approach_orders");
		for (Entry<String, ApproachOrder> entry : new TreeMap<>(round.approachOrders()).entrySet())
		{
			ObjectNode order = orders.putObject(entry.getKey());
			order.put("fallback", entry.getValue().fallback());
			ArrayNode approaches = order.putArray("approaches");
			for (int index = 0; index < entry.getValue().approaches().size(); ++index)
			{
				approaches.addObject().put("name", EnumNames.lowerCase(entry.getValue().approaches().get(index))).
					put("reason", entry.getValue().reasons().get(index));
			}
		}
		ArrayNode attempts = node.putArray("attempts");
		for (Entry<String, Integer> entry : round.attempts().entrySet())
		{
			ObjectNode element = attempts.addObject();
			element.put("cell", entry.getKey());
			element.put("attempts", entry.getValue());
		}
		ArrayNode reservations = node.putArray("reservations");
		for (Entry<Integer, String> entry : round.reservations().entrySet())
		{
			ObjectNode reservation = reservations.addObject();
			reservation.put("number", entry.getKey());
			reservation.put("cell", entry.getValue());
		}
		ArrayNode contenders = node.putArray("contenders");
		for (PromptAuthorAttempt attempt : round.validationAttempts())
			contenders.add(writePromptAuthorAttempt(attempt));
		return node;
	}

	/**
	 * Writes one prompt author attempt and all promotion or reduction progress.
	 *
	 * @param attempt the attempt
	 * @return its document
	 */
	private ObjectNode writePromptAuthorAttempt(PromptAuthorAttempt attempt)
	{
		ObjectNode node = json.createObjectNode();
		node.put("number", attempt.number());
		node.put("cell", attempt.cell());
		node.put("snapshot", attempt.snapshot().hex());
		node.set("minimality", writeMinimality(attempt.minimality()));
		node.set("runs", writePromotionRuns(attempt.runs()));
		return node;
	}

	/**
	 * Writes the targeted evidence used by promotion, minimality and retained reports.
	 *
	 * @param runs the promotion runs
	 * @return their documents
	 */
	private ArrayNode writePromotionRuns(List<PromotionRun> runs)
	{
		ArrayNode array = json.createArrayNode();
		for (PromotionRun run : runs)
		{
			ObjectNode node = array.addObject();
			node.put("role", EnumNames.lowerCase(run.role()));
			node.set("test_case", writeTestCase(run.testCase()));
			putKey(node, "trial", run.trial());
			putConstant(node, "verdict", run.verdict());
		}
		return array;
	}

	/**
	 * Reads a prompt author attempt without reconstructing its evidence.
	 *
	 * @param node its document
	 * @return the attempt
	 */
	private static PromptAuthorAttempt readPromptAuthorAttempt(JsonNode node)
	{
		return new PromptAuthorAttempt(integer(node, "number"), text(node, "cell"), sha256(node, "snapshot"),
			readPromotionRuns(node, "runs"), readMinimality(field(node, "minimality")));
	}

	/**
	 * Reads targeted promotion evidence.
	 *
	 * @param node the parent document
	 * @param name the array field
	 * @return the runs in plan order
	 */
	private static List<PromotionRun> readPromotionRuns(JsonNode node, String name)
	{
		List<PromotionRun> runs = new ArrayList<>();
		for (JsonNode run : elements(node, name))
		{
			runs.add(new PromotionRun(constant(run, "role", PromotionRun.Role.class),
				readTestCase(field(run, "test_case")), nullableKey(run, "trial"),
				nullableConstant(run, "verdict", Verdict.class)));
		}
		return runs;
	}

	/**
	 * Writes all bytes and progress needed to resume a minimality search without the authoring copy.
	 *
	 * @param state the search
	 * @return its document
	 */
	private ObjectNode writeMinimality(Minimality state)
	{
		ObjectNode node = json.createObjectNode();
		node.put("groups", state.groups());
		node.put("group", state.group());
		node.put("accepted", state.accepted().hex());
		ArrayNode retained = node.putArray("retained");
		for (String hunk : state.retained())
			retained.add(hunk);
		if (state.testing() == null)
			node.putNull("testing");
		else
		{
			ArrayNode testing = node.putArray("testing");
			for (String hunk : state.testing())
				testing.add(hunk);
		}
		ArrayNode files = node.putArray("files");
		for (FileDelta file : state.files())
		{
			ObjectNode element = files.addObject();
			element.put("path", PortablePaths.text(file.path()));
			element.put("permissions", PosixFilePermissions.toString(file.permissions()));
			putContent(element, "before", file.before());
			putContent(element, "after", file.after());
		}
		ArrayNode runs = node.putArray("accepted_runs");
		for (PromotionRun run : state.acceptedRuns())
		{
			ObjectNode element = runs.addObject();
			element.put("role", EnumNames.lowerCase(run.role()));
			element.set("test_case", writeTestCase(run.testCase()));
			putKey(element, "trial", run.trial());
			putConstant(element, "verdict", run.verdict());
		}
		return node;
	}

	/**
	 * Writes nullable file bytes using base64, preserving binary files and final newlines.
	 *
	 * @param node the containing document
	 * @param name the side of the delta
	 * @param content the content, or {@code null} for absence
	 */
	private static void putContent(ObjectNode node, String name, FileContent content)
	{
		if (content == null)
			node.putNull(name);
		else
			node.put(name, Base64.getEncoder().encodeToString(content.bytes()));
	}

	/**
	 * Reads a persisted minimality search.
	 *
	 * @param node its document
	 * @return the search
	 */
	private static Minimality readMinimality(JsonNode node)
	{
		List<FileDelta> files = new ArrayList<>();
		for (JsonNode element : elements(node, "files"))
		{
			FileContent before = null;
			FileContent after = null;
			if (nullable(element, "before") != null)
				before = new FileContent(Base64.getDecoder().decode(text(element, "before")));
			if (nullable(element, "after") != null)
				after = new FileContent(Base64.getDecoder().decode(text(element, "after")));
			files.add(new FileDelta(Path.of(text(element, "path")), before, after,
				PosixFilePermissions.fromString(text(element, "permissions"))));
		}
		List<PromotionRun> runs = new ArrayList<>();
		for (JsonNode run : elements(node, "accepted_runs"))
		{
			runs.add(new PromotionRun(constant(run, "role", PromotionRun.Role.class),
				readTestCase(field(run, "test_case")), nullableKey(run, "trial"),
				nullableConstant(run, "verdict", Verdict.class)));
		}
		List<String> testing = null;
		if (nullable(node, "testing") != null)
			testing = readStrings(node, "testing");
		return new Minimality(files, readStrings(node, "retained"), integer(node, "groups"), integer(node, "group"),
			testing, sha256(node, "accepted"), runs);
	}

	/**
	 * Converts a document to the development state.
	 *
	 * @param node the document
	 * @return the state
	 */
	private static Development readDevelopment(JsonNode node)
	{
		Diagnosis diagnosis = null;
		JsonNode diagnosisNode = nullable(node, "diagnosis");
		if (diagnosisNode != null)
		{
			diagnosis = new Diagnosis(readTestCases(diagnosisNode, "pending"), integer(diagnosisNode, "passed_reruns"),
				nullableKey(diagnosisNode, "rerun"), bool(diagnosisNode, "reproduced"));
		}
		Round round = null;
		JsonNode roundNode = nullable(node, "round");
		if (roundNode != null)
			round = readRound(roundNode);
		PromptAuthorAttempt promptAuthor = null;
		JsonNode promptAuthorNode = nullable(node, "prompt_author");
		if (promptAuthorNode != null)
			promptAuthor = readPromptAuthorAttempt(promptAuthorNode);
		Map<String, Integer> reviewPoints = new LinkedHashMap<>();
		for (Entry<String, JsonNode> entry : object(node, "review_points").properties())
			reviewPoints.put(entry.getKey(), intValue(entry.getValue(), "review_points." + entry.getKey()));
		List<Failure> failures = readFailures(node, "failures");
		List<TestCaseOutcomes> outcomes = new ArrayList<>();
		for (JsonNode outcome : elements(node, "test_case_outcomes"))
		{
			outcomes.add(new TestCaseOutcomes(readTestCase(field(outcome, "test_case")), integer(outcome, "passes"),
				integer(outcome, "outcomes")));
		}
		Predecessor predecessor = null;
		JsonNode predecessorNode = nullable(node, "predecessor");
		if (predecessorNode != null)
		{
			List<RegressionTestCase> regressions = new ArrayList<>();
			for (JsonNode regression : elements(predecessorNode, "regression_set"))
			{
				regressions.add(new RegressionTestCase(readTestCase(field(regression, "test_case")),
					nullableConstant(regression, "working_verdict", Verdict.class), bool(regression, "settled")));
			}
			predecessor = new Predecessor(readDevelopment(field(predecessorNode, "development")),
				readRound(field(predecessorNode, "round")), text(predecessorNode, "cell"), regressions);
		}
		List<Revert> reverts = new ArrayList<>();
		for (JsonNode revert : elements(node, "reverts"))
		{
			reverts.add(new Revert(readCandidate(field(revert, "withdrawn")), text(revert, "cell"),
				floating(revert, "upper_bound"), floating(revert, "predecessor_lower_bound"),
				readDevelopment(field(revert, "development"))));
		}
		return new Development(readCandidate(field(node, "working")), integer(node, "series"),
			readCounts(field(node, "counts")), failures, diagnosis, round, promptAuthor,
			reviewPoints, outcomes, predecessor, reverts);
	}

	/**
	 * Reads a round's frozen prompt author policy and durable attempt reservations.
	 *
	 * @param node the round document
	 * @return the round
	 */
	private static Round readRound(JsonNode node)
	{
		Map<String, Integer> attempts = new LinkedHashMap<>();
		for (JsonNode element : elements(node, "attempts"))
		{
			String cell = text(element, "cell");
			if (attempts.put(cell, integer(element, "attempts")) != null)
				throw new IllegalArgumentException("round: duplicate cell " + cell);
		}
		Map<Integer, String> reservations = new LinkedHashMap<>();
		for (JsonNode reservation : elements(node, "reservations"))
		{
			int number = integer(reservation, "number");
			if (reservations.put(number, text(reservation, "cell")) != null)
				throw new IllegalArgumentException("round: duplicate reservation " + number);
		}
		List<PromptAuthorAttempt> contenders = new ArrayList<>();
		for (JsonNode contender : elements(node, "contenders"))
			contenders.add(readPromptAuthorAttempt(contender));
		Map<String, ApproachOrder> orders = new LinkedHashMap<>();
		for (Entry<String, JsonNode> entry : object(node, "approach_orders").properties())
		{
			List<PromptAuthorApproach> approaches = new ArrayList<>();
			List<String> reasons = new ArrayList<>();
			for (JsonNode approach : elements(entry.getValue(), "approaches"))
			{
				approaches.add(constant(approach, "name", PromptAuthorApproach.class));
				reasons.add(text(approach, "reason"));
			}
			orders.put(entry.getKey(), new ApproachOrder(approaches, reasons, bool(entry.getValue(), "fallback")));
		}
		return new Round(attempts, bool(node, "weak"), reservations, contenders, orders,
			bool(node, "rank_approaches"), integer(node, "rotation_size"));
	}

	/**
	 * Converts a certification attempt to a document.
	 *
	 * @param attempt the attempt
	 * @return the document
	 */
	private ObjectNode writeAttempt(CertificationAttempt attempt)
	{
		ObjectNode node = json.createObjectNode();
		node.put("index", attempt.index());
		node.put("lineage", attempt.lineage());
		node.set("candidate", writeCandidate(attempt.candidate()));
		node.set("identity", writeIdentity(attempt.identity()));
		CertificationProcedure procedure = attempt.procedure();
		ObjectNode procedureNode = node.putObject("procedure");
		procedureNode.put("improvement_level", procedure.improvementLevel().threshold().toString());
		procedureNode.put("baseline_index", procedure.baselineIndex());
		procedureNode.set("baseline_identity", writeIdentity(procedure.baselineIdentity()));
		ArrayNode cells = procedureNode.putArray("cells");
		for (String cell : procedure.cells())
			cells.add(cell);
		procedureNode.set("boundaries", writeBoundaries(procedure.boundaries()));
		procedureNode.put("bets", procedure.bets());
		procedureNode.put("allocation_policy", procedure.allocationPolicy());
		node.put("control", attempt.control().hex());
		node.set("counts", writeCounts(attempt.counts()));
		node.set("control_pairings", writePairings(attempt.controlPairings()));
		node.set("incumbent_pairings", writePairings(attempt.incumbentPairings()));
		node.set("failures", writeFailures(attempt.failures()));
		node.set("formal_stream", writeStream(attempt.formalStream()));
		putConstant(node, "outcome", attempt.outcome());
		node.set("groups", writeGroups(attempt.groups()));
		return node;
	}

	/**
	 * Converts a document to a certification attempt.
	 *
	 * @param node the document
	 * @return the attempt
	 */
	private static CertificationAttempt readAttempt(JsonNode node)
	{
		JsonNode procedureNode = field(node, "procedure");
		List<String> cellIds = new ArrayList<>();
		for (JsonNode cell : elements(procedureNode, "cells"))
			cellIds.add(stringValue(cell, "cells"));
		CertificationProcedure procedure = new CertificationProcedure(level(procedureNode, "improvement_level"),
			integer(procedureNode, "baseline_index"), readIdentity(field(procedureNode, "baseline_identity")), cellIds,
			readBoundaries(field(procedureNode, "boundaries")),
			text(procedureNode, "bets"), text(procedureNode, "allocation_policy"));
		return new CertificationAttempt(integer(node, "index"), integer(node, "lineage"),
			readCandidate(field(node, "candidate")), readIdentity(field(node, "identity")), procedure,
			sha256(node, "control"), readCounts(field(node, "counts")),
			readPairings(field(node, "control_pairings"), "control_pairings"),
			readPairings(field(node, "incumbent_pairings"), "incumbent_pairings"), readFailures(node, "failures"),
			readStream(field(node, "formal_stream")), nullableConstant(node, "outcome", Outcome.class),
			readGroups(node));
	}

	/**
	 * Converts frozen decision boundaries to a document.
	 *
	 * @param boundaries the boundaries
	 * @return the document
	 */
	private ObjectNode writeBoundaries(DecisionBoundaries boundaries)
	{
		ObjectNode node = json.createObjectNode();
		node.put("level", boundaries.level().threshold().toString());
		node.put("minimum_pass_rate", boundaries.minimumPassRate().toPlainString());
		node.put("cap", boundaries.cap());
		node.set("minimum_certifying_passes", writeIntegers(boundaries.minimumCertifyingPasses()));
		node.set("maximum_clearly_below_passes", writeIntegers(boundaries.maximumClearlyBelowPasses()));
		ArrayNode comparisons = node.putArray("comparisons");
		for (ComparisonBoundaries comparison : boundaries.comparisons())
		{
			ObjectNode element = comparisons.addObject();
			element.put("incumbent_upper", comparison.comparison().incumbentUpper().toPlainString());
			element.put("incumbent_lower", comparison.comparison().incumbentLower().toPlainString());
			element.put("level", comparison.comparison().level().threshold().toString());
			element.set("minimum_proven_better_passes", writeIntegers(comparison.minimumProvenBetterPasses()));
			element.set("maximum_proven_worse_passes", writeIntegers(comparison.maximumProvenWorsePasses()));
		}
		return node;
	}

	/**
	 * Converts a document to frozen decision boundaries.
	 *
	 * @param node the document
	 * @return the boundaries
	 */
	private static DecisionBoundaries readBoundaries(JsonNode node)
	{
		BigDecimal minimumPassRate = decimal(node, "minimum_pass_rate");
		List<ComparisonBoundaries> comparisons = new ArrayList<>();
		for (JsonNode element : elements(node, "comparisons"))
		{
			Comparison comparison = new Comparison(decimal(element, "incumbent_upper"),
				decimal(element, "incumbent_lower"), level(element, "level"), minimumPassRate);
			comparisons.add(new ComparisonBoundaries(comparison, readIntegers(element, "minimum_proven_better_passes"),
				readIntegers(element, "maximum_proven_worse_passes")));
		}
		return new DecisionBoundaries(level(node, "level"), minimumPassRate,
			integer(node, "cap"), readIntegers(node, "minimum_certifying_passes"),
			readIntegers(node, "maximum_clearly_below_passes"), comparisons);
	}

	/**
	 * Converts a logical trial to a document.
	 *
	 * @param trial the trial
	 * @return the document
	 */
	private ObjectNode writeTrial(LogicalTrial trial)
	{
		ObjectNode node = json.createObjectNode();
		node.set("key", writeKey(trial.key()));
		node.set("test_case", writeTestCase(trial.testCase()));
		node.set("identity", writeIdentity(trial.identity()));
		node.set("allocation", writeAllocation(trial.allocation()));
		node.put("state", EnumNames.lowerCase(trial.state()));
		putConstant(node, "verdict", trial.verdict());
		node.put("rationale", trial.rationale());
		putFailedAssertions(node, trial.failedAssertions());
		node.set("triage", writeTriage(trial.triage()));
		node.put("infrastructure_failures", trial.infrastructureFailures());
		node.set("infrastructure_attempts", writeInfrastructureAttempts(trial.infrastructureAttempts()));
		node.put("observations", trial.observations());
		node.set("responses", writeResponses(trial.responses()));
		node.set("files", writeFiles(trial.files()));
		return node;
	}

	/**
	 * Converts a document to a logical trial.
	 *
	 * @param node the document
	 * @return the trial
	 */
	private static LogicalTrial readTrial(JsonNode node)
	{
		Verdict verdict = nullableConstant(node, "verdict", Verdict.class);
		String rationale = nullableText(node, "rationale");
		return new LogicalTrial(readKey(field(node, "key")), readTestCase(field(node, "test_case")),
			readIdentity(field(node, "identity")), readAllocation(field(node, "allocation")),
			constant(node, "state", TrialState.class), verdict, rationale, readFailedAssertions(node),
			readTriage(node),
			integer(node, "infrastructure_failures"), readInfrastructureAttempts(node), integer(node, "observations"),
			readResponses(node), readFiles(node));
	}

	/**
	 * Converts a failure's impact to a document.
	 *
	 * @param triage the impact, or {@code null} if none was decided
	 * @return the document, or a JSON null if {@code triage} is null
	 */
	private JsonNode writeTriage(Triage triage)
	{
		if (triage == null)
			return json.nullNode();
		ObjectNode node = json.createObjectNode();
		node.put("impact", EnumNames.lowerCase(triage.impact()));
		node.put("rationale", triage.rationale());
		return node;
	}

	/**
	 * Reads a failure's impact.
	 *
	 * @param node the object that holds the {@code triage} field
	 * @return the impact, or {@code null} if none was decided
	 */
	private static Triage readTriage(JsonNode node)
	{
		JsonNode triage = nullable(node, "triage");
		if (triage == null)
			return null;
		return new Triage(constant(triage, "impact", Impact.class), text(triage, "rationale"));
	}

	/**
	 * Converts an allocation decision to a document.
	 *
	 * @param allocation the allocation decision
	 * @return the document
	 */
	private ObjectNode writeAllocation(Allocation allocation)
	{
		ObjectNode node = json.createObjectNode();
		node.put("rule", EnumNames.lowerCase(allocation.rule()));
		node.put("evidence_revision", allocation.evidenceRevision());
		node.put("assigned_at", allocation.assignedAt().toString());
		return node;
	}

	/**
	 * Converts a document to an allocation decision.
	 *
	 * @param node the document
	 * @return the allocation decision
	 */
	private static Allocation readAllocation(JsonNode node)
	{
		return new Allocation(constant(node, "rule", AllocationRule.class), longInteger(node, "evidence_revision"),
			instant(node, "assigned_at"));
	}

	/**
	 * Converts infrastructure attempts to a document.
	 *
	 * @param attempts the attempts
	 * @return the document
	 */
	private ArrayNode writeInfrastructureAttempts(List<InfrastructureAttempt> attempts)
	{
		ArrayNode array = json.createArrayNode();
		for (InfrastructureAttempt attempt : attempts)
		{
			ObjectNode node = array.addObject();
			node.put("ended_at", attempt.endedAt().toString());
			node.put("failure", EnumNames.lowerCase(attempt.failure()));
			node.put("detail", attempt.detail());
		}
		return array;
	}

	/**
	 * Reads the {@code infrastructure_attempts} field of a trial or trial record.
	 *
	 * @param node the object that holds the field
	 * @return the attempts
	 */
	private static List<InfrastructureAttempt> readInfrastructureAttempts(JsonNode node)
	{
		List<InfrastructureAttempt> attempts = new ArrayList<>();
		for (JsonNode element : elements(node, "infrastructure_attempts"))
		{
			attempts.add(new InfrastructureAttempt(instant(element, "ended_at"),
				constant(element, "failure", RunFailure.class), text(element, "detail")));
		}
		return attempts;
	}

	/**
	 * Converts a pending decision to a document.
	 *
	 * @param decision the decision
	 * @return the document
	 */
	private ObjectNode writeDecision(PendingDecision decision)
	{
		ObjectNode node = json.createObjectNode();
		switch (decision)
		{
			case PendingDecision.MeasurementChanged changed ->
			{
				node.put("type", "measurement_changed");
				node.set("observed", writeIdentity(changed.observed()));
				ArrayNode conditions = node.putArray("changed");
				for (MeasurementCondition condition : changed.changed())
					conditions.add(EnumNames.lowerCase(condition));
			}
			case PendingDecision.ConflictFound conflicts ->
			{
				node.put("type", "conflict_found");
				node.set("conflicts", writeConflicts(conflicts.conflicts()));
				node.set("resolutions", writeIntegers(conflicts.resolutions()));
			}
			case PendingDecision.UnownedChange change ->
			{
				node.put("type", "unowned_change");
				node.put("description", change.description());
			}
			case PendingDecision.TrialFailures failures ->
			{
				node.put("type", "trial_failures");
				node.set("key", writeKey(failures.key()));
			}
			case PendingDecision.CalibrationFailure failure ->
			{
				node.put("type", "calibration_failure");
				node.put("detail", failure.detail());
			}
			case PendingDecision.CheckpointReached checkpoint ->
			{
				node.put("type", "checkpoint_reached");
				node.put("cell", checkpoint.cell());
				node.put("outcomes", checkpoint.outcomes());
			}
			case PendingDecision.FailuresNotReproduced notReproduced ->
			{
				node.put("type", "failures_not_reproduced");
				node.put("attempt", notReproduced.attempt());
			}
			case PendingDecision.ImprovementProven proven ->
			{
				node.put("type", "improvement_proven");
				node.put("attempt", proven.attempt());
			}
			case PendingDecision.RegressionTradeOff tradeOff ->
			{
				node.put("type", "regression_trade_off");
				node.put("attempt", tradeOff.attempt());
			}
			case PendingDecision.CertificationUnavailable unavailable ->
			{
				node.put("type", "certification_unavailable");
				node.put("outcome", EnumNames.lowerCase(unavailable.outcome()));
			}
			case PendingDecision.AuthorityWarnings warnings ->
			{
				node.put("type", "authority_warnings");
				ArrayNode array = node.putArray("warnings");
				for (AuthorityWarning warning : warnings.warnings())
					writeWarning(array.addObject(), warning);
			}
			case PendingDecision.TestRevisionBlocked blocked ->
			{
				node.put("type", "test_revision_blocked");
				node.put("test", blocked.test());
				node.put("reason", EnumNames.lowerCase(blocked.reason()));
				node.put("reasons", blocked.reasons());
				node.put("proposed_change", blocked.proposedChange());
			}
		}
		return node;
	}

	/**
	 * Writes a warning about an edit outside the edit authority.
	 *
	 * @param node the empty object that receives the warning's requirement, path, target, limit and choices
	 * @param warning the warning
	 */
	private static void writeWarning(ObjectNode node, AuthorityWarning warning)
	{
		node.put("requirement", warning.requirement());
		node.put("path", PortablePaths.text(warning.path()));
		node.put("target", PortablePaths.text(warning.target()));
		node.put("limit", EnumNames.lowerCase(warning.limit()));
		ArrayNode choices = node.putArray("choices");
		for (AuthorityWarning.Choice choice : warning.choices())
			choices.add(EnumNames.lowerCase(choice));
	}

	/**
	 * Indicates whether a pending decision reports a measurement change that consists only of conditions that CAT no
	 * longer records, so that nothing remains for the user to decide.
	 *
	 * @param node the pending decision's document
	 * @return {@code true} if the decision is a measurement change whose changed conditions are all retired
	 */
	private static boolean changesOnlyRetiredConditions(JsonNode node)
	{
		if (!text(node, "type").equals("measurement_changed"))
			return false;
		for (JsonNode element : elements(node, "changed"))
		{
			if (!RETIRED_CONDITIONS.contains(stringValue(element, "changed")))
				return false;
		}
		return true;
	}

	/**
	 * Converts a document to a pending decision.
	 *
	 * @param node the document
	 * @return the decision
	 */
	private static PendingDecision readDecision(JsonNode node)
	{
		String type = text(node, "type");
		return switch (type)
		{
			case "measurement_changed" ->
			{
				List<MeasurementCondition> changed = new ArrayList<>();
				for (JsonNode element : elements(node, "changed"))
				{
					if (!RETIRED_CONDITIONS.contains(stringValue(element, "changed")))
						changed.add(constantValue(element, "changed", MeasurementCondition.class));
				}
				yield new PendingDecision.MeasurementChanged(readIdentity(field(node, "observed")), changed);
			}
			case "conflict_found" ->
				new PendingDecision.ConflictFound(readConflicts(node), readIntegers(node, "resolutions"));
			case "unowned_change" -> new PendingDecision.UnownedChange(text(node, "description"));
			case "trial_failures" -> new PendingDecision.TrialFailures(readKey(field(node, "key")));
			case "calibration_failure" -> new PendingDecision.CalibrationFailure(text(node, "detail"));
			case "checkpoint_reached" ->
				new PendingDecision.CheckpointReached(text(node, "cell"), integer(node, "outcomes"));
			case "failures_not_reproduced" -> new PendingDecision.FailuresNotReproduced(integer(node, "attempt"));
			case "improvement_proven" -> new PendingDecision.ImprovementProven(integer(node, "attempt"));
			case "regression_trade_off" -> new PendingDecision.RegressionTradeOff(integer(node, "attempt"));
			case "certification_unavailable" ->
				new PendingDecision.CertificationUnavailable(constant(node, "outcome", Outcome.class));
			case "authority_warnings" ->
			{
				List<AuthorityWarning> warnings = new ArrayList<>();
				for (JsonNode element : elements(node, "warnings"))
					warnings.add(readWarning(element));
				yield new PendingDecision.AuthorityWarnings(warnings);
			}
			case "test_revision_blocked" -> new PendingDecision.TestRevisionBlocked(text(node, "test"),
				constant(node, "reason", PendingDecision.TestRevisionBlocked.Reason.class), text(node, "reasons"),
				text(node, "proposed_change"));
			default -> throw new IllegalArgumentException("pending_decisions: unknown type " + type);
		};
	}

	/**
	 * Converts a document to a warning about an edit outside the edit authority.
	 *
	 * @param node the object that holds the warning's requirement, path, target, limit and choices
	 * @return the warning
	 */
	private static AuthorityWarning readWarning(JsonNode node)
	{
		List<AuthorityWarning.Choice> choices = new ArrayList<>();
		for (JsonNode choice : elements(node, "choices"))
			choices.add(constantValue(choice, "choices", AuthorityWarning.Choice.class));
		return new AuthorityWarning(text(node, "requirement"), pathValue(field(node, "path"), "path"),
			pathValue(field(node, "target"), "target"), constant(node, "limit", Limit.class), choices);
	}

	/**
	 * Writes ordered prompt author conflicts for a pending decision.
	 *
	 * @param conflicts the conflicts
	 * @return their JSON array
	 */
	private ArrayNode writeConflicts(List<PromptAuthorConflict> conflicts)
	{
		ArrayNode array = json.createArrayNode();
		for (PromptAuthorConflict conflict : conflicts)
		{
			ObjectNode node = array.addObject();
			ArrayNode failures = node.putArray("failures");
			for (String failure : conflict.failures())
				failures.add(failure);
			ArrayNode requirements = node.putArray("requirement_ids");
			for (String requirement : conflict.requirementIds())
				requirements.add(requirement);
			ArrayNode quotations = node.putArray("quotations");
			for (PromptAuthorConflict.Quotation quotation : conflict.quotations())
			{
				ObjectNode entry = quotations.addObject();
				entry.put("path", PortablePaths.text(quotation.path()));
				entry.put("text", quotation.text());
			}
			ArrayNode resolutions = node.putArray("resolutions");
			for (String resolution : conflict.resolutions())
				resolutions.add(resolution);
			node.put("recommended", conflict.recommended());
			node.put("reason", conflict.reason());
		}
		return array;
	}

	/**
	 * Reads ordered prompt author conflicts from a pending-decision document.
	 *
	 * @param node the pending decision
	 * @return its conflicts
	 */
	private static List<PromptAuthorConflict> readConflicts(JsonNode node)
	{
		List<PromptAuthorConflict> conflicts = new ArrayList<>();
		for (JsonNode element : elements(node, "conflicts"))
		{
			List<PromptAuthorConflict.Quotation> quotations = new ArrayList<>();
			for (JsonNode quotation : elements(element, "quotations"))
			{
				quotations.add(new PromptAuthorConflict.Quotation(pathValue(field(quotation, "path"), "path"),
					text(quotation, "text")));
			}
			conflicts.add(new PromptAuthorConflict(readStrings(element, "failures"), readStrings(element,
				"requirement_ids"), quotations, readStrings(element, "resolutions"), integer(element, "recommended"),
				text(element, "reason")));
		}
		return conflicts;
	}

	/**
	 * Converts a trial key to a document.
	 *
	 * @param key the key
	 * @return the document
	 */
	private ObjectNode writeKey(TrialKey key)
	{
		ObjectNode node = json.createObjectNode();
		node.put("purpose", EnumNames.lowerCase(key.purpose()));
		node.put("series", key.series());
		node.put("snapshot", key.snapshot().hex());
		node.put("cell", key.cell());
		node.put("sequence", key.sequence());
		return node;
	}

	/**
	 * Writes a trial key, or null.
	 *
	 * @param node the object to write to
	 * @param name the field's name
	 * @param key the key, or null
	 */
	private void putKey(ObjectNode node, String name, TrialKey key)
	{
		if (key == null)
			node.putNull(name);
		else
			node.set(name, writeKey(key));
	}

	/**
	 * Converts a document to a trial key.
	 *
	 * @param node the document
	 * @return the key
	 */
	private static TrialKey readKey(JsonNode node)
	{
		return new TrialKey(constant(node, "purpose", Purpose.class), integer(node, "series"),
			sha256(node, "snapshot"), text(node, "cell"), longInteger(node, "sequence"));
	}

	/**
	 * Reads a trial key that may be null.
	 *
	 * @param node the object that holds the field
	 * @param name the field's name
	 * @return the key, or null
	 */
	private static TrialKey nullableKey(JsonNode node, String name)
	{
		JsonNode value = nullable(node, name);
		if (value == null)
			return null;
		return readKey(value);
	}

	/**
	 * Converts a test case to a document.
	 *
	 * @param testCase the test case
	 * @return the document
	 */
	private ObjectNode writeTestCase(DrawnTestCase testCase)
	{
		ObjectNode node = json.createObjectNode();
		node.put("cell_id", testCase.cellId());
		node.put("test_id", testCase.testId());
		node.set("test_case", writeValues(testCase.testCase()));
		ArrayNode turns = node.putArray("turns");
		for (String turn : testCase.turns())
			turns.add(turn);
		node.put("expected_behaviour", testCase.expectedBehaviour());
		node.put("generator", testCase.generator());
		node.put("stream_kind", EnumNames.lowerCase(testCase.streamKind()));
		node.put("stream_position", testCase.streamPosition());
		node.put("targeted", testCase.targeted());
		return node;
	}

	/**
	 * Converts a document to a test case.
	 *
	 * @param node the document
	 * @return the test case
	 */
	private static DrawnTestCase readTestCase(JsonNode node)
	{
		List<String> turns = new ArrayList<>();
		for (JsonNode element : elements(node, "turns"))
			turns.add(stringValue(element, "turns"));
		return new DrawnTestCase(text(node, "cell_id"), text(node, "test_id"),
			readValues(field(node, "test_case")), turns, text(node, "expected_behaviour"), text(node, "generator"),
			constant(node, "stream_kind", DrawStream.Kind.class), longInteger(node, "stream_position"),
			bool(node, "targeted"));
	}

	/**
	 * Converts test cases to a document.
	 *
	 * @param testCases the test cases
	 * @return the document
	 */
	private ArrayNode writeTestCases(List<DrawnTestCase> testCases)
	{
		ArrayNode node = json.createArrayNode();
		for (DrawnTestCase testCase : testCases)
			node.add(writeTestCase(testCase));
		return node;
	}

	/**
	 * Reads a list of test cases.
	 *
	 * @param node the object that holds the field
	 * @param name the field's name
	 * @return the test cases
	 */
	private static List<DrawnTestCase> readTestCases(JsonNode node, String name)
	{
		List<DrawnTestCase> testCases = new ArrayList<>();
		for (JsonNode element : elements(node, name))
			testCases.add(readTestCase(element));
		return testCases;
	}

	/**
	 * Converts failures to a document. Each failure is its test case's document with the judge's rationale, the
	 * failure's impact and the diagnostic reruns added.
	 *
	 * @param failures the failures
	 * @return the document
	 */
	private ArrayNode writeFailures(List<Failure> failures)
	{
		ArrayNode node = json.createArrayNode();
		for (Failure failure : failures)
		{
			ObjectNode element = writeTestCase(failure.testCase());
			element.put("rationale", failure.rationale());
			putFailedAssertions(element, failure.failedAssertions());
			element.set("triage", writeTriage(failure.triage()));
			element.set("responses", writeResponses(failure.responses()));
			element.set("files", writeFiles(failure.files()));
			ArrayNode reruns = element.putArray("reruns");
			for (DiagnosticRerun rerun : failure.reruns())
			{
				ObjectNode rerunNode = reruns.addObject();
				rerunNode.put("verdict", EnumNames.lowerCase(rerun.verdict()));
				rerunNode.put("rationale", rerun.rationale());
			}
			node.add(element);
		}
		return node;
	}

	/**
	 * Reads a list of failures.
	 *
	 * @param node the object that holds the field
	 * @param name the field's name
	 * @return the failures
	 */
	private static List<Failure> readFailures(JsonNode node, String name)
	{
		List<Failure> failures = new ArrayList<>();
		for (JsonNode element : elements(node, name))
		{
			List<DiagnosticRerun> reruns = new ArrayList<>();
			for (JsonNode rerun : elements(element, "reruns"))
				reruns.add(new DiagnosticRerun(constant(rerun, "verdict", Verdict.class), text(rerun, "rationale")));
			failures.add(new Failure(readTestCase(element), text(element, "rationale"),
				readFailedAssertions(element), readTriage(element), reruns, readResponses(element), readFiles(element)));
		}
		return failures;
	}

	/**
	 * Converts a draw stream's state to a document.
	 *
	 * @param state the state
	 * @return the document
	 */
	private ObjectNode writeStream(DrawStream.State state)
	{
		ObjectNode node = json.createObjectNode();
		node.put("kind", EnumNames.lowerCase(state.kind()));
		node.put("seed", state.seed());
		node.put("position", state.position());
		node.put("open", state.open());
		return node;
	}

	/**
	 * Converts a document to a draw stream's state.
	 *
	 * @param node the document
	 * @return the state
	 */
	private static DrawStream.State readStream(JsonNode node)
	{
		return new DrawStream.State(constant(node, "kind", DrawStream.Kind.class), longInteger(node, "seed"),
			longInteger(node, "position"), bool(node, "open"));
	}

	/**
	 * Converts a candidate to a document.
	 *
	 * @param candidate the candidate
	 * @return the document
	 */
	private ObjectNode writeCandidate(Candidate candidate)
	{
		ObjectNode node = json.createObjectNode();
		node.put("number", candidate.number());
		node.put("snapshot", candidate.snapshot().hex());
		return node;
	}

	/**
	 * Converts a document to a candidate.
	 *
	 * @param node the document
	 * @return the candidate
	 */
	private static Candidate readCandidate(JsonNode node)
	{
		return new Candidate(integer(node, "number"), sha256(node, "snapshot"));
	}

	/**
	 * Converts measurement conditions to a document: the campaign-wide conditions in condition order, then each
	 * cell's conditions in cell order.
	 *
	 * @param identity the conditions
	 * @return the document
	 */
	ObjectNode writeIdentity(MeasurementIdentity identity)
	{
		ObjectNode node = json.createObjectNode();
		writeConditions(node.putObject("conditions"), identity.values());
		ObjectNode cells = node.putObject("cells");
		for (Entry<String, Map<MeasurementCondition, String>> cell : new TreeMap<>(identity.cells()).entrySet())
			writeConditions(cells.putObject(cell.getKey()), cell.getValue());
		if (!identity.acceptedChanges().isEmpty())
		{
			ArrayNode accepted = node.putArray("accepted_changes");
			for (MeasurementIdentity observation : identity.acceptedChanges())
				accepted.add(writeIdentity(observation));
		}
		return node;
	}

	/**
	 * Writes measurement conditions, in condition order.
	 *
	 * @param node the object to write to
	 * @param values the value of each recorded condition
	 */
	private static void writeConditions(ObjectNode node, Map<MeasurementCondition, String> values)
	{
		for (MeasurementCondition condition : MeasurementCondition.values())
		{
			String value = values.get(condition);
			if (value != null)
				node.put(EnumNames.lowerCase(condition), value);
		}
	}

	/**
	 * Converts a document to measurement conditions.
	 *
	 * @param node the document
	 * @return the conditions
	 */
	static MeasurementIdentity readIdentity(JsonNode node)
	{
		requireObject(node, "identity");
		List<MeasurementIdentity> accepted = new ArrayList<>();
		if (node.has("accepted_changes"))
		{
			for (JsonNode observation : elements(node, "accepted_changes"))
			{
				if (observation.has("accepted_changes"))
					throw new IllegalArgumentException("Accepted observations cannot contain nested acceptances");
				accepted.add(readIdentity(observation));
			}
		}
		for (String name : node.propertyNames())
		{
			if (!Set.of("conditions", "cells", "accepted_changes").contains(name))
				throw new IllegalArgumentException("identity: unknown field " + name);
		}
		Map<String, Map<MeasurementCondition, String>> cells = new HashMap<>();
		for (Entry<String, JsonNode> cell : object(node, "cells").properties())
			cells.put(cell.getKey(), readConditions(cell.getValue(), "identity.cells." + cell.getKey()));
		return new MeasurementIdentity(readConditions(field(node, "conditions"), "identity.conditions"), cells,
			accepted);
	}

	/**
	 * Converts a document to measurement conditions.
	 *
	 * @param node the document
	 * @param name the document's name in error messages
	 * @return the value of each recorded condition
	 */
	private static Map<MeasurementCondition, String> readConditions(JsonNode node, String name)
	{
		requireObject(node, name);
		Map<MeasurementCondition, String> values = new EnumMap<>(MeasurementCondition.class);
		for (Entry<String, JsonNode> entry : node.properties())
		{
			if (!RETIRED_CONDITIONS.contains(entry.getKey()))
			{
				values.put(EnumNames.parse(entry.getKey(), MeasurementCondition.class),
					stringValue(entry.getValue(), name + "." + entry.getKey()));
			}
		}
		return values;
	}

	/**
	 * Converts per-cell counts to a document, sorted by cell identifier.
	 *
	 * @param counts the counts
	 * @return the document
	 */
	private ObjectNode writeCounts(Map<String, CellCounts> counts)
	{
		ObjectNode node = json.createObjectNode();
		for (Entry<String, CellCounts> entry : new TreeMap<>(counts).entrySet())
		{
			ObjectNode element = node.putObject(entry.getKey());
			element.put("passes", entry.getValue().passes());
			element.put("failures", entry.getValue().failures());
			element.set("batch_failures", writeIntegers(entry.getValue().batchFailures()));
		}
		return node;
	}

	/**
	 * Converts a document to per-cell counts.
	 *
	 * @param node the document
	 * @return the counts
	 */
	private static Map<String, CellCounts> readCounts(JsonNode node)
	{
		requireObject(node, "counts");
		Map<String, CellCounts> counts = new LinkedHashMap<>();
		for (Entry<String, JsonNode> entry : node.properties())
		{
			JsonNode element = entry.getValue();
			counts.put(entry.getKey(), new CellCounts(integer(element, "passes"), integer(element, "failures"),
				readIntegers(element, "batch_failures")));
		}
		return counts;
	}

	/**
	 * Converts per-cell comparisons with one kind of reference trial to a document, sorted by cell identifier.
	 *
	 * @param pairings the comparisons
	 * @return the document
	 */
	private ObjectNode writePairings(Map<String, Pairing> pairings)
	{
		ObjectNode node = json.createObjectNode();
		for (Entry<String, Pairing> entry : new TreeMap<>(pairings).entrySet())
		{
			ObjectNode element = node.putObject(entry.getKey());
			ArrayNode unpaired = element.putArray("unpaired");
			for (UnpairedTrial trial : entry.getValue().unpaired())
			{
				ObjectNode trialNode = unpaired.addObject();
				trialNode.set("test_case", writeTestCase(trial.testCase()));
				ArrayNode passed = trialNode.putArray("passed");
				for (String assertionId : new TreeSet<>(trial.passed()))
					passed.add(assertionId);
			}
			ArrayNode comparisons = element.putArray("comparisons");
			for (Entry<String, PairCounts> comparison : entry.getValue().comparisons().entrySet())
			{
				ObjectNode comparisonNode = comparisons.addObject();
				comparisonNode.put("assertion", comparison.getKey());
				PairCounts counts = comparison.getValue();
				comparisonNode.put("both_passed", counts.bothPassed());
				comparisonNode.put("candidate_only", counts.candidateOnly());
				comparisonNode.put("reference_only", counts.referenceOnly());
				comparisonNode.put("neither_passed", counts.neitherPassed());
			}
		}
		return node;
	}

	/**
	 * Converts a document to per-cell comparisons with one kind of reference trial.
	 *
	 * @param node the document
	 * @param name the document's field name
	 * @return the comparisons
	 */
	private static Map<String, Pairing> readPairings(JsonNode node, String name)
	{
		requireObject(node, name);
		Map<String, Pairing> pairings = new LinkedHashMap<>();
		for (Entry<String, JsonNode> entry : node.properties())
		{
			JsonNode element = entry.getValue();
			List<UnpairedTrial> unpaired = new ArrayList<>();
			for (JsonNode trial : elements(element, "unpaired"))
			{
				Set<String> passed = new LinkedHashSet<>();
				for (JsonNode assertionId : elements(trial, "passed"))
					passed.add(stringValue(assertionId, "passed"));
				unpaired.add(new UnpairedTrial(readTestCase(field(trial, "test_case")), passed));
			}
			Map<String, PairCounts> comparisons = new LinkedHashMap<>();
			for (JsonNode comparison : elements(element, "comparisons"))
			{
				String assertionId = text(comparison, "assertion");
				PairCounts counts = new PairCounts(integer(comparison, "both_passed"),
					integer(comparison, "candidate_only"), integer(comparison, "reference_only"),
					integer(comparison, "neither_passed"));
				if (comparisons.put(assertionId, counts) != null)
					throw new IllegalArgumentException(name + ": duplicate assertion " + assertionId);
			}
			pairings.put(entry.getKey(), new Pairing(unpaired, comparisons));
		}
		return pairings;
	}

	/**
	 * Converts integers to a document.
	 *
	 * @param values the integers
	 * @return the document
	 */
	private ArrayNode writeIntegers(List<Integer> values)
	{
		ArrayNode node = json.createArrayNode();
		for (int value : values)
			node.add(value);
		return node;
	}

	/**
	 * Reads a list of integers.
	 *
	 * @param node the object that holds the field
	 * @param name the field's name
	 * @return the integers
	 */
	private static List<Integer> readIntegers(JsonNode node, String name)
	{
		List<Integer> values = new ArrayList<>();
		for (JsonNode element : elements(node, name))
			values.add(intValue(element, name));
		return values;
	}

	/**
	 * Writes a constant's name, or null.
	 *
	 * @param node the object to write to
	 * @param name the field's name
	 * @param value the constant, or null
	 */
	private static void putConstant(ObjectNode node, String name, Enum<?> value)
	{
		if (value == null)
			node.putNull(name);
		else
			node.put(name, EnumNames.lowerCase(value));
	}

	/**
	 * Checks a document's format version.
	 *
	 * @param root the document
	 * @throws IllegalArgumentException if the document is not an object or has a different format version
	 */
	private static void requireVersion(JsonNode root)
	{
		requireObject(root, "$");
		int version = integer(root, VERSION);
		if (version == 43)
			upgradeVersion43((ObjectNode) root);
		// Version 44 differs from version 45 only by lacking the paired-rerun roles, so its runs read unchanged
		boolean lacksPeakWindow = version >= 43 && version <= 45;
		if (lacksPeakWindow)
		{
			upgradeVersion45((ObjectNode) root);
			return;
		}
		if (version != FORMAT_VERSION)
		{
			throw new IllegalArgumentException(VERSION + " is " + version + ": this is a version " + version +
				" campaign file, but this version of CAT reads only version " + FORMAT_VERSION + ". Start a new " +
				"campaign.");
		}
	}

	/**
	 * Upgrades a saved campaign or trial record before the current reader consumes it. Rounds retain their
	 * nine-approach rotation; subsequently created rounds rank all available approaches.
	 *
	 * @param root the version-43 document, mutated to the version-45 format
	 */
	private static void upgradeVersion43(ObjectNode root)
	{
		JsonNode optimization = root.get("optimization");
		if (optimization instanceof ObjectNode object)
			object.putObject("approaches");
		upgradeDevelopment(root.get("development"));
	}

	/**
	 * Adds the peak window to the throttle states of a saved campaign or trial record. A snapshot whose throttle counts
	 * backoffs was last changed by a rejection that no completed trial followed, so its peak window becomes 1, as the
	 * rejection would have set it. Otherwise its peak window becomes the campaign's ceiling: its window cap may hold
	 * a window that had just dropped when utilization reached the freeze level, and no saved value records the window
	 * that the campaign had reached. The units' own windows and the next rejection still limit the window, and a hold
	 * in progress still holds admissions until it ends. A trial session's peak window becomes its window cap, the
	 * level at which that historical throttle stopped growth.
	 *
	 * @param root the version-43 to version-45 document, mutated to the current format
	 */
	private static void upgradeVersion45(ObjectNode root)
	{
		root.put(VERSION, FORMAT_VERSION);
		if (root.get("throttle") instanceof ObjectNode throttle)
		{
			int peak = Campaign.DEFAULT_CEILING;
			if (root.has("ceiling"))
				peak = integer(root, "ceiling");
			if (integer(throttle, "backoffs") > 0)
				peak = 1;
			throttle.put("peak_window", peak);
		}
		if (root.get("sessions") instanceof ArrayNode sessions)
		{
			for (JsonNode session : sessions)
			{
				if (session.get("throttle") instanceof ObjectNode throttle)
					throttle.put("peak_window", integer(throttle, "window_cap"));
			}
		}
	}

	/**
	 * Upgrades the current development and recursively retained predecessor developments.
	 *
	 * @param node the development, or null for a document without development state
	 */
	private static void upgradeDevelopment(JsonNode node)
	{
		if (!(node instanceof ObjectNode development))
			return;
		upgradeRound(development.get("round"));
		JsonNode predecessor = development.get("predecessor");
		if (predecessor instanceof ObjectNode previous)
		{
			upgradeRound(previous.get("round"));
			upgradeDevelopment(previous.get("development"));
		}
	}

	/**
	 * Adds the saved rotation policy to a version-43 round.
	 *
	 * @param node the saved round, or null
	 */
	private static void upgradeRound(JsonNode node)
	{
		if (node instanceof ObjectNode round)
		{
			round.put("rank_approaches", false);
			round.put("rotation_size", PromptAuthorApproach.LEGACY_COUNT);
			round.putObject("approach_orders");
		}
	}

	/**
	 * Returns an object field.
	 *
	 * @param node the object that holds the field
	 * @param name the field's name
	 * @return the object
	 */
	private static JsonNode object(JsonNode node, String name)
	{
		JsonNode value = field(node, name);
		requireObject(value, name);
		return value;
	}

	/**
	 * Returns a string field that must be present but may be null.
	 *
	 * @param node the object that holds the field
	 * @param name the field's name
	 * @return the string, or null if it is JSON {@code null}
	 * @throws IllegalArgumentException if the field is missing or is neither a string nor null
	 */
	private static String nullableText(JsonNode node, String name)
	{
		JsonNode value = nullable(node, name);
		if (value == null)
			return null;
		return stringValue(value, name);
	}

	/**
	 * Returns the path that a string value holds.
	 *
	 * @param value the value
	 * @param name the value's name
	 * @return the path
	 * @throws IllegalArgumentException if the value is not a string or not a valid path
	 */
	private static Path pathValue(JsonNode value, String name)
	{
		String text = stringValue(value, name);
		try
		{
			return Path.of(text);
		}
		catch (InvalidPathException e)
		{
			throw new IllegalArgumentException(name + ": " + text + " is not a path", e);
		}
	}

	/**
	 * Returns a {@code long} field.
	 *
	 * @param node the object that holds the field
	 * @param name the field's name
	 * @return the value
	 * @throws IllegalArgumentException if the value is not a {@code long}
	 */
	private static long longInteger(JsonNode node, String name)
	{
		return longValue(field(node, name), name);
	}

	/**
	 * Reads a parsed integer value without coercing strings or fractional numbers.
	 *
	 * @param value the parsed value
	 * @param name its diagnostic name
	 * @return the integer
	 * @throws IllegalArgumentException if the value is not a 64-bit integer
	 */
	private static long longValue(JsonNode value, String name)
	{
		if (!value.isIntegralNumber() || !value.canConvertToLong())
			throw new IllegalArgumentException(name + ": must be a 64-bit integer");
		return value.asLong();
	}

	/**
	 * Returns a {@code double} field.
	 *
	 * @param node the object that holds the field
	 * @param name the field's name
	 * @return the value
	 * @throws IllegalArgumentException if the value is not a number
	 */
	private static double floating(JsonNode node, String name)
	{
		JsonNode value = field(node, name);
		if (!value.isNumber())
			throw new IllegalArgumentException(name + ": must be a number");
		return value.asDouble();
	}

	/**
	 * Returns a decimal field that is written as a string.
	 *
	 * @param node the object that holds the field
	 * @param name the field's name
	 * @return the value
	 * @throws IllegalArgumentException if the value is not a decimal string
	 */
	static BigDecimal decimal(JsonNode node, String name)
	{
		String value = text(node, name);
		try
		{
			return new BigDecimal(value);
		}
		catch (NumberFormatException e)
		{
			throw new IllegalArgumentException(name + ": must be a decimal number: " + value, e);
		}
	}

	/**
	 * Returns a significance level whose threshold is written as a string.
	 *
	 * @param node the object that holds the field
	 * @param name the field's name
	 * @return the level
	 * @throws IllegalArgumentException if the value is not a valid threshold
	 */
	private static SignificanceLevel level(JsonNode node, String name)
	{
		String value = text(node, name);
		try
		{
			return new SignificanceLevel(new BigInteger(value));
		}
		catch (NumberFormatException e)
		{
			throw new IllegalArgumentException(name + ": must be an integer: " + value, e);
		}
	}

	/**
	 * Returns an instant field that is written in ISO-8601 form.
	 *
	 * @param node the object that holds the field
	 * @param name the field's name
	 * @return the instant
	 * @throws IllegalArgumentException if the value is not an instant
	 */
	private static Instant instant(JsonNode node, String name)
	{
		String value = text(node, name);
		try
		{
			return Instant.parse(value);
		}
		catch (DateTimeParseException e)
		{
			throw new IllegalArgumentException(name + ": must be an ISO-8601 instant: " + value, e);
		}
	}

	/**
	 * Returns an instant field that must be present but may be null, written in ISO-8601 form.
	 *
	 * @param node the object that holds the field
	 * @param name the field's name
	 * @return the instant, or null if it is JSON {@code null}
	 * @throws IllegalArgumentException if the field is missing or its value is not an instant
	 */
	private static Instant nullableInstant(JsonNode node, String name)
	{
		if (nullable(node, name) == null)
			return null;
		return instant(node, name);
	}

	/**
	 * Returns a SHA-256 field.
	 *
	 * @param node the object that holds the field
	 * @param name the field's name
	 * @return the hash
	 */
	private static Sha256 sha256(JsonNode node, String name)
	{
		return new Sha256(text(node, name));
	}

	/**
	 * Returns a constant whose name a field holds.
	 *
	 * @param <E> the constant's type
	 * @param node the object that holds the field
	 * @param name the field's name
	 * @param type the constant's type
	 * @return the constant
	 */
	private static <E extends Enum<E>> E constant(JsonNode node, String name, Class<E> type)
	{
		return constantValue(field(node, name), name, type);
	}

	/**
	 * Returns the constant that a string value names.
	 *
	 * @param <E> the constant's type
	 * @param value the value
	 * @param name the value's name
	 * @param type the constant's type
	 * @return the constant
	 * @throws IllegalArgumentException if the value names no constant of {@code type}
	 */
	private static <E extends Enum<E>> E constantValue(JsonNode value, String name, Class<E> type)
	{
		String constantName = stringValue(value, name);
		try
		{
			return EnumNames.parse(constantName, type);
		}
		catch (IllegalArgumentException e)
		{
			throw new IllegalArgumentException(name + ": " + constantName + " is not a " + type.getSimpleName(), e);
		}
	}

	/**
	 * Returns a constant whose name a field holds, or null.
	 *
	 * @param <E> the constant's type
	 * @param node the object that holds the field
	 * @param name the field's name
	 * @param type the constant's type
	 * @return the constant, or null
	 */
	private static <E extends Enum<E>> E nullableConstant(JsonNode node, String name, Class<E> type)
	{
		JsonNode value = nullable(node, name);
		if (value == null)
			return null;
		return constantValue(value, name, type);
	}
}
