package com.holdmyspot.cat.common.optimizer.store;

import static com.holdmyspot.cat.common.optimizer.store.JsonFields.VERSION;
import static com.holdmyspot.cat.common.optimizer.store.JsonFields.bool;
import static com.holdmyspot.cat.common.optimizer.store.JsonFields.elements;
import static com.holdmyspot.cat.common.optimizer.store.JsonFields.field;
import static com.holdmyspot.cat.common.optimizer.store.JsonFields.integer;
import static com.holdmyspot.cat.common.optimizer.store.JsonFields.requireObject;
import static com.holdmyspot.cat.common.optimizer.store.JsonFields.requireVersion;
import static com.holdmyspot.cat.common.optimizer.store.JsonFields.stringValue;
import static com.holdmyspot.cat.common.optimizer.store.JsonFields.text;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.PortablePaths;
import com.holdmyspot.cat.common.optimizer.campaign.CertificationHistory;
import com.holdmyspot.cat.common.optimizer.campaign.CertificationParameters;
import com.holdmyspot.cat.common.optimizer.campaign.CertificationRecord;
import com.holdmyspot.cat.common.optimizer.campaign.CertificationRecord.CellResult;
import com.holdmyspot.cat.common.optimizer.campaign.DeliveryEntry;
import com.holdmyspot.cat.common.optimizer.campaign.DeliveryLabel;
import com.holdmyspot.cat.common.optimizer.campaign.HistoryEntry;
import com.holdmyspot.cat.common.optimizer.campaign.PairCounts;
import com.holdmyspot.cat.common.optimizer.contract.CertificationScope;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;
import com.holdmyspot.cat.common.optimizer.statistics.PairedComparison;
import com.holdmyspot.cat.common.optimizer.statistics.SignificanceLevel;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Converts a {@link CertificationHistory} to and from the JSON file that every delivery appends to in the
 * project.
 * <p>
 * The file holds the format version and the {@code records}, one entry per delivery, oldest first. Each entry holds
 * its {@code label}: {@code certified} for a certification record, or the label of a {@link DeliveryEntry}. An entry
 * without a label is a certification record that an earlier version of CAT wrote.
 * <p>
 * A delivery entry holds the target, the label, the delivered attempt's certification index or 0, the cells proven
 * better than the incumbent, the incumbent's snapshot hash, each prompt file's content hash and the measurement
 * conditions. Reading ignores the fields that it does not know.
 * <p>
 * A certification record holds the target, the certification index, the scope's identifier, the minimum pass rate
 * and the significance level {@code alpha} as decimal strings, whether the control and incumbent comparisons follow
 * from the minimum pass rate, each cell's counts with the pairs of each assertion's control and incumbent
 * comparisons, each prompt file's content hash and the measurement conditions. A cell whose comparisons follow from
 * the minimum pass rate has no pairs.
 * <p>
 * {@code alpha} is {@code 1 / threshold} of the level. A reciprocal that has no finite decimal form is rounded up to
 * {@value #ALPHA_DIGITS} significant digits, so the written value never understates the level's error probability.
 * Reading takes the smallest threshold whose written form is {@code alpha} and refuses an {@code alpha} that no
 * threshold writes.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class CertificationHistoryJson
{
	/**
	 * The format version that this version of CAT writes and reads.
	 */
	private static final int FORMAT_VERSION = 1;
	private static final String MALFORMED = "is malformed";
	private static final String RECORDS = "records";
	private static final String LABEL = "label";
	/**
	 * The significant digits of an {@code alpha} that has no finite decimal form.
	 */
	private static final int ALPHA_DIGITS = 10;
	/**
	 * The precision of the reciprocal that reading {@code alpha} computes, which exceeds the digits of any threshold
	 * that a campaign uses.
	 */
	private static final MathContext RECIPROCAL = new MathContext(34, RoundingMode.CEILING);
	private final JsonMapper json;

	/**
	 * Creates a converter.
	 *
	 * @param json the JSON mapper
	 * @throws NullPointerException if {@code json} is null
	 */
	public CertificationHistoryJson(JsonMapper json)
	{
		requireThat(json, "json").isNotNull();
		this.json = json;
	}

	/**
	 * Converts a history to the content of its file.
	 *
	 * @param history the history
	 * @return the file's bytes
	 * @throws NullPointerException if {@code history} is null
	 */
	public byte[] write(CertificationHistory history)
	{
		requireThat(history, "history").isNotNull();
		ObjectNode root = json.createObjectNode();
		root.put(VERSION, FORMAT_VERSION);
		ArrayNode records = root.putArray(RECORDS);
		for (HistoryEntry entry : history.entries())
		{
			switch (entry)
			{
				case CertificationRecord record -> writeRecord(records.addObject(), record);
				case DeliveryEntry delivery -> writeDeliveryEntry(records.addObject(), delivery);
			}
		}
		return JsonFiles.write(json, root);
	}

	/**
	 * Reads a history file.
	 *
	 * @param file the file
	 * @return the history
	 * @throws NullPointerException if {@code file} is null
	 * @throws IOException if the file cannot be read, is not valid JSON, or does not describe a valid history; the
	 *   message names the file and the JSON path of the first problem
	 */
	public CertificationHistory read(Path file) throws IOException
	{
		requireThat(file, "file").isNotNull();
		return JsonFiles.read(json, file, CertificationHistoryJson::readHistory, MALFORMED);
	}

	/**
	 * Writes a record.
	 *
	 * @param root the object that receives the record's fields
	 * @param record the record
	 */
	private void writeRecord(ObjectNode root, CertificationRecord record)
	{
		root.put("target", PortablePaths.text(record.target()));
		root.put(LABEL, record.label().label());
		root.put("certification_index", record.certificationIndex());
		root.put("scope", record.scope().id());
		CertificationParameters parameters = record.parameters();
		root.put("minimum_pass_rate", parameters.minimumPassRate().toPlainString());
		root.put("comparisons_implied", PairedComparison.isImpliedBy(parameters.minimumPassRate()));
		root.put("alpha", alpha(parameters.level()));

		ArrayNode cells = root.putArray("cells");
		for (CellResult cell : record.cells())
		{
			ObjectNode element = cells.addObject();
			element.put("test", cell.test());
			element.put("cell", cell.cell());
			element.put("passes", cell.passes());
			element.put("failures", cell.failures());
			writeComparisons(element.putArray("control_comparisons"), cell.controlComparisons(), "control_only");
			writeComparisons(element.putArray("incumbent_comparisons"), cell.incumbentComparisons(),
				"incumbent_only");
		}

		writePromptFiles(root, record.promptFiles());
		root.set("identity", new CampaignJson(json).writeIdentity(record.identity()));
	}

	/**
	 * Writes a delivery entry.
	 *
	 * @param root the object that receives the entry's fields
	 * @param entry the entry
	 */
	private void writeDeliveryEntry(ObjectNode root, DeliveryEntry entry)
	{
		root.put("target", PortablePaths.text(entry.target()));
		root.put(LABEL, entry.label().label());
		root.put("attempt", entry.attempt());
		ArrayNode provenBetter = root.putArray("proven_better");
		for (String cell : entry.provenBetter())
			provenBetter.add(cell);
		root.put("incumbent", entry.incumbent().hex());
		writePromptFiles(root, entry.promptFiles());
		root.set("identity", new CampaignJson(json).writeIdentity(entry.identity()));
	}

	/**
	 * Writes the content hash of each prompt file.
	 *
	 * @param root the object that receives the {@code prompt_files} field
	 * @param promptFiles the hashes, keyed by the file's path relative to the project
	 */
	private static void writePromptFiles(ObjectNode root, Map<Path, Sha256> promptFiles)
	{
		ObjectNode node = root.putObject("prompt_files");
		for (Entry<Path, Sha256> entry : promptFiles.entrySet())
			node.put(PortablePaths.text(entry.getKey()), entry.getValue().hex());
	}

	/**
	 * Reads the content hash of each prompt file.
	 *
	 * @param root the entry's node
	 * @return the hashes, keyed by the file's path relative to the project
	 * @throws IllegalArgumentException if the field is missing or malformed
	 */
	private static Map<Path, Sha256> readPromptFiles(JsonNode root)
	{
		JsonNode promptFilesNode = field(root, "prompt_files");
		requireObject(promptFilesNode, "prompt_files");
		Map<Path, Sha256> promptFiles = new TreeMap<>();
		for (Entry<String, JsonNode> entry : promptFilesNode.properties())
		{
			promptFiles.put(Path.of(entry.getKey()),
				new Sha256(stringValue(entry.getValue(), "prompt_files." + entry.getKey())));
		}
		return promptFiles;
	}

	/**
	 * Writes the pairs of each assertion's comparison with one kind of reference trial.
	 *
	 * @param nodes the array to write to
	 * @param comparisons the pairs, keyed by the assertion's identifier
	 * @param referenceOnly the name of the field that counts the pairs that only the reference trial passed
	 */
	private static void writeComparisons(ArrayNode nodes, Map<String, PairCounts> comparisons, String referenceOnly)
	{
		for (Entry<String, PairCounts> comparison : comparisons.entrySet())
		{
			PairCounts pairs = comparison.getValue();
			nodes.addObject().put("assertion", comparison.getKey()).
				put("both_passed", pairs.bothPassed()).put("candidate_only", pairs.candidateOnly()).
				put(referenceOnly, pairs.referenceOnly()).put("neither_passed", pairs.neitherPassed());
		}
	}

	/**
	 * Reads the pairs of each assertion's comparison with one kind of reference trial.
	 *
	 * @param element the cell's node
	 * @param name the name of the array
	 * @param referenceOnly the name of the field that counts the pairs that only the reference trial passed
	 * @return the pairs, keyed by the assertion's identifier in file order
	 * @throws IllegalArgumentException if the array is missing or malformed, or names an assertion twice
	 */
	private static Map<String, PairCounts> readComparisons(JsonNode element, String name, String referenceOnly)
	{
		Map<String, PairCounts> comparisons = new LinkedHashMap<>();
		for (JsonNode comparison : elements(element, name))
		{
			String assertionId = text(comparison, "assertion");
			PairCounts pairs = new PairCounts(integer(comparison, "both_passed"),
				integer(comparison, "candidate_only"), integer(comparison, referenceOnly),
				integer(comparison, "neither_passed"));
			if (comparisons.put(assertionId, pairs) != null)
				throw new IllegalArgumentException(name + ": duplicate assertion " + assertionId);
		}
		return comparisons;
	}

	/**
	 * Converts a history file's document to a history.
	 *
	 * @param root the document
	 * @return the history
	 * @throws IllegalArgumentException if the document does not describe a valid history; the message starts with the
	 *   JSON path of the first problem
	 */
	private static CertificationHistory readHistory(JsonNode root)
	{
		requireVersion(root, FORMAT_VERSION);
		List<HistoryEntry> records = new ArrayList<>();
		List<JsonNode> elements = elements(root, RECORDS);
		for (int i = 0; i < elements.size(); ++i)
		{
			String path = RECORDS + "[" + i + "]";
			requireObject(elements.get(i), path);
			try
			{
				records.add(readEntry(elements.get(i)));
			}
			catch (IllegalArgumentException | NullPointerException e)
			{
				throw new IllegalArgumentException(path + "." + e.getMessage(), e);
			}
		}
		return new CertificationHistory(records);
	}

	/**
	 * Converts an entry's document to an entry.
	 *
	 * @param root the document
	 * @return the entry
	 * @throws IllegalArgumentException if the document does not describe a valid entry
	 */
	private static HistoryEntry readEntry(JsonNode root)
	{
		// Earlier versions of CAT wrote certification records alone, without a label
		if (!root.has(LABEL))
			return readRecord(root);
		String text = text(root, LABEL);
		DeliveryLabel label = DeliveryLabel.fromLabel(text).orElseThrow(() ->
			new IllegalArgumentException(LABEL + ": " + text + " is not a delivery label"));
		if (label == DeliveryLabel.CERTIFIED)
			return readRecord(root);
		List<String> provenBetter = new ArrayList<>();
		List<JsonNode> cells = elements(root, "proven_better");
		for (int i = 0; i < cells.size(); ++i)
			provenBetter.add(stringValue(cells.get(i), "proven_better[" + i + "]"));
		return new DeliveryEntry(Path.of(text(root, "target")), label, integer(root, "attempt"), provenBetter,
			new Sha256(text(root, "incumbent")), readPromptFiles(root), CampaignJson.readIdentity(field(root, "identity")));
	}

	/**
	 * Converts a record's document to a record.
	 *
	 * @param root the document
	 * @return the record
	 * @throws IllegalArgumentException if the document does not describe a valid record
	 */
	private static CertificationRecord readRecord(JsonNode root)
	{
		String scopeId = text(root, "scope");
		CertificationScope scope = CertificationScope.fromId(scopeId).orElseThrow(() ->
			new IllegalArgumentException("scope: " + scopeId + " is not a certification scope"));
		CertificationParameters parameters = new CertificationParameters(
			CampaignJson.decimal(root, "minimum_pass_rate"), level(CampaignJson.decimal(root, "alpha")));
		boolean comparisonsImplied = bool(root, "comparisons_implied");
		if (comparisonsImplied != PairedComparison.isImpliedBy(parameters.minimumPassRate()))
		{
			throw new IllegalArgumentException("comparisons_implied: " + comparisonsImplied +
				" contradicts minimum_pass_rate " + parameters.minimumPassRate().toPlainString());
		}

		List<CellResult> cells = new ArrayList<>();
		for (JsonNode element : elements(root, "cells"))
		{
			cells.add(new CellResult(text(element, "test"), text(element, "cell"), integer(element, "passes"),
				integer(element, "failures"), readComparisons(element, "control_comparisons", "control_only"),
				readComparisons(element, "incumbent_comparisons", "incumbent_only")));
		}

		return new CertificationRecord(Path.of(text(root, "target")), integer(root, "certification_index"), scope,
			parameters, cells, readPromptFiles(root), CampaignJson.readIdentity(field(root, "identity")));
	}

	/**
	 * Returns a level's error probability as a decimal string.
	 *
	 * @param level the level
	 * @return {@code 1 / threshold}, rounded up to {@value #ALPHA_DIGITS} significant digits if it has no finite
	 *   decimal form
	 */
	private static String alpha(SignificanceLevel level)
	{
		BigDecimal threshold = new BigDecimal(level.threshold());
		BigDecimal alpha;
		try
		{
			alpha = BigDecimal.ONE.divide(threshold);
		}
		catch (ArithmeticException _)
		{
			// The reciprocal has no finite decimal form
			alpha = BigDecimal.ONE.divide(threshold, new MathContext(ALPHA_DIGITS, RoundingMode.CEILING));
		}
		return alpha.stripTrailingZeros().toPlainString();
	}

	/**
	 * Returns the level whose error probability is written as {@code alpha}.
	 *
	 * @param alpha the error probability that the record holds
	 * @return the level
	 * @throws IllegalArgumentException if no threshold writes {@code alpha}
	 */
	private static SignificanceLevel level(BigDecimal alpha)
	{
		if (alpha.signum() <= 0 || alpha.compareTo(BigDecimal.ONE) >= 0)
			throw new IllegalArgumentException("alpha: must lie strictly between 0 and 1: " + alpha.toPlainString());
		// Rounding up the written value lowers the reciprocal, so the threshold is the reciprocal rounded up
		BigInteger threshold = BigDecimal.ONE.divide(alpha, RECIPROCAL).setScale(0, RoundingMode.CEILING).
			toBigIntegerExact();
		if (threshold.compareTo(BigInteger.ONE) > 0)
		{
			SignificanceLevel level = new SignificanceLevel(threshold);
			if (new BigDecimal(alpha(level)).compareTo(alpha) == 0)
				return level;
		}
		throw new IllegalArgumentException("alpha: must be the reciprocal of an integer threshold, as CAT writes it: " +
			alpha.toPlainString());
	}
}
