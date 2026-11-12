package com.holdmyspot.cat.common.optimizer.store;

import static com.holdmyspot.cat.common.optimizer.store.JsonFields.field;
import static com.holdmyspot.cat.common.optimizer.store.JsonFields.integer;
import static com.holdmyspot.cat.common.optimizer.store.JsonFields.requireObject;
import static com.holdmyspot.cat.common.optimizer.store.JsonFields.requireVersion;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.optimizer.campaign.Campaign;
import com.holdmyspot.cat.common.optimizer.campaign.CarriedJudge;
import com.holdmyspot.cat.common.optimizer.campaign.DevelopmentJudge;
import com.holdmyspot.cat.common.optimizer.campaign.ExaminedJudge;
import com.holdmyspot.cat.common.optimizer.campaign.JudgeBasis;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.TreeMap;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The development judges that had qualified when earlier campaigns ended, kept in the harness's CAT data directory so
 * that a later campaign on the same project and target can start each test with its judge qualified.
 * <p>
 * Each project and target has one file, named by the identity of their real paths, that holds one record per prompt
 * test. A campaign that ends records each test whose judge qualified, replacing an earlier record, and removes the
 * record of every other test that examined a candidate. These records are not certification records and are never
 * written to the project.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe if its directory has one writer at a time.
 */
public final class DevelopmentJudgeRecords
{
	/**
	 * The directory, under the harness's CAT data directory, that holds the records.
	 */
	public static final String DIRECTORY = "development-judges";
	private static final int VERSION = 1;
	private static final String MALFORMED = "is not a valid development judge record";
	private final Optional<Path> directory;
	/**
	 * Reads and writes the records, or {@code null} if nothing is kept.
	 */
	private final JsonMapper json;
	/**
	 * Replaces a record file atomically, or {@code null} if nothing is kept.
	 */
	private final FileReplacer replacer;

	/**
	 * Creates the records that live in a data directory.
	 *
	 * @param dataDirectory the harness's CAT data directory
	 * @param json          reads and writes the records
	 * @param replacer      replaces a record file atomically
	 * @throws NullPointerException if any of the arguments are null
	 */
	public DevelopmentJudgeRecords(Path dataDirectory, JsonMapper json, FileReplacer replacer)
	{
		requireThat(dataDirectory, "dataDirectory").isNotNull();
		requireThat(json, "json").isNotNull();
		requireThat(replacer, "replacer").isNotNull();
		this.directory = Optional.of(dataDirectory.resolve(DIRECTORY));
		this.json = json;
		this.replacer = replacer;
	}

	/**
	 * Creates records that keep nothing.
	 */
	private DevelopmentJudgeRecords()
	{
		this.directory = Optional.empty();
		this.json = null;
		this.replacer = null;
	}

	/**
	 * Returns records that keep nothing: every lookup finds no record and every campaign end is ignored.
	 *
	 * @return the records
	 */
	public static DevelopmentJudgeRecords none()
	{
		return new DevelopmentJudgeRecords();
	}

	/**
	 * Returns the file that holds the records of a project and target.
	 *
	 * @param root   the project's root
	 * @param target the optimization target
	 * @return the file, or empty if nothing is kept
	 */
	public Optional<Path> file(Path root, Path target)
	{
		requireThat(root, "root").isNotNull();
		requireThat(target, "target").isNotNull();
		String identity = realPath(root) + "\n" + realPath(target);
		return directory.map(path -> path.resolve(Sha256.of(identity.getBytes(UTF_8)).hex() + ".json"));
	}

	/**
	 * Returns the qualification that an earlier campaign on a project and target recorded for a prompt test.
	 *
	 * @param root   the project's root
	 * @param target the optimization target
	 * @param test   the prompt test's identifier
	 * @return the qualification, or empty if there is none or the record cannot be read
	 * @throws NullPointerException if any of the arguments are null
	 */
	public Optional<CarriedJudge> find(Path root, Path target, String test)
	{
		requireThat(test, "test").isNotNull();
		Optional<Path> file = file(root, target);
		if (file.isEmpty() || Files.notExists(file.get()))
			return Optional.empty();
		try
		{
			return Optional.ofNullable(JsonFiles.read(json, file.get(), this::readTests, MALFORMED).get(test));
		}
		catch (IOException _)
		{
			// A record that cannot be read carries nothing over; the test examines its candidates again
			return Optional.empty();
		}
	}

	/**
	 * Records how each prompt test's judge choice stood when a campaign ended: a test whose judge qualified replaces
	 * its record, and every other test that examined a candidate removes its record.
	 *
	 * @param campaign the campaign that ended
	 * @throws NullPointerException if {@code campaign} is null
	 * @throws IOException          if the records cannot be written
	 */
	public void campaignEnded(Campaign campaign) throws IOException
	{
		requireThat(campaign, "campaign").isNotNull();
		if (campaign.judges().isEmpty())
			return;
		Optional<Path> file = file(campaign.header().root(), campaign.header().target());
		if (file.isEmpty())
			return;
		Map<String, CarriedJudge> tests = new TreeMap<>();
		if (Files.exists(file.get()))
		{
			try
			{
				tests.putAll(JsonFiles.read(json, file.get(), this::readTests, MALFORMED));
			}
			catch (IOException _)
			{
				// A record that cannot be read is replaced by what this campaign showed
			}
		}
		Map<String, CarriedJudge> before = new TreeMap<>(tests);
		for (Entry<String, DevelopmentJudge> entry : campaign.judges().entrySet())
		{
			DevelopmentJudge judge = entry.getValue();
			Optional<ExaminedJudge> qualified = judge.qualified();
			if (qualified.isPresent() && judge.basis().isPresent())
			{
				ExaminedJudge candidate = qualified.get();
				JudgeBasis basis = judge.basis().get();
				tests.put(entry.getKey(), new CarriedJudge(candidate.profile(), candidate.shadowed(),
					candidate.spotChecked(), candidate.lenient(), candidate.strict(), basis.testVersion(),
					judge.labels(), basis.judgeVersion()));
			}
			else
				tests.remove(entry.getKey());
		}
		if (tests.equals(before))
			return;
		AtomicFiles.createDirectories(file.get().getParent());
		JsonFiles.replace(json, replacer, file.get(), writeTests(campaign, tests), _ ->
		{
		});
	}

	/**
	 * Converts the records of a project and target to a document.
	 *
	 * @param campaign the campaign whose project and target the records describe
	 * @param tests    the record of each prompt test
	 * @return the document
	 */
	private ObjectNode writeTests(Campaign campaign, Map<String, CarriedJudge> tests)
	{
		ObjectNode root = json.createObjectNode();
		root.put(JsonFields.VERSION, VERSION);
		root.put("root", realPath(campaign.header().root()).toString());
		root.put("target", realPath(campaign.header().target()).toString());
		ObjectNode node = root.putObject("tests");
		for (Entry<String, CarriedJudge> entry : tests.entrySet())
		{
			CarriedJudge judge = entry.getValue();
			ObjectNode element = node.putObject(entry.getKey());
			DevelopmentJudgeJson.writeProfile(element.putObject("profile"), judge.profile());
			element.put("shadowed", judge.shadowed());
			element.put("spot_checked", judge.spotChecked());
			element.put("lenient", judge.lenient());
			element.put("strict", judge.strict());
			DevelopmentJudgeJson.writeBasis(element.putObject("basis"),
				new JudgeBasis(judge.testVersion(), judge.judgeVersion()));
			DevelopmentJudgeJson.writeLabels(element.putObject("labels"), judge.labels());
		}
		return root;
	}

	/**
	 * Reads the records of a project and target.
	 *
	 * @param root the document
	 * @return the record of each prompt test
	 * @throws IllegalArgumentException if the document does not describe valid records
	 */
	private Map<String, CarriedJudge> readTests(JsonNode root)
	{
		requireVersion(root, VERSION);
		JsonNode node = field(root, "tests");
		requireObject(node, "tests");
		Map<String, CarriedJudge> tests = new LinkedHashMap<>();
		for (Entry<String, JsonNode> entry : node.properties())
		{
			JsonNode element = entry.getValue();
			JudgeBasis basis = DevelopmentJudgeJson.readBasis(field(element, "basis"));
			tests.put(entry.getKey(), new CarriedJudge(DevelopmentJudgeJson.readProfile(field(element, "profile")),
				integer(element, "shadowed"), integer(element, "spot_checked"), integer(element, "lenient"),
				integer(element, "strict"), basis.testVersion(),
				DevelopmentJudgeJson.readLabels(field(element, "labels")), basis.judgeVersion()));
		}
		return tests;
	}

	/**
	 * Returns a path with its links resolved.
	 *
	 * @param path the path
	 * @return the real path if it exists, otherwise the absolute normalized path
	 */
	private static Path realPath(Path path)
	{
		try
		{
			return path.toRealPath();
		}
		catch (IOException _)
		{
			return path.toAbsolutePath().normalize();
		}
	}
}
