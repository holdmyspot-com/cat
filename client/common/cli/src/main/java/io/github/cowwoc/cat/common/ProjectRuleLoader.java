package io.github.cowwoc.cat.common;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.that;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Loads CAT project rules for a harness, placing each declared prerequisite before the rule that needs it.
 */
public final class ProjectRuleLoader
{
	static final int MAXIMUM_RULE_BYTES = 1_048_576;
	private static final Pattern DESIGN_GOALS = Pattern.compile("(?ms)^## Design Goals\\R.*?(?=^## |\\z)");
	private static final Pattern LAZY_RULE_REFERENCE = Pattern.compile("`([^`]+\\.md)`");

	private final YAMLMapper yamlMapper;

	/**
	 * Creates a loader backed by a YAML mapper.
	 *
	 * @param yamlMapper a non-null YAML parser
	 * @throws NullPointerException if {@code yamlMapper} is null
	 */
	public ProjectRuleLoader(YAMLMapper yamlMapper)
	{
		requireThat(yamlMapper, "yamlMapper").isNotNull();
		this.yamlMapper = yamlMapper;
	}

	/**
	 * Loads all direct rules addressed to a harness's primary agent and their declared prerequisites.
	 *
	 * @param pluginRoot a non-null CAT installation directory
	 * @param projectRoot a non-null directory whose {@code .cat/rules} subdirectories are loaded
	 * @param harness a non-blank lowercase harness identifier
	 * @return the generated context
	 * @throws NullPointerException if {@code pluginRoot}, {@code projectRoot}, or {@code harness} are null
	 * @throws IllegalArgumentException if {@code harness} is blank or invalid
	 * @throws IOException if a rule directory or file is not a real directory or regular file, or its content is invalid
	 */
	public String loadForMainAgent(Path pluginRoot, Path projectRoot, String harness) throws IOException
	{
		return load(pluginRoot, projectRoot, harness, RuleAudience.primary());
	}

	/**
	 * Loads all direct rules addressed to one harness subagent and their declared prerequisites.
	 *
	 * @param pluginRoot a non-null CAT installation directory
	 * @param projectRoot a non-null directory whose {@code .cat/rules} subdirectories are loaded
	 * @param harness a non-blank lowercase harness identifier
	 * @param agentType the harness subagent type, or blank when the host did not provide one
	 * @return the generated context
	 * @throws NullPointerException if {@code pluginRoot}, {@code projectRoot}, {@code harness}, or {@code agentType}
	 *         are null
	 * @throws IllegalArgumentException if {@code harness} is blank or invalid
	 * @throws IOException if a rule directory or file is not a real directory or regular file, or its content is invalid
	 */
	public String loadForSubagent(Path pluginRoot, Path projectRoot, String harness, String agentType) throws IOException
	{
		requireThat(agentType, "agentType").isNotNull();
		return load(pluginRoot, projectRoot, harness, RuleAudience.subagent(agentType));
	}

	/**
	 * Loads all direct rules addressed to one recipient and their declared prerequisites.
	 *
	 * @param pluginRoot a non-null CAT installation directory
	 * @param projectRoot a non-null directory whose {@code .cat/rules} subdirectories are loaded
	 * @param harness a non-blank lowercase harness identifier
	 * @param audience the rule recipient
	 * @return the generated context
	 * @throws IOException if a rule directory or file is not a real directory or regular file, or its content is invalid
	 */
	private String load(Path pluginRoot, Path projectRoot, String harness, RuleAudience audience) throws IOException
	{
		requireThat(harness, "harness").isNotBlank();
		if (!harness.matches("[a-z][a-z0-9-]*"))
			throw new IllegalArgumentException("Invalid CAT harness: " + harness);
		List<Rule> rules = new ArrayList<>();
		loadDirectory(pluginRoot, pluginRoot.resolve("rules/common"), RuleRoot.PLUGIN, audience, rules);
		loadDirectory(pluginRoot, pluginRoot.resolve("rules").resolve(harness), RuleRoot.PLUGIN, audience, rules);
		loadDirectory(projectRoot, projectRoot.resolve(".cat/rules/common"), RuleRoot.PROJECT, audience, rules);
		loadDirectory(projectRoot, projectRoot.resolve(".cat/rules").resolve(harness), RuleRoot.PROJECT, audience, rules);
		List<String> context = new ArrayList<>(ordered(rules).stream().map(Rule::context).toList());
		String lazyDependencies = lazyDependencies(projectRoot, audience);
		if (!lazyDependencies.isBlank())
			context.add(lazyDependencies);
		return String.join("\n\n---\n", context);
	}

	/**
	 * Renders dependency-first lazy-loading instructions for project-local included rules.
	 *
	 * @param projectRoot the project that owns the optional included rules
	 * @param audience the recipient whose routes are loaded
	 * @return direct context for lazy dependency routes, or an empty string when none exist
	 * @throws IOException if declared lazy-rule metadata is invalid or names an unavailable file
	 */
	private String lazyDependencies(Path projectRoot, RuleAudience audience) throws IOException
	{
		Path include = projectRoot.resolve(".cat/rules/include");
		if (!Files.isDirectory(include, NOFOLLOW_LINKS))
			return "";
		List<String> routes = new ArrayList<>();
		try (Stream<Path> files = Files.walk(include))
		{
			for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".md")).sorted().toList())
			{
				String path = relative(projectRoot, file);
				Frontmatter frontmatter = frontmatter(Files.readString(file, UTF_8), path);
				if (!matches(frontmatter, path, audience))
					continue;
				List<String> dependencies = dependencies(frontmatter, path);
				List<String> lazy = new ArrayList<>();
				for (String dependency : dependencies)
				{
					Path resolved = file.getParent().resolve(dependency).normalize();
					if (!Files.isRegularFile(resolved, NOFOLLOW_LINKS))
						throw new IOException("Lazy rule dependency is not available: " + path + " depends on " + dependency);
					if (resolved.startsWith(include))
						lazy.add(relative(projectRoot, resolved));
				}
				if (!lazy.isEmpty())
					routes.add("When lazy loading `" + path + "`, first lazy load `" + String.join("` and `", lazy) + "`.");
			}
		}
		if (routes.isEmpty())
			return "";
		return "# CAT Lazy Rule Dependencies\n\n" +
			"Before lazy loading a listed rule, first lazy load its listed prerequisites.\n\n" +
			String.join("\n", routes);
	}

	/**
	 * Loads one direct rule directory.
	 *
	 * @param projectRoot a non-null project root
	 * @param directory a non-null rule directory
	 * @param root the source root that contains {@code directory}
	 * @param audience the recipient whose rules are loaded
	 * @param rules a non-null list of discovered rules
	 * @throws IOException if the directory is not a real directory or one of its rules is invalid
	 */
	private void loadDirectory(Path projectRoot, Path directory, RuleRoot root, RuleAudience audience, List<Rule> rules)
		throws IOException
	{
		if (!Files.exists(directory, NOFOLLOW_LINKS))
			return;
		if (Files.isSymbolicLink(directory))
		{
			throw new IOException("CAT rule directory is a symbolic link: " + relative(projectRoot, directory) +
				". Replace the symbolic link with a real directory inside the project.");
		}
		if (!Files.isDirectory(directory, NOFOLLOW_LINKS))
		{
			throw new IOException("CAT rule directory is not a directory: " + relative(projectRoot, directory) +
				". Replace it with a directory containing CAT rule files.");
		}
		try (Stream<Path> entries = Files.list(directory))
		{
			for (Path file : entries.filter(path -> path.getFileName().toString().endsWith(".md")).sorted(
				Comparator.comparing(this::ruleOrder).thenComparing(path -> path.getFileName().toString())).toList())
			{
				Rule rule = render(projectRoot, file, root, audience);
				if (rule != null)
					rules.add(rule);
			}
		}
	}

	/**
	 * Orders the direct-routing rule before supporting guidance so lazy routes are visible before a harness plans work.
	 *
	 * @param path a direct rule path
	 * @return the rule's stable sort key
	 */
	private String ruleOrder(Path path)
	{
		assert path != null;
		if (path.getFileName().toString().equals("default-rules.md"))
			return "";
		return path.getFileName().toString();
	}

	/**
	 * Renders one rule as immediate or lazy context.
	 *
	 * @param projectRoot a non-null project root
	 * @param file a non-null rule file
	 * @param root the source root that contains {@code file}
	 * @param audience the recipient whose rules are loaded
	 * @return the rule's dependency metadata and context block
	 * @throws IOException if the rule is not a regular file, is too large, or has invalid content
	 */
	private Rule render(Path projectRoot, Path file, RuleRoot root, RuleAudience audience) throws IOException
	{
		if (Files.isSymbolicLink(file))
		{
			throw new IOException("CAT rule must be a regular file, not a symbolic link: " + relative(projectRoot, file) +
				". Replace the symbolic link with a regular Markdown rule file inside the project.");
		}
		if (!Files.isRegularFile(file, NOFOLLOW_LINKS))
		{
			throw new IOException("CAT rule must be a regular file: " + relative(projectRoot, file) +
				". Replace it with a regular Markdown rule file inside the project.");
		}
		if (Files.size(file) > MAXIMUM_RULE_BYTES)
			throw new IOException("CAT rule is too large: " + relative(projectRoot, file));
		String content = Files.readString(file, UTF_8);
		String path = relative(projectRoot, file);
		Frontmatter frontmatter = frontmatter(content, path);
		if (!matches(frontmatter, path, audience))
			return null;
		JsonNode paths = null;
		if (frontmatter != null)
			paths = frontmatter.metadata().get("paths");
		if (paths == null)
		{
			Path ruleRoot = projectRoot.resolve(".cat/rules");
			if (root == RuleRoot.PLUGIN)
				ruleRoot = projectRoot.resolve("rules");
			String body = content;
			if (frontmatter != null)
				body = frontmatter.body();
			return new Rule(root, path, true, dependencies(frontmatter, path), injectedBody(null,
				expandDirectIncludes(body, file, ruleRoot, new HashSet<>()), file, ruleRoot));
		}
		if (!paths.isArray())
			throw new IOException("Invalid paths frontmatter: " + path);
		if (!dependencies(frontmatter, path).isEmpty())
			throw new IOException("Path-scoped rule cannot declare depends-on: " + path);
		List<String> globs = new ArrayList<>();
		for (JsonNode glob : paths)
		{
			if (!glob.isString() || glob.asString().isBlank())
				throw new IOException("Invalid paths frontmatter: " + path);
			globs.add(glob.asString());
		}
		return new Rule(root, path, false, List.of(), "Load `" + path +
			"` only when an accessed file matches " + globs + ".");
	}

	/**
	 * Returns a dependency-first ordering that otherwise preserves directory and filename order.
	 *
	 * @param rules the discovered rules
	 * @return the rules in injection order
	 * @throws IOException if a dependency is missing or cyclic
	 */
	private static List<Rule> ordered(List<Rule> rules) throws IOException
	{
		Map<String, Rule> byPath = new HashMap<>();
		for (Rule rule : rules)
		{
			if (rule.direct())
				byPath.put(rule.path(), rule);
		}

		List<Rule> result = new ArrayList<>();
		Set<String> visiting = new HashSet<>();
		Set<String> visited = new HashSet<>();
		for (Rule rule : rules)
			addWithDependencies(rule, byPath, visiting, visited, result);
		return result;
	}

	/**
	 * Adds a rule after recursively adding every declared dependency.
	 *
	 * @param rule the rule to add
	 * @param byPath rules indexed by their source paths
	 * @param visiting paths in the current dependency chain
	 * @param visited paths already added to {@code result}
	 * @param result ordered rules
	 * @throws IOException if a dependency is missing or cyclic
	 */
	private static void addWithDependencies(Rule rule, Map<String, Rule> byPath, Set<String> visiting,
		Set<String> visited, List<Rule> result) throws IOException
	{
		if (visited.contains(rule.path()))
			return;
		if (!visiting.add(rule.path()))
			throw new IOException("Cyclic rule dependency: " + rule.path());
		for (String dependency : rule.dependencies())
		{
			String dependencyPath = resolveDependency(rule, dependency);
			Rule prerequisite = byPath.get(dependencyPath);
			if (prerequisite == null)
			{
				throw new IOException("Rule dependency is not available: " + rule.path() + " depends on " +
					dependency);
			}
			addWithDependencies(prerequisite, byPath, visiting, visited, result);
		}
		visiting.remove(rule.path());
		visited.add(rule.path());
		result.add(rule);
	}

	/**
	 * Resolves one dependency relative to its declaring rule without allowing it to leave that rule's source root.
	 *
	 * @param rule the rule that declares the dependency
	 * @param dependency the declared relative dependency path
	 * @return the source-root-relative dependency path
	 * @throws IOException if the dependency is absolute or leaves the declaring rule's source root
	 */
	private static String resolveDependency(Rule rule, String dependency) throws IOException
	{
		Path declaredPath = Path.of(dependency);
		if (declaredPath.isAbsolute())
			throw new IOException("Rule dependency must be relative: " + rule.path() + " depends on " + dependency);
		Path resolvedPath = Path.of(rule.path()).getParent().resolve(declaredPath).normalize();
		String resolved = resolvedPath.toString().replace(resolvedPath.getFileSystem().getSeparator(), "/");
		if (!resolved.equals(rule.root().path()) && !resolved.startsWith(rule.root().path() + "/"))
		{
			throw new IOException("Rule dependency leaves its source root: " + rule.path() + " depends on " +
				dependency);
		}
		return resolved;
	}

	/**
	 * Reads optional {@code depends-on} metadata.
	 *
	 * @param frontmatter parsed rule frontmatter, if present
	 * @param path the rule source path
	 * @return declared prerequisite source paths
	 * @throws IOException if the metadata is malformed
	 */
	private static List<String> dependencies(Frontmatter frontmatter, String path) throws IOException
	{
		if (frontmatter == null)
			return List.of();
		JsonNode dependencies = frontmatter.metadata().get("depends-on");
		if (dependencies == null)
			return List.of();
		if (!dependencies.isArray())
			throw new IOException("Invalid depends-on frontmatter: " + path);
		List<String> result = new ArrayList<>();
		for (JsonNode dependency : dependencies)
		{
			if (!dependency.isString() || dependency.asString().isBlank())
				throw new IOException("Invalid depends-on frontmatter: " + path);
			result.add(dependency.asString());
		}
		return List.copyOf(result);
	}

	/**
	 * Indicates whether rule frontmatter addresses the current recipient.
	 *
	 * @param frontmatter parsed rule frontmatter, if present
	 * @param path the rule source path
	 * @param audience the current recipient
	 * @return {@code true} if the rule applies
	 * @throws IOException if {@code agents} metadata is malformed
	 */
	private static boolean matches(Frontmatter frontmatter, String path, RuleAudience audience) throws IOException
	{
		if (frontmatter == null)
			return true;
		JsonNode agents = frontmatter.metadata().get("agents");
		if (agents == null)
			return true;
		if (!agents.isArray() || agents.isEmpty())
			throw new IOException("Invalid agents frontmatter: " + path);
		boolean allSubagents = false;
		boolean namedSubagent = false;
		for (JsonNode agent : agents)
		{
			if (!agent.isString() || agent.asString().isBlank())
				throw new IOException("Invalid agents frontmatter: " + path);
			String name = agent.asString().strip();
			if (name.equals("main"))
			{
				if (audience.main())
					return true;
				continue;
			}
			if (name.equals("subagents"))
			{
				allSubagents = true;
				continue;
			}
			namedSubagent = true;
			if (!audience.main() && name.equals(audience.agentType()))
				return true;
		}
		if (allSubagents && namedSubagent)
			throw new IOException("Invalid agents frontmatter: " + path +
				" must not combine subagents with named subagent types");
		return !audience.main() && allSubagents;
	}

	/**
	 * Removes source-only design goals and trailing line breaks from injected rule content.
	 *
	 * @param frontmatter parsed rule frontmatter, if present
	 * @param content a non-null rule body
	 * @param file the direct rule's source file
	 * @param ruleRoot the rule tree that bounds available lazy rules
	 * @return the rule content to inject
	 */
	private static String injectedBody(Frontmatter frontmatter, String content, Path file, Path ruleRoot)
	{
		String body = content;
		if (frontmatter != null)
			body = frontmatter.body();
		String withoutDesignGoals = DESIGN_GOALS.matcher(body).replaceFirst("");
		int end = withoutDesignGoals.length();
		while (end > 0 && (withoutDesignGoals.charAt(end - 1) == '\n' || withoutDesignGoals.charAt(end - 1) == '\r'))
			--end;
		return resolveLazyRuleReferences(withoutDesignGoals.substring(0, end), file, ruleRoot);
	}

	/**
	 * Expands source-only Markdown bodies referenced by a direct rule.
	 * <p>
	 * This is performed at runtime because project rules can contain {@code cat:include} directives and do not exist when
	 * the plugin artifact is built.
	 *
	 * @param content the direct rule body without its frontmatter
	 * @param file the file that contains {@code content}
	 * @param ruleRoot the source-root boundary for included files
	 * @param expanding files in the active include chain
	 * @return the expanded rule body
	 * @throws IOException if an include is ambiguous, unsafe, cyclic, or unreadable
	 */
	private String expandDirectIncludes(String content, Path file, Path ruleRoot, Set<Path> expanding) throws IOException
	{
		Path normalized = file.normalize();
		if (!expanding.add(normalized))
			throw new IOException("Cyclic direct rule include: " + normalized);
		Matcher matcher = MarkdownIncludes.DIRECTIVE.matcher(content);
		StringBuffer result = new StringBuffer(content.length());
		while (matcher.find())
		{
			Path included = MarkdownIncludes.resolve(file, matcher.group(1), "Direct rule include", ruleRoot);
			if (Files.size(included) > MAXIMUM_RULE_BYTES)
				throw new IOException("Direct rule include is too large: " + included);
			String includedContent = Files.readString(included, UTF_8);
			Frontmatter includedFrontmatter = frontmatter(includedContent, included.toString());
			if (includedFrontmatter != null)
			{
				throw new IOException("Direct rule include must name source-only Markdown without frontmatter: " +
					included);
			}
			String expanded = expandDirectIncludes(includedContent, included, ruleRoot, expanding);
			expanded = injectedBody(null, expanded, included, ruleRoot);
			matcher.appendReplacement(result, Matcher.quoteReplacement(expanded));
		}
		matcher.appendTail(result);
		expanding.remove(normalized);
		return result.toString();
	}

	/**
	 * Replaces a direct rule's references to included rules with paths that remain usable after its source location is
	 * removed from injected context.
	 *
	 * @param content the injected direct-rule content
	 * @param file the direct rule's source file
	 * @param ruleRoot the rule tree that bounds available lazy rules
	 * @return context with resolvable lazy-rule paths
	 */
	private static String resolveLazyRuleReferences(String content, Path file, Path ruleRoot)
	{
		Matcher matcher = LAZY_RULE_REFERENCE.matcher(content);
		StringBuffer result = new StringBuffer(content.length());
		while (matcher.find())
		{
			Path declared;
			try
			{
				declared = Path.of(matcher.group(1));
			}
		catch (java.nio.file.InvalidPathException _)
			{
				continue;
			}
			if (declared.isAbsolute())
				continue;
			Path resolved = file.getParent().resolve(declared).normalize();
			if (!resolved.startsWith(ruleRoot) || !Files.isRegularFile(resolved, NOFOLLOW_LINKS))
				continue;
			matcher.appendReplacement(result, Matcher.quoteReplacement("`" + resolved + "`"));
		}
		matcher.appendTail(result);
		return result.toString();
	}

	/**
	 * Parses optional YAML frontmatter.
	 *
	 * @param content non-null rule content
	 * @param path a non-blank project-relative rule path
	 * @return the frontmatter and body, or {@code null}
	 * @throws IOException if the frontmatter is malformed
	 */
	private Frontmatter frontmatter(String content, String path) throws IOException
	{
		assert that(path, "path").isNotBlank().elseThrow();
		if (!content.startsWith("---\n") && !content.startsWith("---\r\n"))
			return null;
		String lineEnding = "\n";
		if (content.startsWith("---\r\n"))
			lineEnding = "\r\n";
		int frontmatterStart = 3 + lineEnding.length();
		String delimiter = lineEnding + "---" + lineEnding;
		int end = content.indexOf(delimiter, frontmatterStart);
		if (end < 0)
			throw new IOException("Unterminated frontmatter: " + path);
		try
		{
			JsonNode result = yamlMapper.readTree(content.substring(frontmatterStart, end));
			if (result == null || !result.isObject())
				throw new IOException("Invalid frontmatter: " + path);
			return new Frontmatter(result, content.substring(end + delimiter.length()));
		}
		catch (IllegalArgumentException e)
		{
			throw new IOException("Invalid frontmatter: " + path, e);
		}
	}

	/**
	 * Returns a slash-separated project-relative path.
	 *
	 * @param projectRoot a non-null project root
	 * @param path a non-null path below the project root
	 * @return the project-relative path
	 */
	private static String relative(Path projectRoot, Path path)
	{
		return projectRoot.relativize(path).toString().replace(path.getFileSystem().getSeparator(), "/");
	}

	private record Frontmatter(JsonNode metadata, String body)
	{
	}

	/**
	 * Identifies the agent context that receives a rule.
	 *
	 * @param main whether the recipient is the primary session agent
	 * @param agentType the host-provided subagent type, blank for the primary session agent
	 */
	private record RuleAudience(boolean main, String agentType)
	{
		/**
		 * Returns the primary-session recipient.
		 *
		 * @return the primary-session recipient
		 */
		private static RuleAudience primary()
		{
			return new RuleAudience(true, "");
		}

		/**
		 * Returns one named subagent recipient.
		 *
		 * @param agentType the host-provided subagent type
		 * @return the named subagent recipient
		 */
		private static RuleAudience subagent(String agentType)
		{
			return new RuleAudience(false, agentType);
		}
	}

	private enum RuleRoot
	{
		PLUGIN("rules"),
		PROJECT(".cat/rules");

		private final String path;

		/**
		 * Creates a rule source root.
		 *
		 * @param path the root's rendered path
		 */
		RuleRoot(String path)
		{
			this.path = path;
		}

		/**
		 * Returns this root's rendered path.
		 *
		 * @return the rendered path
		 */
		private String path()
		{
			return path;
		}
	}

	private record Rule(RuleRoot root, String path, boolean direct, List<String> dependencies, String context)
	{
	}
}
