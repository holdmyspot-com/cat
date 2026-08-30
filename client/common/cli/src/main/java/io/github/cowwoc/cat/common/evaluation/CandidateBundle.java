package io.github.cowwoc.cat.common.evaluation;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import io.github.cowwoc.cat.common.git.Git;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Validates and applies the exact rules that form one evaluated prompt candidate.
 */
public final class CandidateBundle
{
	private static final String MANIFEST = "candidate-bundle.json";

	/**
	 * Prevents construction of this static utility.
	 */
	private CandidateBundle()
	{
	}

	/**
	 * Materializes selected rule files from one source tree as an immutable candidate bundle.
	 *
	 * @param json the JSON writer used for the manifest
	 * @param sourceRules the source tree whose layout matches installed rule paths
	 * @param bundleRoot the initially absent bundle directory
	 * @param primaryRule the canonical relative path of the evaluated prompt file
	 * @param additionalRules additional canonical relative paths whose exact versions the candidate needs
	 * @return the validated published bundle
	 * @throws IOException if an input is unsafe, missing, changes during copying, or cannot be published atomically
	 */
	public static Candidate materialize(JsonMapper json, Path sourceRules, Path bundleRoot, String primaryRule,
		List<String> additionalRules) throws IOException
	{
		return materialize(json, sourceRules, bundleRoot, primaryRule, additionalRules, Map.of());
	}

	/**
	 * Materializes selected rule files and their candidate-specific route mapping from one source tree.
	 *
	 * @param json the JSON writer used for the manifest
	 * @param sourceRules the source tree whose layout matches installed rule paths
	 * @param bundleRoot the initially absent bundle directory
	 * @param primaryRule the canonical relative path of the evaluated prompt file
	 * @param additionalRules additional canonical relative paths whose exact versions the candidate needs
	 * @param routeReplacements ordinary suite paths mapped to candidate-specific canonical rule paths
	 * @return the validated published bundle
	 * @throws IOException if an input is unsafe, missing, changes during copying, or cannot be published atomically
	 */
	public static Candidate materialize(JsonMapper json, Path sourceRules, Path bundleRoot, String primaryRule,
		List<String> additionalRules, Map<String, String> routeReplacements) throws IOException
	{
		Path source = sourceRules.toAbsolutePath().normalize();
		Path destination = bundleRoot.toAbsolutePath().normalize();
		if (!Files.isDirectory(source, NOFOLLOW_LINKS) || Files.isSymbolicLink(source))
			throw new IOException("Candidate source rules directory is not a regular directory: " + source);
		if (Files.exists(destination, NOFOLLOW_LINKS))
			throw new IOException("Candidate bundle directory already exists: " + destination);
		Path parent = destination.getParent();
		if (parent == null)
			throw new IOException("Candidate bundle directory has no parent: " + destination);

		Set<String> selectedRules = new LinkedHashSet<>();
		selectedRules.add(canonicalRelativePath(primaryRule, "primary rule"));
		for (String rule : additionalRules)
			selectedRules.add(canonicalRelativePath(rule, "candidate rule"));
		Map<String, String> replacements = canonicalRouteReplacements(routeReplacements, selectedRules);
		Path staging = null;
		try
		{
			Files.createDirectories(parent);
			staging = Files.createTempDirectory(parent, ".candidate-bundle-");
			Path stagingRules = staging.resolve("rules");
			for (String relativeRule : selectedRules)
			{
				Path sourceRule = source.resolve(relativeRule).normalize();
				if (!sourceRule.startsWith(source) || !Files.isRegularFile(sourceRule, NOFOLLOW_LINKS) ||
					Files.isSymbolicLink(sourceRule))
				{
					throw new IOException("Candidate source rule is unavailable or unsafe: " + sourceRule);
				}
				Path destinationRule = stagingRules.resolve(relativeRule).normalize();
				if (!destinationRule.startsWith(stagingRules))
					throw new IOException("Candidate rule escapes its bundle: " + relativeRule);
				Files.createDirectories(destinationRule.getParent());
				Files.copy(sourceRule, destinationRule, REPLACE_EXISTING);
			}
			ObjectNode manifest = json.createObjectNode();
			manifest.put("primary_rule", canonicalRelativePath(primaryRule, "primary rule"));
			if (!replacements.isEmpty())
			{
				ObjectNode routes = manifest.putObject("route_replacements");
				for (Map.Entry<String, String> replacement : replacements.entrySet())
					routes.put(replacement.getKey(), replacement.getValue());
			}
			Files.writeString(staging.resolve(MANIFEST), json.writeValueAsString(manifest) + "\n", UTF_8);
			read(json, staging);
			Files.move(staging, destination, ATOMIC_MOVE);
			staging = null;
			return read(json, destination);
		}
		finally
		{
			if (staging != null && Files.exists(staging, NOFOLLOW_LINKS))
				TemporaryDirectories.deleteRecursively(staging);
		}
	}

	/**
	 * Materializes selected rule files exactly as they existed in one Git revision.
	 *
	 * @param json the JSON writer used for the manifest
	 * @param repository the Git repository that contains the historical files
	 * @param revision the immutable Git object name that selects their version
	 * @param repositoryRelativeRulesRoot the rules root relative to the repository
	 * @param bundleRoot the initially absent bundle directory
	 * @param primaryRule the canonical relative path of the evaluated prompt file
	 * @param additionalRules additional canonical relative paths whose exact versions the candidate needs
	 * @return the validated published bundle
	 * @throws IOException if an input is unsafe, Git cannot read a selected file, or the bundle cannot be published
	 */
	public static Candidate materializeFromGit(JsonMapper json, Path repository, String revision,
		String repositoryRelativeRulesRoot, Path bundleRoot, String primaryRule, List<String> additionalRules)
		throws IOException
	{
		return materializeFromGit(json, repository, revision, repositoryRelativeRulesRoot, bundleRoot, primaryRule,
			additionalRules, Map.of());
	}

	/**
	 * Materializes selected rule files and their candidate-specific route mapping exactly as they existed in one Git
	 * revision.
	 *
	 * @param json the JSON writer used for the manifest
	 * @param repository the Git repository that contains the historical files
	 * @param revision the immutable Git object name that selects their version
	 * @param repositoryRelativeRulesRoot the rules root relative to the repository
	 * @param bundleRoot the initially absent bundle directory
	 * @param primaryRule the canonical relative path of the evaluated prompt file
	 * @param additionalRules additional canonical relative paths whose exact versions the candidate needs
	 * @param routeReplacements ordinary suite paths mapped to candidate-specific canonical rule paths
	 * @return the validated published bundle
	 * @throws IOException if an input is unsafe, Git cannot read a selected file, or the bundle cannot be published
	 */
	public static Candidate materializeFromGit(JsonMapper json, Path repository, String revision,
		String repositoryRelativeRulesRoot, Path bundleRoot, String primaryRule, List<String> additionalRules,
		Map<String, String> routeReplacements)
		throws IOException
	{
		if (revision == null || !revision.matches("[0-9A-Fa-f]{7,64}"))
			throw new IOException("Candidate Git revision must be an immutable object name: " + revision);
		String rulesRoot = canonicalRelativePath(repositoryRelativeRulesRoot, "repository rules root");
		Path destination = bundleRoot.toAbsolutePath().normalize();
		Path parent = destination.getParent();
		if (parent == null)
			throw new IOException("Candidate bundle directory has no parent: " + destination);
		Set<String> selectedRules = new LinkedHashSet<>();
		selectedRules.add(canonicalRelativePath(primaryRule, "primary rule"));
		for (String rule : additionalRules)
			selectedRules.add(canonicalRelativePath(rule, "candidate rule"));

		Files.createDirectories(parent);
		Path historicalSource = Files.createTempDirectory(parent, ".candidate-history-");
		try
		{
			Git git = new Git(repository.toAbsolutePath().normalize());
			for (String relativeRule : selectedRules)
			{
				Path target = historicalSource.resolve(relativeRule).normalize();
				if (!target.startsWith(historicalSource))
					throw new IOException("Candidate rule escapes its historical source: " + relativeRule);
				Files.createDirectories(target.getParent());
				Files.write(target, git.fileAtRevision(revision, rulesRoot + "/" + relativeRule));
			}
			return materialize(json, historicalSource, destination, primaryRule, additionalRules, routeReplacements);
		}
		finally
		{
			TemporaryDirectories.deleteRecursively(historicalSource);
		}
	}

	/**
	 * Reads one candidate bundle whose rules are mapped to their installed paths.
	 *
	 * @param json the JSON reader and writer
	 * @param bundleRoot the directory that contains the manifest and rules directory
	 * @return the validated candidate bundle
	 * @throws IOException if the bundle is malformed or unsafe
	 */
	public static Candidate read(JsonMapper json, Path bundleRoot) throws IOException
	{
		Path root = bundleRoot.toAbsolutePath().normalize();
		Path manifest = root.resolve(MANIFEST);
		Path rules = root.resolve("rules");
		if (!Files.isDirectory(root, NOFOLLOW_LINKS) || Files.isSymbolicLink(root))
			throw new IOException("Candidate bundle is not a regular directory: " + root);
		if (!Files.isRegularFile(manifest, NOFOLLOW_LINKS) || Files.isSymbolicLink(manifest))
			throw new IOException("Candidate bundle is missing its manifest: " + manifest);
		if (!Files.isDirectory(rules, NOFOLLOW_LINKS) || Files.isSymbolicLink(rules))
			throw new IOException("Candidate bundle is missing its rules directory: " + rules);

		ObjectNode metadata = object(json.readTree(manifest.toFile()), manifest);
		for (String field : List.of("primary_rule"))
		{
			if (!metadata.has(field))
				throw new IOException("Candidate bundle is missing " + field + ": " + manifest);
		}
		if (metadata.size() > 2 || (metadata.size() == 2 && !metadata.has("route_replacements")))
			throw new IOException("Candidate bundle has an unsupported manifest field: " + manifest);
		String primaryRule = text(metadata, "primary_rule", manifest);
		List<Entry> entries = entries(rules);
		Entry primary = entries.stream().filter(entry -> entry.relativePath().equals(primaryRule)).findFirst().orElseThrow(
			() -> new IOException("Candidate bundle primary_rule is absent from its rules directory: " + primaryRule));
		Map<String, String> replacements = readRouteReplacements(metadata, entries, manifest);
		return new Candidate(primary, entries, replacements, fingerprint(entries, replacements));
	}

	/**
	 * Copies this bundle's exact rule files into one staged runner artifact.
	 *
	 * @param candidate the validated candidate bundle
	 * @param artifact the staged runner artifact
	 * @throws IOException if a source changes or a destination is unsafe
	 */
	public static void apply(Candidate candidate, Path artifact) throws IOException
	{
		Path rules = artifact.toAbsolutePath().normalize().resolve("rules");
		if (!Files.isDirectory(rules, NOFOLLOW_LINKS) || Files.isSymbolicLink(rules))
			throw new IOException("Runner artifact is missing its rules directory: " + rules);
		for (Entry entry : candidate.entries())
		{
			if (!fingerprint(entry.source()).equals(entry.sha256()))
				throw new IOException("Candidate bundle rule changed while preparing evaluation: " + entry.source());
			Path destination = rules.resolve(entry.relativePath()).normalize();
			if (!destination.startsWith(rules))
				throw new IOException("Candidate bundle rule escapes the runner artifact: " + entry.relativePath());
			Files.createDirectories(destination.getParent());
			Files.copy(entry.source(), destination, REPLACE_EXISTING);
		}
	}

	/**
	 * Returns the persisted receipt for one candidate bundle.
	 *
	 * @param json the JSON factory
	 * @param candidate the candidate bundle
	 * @return the self-contained bundle receipt
	 */
	public static ObjectNode receipt(JsonMapper json, Candidate candidate)
	{
		ObjectNode result = json.createObjectNode();
		result.put("primary_rule", candidate.primary().relativePath());
		result.put("sha256", candidate.sha256());
		ObjectNode replacements = result.putObject("route_replacements");
		for (Map.Entry<String, String> replacement : candidate.routeReplacements().entrySet())
			replacements.put(replacement.getKey(), replacement.getValue());
		ArrayNode rules = result.putArray("rules");
		for (Entry entry : candidate.entries())
		{
			ObjectNode rule = rules.addObject();
			rule.put("relative_path", entry.relativePath());
			rule.put("sha256", entry.sha256());
		}
		return result;
	}

	/**
	 * Lists and fingerprints every regular rule in canonical path order.
	 *
	 * @param rules the bundle's rules root
	 * @return the contained rule entries
	 * @throws IOException if a rule is unsafe or absent
	 */
	private static List<Entry> entries(Path rules) throws IOException
	{
		List<Entry> result = new ArrayList<>();
		Files.walkFileTree(rules, new SimpleFileVisitor<>()
		{
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException
			{
				if (!attributes.isRegularFile() || Files.isSymbolicLink(file))
					throw new IOException("Candidate bundle contains an unsafe rule entry: " + file);
				Path relative = rules.relativize(file);
				result.add(new Entry(file, relative.toString().replace(file.getFileSystem().getSeparator(), "/"),
					fingerprint(file)));
				return FileVisitResult.CONTINUE;
			}
		});
		if (result.isEmpty())
			throw new IOException("Candidate bundle contains no rules: " + rules);
		return result.stream().sorted(java.util.Comparator.comparing(Entry::relativePath)).toList();
	}

	/**
	 * Fingerprints the canonical path and content hash of every candidate rule.
	 *
	 * @param entries the candidate rules in canonical order
	 * @param routeReplacements ordinary suite paths mapped to candidate-specific canonical rule paths
	 * @return the bundle identity
	 * @throws IOException if SHA-256 is unavailable
	 */
	private static String fingerprint(List<Entry> entries, Map<String, String> routeReplacements) throws IOException
	{
		StringBuilder content = new StringBuilder((entries.size() + routeReplacements.size()) * 80);
		for (Entry entry : entries)
			content.append(entry.relativePath()).append('\t').append(entry.sha256()).append('\n');
		content.append("route_replacements\n");
		for (Map.Entry<String, String> replacement : new TreeMap<>(routeReplacements).entrySet())
			content.append(replacement.getKey()).append('\t').append(replacement.getValue()).append('\n');
		return fingerprint(content.toString());
	}

	/**
	 * Fingerprints one rule file.
	 *
	 * @param file the regular rule file
	 * @return its content hash
	 * @throws IOException if the file cannot be read
	 */
	private static String fingerprint(Path file) throws IOException
	{
		return fingerprint(Files.readString(file, UTF_8));
	}

	/**
	 * Fingerprints one canonical text representation.
	 *
	 * @param text the representation to hash
	 * @return its SHA-256 value
	 * @throws IOException if SHA-256 is unavailable
	 */
	private static String fingerprint(String text) throws IOException
	{
		try
		{
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(UTF_8)));
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IOException("SHA-256 is unavailable", e);
		}
	}

	/**
	 * Requires one JSON object.
	 *
	 * @param value the parsed value
	 * @param source the manifest path for diagnostics
	 * @return the object value
	 * @throws IOException if the value is not an object
	 */
	private static ObjectNode object(JsonNode value, Path source) throws IOException
	{
		if (!(value instanceof ObjectNode result))
			throw new IOException("Candidate bundle manifest must contain an object: " + source);
		return result;
	}

	/**
	 * Requires one safe canonical rule path from the manifest.
	 *
	 * @param object the manifest object
	 * @param field the required field name
	 * @param source the manifest path for diagnostics
	 * @return the relative canonical path
	 * @throws IOException if the field is absent, blank, or unsafe
	 */
	private static String text(ObjectNode object, String field, Path source) throws IOException
	{
		JsonNode value = object.path(field);
		if (!value.isString() || value.asString().isBlank())
			throw new IOException("Candidate bundle manifest has invalid " + field + ": " + source);
		return canonicalRelativePath(value.asString(), "manifest " + field);
	}

	/**
	 * Validates route replacements supplied while materializing one candidate.
	 *
	 * @param routeReplacements ordinary suite paths mapped to candidate-specific rule paths
	 * @param selectedRules every rule copied into the candidate bundle
	 * @return an immutable canonical mapping ordered by ordinary suite path
	 * @throws IOException if a mapping is unsafe or its target is not a copied candidate rule
	 */
	private static Map<String, String> canonicalRouteReplacements(Map<String, String> routeReplacements,
		Set<String> selectedRules) throws IOException
	{
		if (routeReplacements == null)
			throw new NullPointerException("routeReplacements must not be null");
		Map<String, String> result = new TreeMap<>();
		for (Map.Entry<String, String> entry : routeReplacements.entrySet())
		{
			String ordinary = canonicalRelativePath(entry.getKey(), "ordinary route path");
			String candidate = canonicalRelativePath(entry.getValue(), "candidate route path");
			if (!selectedRules.contains(candidate))
			{
				throw new IOException("Candidate route replacement target is not in the candidate bundle: " + candidate);
			}
			result.put(ordinary, candidate);
		}
		return Map.copyOf(result);
	}

	/**
	 * Reads the optional ordinary-to-candidate route map retained in one bundle manifest.
	 *
	 * @param metadata the parsed manifest
	 * @param entries the copied candidate rule files
	 * @param manifest the manifest location for diagnostics
	 * @return an immutable canonical mapping ordered by ordinary suite path
	 * @throws IOException if the mapping is malformed or points outside the copied rules
	 */
	private static Map<String, String> readRouteReplacements(ObjectNode metadata, List<Entry> entries, Path manifest)
		throws IOException
	{
		JsonNode declared = metadata.path("route_replacements");
		if (declared.isMissingNode())
			return Map.of();
		if (!(declared instanceof ObjectNode replacements))
			throw new IOException("Candidate bundle route_replacements must be an object: " + manifest);
		Set<String> copiedRules = entries.stream().map(Entry::relativePath).collect(java.util.stream.Collectors.toSet());
		Map<String, String> raw = new LinkedHashMap<>();
		for (String name : replacements.propertyNames())
		{
			JsonNode value = replacements.path(name);
			if (!value.isString())
			{
				throw new IOException("Candidate bundle route replacement value is not text: " + name + " in " +
					manifest);
			}
			raw.put(name, value.asString());
		}
		return canonicalRouteReplacements(raw, copiedRules);
	}

	/**
	 * Validates one canonical path below an installed rules directory.
	 *
	 * @param value the requested relative path
	 * @param description the value's role in a diagnostic
	 * @return the slash-separated canonical path
	 * @throws IOException if the path is blank, absolute, or escapes its rules root
	 */
	private static String canonicalRelativePath(String value, String description) throws IOException
	{
		if (value == null || value.isBlank())
			throw new IOException("Candidate bundle has an invalid " + description);
		Path path = Path.of(value);
		if (path.isAbsolute() || path.normalize().startsWith(".."))
			throw new IOException("Candidate bundle has an unsafe " + description + ": " + value);
		return path.normalize().toString().replace(path.getFileSystem().getSeparator(), "/");
	}

	/**
	 * One source rule and its canonical installed path.
	 *
	 * @param source the source file
	 * @param relativePath its canonical path below the installed rules directory
	 * @param sha256 the source content fingerprint
	 */
	public record Entry(Path source, String relativePath, String sha256)
	{
	}

	/**
	 * A complete prompt candidate prepared for one runner artifact.
	 *
	 * @param primary the source rule used for prompt-level audits
	 * @param entries every rule file that the candidate replaces
	 * @param routeReplacements ordinary suite paths mapped to the candidate's canonical paths
	 * @param sha256 the candidate's stable identity
	 */
	public record Candidate(Entry primary, List<Entry> entries, Map<String, String> routeReplacements, String sha256)
	{
	}
}
