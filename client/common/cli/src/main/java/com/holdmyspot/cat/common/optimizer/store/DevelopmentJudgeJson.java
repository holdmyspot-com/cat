package com.holdmyspot.cat.common.optimizer.store;

import static com.holdmyspot.cat.common.optimizer.store.JsonFields.bool;
import static com.holdmyspot.cat.common.optimizer.store.JsonFields.elements;
import static com.holdmyspot.cat.common.optimizer.store.JsonFields.field;
import static com.holdmyspot.cat.common.optimizer.store.JsonFields.integer;
import static com.holdmyspot.cat.common.optimizer.store.JsonFields.requireObject;
import static com.holdmyspot.cat.common.optimizer.store.JsonFields.text;

import com.holdmyspot.cat.common.optimizer.campaign.DevelopmentJudge;
import com.holdmyspot.cat.common.optimizer.campaign.ExaminedJudge;
import com.holdmyspot.cat.common.optimizer.campaign.JudgeBasis;
import com.holdmyspot.cat.common.optimizer.campaign.RejectedJudge;
import com.holdmyspot.cat.common.optimizer.campaign.ShadowResult;
import com.holdmyspot.cat.common.optimizer.contract.TrialProfile;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;
import com.holdmyspot.cat.common.optimizer.report.EnumNames;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
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
 * or a trial has a {@code judge} object and a {@code shadows} array only when a model judged it; a document without
 * them reads as no candidate examined and no judge recorded, so campaigns that started before CAT chose development
 * judges still load.
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
			writeLabels(node.putObject("labels"), judge.labels());
			ArrayNode waiting = node.putArray("waiting");
			for (TrialProfile candidate : judge.waiting())
				writeProfile(waiting.addObject(), candidate);
			ArrayNode examined = node.putArray("examined");
			for (ExaminedJudge candidate : judge.examined())
			{
				ObjectNode element = examined.addObject();
				writeProfile(element.putObject("profile"), candidate.profile());
				element.put("stage", EnumNames.lowerCase(candidate.stage()));
				element.put("shadowed", candidate.shadowed());
				element.put("held_out", candidate.heldOut());
				element.put("spot_checked", candidate.spotChecked());
				element.put("lenient", candidate.lenient());
				element.put("strict", candidate.strict());
			}
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
			if (judge.basis().isPresent())
				writeBasis(node.putObject("basis"), judge.basis().get());
			node.put("development_trials", judge.developmentTrials());
			node.put("rounds", judge.rounds());
		}
		return root;
	}

	/**
	 * Reads each prompt test's development judge state.
	 * <p>
	 * A state without an {@code examined} array was written before a test could examine several candidates at once:
	 * its first {@code candidates} element is the candidate in examination, at the state's stage and counts, and the
	 * rest are waiting.
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
			Map<String, Boolean> labels = readLabels(field(element, "labels"));
			List<RejectedJudge> rejected = new ArrayList<>();
			for (JsonNode turnedDown : elements(element, "rejected"))
			{
				rejected.add(new RejectedJudge(readProfile(field(turnedDown, "profile")),
					EnumNames.parse(text(turnedDown, "reason"), RejectedJudge.Rejection.class),
					integer(turnedDown, "shadowed"), integer(turnedDown, "spot_checked"), integer(turnedDown, "lenient"),
					integer(turnedDown, "strict")));
			}
			List<TrialProfile> waiting = new ArrayList<>();
			List<ExaminedJudge> examined = new ArrayList<>();
			if (element.has("examined"))
			{
				for (JsonNode candidate : elements(element, "waiting"))
					waiting.add(readProfile(candidate));
				for (JsonNode candidate : elements(element, "examined"))
				{
					examined.add(new ExaminedJudge(readProfile(field(candidate, "profile")),
						EnumNames.parse(text(candidate, "stage"), DevelopmentJudge.Stage.class),
						integer(candidate, "shadowed"), integer(candidate, "held_out"), integer(candidate, "spot_checked"),
						integer(candidate, "lenient"), integer(candidate, "strict")));
				}
			}
			else
			{
				DevelopmentJudge.Stage stage = EnumNames.parse(text(element, "stage"), DevelopmentJudge.Stage.class);
				List<TrialProfile> candidates = new ArrayList<>();
				for (JsonNode candidate : elements(element, "candidates"))
					candidates.add(readProfile(candidate));
				if (stage != DevelopmentJudge.Stage.FROZEN && !candidates.isEmpty())
				{
					examined.add(new ExaminedJudge(candidates.getFirst(), stage, integer(element, "shadowed"), 0,
						integer(element, "spot_checked"), integer(element, "lenient"), integer(element, "strict")));
					if (stage != DevelopmentJudge.Stage.QUALIFIED)
						waiting.addAll(candidates.subList(1, candidates.size()));
				}
			}
			Optional<JudgeBasis> basis = Optional.empty();
			if (element.has("basis"))
				basis = Optional.of(readBasis(field(element, "basis")));
			int developmentTrials = 0;
			if (element.has("development_trials"))
				developmentTrials = integer(element, "development_trials");
			int rounds = 0;
			if (element.has("rounds"))
				rounds = integer(element, "rounds");
			judges.put(entry.getKey(), new DevelopmentJudge(labels, waiting, examined, rejected, basis,
				developmentTrials, rounds));
		}
		return judges;
	}

	/**
	 * Writes near-miss labels into an object.
	 *
	 * @param node   the object to write to
	 * @param labels the label of each near-miss reply, by reply key
	 */
	static void writeLabels(ObjectNode node, Map<String, Boolean> labels)
	{
		for (Entry<String, Boolean> label : new TreeMap<>(labels).entrySet())
			node.put(label.getKey(), label.getValue());
	}

	/**
	 * Reads near-miss labels.
	 *
	 * @param node the object that holds the labels
	 * @return the label of each near-miss reply, by reply key
	 * @throws IllegalArgumentException if the node is not an object of booleans
	 */
	static Map<String, Boolean> readLabels(JsonNode node)
	{
		requireObject(node, "labels");
		Map<String, Boolean> labels = new LinkedHashMap<>();
		for (Entry<String, JsonNode> label : node.properties())
			labels.put(label.getKey(), bool(node, label.getKey()));
		return labels;
	}

	/**
	 * Writes what a judge choice rests on into an object.
	 *
	 * @param node  the object to write to
	 * @param basis the test's definition and the judge version
	 */
	static void writeBasis(ObjectNode node, JudgeBasis basis)
	{
		node.put("test_version", basis.testVersion().hex());
		node.put("judge_version", basis.judgeVersion());
	}

	/**
	 * Reads what a judge choice rests on.
	 *
	 * @param node the object that holds it
	 * @return the test's definition and the judge version
	 * @throws IllegalArgumentException if the object does not describe a valid basis
	 */
	static JudgeBasis readBasis(JsonNode node)
	{
		return new JudgeBasis(new Sha256(text(node, "test_version")), integer(node, "judge_version"));
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
		node.put("held_out", shadow.heldOut());
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
		boolean heldOut = node.has("held_out") && bool(node, "held_out");
		return new ShadowResult(readProfile(field(node, "candidate")), bool(node, "spot_check"), heldOut,
			bool(node, "lenient"), bool(node, "strict"));
	}
}
