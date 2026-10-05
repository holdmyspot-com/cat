package io.github.cowwoc.cat.common.optimizer.store;

import io.github.cowwoc.cat.common.optimizer.execution.AuthorConflict;
import io.github.cowwoc.cat.common.optimizer.report.EnumNames;
import static io.github.cowwoc.cat.common.optimizer.store.JsonFields.VERSION;
import static io.github.cowwoc.cat.common.optimizer.store.JsonFields.elements;
import static io.github.cowwoc.cat.common.optimizer.store.JsonFields.field;
import static io.github.cowwoc.cat.common.optimizer.store.JsonFields.intValue;
import static io.github.cowwoc.cat.common.optimizer.store.JsonFields.integer;
import static io.github.cowwoc.cat.common.optimizer.store.JsonFields.nullable;
import static io.github.cowwoc.cat.common.optimizer.store.JsonFields.requireObject;
import static io.github.cowwoc.cat.common.optimizer.store.JsonFields.stringValue;
import static io.github.cowwoc.cat.common.optimizer.store.JsonFields.text;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.Allocation;
import io.github.cowwoc.cat.common.optimizer.campaign.AllocationRule;
import io.github.cowwoc.cat.common.optimizer.campaign.AuthorAttempt;
import io.github.cowwoc.cat.common.optimizer.campaign.AttemptResult;
import io.github.cowwoc.cat.common.optimizer.campaign.AuthorDiagnosis;
import io.github.cowwoc.cat.common.optimizer.campaign.FailureKind;
import io.github.cowwoc.cat.common.optimizer.campaign.OptimizationEvidence;
import io.github.cowwoc.cat.common.optimizer.campaign.PromptSize;
import io.github.cowwoc.cat.common.optimizer.campaign.TrialInputs;
import io.github.cowwoc.cat.common.optimizer.campaign.Minimality;
import io.github.cowwoc.cat.common.optimizer.inventory.FileDelta;
import io.github.cowwoc.cat.common.optimizer.inventory.FileContent;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.CampaignEvent;
import io.github.cowwoc.cat.common.optimizer.campaign.Header;
import io.github.cowwoc.cat.common.optimizer.campaign.Candidate;
import io.github.cowwoc.cat.common.optimizer.campaign.CellCounts;
import io.github.cowwoc.cat.common.optimizer.campaign.CertificationAttempt;
import io.github.cowwoc.cat.common.optimizer.campaign.CertificationProcedure;
import io.github.cowwoc.cat.common.optimizer.campaign.DeliveryRequest;
import io.github.cowwoc.cat.common.optimizer.campaign.Development;
import io.github.cowwoc.cat.common.optimizer.campaign.InstanceOutcomes;
import io.github.cowwoc.cat.common.optimizer.campaign.Predecessor;
import io.github.cowwoc.cat.common.optimizer.campaign.Revert;
import io.github.cowwoc.cat.common.optimizer.campaign.Diagnosis;
import io.github.cowwoc.cat.common.optimizer.campaign.DiagnosticRerun;
import io.github.cowwoc.cat.common.optimizer.campaign.FailedRequirement;
import io.github.cowwoc.cat.common.optimizer.campaign.FailureGroup;
import io.github.cowwoc.cat.common.optimizer.campaign.Failure;
import io.github.cowwoc.cat.common.optimizer.campaign.Harness;
import io.github.cowwoc.cat.common.optimizer.campaign.Impact;
import io.github.cowwoc.cat.common.optimizer.campaign.IncumbentBaseline;
import io.github.cowwoc.cat.common.optimizer.campaign.InfrastructureAttempt;
import io.github.cowwoc.cat.common.optimizer.campaign.Lifecycle;
import io.github.cowwoc.cat.common.optimizer.campaign.Lineage;
import io.github.cowwoc.cat.common.optimizer.campaign.LogicalTrial;
import io.github.cowwoc.cat.common.optimizer.campaign.MeasurementCondition;
import io.github.cowwoc.cat.common.optimizer.campaign.MeasurementIdentity;
import io.github.cowwoc.cat.common.optimizer.campaign.Outcome;
import io.github.cowwoc.cat.common.optimizer.campaign.PendingDecision;
import io.github.cowwoc.cat.common.optimizer.campaign.PromotionRun;
import io.github.cowwoc.cat.common.optimizer.campaign.Purpose;
import io.github.cowwoc.cat.common.optimizer.campaign.RegressionInstance;
import io.github.cowwoc.cat.common.optimizer.campaign.ReproductionRound;
import io.github.cowwoc.cat.common.optimizer.campaign.Round;
import io.github.cowwoc.cat.common.optimizer.campaign.RunFailure;
import io.github.cowwoc.cat.common.optimizer.campaign.SequenceGroup;
import io.github.cowwoc.cat.common.optimizer.campaign.SlowStart;
import io.github.cowwoc.cat.common.optimizer.campaign.Throttle;
import io.github.cowwoc.cat.common.optimizer.campaign.TrialKey;
import io.github.cowwoc.cat.common.optimizer.campaign.TrialState;
import io.github.cowwoc.cat.common.optimizer.campaign.Triage;
import io.github.cowwoc.cat.common.optimizer.campaign.TriedCombination;
import io.github.cowwoc.cat.common.optimizer.campaign.Verdict;
import io.github.cowwoc.cat.common.optimizer.contract.AddedCombination;
import io.github.cowwoc.cat.common.optimizer.contract.AddedValues;
import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.contract.DrawStream;
import io.github.cowwoc.cat.common.optimizer.contract.InvalidProposalException;
import io.github.cowwoc.cat.common.optimizer.contract.ProposalSchema;
import io.github.cowwoc.cat.common.optimizer.contract.TrialInstance;
import io.github.cowwoc.cat.common.optimizer.contract.VariationChoice;
import io.github.cowwoc.cat.common.optimizer.contract.VariationValue;
import io.github.cowwoc.cat.common.optimizer.inventory.AuthorityWarning;
import io.github.cowwoc.cat.common.optimizer.inventory.Limit;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;
import io.github.cowwoc.cat.common.optimizer.port.SessionRole;
import io.github.cowwoc.cat.common.optimizer.port.TokenUsage;
import io.github.cowwoc.cat.common.optimizer.statistics.Comparison;
import io.github.cowwoc.cat.common.optimizer.statistics.ComparisonBoundaries;
import io.github.cowwoc.cat.common.optimizer.statistics.DecisionBoundaries;
import io.github.cowwoc.cat.common.optimizer.statistics.SignificanceLevel;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;

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
 * round-trip exactly. Only the current format is readable: a document of another format version is refused with a
 * message that asks the user to start a new campaign.
 * <p>
 * Reading reports a document that does not describe a valid value with an {@link IllegalArgumentException} or a
 * {@link NullPointerException}.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 */
final class CampaignJson
{
	/**
	 * The version of the layout that this class writes.
	 */
	private static final int FORMAT_VERSION = 19;
	private static final String TOKEN_USAGE = "token_usage";
	private static final String FAILED_REQUIREMENTS = "failed_requirements";
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
		root.put("author_attempts", campaign.authorAttempts());
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
		ObjectNode slowStart = root.putObject("slow_start");
		slowStart.put("size", campaign.slowStart().size());
		slowStart.put("passes", campaign.slowStart().passes());
		ObjectNode throttle = root.putObject("throttle");
		throttle.put("hold_until", campaign.throttle().holdUntil().toString());
		throttle.put("window_cap", campaign.throttle().windowCap());
		throttle.put("backoffs", campaign.throttle().backoffs());
		ArrayNode pending = root.putArray("pending_decisions");
		for (PendingDecision decision : campaign.pendingDecisions())
			pending.add(writeDecision(decision));
		root.put("revision_requested", campaign.revisionRequested());
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
		JsonNode slowStart = field(root, "slow_start");
		JsonNode throttle = field(root, "throttle");
		List<PendingDecision> pending = new ArrayList<>();
		for (JsonNode node : elements(root, "pending_decisions"))
			pending.add(readDecision(node));
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
		return new Campaign(readHeader(field(root, "header")), ceiling, lineages,
			readIdentity(field(root, "identity")), readCandidate(field(root, "incumbent")),
			integer(root, "candidates"), integer(root, "author_attempts"),
			readDevelopment(field(root, "development")), baseline, attempts, trials, sequences,
			longInteger(root, "evidence_revision"), new SlowStart(integer(slowStart, "size"), integer(slowStart, "passes")),
			new Throttle(instant(throttle, "hold_until"), integer(throttle, "window_cap"),
				integer(throttle, "backoffs")),
			pending, bool(root, "revision_requested"), nullableConstant(root, "outcome", Outcome.class),
			delivery, readOptimization(field(root, "optimization")));
	}

	/**
	 * Writes retained author diagnoses and removed hunks.
	 *
	 * @param evidence the durable descriptive evidence
	 * @return its document
	 */
	private ObjectNode writeOptimization(OptimizationEvidence evidence)
	{
		ObjectNode node = json.createObjectNode();
		ObjectNode usage = node.putObject(TOKEN_USAGE);
		for (SessionRole role : SessionRole.values())
		{
			List<TokenUsage> sessions = evidence.tokenUsage().get(role);
			if (sessions != null)
				usage.set(EnumNames.lowerCase(role), writeUsage(sessions));
		}

		ArrayNode authors = node.putArray("authors");
		for (AuthorDiagnosis diagnosis : evidence.authors())
		{
			ObjectNode author = authors.addObject();
			author.put("cell", diagnosis.cell());
			author.put("snapshot", diagnosis.snapshot().hex());
			author.put("transcript", diagnosis.transcript().toString());
			author.put("effects", diagnosis.effects().toString());
			ObjectNode classifications = author.putObject("classifications");
			for (Entry<String, FailureKind> entry : new TreeMap<>(diagnosis.classifications()).entrySet())
				classifications.put(entry.getKey(), EnumNames.lowerCase(entry.getValue()));
			ArrayNode rejections = author.putArray("rejections");
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
	 * Reads retained author diagnoses and removed hunks.
	 *
	 * @param node the document
	 * @return the durable evidence
	 */
	private static OptimizationEvidence readOptimization(JsonNode node)
	{
		List<AuthorDiagnosis> authors = new ArrayList<>();
		for (JsonNode author : elements(node, "authors"))
		{
			Map<String, FailureKind> classifications = new HashMap<>();
			for (Entry<String, JsonNode> entry : object(author, "classifications").properties())
				classifications.put(entry.getKey(), constantValue(entry.getValue(), "classification", FailureKind.class));
			authors.add(new AuthorDiagnosis(text(author, "cell"), sha256(author, "snapshot"), classifications,
				readStrings(author, "rejections"), Path.of(text(author, "transcript")), Path.of(text(author, "effects"))));
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
		return new OptimizationEvidence(authors, readStrings(node, "removed"), sizes, inputs, results,
			readRoleUsage(node));
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
	 * Writes the requirements that a trial failed, each with its behavior.
	 *
	 * @param node the containing object
	 * @param failures the failed requirements
	 */
	private static void putFailedRequirements(ObjectNode node, List<FailedRequirement> failures)
	{
		ArrayNode array = node.putArray(FAILED_REQUIREMENTS);
		for (FailedRequirement failure : failures)
			array.addObject().put("requirement_id", failure.requirementId()).put("behavior", failure.behavior());
	}

	/**
	 * Reads the requirements that a trial failed, each with its behavior.
	 *
	 * @param node the containing object
	 * @return the failed requirements
	 */
	private static List<FailedRequirement> readFailedRequirements(JsonNode node)
	{
		List<FailedRequirement> failures = new ArrayList<>();
		for (JsonNode element : elements(node, FAILED_REQUIREMENTS))
			failures.add(new FailedRequirement(text(element, "requirement_id"), text(element, "behavior")));
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
		root.set("instance", writeInstance(record.instance()));
		root.set("identity", writeIdentity(record.identity()));
		root.set("allocation", writeAllocation(record.allocation()));
		root.set("infrastructure_attempts", writeInfrastructureAttempts(record.infrastructureAttempts()));
		root.set(TOKEN_USAGE, writeUsage(record.tokenUsage()));
		CampaignEvent.Complete outcome = record.outcome();
		if (outcome == null)
			root.putNull("outcome");
		else
		{
			ObjectNode node = root.putObject("outcome");
			node.put("verdict", EnumNames.lowerCase(outcome.verdict()));
			node.put("rationale", outcome.rationale());
			putFailedRequirements(node, outcome.failedRequirements());
			node.set("triage", writeTriage(outcome.triage()));
			node.set("observed", writeIdentity(outcome.observed()));
			node.put("input_tokens", outcome.inputTokens());
			node.set("responses", writeResponses(outcome.responses()));
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
				text(outcomeNode, "rationale"), readFailedRequirements(outcomeNode), readTriage(outcomeNode),
				readIdentity(field(outcomeNode, "observed")), nullableLong(outcomeNode, "input_tokens"),
				readResponses(outcomeNode));
		}
		return new TrialRecord(key, readInstance(field(root, "instance")), readIdentity(field(root, "identity")),
			readAllocation(field(root, "allocation")), readInfrastructureAttempts(root), outcome,
			readUsage(root, TOKEN_USAGE));
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
		{
			ObjectNode node = array.addObject();
			node.put("input", usage.input());
			node.put("cached_input", usage.cachedInput());
			node.put("output", usage.output());
			node.put("reasoning_output", usage.reasoningOutput());
		}
		return array;
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
		{
			sessions.add(new TokenUsage(longInteger(usage, "input"), longInteger(usage, "cached_input"),
				longInteger(usage, "output"), longInteger(usage, "reasoning_output")));
		}
		return sessions;
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
		node.put("target", header.target().toString());
		node.put("harness", EnumNames.lowerCase(header.harness()));
		node.put("lifecycle", EnumNames.lowerCase(header.lifecycle()));
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
			constant(node, "harness", Harness.class), constant(node, "lifecycle", Lifecycle.class));
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
		for (RegressionInstance regression : lineage.regressionSet())
		{
			ObjectNode element = regressionSet.addObject();
			element.set("instance", writeInstance(regression.instance()));
			putConstant(element, "working_verdict", regression.working());
		}
		ArrayNode unavailable = node.putArray("unavailable");
		for (Outcome outcome : Outcome.values())
		{
			if (lineage.unavailable().contains(outcome))
				unavailable.add(EnumNames.lowerCase(outcome));
		}
		ArrayNode added = node.putArray("added_values");
		for (AddedCombination combination : lineage.added().combinations())
			added.add(writeCombination(combination));
		return node;
	}

	/**
	 * Converts a key-value combination beyond the confirmed values to a document.
	 *
	 * @param combination the combination
	 * @return the document
	 */
	private ObjectNode writeCombination(AddedCombination combination)
	{
		ObjectNode element = json.createObjectNode();
		element.put("attempt", combination.attempt());
		element.put("test_id", combination.testId());
		ArrayNode values = element.putArray("values");
		for (VariationValue value : combination.values())
		{
			values.addObject().
				put("label", value.label()).
				put("text", value.text()).
				put("probability", value.probability());
		}
		return element;
	}

	/**
	 * Converts a document to a key-value combination beyond the confirmed values.
	 *
	 * @param element the document
	 * @return the combination
	 */
	private static AddedCombination readCombination(JsonNode element)
	{
		List<VariationValue> values = new ArrayList<>();
		for (JsonNode value : elements(element, "values"))
			values.add(new VariationValue(text(value, "label"), text(value, "text"), floating(value, "probability")));
		return new AddedCombination(integer(element, "attempt"), text(element, "test_id"), values);
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
			node.put("requirement_id", group.failed().requirementId());
			node.put("behavior", group.failed().behavior());
			node.put("formal_failures", group.formalFailures());
			node.put("round_limit", group.roundLimit());
			ArrayNode rounds = node.putArray("rounds");
			for (ReproductionRound round : group.rounds())
			{
				ObjectNode roundNode = rounds.addObject();
				roundNode.put("cell", round.cellId());
				ArrayNode combinations = roundNode.putArray("combinations");
				for (TriedCombination tried : round.combinations())
				{
					ObjectNode triedNode = combinations.addObject();
					triedNode.set("combination", writeCombination(tried.combination()));
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
				List<TriedCombination> combinations = new ArrayList<>();
				for (JsonNode tried : elements(roundNode, "combinations"))
				{
					combinations.add(new TriedCombination(readCombination(field(tried, "combination")),
						integer(tried, "trials"), integer(tried, "failures")));
				}
				rounds.add(new ReproductionRound(text(roundNode, "cell"), combinations));
			}
			groups.add(new FailureGroup(text(element, "cell"), text(element, "test_id"),
				new FailedRequirement(text(element, "requirement_id"), text(element, "behavior")),
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
		List<RegressionInstance> regressionSet = new ArrayList<>();
		for (JsonNode element : elements(node, "regression_set"))
		{
			regressionSet.add(new RegressionInstance(readInstance(field(element, "instance")),
				nullableConstant(element, "working_verdict", Verdict.class)));
		}
		Set<Outcome> unavailable = EnumSet.noneOf(Outcome.class);
		for (JsonNode element : elements(node, "unavailable"))
			unavailable.add(constantValue(element, "unavailable", Outcome.class));
		List<AddedCombination> added = new ArrayList<>();
		for (JsonNode element : elements(node, "added_values"))
			added.add(readCombination(element));
		return new Lineage(integer(node, "number"), contract, sha256(node, "contract_hash"),
			readStream(field(node, "development_stream")), readStream(field(node, "baseline_stream")), regressionSet,
			unavailable, new AddedValues(added));
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
			element.set("pending", writeInstances(diagnosis.pending()));
			element.put("passed_reruns", diagnosis.passedReruns());
			putKey(element, "rerun", diagnosis.rerun());
			element.put("reproduced", diagnosis.reproduced());
		}
		Round round = development.round();
		if (round == null)
			node.putNull("round");
		else
			node.set("round", writeRound(round));
		AuthorAttempt author = development.author();
		if (author == null)
			node.putNull("author");
		else
			node.set("author", writeAuthorAttempt(author));
		ObjectNode reviewPoints = node.putObject("review_points");
		for (Entry<String, Integer> entry : new TreeMap<>(development.reviewPoints()).entrySet())
			reviewPoints.put(entry.getKey(), entry.getValue());
		ArrayNode outcomes = node.putArray("instance_outcomes");
		for (InstanceOutcomes outcome : development.instanceOutcomes())
		{
			ObjectNode element = outcomes.addObject();
			element.set("instance", writeInstance(outcome.instance()));
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
			for (RegressionInstance regression : predecessor.regressionSet())
			{
				ObjectNode instance = regressions.addObject();
				instance.set("instance", writeInstance(regression.instance()));
				putConstant(instance, "working_verdict", regression.working());
			}
		}
		ArrayNode reverts = node.putArray("reverts");
		for (Revert revert : development.reverts())
		{
			ObjectNode element = reverts.addObject();
			element.set("withdrawn", writeCandidate(revert.withdrawn()));
			element.put("cell", revert.cell());
			element.put("upper_bound", revert.upperBound());
			element.put("predecessor_rate", revert.predecessorRate());
			element.set("development", writeDevelopment(revert.development()));
		}
		return node;
	}

	/**
	 * Writes the frozen author policy and all reserved or validated attempts of a round.
	 *
	 * @param round the round
	 * @return its document
	 */
	private ObjectNode writeRound(Round round)
	{
		ObjectNode node = json.createObjectNode();
		node.put("weak", round.weak());
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
		for (AuthorAttempt attempt : round.validationAttempts())
			contenders.add(writeAuthorAttempt(attempt));
		return node;
	}

	/**
	 * Writes one author attempt and all promotion or reduction progress.
	 *
	 * @param attempt the attempt
	 * @return its document
	 */
	private ObjectNode writeAuthorAttempt(AuthorAttempt attempt)
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
			node.set("instance", writeInstance(run.instance()));
			putKey(node, "trial", run.trial());
			putConstant(node, "verdict", run.verdict());
		}
		return array;
	}

	/**
	 * Reads an author attempt without reconstructing its evidence.
	 *
	 * @param node its document
	 * @return the attempt
	 */
	private static AuthorAttempt readAuthorAttempt(JsonNode node)
	{
		return new AuthorAttempt(integer(node, "number"), text(node, "cell"), sha256(node, "snapshot"),
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
				readInstance(field(run, "instance")), nullableKey(run, "trial"),
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
			element.put("path", file.path().toString());
			element.put("permissions", PosixFilePermissions.toString(file.permissions()));
			putContent(element, "before", file.before());
			putContent(element, "after", file.after());
		}
		ArrayNode runs = node.putArray("accepted_runs");
		for (PromotionRun run : state.acceptedRuns())
		{
			ObjectNode element = runs.addObject();
			element.put("role", EnumNames.lowerCase(run.role()));
			element.set("instance", writeInstance(run.instance()));
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
				readInstance(field(run, "instance")), nullableKey(run, "trial"),
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
			diagnosis = new Diagnosis(readInstances(diagnosisNode, "pending"), integer(diagnosisNode, "passed_reruns"),
				nullableKey(diagnosisNode, "rerun"), bool(diagnosisNode, "reproduced"));
		}
		Round round = null;
		JsonNode roundNode = nullable(node, "round");
		if (roundNode != null)
			round = readRound(roundNode);
		AuthorAttempt author = null;
		JsonNode authorNode = nullable(node, "author");
		if (authorNode != null)
			author = readAuthorAttempt(authorNode);
		Map<String, Integer> reviewPoints = new LinkedHashMap<>();
		for (Entry<String, JsonNode> entry : object(node, "review_points").properties())
			reviewPoints.put(entry.getKey(), intValue(entry.getValue(), "review_points." + entry.getKey()));
		List<Failure> failures = readFailures(node, "failures");
		List<InstanceOutcomes> outcomes = new ArrayList<>();
		for (JsonNode outcome : elements(node, "instance_outcomes"))
		{
			outcomes.add(new InstanceOutcomes(readInstance(field(outcome, "instance")), integer(outcome, "passes"),
				integer(outcome, "outcomes")));
		}
		Predecessor predecessor = null;
		JsonNode predecessorNode = nullable(node, "predecessor");
		if (predecessorNode != null)
		{
			List<RegressionInstance> regressions = new ArrayList<>();
			for (JsonNode regression : elements(predecessorNode, "regression_set"))
			{
				regressions.add(new RegressionInstance(readInstance(field(regression, "instance")),
					nullableConstant(regression, "working_verdict", Verdict.class)));
			}
			predecessor = new Predecessor(readDevelopment(field(predecessorNode, "development")),
				readRound(field(predecessorNode, "round")), text(predecessorNode, "cell"), regressions);
		}
		List<Revert> reverts = new ArrayList<>();
		for (JsonNode revert : elements(node, "reverts"))
		{
			reverts.add(new Revert(readCandidate(field(revert, "withdrawn")), text(revert, "cell"),
				floating(revert, "upper_bound"), floating(revert, "predecessor_rate"),
				readDevelopment(field(revert, "development"))));
		}
		return new Development(readCandidate(field(node, "working")), integer(node, "series"),
			readCounts(field(node, "counts")), failures, diagnosis, round, author,
			reviewPoints, outcomes, predecessor, reverts);
	}

	/**
	 * Reads a round's frozen author policy and durable attempt reservations.
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
		List<AuthorAttempt> contenders = new ArrayList<>();
		for (JsonNode contender : elements(node, "contenders"))
			contenders.add(readAuthorAttempt(contender));
		return new Round(attempts, bool(node, "weak"), reservations, contenders);
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
		node.set("counts", writeCounts(attempt.counts()));
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
			readCounts(field(node, "counts")), readFailures(node, "failures"),
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
		node.put("required_pass_rate", boundaries.requiredPassRate().toPlainString());
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
		List<ComparisonBoundaries> comparisons = new ArrayList<>();
		for (JsonNode element : elements(node, "comparisons"))
		{
			Comparison comparison = new Comparison(decimal(element, "incumbent_upper"),
				decimal(element, "incumbent_lower"), level(element, "level"));
			comparisons.add(new ComparisonBoundaries(comparison, readIntegers(element, "minimum_proven_better_passes"),
				readIntegers(element, "maximum_proven_worse_passes")));
		}
		return new DecisionBoundaries(level(node, "level"), decimal(node, "required_pass_rate"), integer(node, "cap"),
			readIntegers(node, "minimum_certifying_passes"), readIntegers(node, "maximum_clearly_below_passes"),
			comparisons);
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
		node.set("instance", writeInstance(trial.instance()));
		node.set("identity", writeIdentity(trial.identity()));
		node.set("allocation", writeAllocation(trial.allocation()));
		node.put("state", EnumNames.lowerCase(trial.state()));
		putConstant(node, "verdict", trial.verdict());
		node.put("rationale", trial.rationale());
		putFailedRequirements(node, trial.failedRequirements());
		node.set("triage", writeTriage(trial.triage()));
		node.put("infrastructure_failures", trial.infrastructureFailures());
		node.set("infrastructure_attempts", writeInfrastructureAttempts(trial.infrastructureAttempts()));
		node.put("observations", trial.observations());
		node.set("responses", writeResponses(trial.responses()));
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
		return new LogicalTrial(readKey(field(node, "key")), readInstance(field(node, "instance")),
			readIdentity(field(node, "identity")), readAllocation(field(node, "allocation")),
			constant(node, "state", TrialState.class), verdict, rationale, readFailedRequirements(node),
			readTriage(node),
			integer(node, "infrastructure_failures"), readInfrastructureAttempts(node), integer(node, "observations"),
			readResponses(node));
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
			case PendingDecision.PlateauReached _ -> node.put("type", "plateau_reached");
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
				{
					ObjectNode element = array.addObject();
					element.put("requirement", warning.requirement());
					element.put("path", warning.path().toString());
					element.put("target", warning.target().toString());
					element.put("limit", EnumNames.lowerCase(warning.limit()));
					ArrayNode choices = element.putArray("choices");
					for (AuthorityWarning.Choice choice : warning.choices())
						choices.add(EnumNames.lowerCase(choice));
				}
			}
		}
		return node;
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
					changed.add(constantValue(element, "changed", MeasurementCondition.class));
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
			case "plateau_reached" -> new PendingDecision.PlateauReached();
			case "certification_unavailable" ->
				new PendingDecision.CertificationUnavailable(constant(node, "outcome", Outcome.class));
			case "authority_warnings" ->
			{
				List<AuthorityWarning> warnings = new ArrayList<>();
				for (JsonNode element : elements(node, "warnings"))
				{
					List<AuthorityWarning.Choice> choices = new ArrayList<>();
					for (JsonNode choice : elements(element, "choices"))
						choices.add(constantValue(choice, "choices", AuthorityWarning.Choice.class));
					warnings.add(new AuthorityWarning(text(element, "requirement"), pathValue(field(element, "path"),
						"path"), pathValue(field(element, "target"), "target"), constant(element, "limit", Limit.class),
						choices));
				}
				yield new PendingDecision.AuthorityWarnings(warnings);
			}
			default -> throw new IllegalArgumentException("pending_decisions: unknown type " + type);
		};
	}

	/**
	 * Writes ordered author conflicts for a pending decision.
	 *
	 * @param conflicts the conflicts
	 * @return their JSON array
	 */
	private ArrayNode writeConflicts(List<AuthorConflict> conflicts)
	{
		ArrayNode array = json.createArrayNode();
		for (AuthorConflict conflict : conflicts)
		{
			ObjectNode node = array.addObject();
			ArrayNode instances = node.putArray("instances");
			for (String instance : conflict.instances())
				instances.add(instance);
			ArrayNode requirements = node.putArray("requirement_ids");
			for (String requirement : conflict.requirementIds())
				requirements.add(requirement);
			ArrayNode quotations = node.putArray("quotations");
			for (AuthorConflict.Quotation quotation : conflict.quotations())
			{
				ObjectNode entry = quotations.addObject();
				entry.put("path", quotation.path().toString());
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
	 * Reads ordered author conflicts from a pending-decision document.
	 *
	 * @param node the pending decision
	 * @return its conflicts
	 */
	private static List<AuthorConflict> readConflicts(JsonNode node)
	{
		List<AuthorConflict> conflicts = new ArrayList<>();
		for (JsonNode element : elements(node, "conflicts"))
		{
			List<AuthorConflict.Quotation> quotations = new ArrayList<>();
			for (JsonNode quotation : elements(element, "quotations"))
			{
				quotations.add(new AuthorConflict.Quotation(pathValue(field(quotation, "path"), "path"),
					text(quotation, "text")));
			}
			conflicts.add(new AuthorConflict(readStrings(element, "instances"), readStrings(element, "requirement_ids"),
				quotations, readStrings(element, "resolutions"), integer(element, "recommended"), text(element, "reason")));
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
	 * Converts a trial instance to a document.
	 *
	 * @param instance the instance
	 * @return the document
	 */
	private ObjectNode writeInstance(TrialInstance instance)
	{
		ObjectNode node = json.createObjectNode();
		node.put("cell_id", instance.cellId());
		node.put("category_id", instance.categoryId());
		node.put("test_id", instance.testId());
		ArrayNode choices = node.putArray("choices");
		for (VariationChoice choice : instance.choices())
		{
			ObjectNode element = choices.addObject();
			element.put("dimension", choice.dimension());
			element.put("label", choice.label());
			element.put("probability", choice.probability());
		}
		ArrayNode turns = node.putArray("turns");
		for (String turn : instance.turns())
			turns.add(turn);
		node.put("expected_behaviour", instance.expectedBehaviour());
		node.put("generator", instance.generator());
		node.put("stream_kind", EnumNames.lowerCase(instance.streamKind()));
		node.put("stream_position", instance.streamPosition());
		node.put("targeted", instance.targeted());
		return node;
	}

	/**
	 * Converts a document to a trial instance.
	 *
	 * @param node the document
	 * @return the instance
	 */
	private static TrialInstance readInstance(JsonNode node)
	{
		List<VariationChoice> choices = new ArrayList<>();
		for (JsonNode element : elements(node, "choices"))
		{
			choices.add(new VariationChoice(text(element, "dimension"), text(element, "label"),
				floating(element, "probability")));
		}
		List<String> turns = new ArrayList<>();
		for (JsonNode element : elements(node, "turns"))
			turns.add(stringValue(element, "turns"));
		return new TrialInstance(text(node, "cell_id"), text(node, "category_id"), text(node, "test_id"), choices,
			turns,
			text(node, "expected_behaviour"), text(node, "generator"),
			constant(node, "stream_kind", DrawStream.Kind.class), longInteger(node, "stream_position"),
			bool(node, "targeted"));
	}

	/**
	 * Converts trial instances to a document.
	 *
	 * @param instances the instances
	 * @return the document
	 */
	private ArrayNode writeInstances(List<TrialInstance> instances)
	{
		ArrayNode node = json.createArrayNode();
		for (TrialInstance instance : instances)
			node.add(writeInstance(instance));
		return node;
	}

	/**
	 * Reads a list of trial instances.
	 *
	 * @param node the object that holds the field
	 * @param name the field's name
	 * @return the instances
	 */
	private static List<TrialInstance> readInstances(JsonNode node, String name)
	{
		List<TrialInstance> instances = new ArrayList<>();
		for (JsonNode element : elements(node, name))
			instances.add(readInstance(element));
		return instances;
	}

	/**
	 * Converts failures to a document. Each failure is its instance's document with the judge's rationale, the
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
			ObjectNode element = writeInstance(failure.instance());
			element.put("rationale", failure.rationale());
			putFailedRequirements(element, failure.failedRequirements());
			element.set("triage", writeTriage(failure.triage()));
			element.set("responses", writeResponses(failure.responses()));
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
			failures.add(new Failure(readInstance(element), text(element, "rationale"),
				readFailedRequirements(element), readTriage(element), reruns, readResponses(element)));
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
		Map<String, Map<MeasurementCondition, String>> cells = new HashMap<>();
		for (Entry<String, JsonNode> cell : object(node, "cells").properties())
			cells.put(cell.getKey(), readConditions(cell.getValue(), "identity.cells." + cell.getKey()));
		return new MeasurementIdentity(readConditions(field(node, "conditions"), "identity.conditions"), cells);
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
			values.put(EnumNames.parse(entry.getKey(), MeasurementCondition.class),
				stringValue(entry.getValue(), name + "." + entry.getKey()));
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
		if (version != FORMAT_VERSION)
		{
			throw new IllegalArgumentException(VERSION + " is " + version + ": this is a version " + version +
				" campaign file, but this version of CAT reads only version " + FORMAT_VERSION + ". Start a new " +
				"campaign.");
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
	 * Returns a {@code boolean} field.
	 *
	 * @param node the object that holds the field
	 * @param name the field's name
	 * @return the value
	 * @throws IllegalArgumentException if the value is not a boolean
	 */
	private static boolean bool(JsonNode node, String name)
	{
		JsonNode value = field(node, name);
		if (!value.isBoolean())
			throw new IllegalArgumentException(name + ": must be a boolean");
		return value.asBoolean();
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
