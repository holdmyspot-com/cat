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
import io.github.cowwoc.cat.common.optimizer.contract.CertificationScope;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
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
 * The file holds the target, the certification index, the scope's identifier, the required pass rate and level as
 * decimal strings, each cell's counts, each prompt file's content hash and the measurement conditions.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 */
public final class CertificationRecordJson
{
	/**
	 * The format version that this version of CAT writes and reads.
	 */
	private static final int FORMAT_VERSION = 1;
	private static final String MALFORMED = "is malformed";
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
		root.put("required_pass_rate", parameters.requiredPassRate().toPlainString());
		root.put("level", parameters.level().threshold().toString());

		ArrayNode cells = root.putArray("cells");
		for (CellResult cell : record.cells())
		{
			ObjectNode element = cells.addObject();
			element.put("category", cell.category());
			element.put("cell", cell.cell());
			element.put("passes", cell.passes());
			element.put("failures", cell.failures());
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
			CampaignJson.decimal(root, "required_pass_rate"), CampaignJson.level(root, "level"));

		List<CellResult> cells = new ArrayList<>();
		for (JsonNode element : elements(root, "cells"))
		{
			cells.add(new CellResult(text(element, "category"), text(element, "cell"), integer(element, "passes"),
				integer(element, "failures")));
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
}
