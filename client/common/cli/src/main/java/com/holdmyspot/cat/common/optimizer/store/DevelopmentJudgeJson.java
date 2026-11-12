package com.holdmyspot.cat.common.optimizer.store;

import static com.holdmyspot.cat.common.optimizer.store.JsonFields.bool;
import static com.holdmyspot.cat.common.optimizer.store.JsonFields.elements;
import static com.holdmyspot.cat.common.optimizer.store.JsonFields.field;
import static com.holdmyspot.cat.common.optimizer.store.JsonFields.integer;
import static com.holdmyspot.cat.common.optimizer.store.JsonFields.requireObject;
import static com.holdmyspot.cat.common.optimizer.store.JsonFields.text;

import com.holdmyspot.cat.common.optimizer.campaign.DevelopmentJudge;
import com.holdmyspot.cat.common.optimizer.campaign.RejectedJudge;
import com.holdmyspot.cat.common.optimizer.campaign.ShadowResult;
import com.holdmyspot.cat.common.optimizer.contract.TrialProfile;
import com.holdmyspot.cat.common.optimizer.report.EnumNames;

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
 * Converts the development judge state of a campaign and the judge profile and shadow comparison of a trial outcome
 * to and from JSON documents.
 * <p>
 * A snapshot has a {@code development_judges} object only when a prompt test has examined a candidate, and an outcome
 * or a trial has a {@code judge} and a {@code shadow} object only when a model judged it; a document without them
 * reads as no candidate examined and no judge recorded, so campaigns that started before CAT chose development judges
 * still load.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
final class DevelopmentJudgeJson
{
	/**
	 * Prevents instantiation.
	 */
	private DevelopmentJudgeJson()
	{
	}

	/**
	 * Converts each prompt test's development judge state to a document.
	 *
	 * @param json   creates the document's nodes
	 * @param judges the state by test identifier
	 * @return the document
	 */
	static ObjectNode write(JsonMapper json, Map<String, DevelopmentJudge> judges)
	{
		ObjectNode root = json.createObjectNode();
		for (Entry<String, DevelopmentJudge> entry : new TreeMap<>(judges).entrySet())
		{
			DevelopmentJudge judge = entry.getValue();
			ObjectNode node = root.putObject(entry.getKey());
			node.put("stage", EnumNames.lowerCase(judge.stage()));
			ObjectNode labels = node.putObject("labels");
			for (Entry<String, Boolean> label : new TreeMap<>(judge.labels()).entrySet())
				labels.put(label.getKey(), label.getValue());
			ArrayNode candidates = node.putArray("candidates");
			for (TrialProfile candidate : judge.candidates())
				writeProfile(candidates.addObject(), candidate);
			ArrayNode rejected = node.putArray("rejected");
			for (RejectedJudge turnedDown : judge.rejected())
			{
				ObjectNode element = rejected.addObject();
				writeProfile(element.putObject("profile"), turnedDown.profile());
				element.put("reason", EnumNames.lowerCase(turnedDown.reason()));
				element.put("shadowed", turnedDown.shadowed());
				element.put("spot_checked", turnedDown.spotChecked());
				element.put("lenient", turnedDown.lenient());
				element.put("strict", turnedDown.strict());
			}
			node.put("shadowed", judge.shadowed());
			node.put("spot_checked", judge.spotChecked());
			node.put("lenient", judge.lenient());
			node.put("strict", judge.strict());
		}
		return root;
	}

	/**
	 * Reads each prompt test's development judge state.
	 *
	 * @param root the snapshot's document
	 * @return the state by test identifier, empty if the document records none
	 * @throws IllegalArgumentException if the document does not describe a valid state
	 */
	static Map<String, DevelopmentJudge> read(JsonNode root)
	{
		Map<String, DevelopmentJudge> judges = new LinkedHashMap<>();
		if (!root.has("development_judges"))
			return judges;
		JsonNode node = field(root, "development_judges");
		requireObject(node, "development_judges");
		for (Entry<String, JsonNode> entry : node.properties())
		{
			JsonNode element = entry.getValue();
			Map<String, Boolean> labels = new LinkedHashMap<>();
			JsonNode labelsNode = field(element, "labels");
			requireObject(labelsNode, "labels");
			for (Entry<String, JsonNode> label : labelsNode.properties())
				labels.put(label.getKey(), bool(labelsNode, label.getKey()));
			List<TrialProfile> candidates = new ArrayList<>();
			for (JsonNode candidate : elements(element, "candidates"))
				candidates.add(readProfile(candidate));
			List<RejectedJudge> rejected = new ArrayList<>();
			for (JsonNode turnedDown : elements(element, "rejected"))
			{
				rejected.add(new RejectedJudge(readProfile(field(turnedDown, "profile")),
					EnumNames.parse(text(turnedDown, "reason"), RejectedJudge.Rejection.class),
					integer(turnedDown, "shadowed"), integer(turnedDown, "spot_checked"), integer(turnedDown, "lenient"),
					integer(turnedDown, "strict")));
			}
			judges.put(entry.getKey(), new DevelopmentJudge(labels, candidates,
				EnumNames.parse(text(element, "stage"), DevelopmentJudge.Stage.class), rejected,
				integer(element, "shadowed"), integer(element, "spot_checked"), integer(element, "lenient"),
				integer(element, "strict")));
		}
		return judges;
	}

	/**
	 * Writes a trial profile into an object.
	 *
	 * @param node    the object to write to
	 * @param profile the profile
	 */
	static void writeProfile(ObjectNode node, TrialProfile profile)
	{
		node.put("model", profile.model());
		node.put("effort", profile.effort());
		node.put("tool_output_token_limit", profile.toolOutputTokenLimit());
	}

	/**
	 * Reads a trial profile.
	 *
	 * @param node the object that holds the profile
	 * @return the profile
	 * @throws IllegalArgumentException if the object does not describe a valid profile
	 */
	static TrialProfile readProfile(JsonNode node)
	{
		TrialProfile profile = TrialProfile.stored(text(node, "model"), text(node, "effort"));
		int tokens = integer(node, "tool_output_token_limit");
		if (tokens == 0)
			return profile;
		return profile.withToolOutputTokenLimit(tokens);
	}

	/**
	 * Writes the shadow comparison of an outcome into an object.
	 *
	 * @param node   the object to write to
	 * @param shadow the comparison
	 */
	static void writeShadow(ObjectNode node, ShadowResult shadow)
	{
		writeProfile(node.putObject("candidate"), shadow.candidate());
		node.put("spot_check", shadow.spotCheck());
		node.put("lenient", shadow.lenient());
		node.put("strict", shadow.strict());
	}

	/**
	 * Reads the shadow comparison of an outcome.
	 *
	 * @param node the object that holds the comparison
	 * @return the comparison
	 * @throws IllegalArgumentException if the object does not describe a valid comparison
	 */
	static ShadowResult readShadow(JsonNode node)
	{
		return new ShadowResult(readProfile(field(node, "candidate")), bool(node, "spot_check"),
			bool(node, "lenient"), bool(node, "strict"));
	}
}
