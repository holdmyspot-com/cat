package io.github.cowwoc.cat.distribution.bundle;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import io.github.cowwoc.cat.common.BoundedInput;
import io.github.cowwoc.cat.common.ProjectRuleLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Converts path-scoped artifact rules into plain-text rule bodies and a direct routing index.
 */
public final class RuleArtifactRouter
{
	private static final int MAXIMUM_RULE_INPUT_BYTES = 1_048_576;
	// The generated indexes are written into directories that hold authored rule files, and authored rule files never
	// start with ".cat-", so the prefix keeps an index from overwriting one of them.
	private static final String ROUTE_INDEX = ".cat-path-scoped-rules.md";
	private static final String LAZY_DEPENDENCY_INDEX = ".cat-lazy-rule-dependencies.md";
	private static final Set<String> FRONTMATTER_KEYS = Set.of("agents", "depends-on", "paths");

	/**
	 * Prevents instantiation.
	 */
	private RuleArtifactRouter()
	{
	}

	/**
	 * Moves each path-scoped direct rule below {@code rules/include}, removes its frontmatter, and creates direct prose
	 * that tells a harness when to load it.
	 *
	 * @param artifact a non-null assembled plugin artifact
	 * @param yamlMapper a non-null YAML parser
	 * @throws IOException if rule metadata cannot be read or is invalid
	 */
	public static void route(Path artifact, YAMLMapper yamlMapper) throws IOException
	{
		requireThat(artifact, "artifact").isNotNull();
		requireThat(yamlMapper, "yamlMapper").isNotNull();
		Path rules = artifact.resolve("rules");
		if (!Files.isDirectory(rules, NOFOLLOW_LINKS))
			return;
		try (Stream<Path> directories = Files.list(rules))
		{
			for (Path directory : directories.filter(path -> Files.isDirectory(path, NOFOLLOW_LINKS)).toList())
				routeDirectory(artifact, directory, yamlMapper);
		}
		routeLazyDependencies(artifact, yamlMapper);
	}

	/**
	 * Removes lazy-rule metadata from packaged bodies and records dependency-first loading instructions.
	 *
	 * @param artifact a non-null assembled plugin artifact
	 * @param yamlMapper a non-null YAML parser
	 * @throws IOException if a lazy rule names an unavailable dependency
	 */
	private static void routeLazyDependencies(Path artifact, YAMLMapper yamlMapper) throws IOException
	{
		Path rules = artifact.resolve("rules");
		Path include = rules.resolve("include");
		Path common = rules.resolve("common");
		if (!Files.isDirectory(include, NOFOLLOW_LINKS))
			return;
		Map<String, List<String>> dependencies = new TreeMap<>();
		try (Stream<Path> files = Files.walk(include))
		{
			for (Path file : files.filter(path -> Files.isRegularFile(path, NOFOLLOW_LINKS) &&
				path.getFileName().toString().endsWith(".md")).toList())
			{
				RuleBody rule = ruleBody(BoundedInput.readUtf8(file, MAXIMUM_RULE_INPUT_BYTES, "Lazy rule"), file,
					yamlMapper);
				if (rule.dependencies().isEmpty())
					continue;
				Files.writeString(file, rule.body(), UTF_8);
				List<String> lazyDependencies = new ArrayList<>();
				for (String dependency : rule.dependencies())
				{
					Path resolved = file.getParent().resolve(dependency).normalize();
					if (!resolved.startsWith(rules) || !Files.isRegularFile(resolved, NOFOLLOW_LINKS))
						throw new IOException("Lazy rule dependency is not available: " + artifact.relativize(file) +
							" depends on " + dependency);
					if (resolved.startsWith(include))
						lazyDependencies.add(common.relativize(resolved).toString().replace('\\', '/'));
				}
				if (!lazyDependencies.isEmpty())
					dependencies.put(common.relativize(file).toString().replace('\\', '/'), lazyDependencies);
			}
		}
		if (dependencies.isEmpty())
			return;
		StringBuilder content = new StringBuilder(256).append("# CAT Lazy Rule Dependencies\n\n" +
			"Before lazy loading a listed rule, first lazy load its listed prerequisites.\n\n");
		for (Map.Entry<String, List<String>> entry : dependencies.entrySet())
			content.append("When lazy loading `").append(entry.getKey()).append("`, first lazy load ").
				append(entry.getValue().stream().map(path -> "`" + path + "`").collect(java.util.stream.Collectors.joining(
					" and "))).append(".\n");
		Files.writeString(rules.resolve("common").resolve(LAZY_DEPENDENCY_INDEX), content, UTF_8);
	}

	/**
	 * Routes scoped rules from one immediate harness or common rule directory.
	 *
	 * @param artifact the assembled plugin artifact
	 * @param directory one direct common or harness rule directory
	 * @param yamlMapper the YAML parser
	 * @throws IOException if a rule cannot be routed
	 */
	private static void routeDirectory(Path artifact, Path directory, YAMLMapper yamlMapper) throws IOException
	{
		List<Route> routes = new ArrayList<>();
		try (Stream<Path> files = Files.list(directory))
		{
			for (Path file : files.filter(path -> Files.isRegularFile(path, NOFOLLOW_LINKS) &&
				path.getFileName().toString().endsWith(".md")).sorted().toList())
			{
				RuleBody rule = ruleBody(BoundedInput.readUtf8(file, MAXIMUM_RULE_INPUT_BYTES, "Rule"), file,
					yamlMapper);
				if (rule.paths() == null)
					continue;
				Path target = artifact.resolve("rules/include").resolve(directory.getFileName()).resolve(file.getFileName());
				Files.createDirectories(target.getParent());
				Files.writeString(target, rule.body(), UTF_8);
				Files.delete(file);
				routes.add(new Route(artifact.relativize(target).toString().replace('\\', '/'), rule.paths()));
			}
		}
		if (!routes.isEmpty())
			Files.writeString(directory.resolve(ROUTE_INDEX), render(routes), UTF_8);
	}

	/**
	 * Parses one rule body and optional {@code paths} frontmatter.
	 *
	 * @param content the rule content
	 * @param path the packaged rule path
	 * @param yamlMapper the YAML parser
	 * @return the frontmatter-free rule body and optional path globs
	 * @throws IOException if frontmatter is invalid
	 */
	private static RuleBody ruleBody(String content, Path path, YAMLMapper yamlMapper) throws IOException
	{
		if (!content.startsWith("---\n") && !content.startsWith("---\r\n"))
			return new RuleBody(content, null, List.of());
		String delimiter = "\n---\n";
		if (content.startsWith("---\r\n"))
			delimiter = "\r\n---\r\n";
		int end = content.indexOf(delimiter, delimiter.length() - 1);
		if (end == -1)
			throw new IOException("Packaged rule has unclosed YAML frontmatter: " + path);
		JsonNode frontmatter;
		try
		{
			frontmatter = yamlMapper.readTree(content.substring(4, end + 1));
		}
		catch (IllegalArgumentException e)
		{
			throw new IOException("Packaged rule has invalid YAML frontmatter: " + path, e);
		}
		if (frontmatter == null || !frontmatter.isObject())
			throw new IOException("Packaged rule has invalid YAML frontmatter: " + path);
		for (Map.Entry<String, JsonNode> property : frontmatter.properties())
		{
			if (!FRONTMATTER_KEYS.contains(property.getKey()))
			{
				throw new IOException("Unknown frontmatter key " + property.getKey() + " in packaged rule " + path +
					"; rule frontmatter supports agents, depends-on and paths");
			}
		}
		List<String> dependencies = strings(frontmatter.get("depends-on"), path, "depends-on");
		JsonNode paths = frontmatter.get("paths");
		if (paths == null)
			return new RuleBody(content.substring(end + delimiter.length()), null, dependencies);
		return new RuleBody(content.substring(end + delimiter.length()), strings(paths, path, "paths"), dependencies);
	}

	/**
	 * Reads one optional non-blank string-array frontmatter field.
	 *
	 * @param node the optional field value
	 * @param path the rule that declares the field
	 * @param field the field name used in diagnostics
	 * @return immutable string values, or an empty list when the field is absent
	 * @throws IOException if the field is present but not a non-blank string array
	 */
	private static List<String> strings(JsonNode node, Path path, String field) throws IOException
	{
		if (node == null)
			return List.of();
		if (!node.isArray())
			throw new IOException("Packaged rule has invalid " + field + " frontmatter: " + path);
		List<String> result = new ArrayList<>();
		for (JsonNode value : node)
		{
			if (!value.isString() || value.asString().isBlank())
				throw new IOException("Packaged rule has invalid " + field + " frontmatter: " + path);
			result.add(value.asString());
		}
		return List.copyOf(result);
	}

	/**
	 * Renders direct context that preserves the lazy routing decision without YAML frontmatter.
	 *
	 * @param routes the routes to render
	 * @return the direct rule content
	 */
	private static String render(List<Route> routes)
	{
		StringBuilder result = new StringBuilder(128);
		result.append("# CAT Path-Scoped Rules\n\n");
		for (Route route : routes)
			result.append(ProjectRuleLoader.pathScopedInstruction(ProjectRuleLoader.PLUGIN_ROOT_DIRECTIVE + '/' +
				route.path(), route.paths())).append('\n');
		return result.toString();
	}

	private record RuleBody(String body, List<String> paths, List<String> dependencies)
	{
	}

	private record Route(String path, List<String> paths)
	{
	}
}
