package io.github.cowwoc.cat.common.optimizer.store;

import static io.github.cowwoc.cat.common.optimizer.store.JsonFields.VERSION;
import static io.github.cowwoc.cat.common.optimizer.store.JsonFields.elements;
import static io.github.cowwoc.cat.common.optimizer.store.JsonFields.field;
import static io.github.cowwoc.cat.common.optimizer.store.JsonFields.integer;
import static io.github.cowwoc.cat.common.optimizer.store.JsonFields.requireObject;
import static io.github.cowwoc.cat.common.optimizer.store.JsonFields.requireVersion;
import static io.github.cowwoc.cat.common.optimizer.store.JsonFields.stringValue;
import static io.github.cowwoc.cat.common.optimizer.store.JsonFields.text;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.CertificationParameters;
import io.github.cowwoc.cat.common.optimizer.campaign.CertificationRecord;
import io.github.cowwoc.cat.common.optimizer.campaign.CertificationRecord.CellResult;
import io.github.cowwoc.cat.common.optimizer.campaign.PairCounts;
import io.github.cowwoc.cat.common.optimizer.contract.CertificationScope;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;
import io.github.cowwoc.cat.common.optimizer.statistics.SignificanceLevel;

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
 * Converts a {@link CertificationRecord} to and from the JSON file that a certified delivery writes to the project.
 * <p>
 * The file holds the target, the certification index, the scope's identifier, the minimum pass rate and the
 * significance level {@code alpha} as decimal strings, each cell's counts with the pairs of each assertion's control
 * comparison, each prompt file's content hash and the
 * measurement conditions.
 * <p>
 * {@code alpha} is {@code 1 / threshold} of the level. A reciprocal that has no finite decimal form is rounded up to
 * {@value #ALPHA_DIGITS} significant digits, so the written value never understates the level's error probability.
 * Reading takes the smallest threshold whose written form is {@code alpha} and refuses an {@code alpha} that no
 * threshold writes.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 */
public final class CertificationRecordJson
{
	/**
	 * The format version that this version of CAT writes and reads.
	 */
	private static final int FORMAT_VERSION = 4;
	private static final String MALFORMED = "is malformed";
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
	public CertificationRecordJson(JsonMapper json)
	{
		requireThat(json, "json").isNotNull();
		this.json = json;
	}

	/**
	 * Converts a record to the content of its file.
	 *
	 * @param record the record
	 * @return the file's bytes
	 * @throws NullPointerException if {@code record} is null
	 */
	public byte[] write(CertificationRecord record)
	{
		requireThat(record, "record").isNotNull();
		ObjectNode root = json.createObjectNode();
		root.put(VERSION, FORMAT_VERSION);
		root.put("target", record.target().toString());
		root.put("certification_index", record.certificationIndex());
		root.put("scope", record.scope().id());
		CertificationParameters parameters = record.parameters();
		root.put("minimum_pass_rate", parameters.minimumPassRate().toPlainString());
		root.put("alpha", alpha(parameters.level()));

		ArrayNode cells = root.putArray("cells");
		for (CellResult cell : record.cells())
		{
			ObjectNode element = cells.addObject();
			element.put("test", cell.test());
			element.put("cell", cell.cell());
			element.put("passes", cell.passes());
			element.put("failures", cell.failures());
			ArrayNode comparisons = element.putArray("comparisons");
			for (Entry<String, PairCounts> comparison : cell.comparisons().entrySet())
			{
				PairCounts pairs = comparison.getValue();
				comparisons.addObject().put("assertion", comparison.getKey()).
					put("both_passed", pairs.bothPassed()).put("candidate_only", pairs.candidateOnly()).
					put("control_only", pairs.controlOnly()).put("neither_passed", pairs.neitherPassed());
			}
		}

		ObjectNode promptFiles = root.putObject("prompt_files");
		for (Entry<Path, Sha256> entry : record.promptFiles().entrySet())
			promptFiles.put(entry.getKey().toString(), entry.getValue().hex());
		root.set("identity", new CampaignJson(json).writeIdentity(record.identity()));
		return json.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
	}

	/**
	 * Reads a record file.
	 *
	 * @param file the file
	 * @return the record
	 * @throws NullPointerException if {@code file} is null
	 * @throws IOException if the file cannot be read, is not valid JSON, or does not describe a valid record; the
	 *   message names the file
	 */
	public CertificationRecord read(Path file) throws IOException
	{
		requireThat(file, "file").isNotNull();
		return JsonFiles.read(json, file, CertificationRecordJson::readRecord, MALFORMED);
	}

	/**
	 * Converts a record file's document to a record.
	 *
	 * @param root the document
	 * @return the record
	 * @throws IllegalArgumentException if the document does not describe a valid record
	 */
	private static CertificationRecord readRecord(JsonNode root)
	{
		requireVersion(root, FORMAT_VERSION);
		String scopeId = text(root, "scope");
		CertificationScope scope = CertificationScope.fromId(scopeId).orElseThrow(() ->
			new IllegalArgumentException("scope: " + scopeId + " is not a certification scope"));
		CertificationParameters parameters = new CertificationParameters(
			CampaignJson.decimal(root, "minimum_pass_rate"), level(CampaignJson.decimal(root, "alpha")));

		List<CellResult> cells = new ArrayList<>();
		for (JsonNode element : elements(root, "cells"))
		{
			Map<String, PairCounts> comparisons = new LinkedHashMap<>();
			for (JsonNode comparison : elements(element, "comparisons"))
			{
				String assertionId = text(comparison, "assertion");
				PairCounts pairs = new PairCounts(integer(comparison, "both_passed"),
					integer(comparison, "candidate_only"), integer(comparison, "control_only"),
					integer(comparison, "neither_passed"));
				if (comparisons.put(assertionId, pairs) != null)
					throw new IllegalArgumentException("comparisons: duplicate assertion " + assertionId);
			}
			cells.add(new CellResult(text(element, "test"), text(element, "cell"), integer(element, "passes"),
				integer(element, "failures"), comparisons));
		}

		JsonNode promptFilesNode = field(root, "prompt_files");
		requireObject(promptFilesNode, "prompt_files");
		Map<Path, Sha256> promptFiles = new TreeMap<>();
		for (Entry<String, JsonNode> entry : promptFilesNode.properties())
		{
			promptFiles.put(Path.of(entry.getKey()),
				new Sha256(stringValue(entry.getValue(), "prompt_files." + entry.getKey())));
		}
		return new CertificationRecord(Path.of(text(root, "target")), integer(root, "certification_index"), scope,
			parameters, cells, promptFiles, CampaignJson.readIdentity(field(root, "identity")));
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
