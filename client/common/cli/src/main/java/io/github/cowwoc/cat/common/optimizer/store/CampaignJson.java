package io.github.cowwoc.cat.common.optimizer.store;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.Allocation;
import io.github.cowwoc.cat.common.optimizer.campaign.AllocationRule;
import io.github.cowwoc.cat.common.optimizer.campaign.AuthorAttempt;
import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.CampaignEvent;
import io.github.cowwoc.cat.common.optimizer.campaign.CampaignHeader;
import io.github.cowwoc.cat.common.optimizer.campaign.Candidate;
import io.github.cowwoc.cat.common.optimizer.campaign.CategoryCounts;
import io.github.cowwoc.cat.common.optimizer.campaign.CertificationAttempt;
import io.github.cowwoc.cat.common.optimizer.campaign.CertificationProcedure;
import io.github.cowwoc.cat.common.optimizer.campaign.DeliveryRequest;
import io.github.cowwoc.cat.common.optimizer.campaign.Development;
import io.github.cowwoc.cat.common.optimizer.campaign.Diagnosis;
import io.github.cowwoc.cat.common.optimizer.campaign.DiagnosticRerun;
import io.github.cowwoc.cat.common.optimizer.campaign.Failure;
import io.github.cowwoc.cat.common.optimizer.campaign.Harness;
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
import io.github.cowwoc.cat.common.optimizer.campaign.Round;
import io.github.cowwoc.cat.common.optimizer.campaign.RunFailure;
import io.github.cowwoc.cat.common.optimizer.campaign.SequenceGroup;
import io.github.cowwoc.cat.common.optimizer.campaign.SlowStart;
import io.github.cowwoc.cat.common.optimizer.campaign.Throttle;
import io.github.cowwoc.cat.common.optimizer.campaign.TrialKey;
import io.github.cowwoc.cat.common.optimizer.campaign.TrialState;
import io.github.cowwoc.cat.common.optimizer.campaign.Verdict;
import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.contract.DrawStream;
import io.github.cowwoc.cat.common.optimizer.contract.InvalidProposalException;
import io.github.cowwoc.cat.common.optimizer.contract.ProposalSchema;
import io.github.cowwoc.cat.common.optimizer.contract.TrialInstance;
import io.github.cowwoc.cat.common.optimizer.contract.VariationChoice;
import io.github.cowwoc.cat.common.optimizer.inventory.AuthorityWarning;
import io.github.cowwoc.cat.common.optimizer.inventory.Limit;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
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
 * written with sorted keys, except a {@link Round}, whose category order is part of its state. Big numbers and
 * instants are written as strings so that they round-trip exactly.
 * <p>
 * Reading reports a document that does not describe a valid value with an {@link IllegalArgumentException} or a
 * {@link NullPointerException}.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 */
final class CampaignJson
{
	/**
	 * The version of the layout that this class writes and reads.
	 */
	static final int FORMAT_VERSION = 1;
	private static final String VERSION = "format_version";
	private static final Comparator<SequenceGroup> GROUP_ORDER = Comparator.comparing(SequenceGroup::category).
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
		IncumbentBaseline baseline = campaign.baseline();
		if (baseline == null)
			root.putNull("baseline");
		else
		{
			ObjectNode node = root.putObject("baseline");
			node.put("index", baseline.index());
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
			node.put("category", entry.getKey().category());
			node.put("purpose", entry.getKey().purpose().name());
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
		ArrayNode authorizedTargets = root.putArray("authorized_targets");
		for (Path target : new TreeSet<>(campaign.authorizedTargets()))
			authorizedTargets.add(target.toString());
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
			node.put("label", delivery.label().name());
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
				readCounts(field(baselineNode, "counts")));
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
			SequenceGroup group = new SequenceGroup(text(node, "category"), constant(node, "purpose", Purpose.class),
				sha256(node, "snapshot"));
			if (sequences.put(group, longInteger(node, "last_sequence")) != null)
				throw new IllegalArgumentException("sequences: duplicate group " + group);
		}
		JsonNode slowStart = field(root, "slow_start");
		JsonNode throttle = field(root, "throttle");
		Set<Path> authorizedTargets = new HashSet<>();
		for (JsonNode node : elements(root, "authorized_targets"))
		{
			if (!authorizedTargets.add(pathValue(node, "authorized_targets")))
				throw new IllegalArgumentException("authorized_targets: duplicate target " + node.asString());
		}
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
		return new Campaign(readHeader(field(root, "header")), integer(root, "ceiling"), lineages,
			readIdentity(field(root, "identity")), readCandidate(field(root, "incumbent")),
			integer(root, "candidates"), integer(root, "author_attempts"),
			readDevelopment(field(root, "development")), baseline, attempts, trials, sequences,
			longInteger(root, "evidence_revision"), new SlowStart(integer(slowStart, "size"), integer(slowStart, "passes")),
			new Throttle(instant(throttle, "hold_until"), integer(throttle, "window_cap"),
				integer(throttle, "backoffs")),
			authorizedTargets, pending, bool(root, "revision_requested"), nullableConstant(root, "outcome", Outcome.class),
			delivery);
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
	CampaignHeader readSnapshotHeader(JsonNode root)
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
		CampaignEvent.Complete outcome = record.outcome();
		if (outcome == null)
			root.putNull("outcome");
		else
		{
			ObjectNode node = root.putObject("outcome");
			node.put("verdict", outcome.verdict().name());
			node.put("rationale", outcome.rationale());
			node.set("observed", writeIdentity(outcome.observed()));
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
				text(outcomeNode, "rationale"), readIdentity(field(outcomeNode, "observed")));
		}
		return new TrialRecord(key, readInstance(field(root, "instance")), readIdentity(field(root, "identity")),
			readAllocation(field(root, "allocation")), readInfrastructureAttempts(root), outcome);
	}

	/**
	 * Converts a campaign header to a document.
	 *
	 * @param header the header
	 * @return the document
	 */
	private ObjectNode writeHeader(CampaignHeader header)
	{
		ObjectNode node = json.createObjectNode();
		node.put("id", header.id());
		node.put("root", header.root().toString());
		node.put("target", header.target().toString());
		node.put("harness", header.harness().name());
		node.put("lifecycle", header.lifecycle().name());
		return node;
	}

	/**
	 * Converts a document to a campaign header.
	 *
	 * @param node the document
	 * @return the header
	 */
	private static CampaignHeader readHeader(JsonNode node)
	{
		return new CampaignHeader(text(node, "id"), Path.of(text(node, "root")), Path.of(text(node, "target")),
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
				unavailable.add(outcome.name());
		}
		return node;
	}

	/**
	 * Converts a document to a contract lineage.
	 *
	 * @param node the document
	 * @return the lineage
	 */
	private Lineage readLineage(JsonNode node)
	{
		Contract contract;
		try
		{
			contract = schema.parse(json.writeValueAsString(field(node, "contract")));
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
		return new Lineage(integer(node, "number"), contract, sha256(node, "contract_hash"),
			readStream(field(node, "development_stream")), readStream(field(node, "baseline_stream")), regressionSet,
			unavailable);
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
		{
			ArrayNode attempts = node.putArray("round");
			for (Entry<String, Integer> entry : round.attempts().entrySet())
			{
				ObjectNode element = attempts.addObject();
				element.put("category", entry.getKey());
				element.put("attempts", entry.getValue());
			}
		}
		AuthorAttempt author = development.author();
		if (author == null)
			node.putNull("author");
		else
		{
			ObjectNode element = node.putObject("author");
			element.put("number", author.number());
			element.put("category", author.category());
			element.put("snapshot", author.snapshot().hex());
			ArrayNode runs = element.putArray("runs");
			for (PromotionRun run : author.runs())
			{
				ObjectNode runNode = runs.addObject();
				runNode.put("role", run.role().name());
				runNode.set("instance", writeInstance(run.instance()));
				putKey(runNode, "trial", run.trial());
				putConstant(runNode, "verdict", run.verdict());
			}
		}
		ObjectNode reviewPoints = node.putObject("review_points");
		for (Entry<String, Integer> entry : new TreeMap<>(development.reviewPoints()).entrySet())
			reviewPoints.put(entry.getKey(), entry.getValue());
		return node;
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
		{
			Map<String, Integer> attempts = new LinkedHashMap<>();
			for (JsonNode element : elements(node, "round"))
			{
				String category = text(element, "category");
				if (attempts.put(category, integer(element, "attempts")) != null)
					throw new IllegalArgumentException("round: duplicate category " + category);
			}
			round = new Round(attempts);
		}
		AuthorAttempt author = null;
		JsonNode authorNode = nullable(node, "author");
		if (authorNode != null)
		{
			List<PromotionRun> runs = new ArrayList<>();
			for (JsonNode run : elements(authorNode, "runs"))
			{
				runs.add(new PromotionRun(constant(run, "role", PromotionRun.Role.class),
					readInstance(field(run, "instance")), nullableKey(run, "trial"),
					nullableConstant(run, "verdict", Verdict.class)));
			}
			author = new AuthorAttempt(integer(authorNode, "number"), text(authorNode, "category"),
				sha256(authorNode, "snapshot"), runs);
		}
		Map<String, Integer> reviewPoints = new LinkedHashMap<>();
		for (Entry<String, JsonNode> entry : object(node, "review_points").properties())
			reviewPoints.put(entry.getKey(), intValue(entry.getValue(), "review_points." + entry.getKey()));
		return new Development(readCandidate(field(node, "working")), integer(node, "series"),
			readCounts(field(node, "counts")), readFailures(node, "failures"), diagnosis, round, author,
			reviewPoints);
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
		ArrayNode categories = procedureNode.putArray("categories");
		for (String category : procedure.categories())
			categories.add(category);
		procedureNode.set("boundaries", writeBoundaries(procedure.boundaries()));
		procedureNode.put("bets", procedure.bets());
		procedureNode.put("allocation_policy", procedure.allocationPolicy());
		node.set("counts", writeCounts(attempt.counts()));
		node.set("failures", writeFailures(attempt.failures()));
		node.set("formal_stream", writeStream(attempt.formalStream()));
		putConstant(node, "outcome", attempt.outcome());
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
		List<String> categories = new ArrayList<>();
		for (JsonNode category : elements(procedureNode, "categories"))
			categories.add(stringValue(category, "categories"));
		CertificationProcedure procedure = new CertificationProcedure(level(procedureNode, "improvement_level"),
			integer(procedureNode, "baseline_index"), categories, readBoundaries(field(procedureNode, "boundaries")),
			text(procedureNode, "bets"), text(procedureNode, "allocation_policy"));
		return new CertificationAttempt(integer(node, "index"), integer(node, "lineage"),
			readCandidate(field(node, "candidate")), readIdentity(field(node, "identity")), procedure,
			readCounts(field(node, "counts")), readFailures(node, "failures"),
			readStream(field(node, "formal_stream")), nullableConstant(node, "outcome", Outcome.class));
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
		return new DecisionBoundaries(level(node, "level"), integer(node, "cap"),
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
		node.put("state", trial.state().name());
		putConstant(node, "verdict", trial.verdict());
		node.put("rationale", trial.rationale());
		node.put("infrastructure_failures", trial.infrastructureFailures());
		node.set("infrastructure_attempts", writeInfrastructureAttempts(trial.infrastructureAttempts()));
		node.put("observations", trial.observations());
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
			constant(node, "state", TrialState.class), verdict, rationale, integer(node, "infrastructure_failures"),
			readInfrastructureAttempts(node), integer(node, "observations"));
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
		node.put("rule", allocation.rule().name());
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
			node.put("failure", attempt.failure().name());
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
				node.put("type", "MEASUREMENT_CHANGED");
				node.set("observed", writeIdentity(changed.observed()));
				ArrayNode conditions = node.putArray("changed");
				for (MeasurementCondition condition : changed.changed())
					conditions.add(condition.name());
			}
			case PendingDecision.UnownedChange change ->
			{
				node.put("type", "UNOWNED_CHANGE");
				node.put("description", change.description());
			}
			case PendingDecision.TrialFailures failures ->
			{
				node.put("type", "TRIAL_FAILURES");
				node.set("key", writeKey(failures.key()));
			}
			case PendingDecision.CalibrationFailure failure ->
			{
				node.put("type", "CALIBRATION_FAILURE");
				node.put("detail", failure.detail());
			}
			case PendingDecision.CheckpointReached checkpoint ->
			{
				node.put("type", "CHECKPOINT_REACHED");
				node.put("category", checkpoint.category());
				node.put("outcomes", checkpoint.outcomes());
			}
			case PendingDecision.ImprovementProven proven ->
			{
				node.put("type", "IMPROVEMENT_PROVEN");
				node.put("attempt", proven.attempt());
			}
			case PendingDecision.RegressionTradeOff tradeOff ->
			{
				node.put("type", "REGRESSION_TRADE_OFF");
				node.put("attempt", tradeOff.attempt());
			}
			case PendingDecision.PlateauReached _ -> node.put("type", "PLATEAU_REACHED");
			case PendingDecision.CertificationUnavailable unavailable ->
			{
				node.put("type", "CERTIFICATION_UNAVAILABLE");
				node.put("outcome", unavailable.outcome().name());
			}
			case PendingDecision.AuthorityWarnings warnings ->
			{
				node.put("type", "AUTHORITY_WARNINGS");
				ArrayNode array = node.putArray("warnings");
				for (AuthorityWarning warning : warnings.warnings())
				{
					ObjectNode element = array.addObject();
					element.put("requirement", warning.requirement());
					element.put("path", warning.path().toString());
					element.put("target", warning.target().toString());
					element.put("limit", warning.limit().name());
					ArrayNode choices = element.putArray("choices");
					for (AuthorityWarning.Choice choice : warning.choices())
						choices.add(choice.name());
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
			case "MEASUREMENT_CHANGED" ->
			{
				List<MeasurementCondition> changed = new ArrayList<>();
				for (JsonNode element : elements(node, "changed"))
					changed.add(constantValue(element, "changed", MeasurementCondition.class));
				yield new PendingDecision.MeasurementChanged(readIdentity(field(node, "observed")), changed);
			}
			case "UNOWNED_CHANGE" -> new PendingDecision.UnownedChange(text(node, "description"));
			case "TRIAL_FAILURES" -> new PendingDecision.TrialFailures(readKey(field(node, "key")));
			case "CALIBRATION_FAILURE" -> new PendingDecision.CalibrationFailure(text(node, "detail"));
			case "CHECKPOINT_REACHED" ->
				new PendingDecision.CheckpointReached(text(node, "category"), integer(node, "outcomes"));
			case "IMPROVEMENT_PROVEN" -> new PendingDecision.ImprovementProven(integer(node, "attempt"));
			case "REGRESSION_TRADE_OFF" -> new PendingDecision.RegressionTradeOff(integer(node, "attempt"));
			case "PLATEAU_REACHED" -> new PendingDecision.PlateauReached();
			case "CERTIFICATION_UNAVAILABLE" ->
				new PendingDecision.CertificationUnavailable(constant(node, "outcome", Outcome.class));
			case "AUTHORITY_WARNINGS" ->
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
	 * Converts a trial key to a document.
	 *
	 * @param key the key
	 * @return the document
	 */
	private ObjectNode writeKey(TrialKey key)
	{
		ObjectNode node = json.createObjectNode();
		node.put("purpose", key.purpose().name());
		node.put("series", key.series());
		node.put("snapshot", key.snapshot().hex());
		node.put("category", key.category());
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
			sha256(node, "snapshot"), text(node, "category"), longInteger(node, "sequence"));
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
		node.put("stream_kind", instance.streamKind().name());
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
		return new TrialInstance(text(node, "category_id"), text(node, "test_id"), choices, turns,
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
	 * Converts failures to a document. Each failure is its instance's document with the judge's rationale and the
	 * diagnostic reruns added.
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
			ArrayNode reruns = element.putArray("reruns");
			for (DiagnosticRerun rerun : failure.reruns())
			{
				ObjectNode rerunNode = reruns.addObject();
				rerunNode.put("verdict", rerun.verdict().name());
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
			failures.add(new Failure(readInstance(element), text(element, "rationale"), reruns));
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
		node.put("kind", state.kind().name());
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
	 * Converts measurement conditions to a document, in condition order.
	 *
	 * @param identity the conditions
	 * @return the document
	 */
	private ObjectNode writeIdentity(MeasurementIdentity identity)
	{
		ObjectNode node = json.createObjectNode();
		for (MeasurementCondition condition : MeasurementCondition.values())
		{
			String value = identity.values().get(condition);
			if (value != null)
				node.put(condition.name(), value);
		}
		return node;
	}

	/**
	 * Converts a document to measurement conditions.
	 *
	 * @param node the document
	 * @return the conditions
	 */
	private static MeasurementIdentity readIdentity(JsonNode node)
	{
		requireObject(node, "identity");
		Map<MeasurementCondition, String> values = new EnumMap<>(MeasurementCondition.class);
		for (Entry<String, JsonNode> entry : node.properties())
		{
			values.put(MeasurementCondition.valueOf(entry.getKey()),
				stringValue(entry.getValue(), "identity." + entry.getKey()));
		}
		return new MeasurementIdentity(values);
	}

	/**
	 * Converts per-category counts to a document, in category order.
	 *
	 * @param counts the counts
	 * @return the document
	 */
	private ObjectNode writeCounts(Map<String, CategoryCounts> counts)
	{
		ObjectNode node = json.createObjectNode();
		for (Entry<String, CategoryCounts> entry : new TreeMap<>(counts).entrySet())
		{
			ObjectNode element = node.putObject(entry.getKey());
			element.put("passes", entry.getValue().passes());
			element.put("failures", entry.getValue().failures());
			element.set("batch_failures", writeIntegers(entry.getValue().batchFailures()));
		}
		return node;
	}

	/**
	 * Converts a document to per-category counts.
	 *
	 * @param node the document
	 * @return the counts
	 */
	private static Map<String, CategoryCounts> readCounts(JsonNode node)
	{
		requireObject(node, "counts");
		Map<String, CategoryCounts> counts = new LinkedHashMap<>();
		for (Entry<String, JsonNode> entry : node.properties())
		{
			JsonNode element = entry.getValue();
			counts.put(entry.getKey(), new CategoryCounts(integer(element, "passes"), integer(element, "failures"),
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
			node.put(name, value.name());
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
			throw new IllegalArgumentException(VERSION + " is " + version + ", but this version of CAT reads " +
				FORMAT_VERSION);
		}
	}

	/**
	 * Checks that a value is an object.
	 *
	 * @param node the value
	 * @param name the value's name
	 * @throws IllegalArgumentException if the value is not an object
	 */
	private static void requireObject(JsonNode node, String name)
	{
		if (node == null || !node.isObject())
			throw new IllegalArgumentException(name + ": must be an object");
	}

	/**
	 * Returns a field that may not be missing or null.
	 *
	 * @param node the object that holds the field
	 * @param name the field's name
	 * @return the value
	 * @throws IllegalArgumentException if the field is missing or null
	 */
	private static JsonNode field(JsonNode node, String name)
	{
		JsonNode value = nullable(node, name);
		if (value == null)
			throw new IllegalArgumentException(name + ": must have a value");
		return value;
	}

	/**
	 * Returns a field that must be present but may be null.
	 *
	 * @param node the object that holds the field
	 * @param name the field's name
	 * @return the value, or null if it is JSON {@code null}
	 * @throws IllegalArgumentException if the field is missing
	 */
	private static JsonNode nullable(JsonNode node, String name)
	{
		requireObject(node, name + "'s parent");
		JsonNode value = node.get(name);
		if (value == null)
			throw new IllegalArgumentException(name + ": is missing");
		if (value.isNull())
			return null;
		return value;
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
	 * Returns the elements of an array field.
	 *
	 * @param node the object that holds the field
	 * @param name the field's name
	 * @return the elements
	 * @throws IllegalArgumentException if the field is not an array
	 */
	private static List<JsonNode> elements(JsonNode node, String name)
	{
		JsonNode value = field(node, name);
		if (!value.isArray())
			throw new IllegalArgumentException(name + ": must be an array");
		return value.valueStream().toList();
	}

	/**
	 * Returns a string field.
	 *
	 * @param node the object that holds the field
	 * @param name the field's name
	 * @return the string
	 */
	private static String text(JsonNode node, String name)
	{
		return stringValue(field(node, name), name);
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
	 * Returns a string value.
	 *
	 * @param value the value
	 * @param name the value's name
	 * @return the string
	 * @throws IllegalArgumentException if the value is not a string
	 */
	private static String stringValue(JsonNode value, String name)
	{
		if (value == null || !value.isString())
			throw new IllegalArgumentException(name + ": must be a string");
		return value.asString();
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
	 * Returns an {@code int} field.
	 *
	 * @param node the object that holds the field
	 * @param name the field's name
	 * @return the value
	 */
	private static int integer(JsonNode node, String name)
	{
		return intValue(field(node, name), name);
	}

	/**
	 * Returns an {@code int} value.
	 *
	 * @param value the value
	 * @param name the value's name
	 * @return the value
	 * @throws IllegalArgumentException if the value is not an {@code int}
	 */
	private static int intValue(JsonNode value, String name)
	{
		if (value == null || !value.isIntegralNumber() || !value.canConvertToInt())
			throw new IllegalArgumentException(name + ": must be a 32-bit integer");
		return value.asInt();
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
		JsonNode value = field(node, name);
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
	private static BigDecimal decimal(JsonNode node, String name)
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
			return Enum.valueOf(type, constantName);
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
