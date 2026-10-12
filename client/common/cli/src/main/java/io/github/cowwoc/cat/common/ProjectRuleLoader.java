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
import java.util.StringJoiner;
import java.util.stream.Stream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Loads CAT project rules for a harness, placing each declared prerequisite before the rule that needs it.
 * <p>
 * In the context of a bundled rule, each {@link #PLUGIN_ROOT_DIRECTIVE} becomes the absolute, normalized CAT
 * installation directory that the caller supplied. The harness skill environment, which states that directory,
 * precedes every other rule.
 */
public final class ProjectRuleLoader
{
	/**
	 * The directive that a bundled rule writes where the injected context must state CAT's installation directory.
	 * <p>
	 * The loader replaces it in rendered bodies from the plugin's rule tree, including the source-only Markdown they
	 * include. Project and user rules receive it unchanged.
	 */
	public static final String PLUGIN_ROOT_DIRECTIVE = "<!-- cat:plugin-root -->";

	/**
	 * The notation that path-routing context writes for the project directory. The injected CAT environment rule
	 * tells the agent which directory it stands for.
	 */
	private static final String PROJECT_ROOT_REFERENCE = "${CAT_PROJECT_ROOT}";
	/**
	 * The filename of the bundled harness rule that defines the {@code CAT_} names, such as CAT's installation
	 * directory. The loader injects it before every other rule, so each name is defined before a rule refers to it.
	 */
	private static final String ENVIRONMENT_RULE = "cat-env.md";
	/**
	 * The filename of the bundled harness rule that tells the agent how to read a file whole. The loader injects it
	 * right after the environment rule, before every rule that asks the agent to read a file, such as a lazy-load
	 * directive.
	 */
	private static final String READ_FILE_RULE = "read-file.md";
	static final int MAXIMUM_RULE_BYTES = 1_048_576;
	private static final Pattern DESIGN_GOALS = Pattern.compile("(?ms)^## Design Goals\\R.*?(?=^## |\\z)");
	private static final Pattern LAZY_RULE_REFERENCE = Pattern.compile("`([^`]+\\.md)`");
	private static final String RULES_DIRECTORY = ".cat/rules";
	private static final Set<String> FRONTMATTER_KEYS = Set.of("agents", "depends-on", "paths");

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
	 * Returns the directory beneath a project or home directory that holds its CAT rules.
	 *
	 * @param root a non-null project or home directory
	 * @return the rule directory
	 * @throws NullPointerException if {@code root} is null
	 */
	public static Path rulesDirectory(Path root)
	{
		requireThat(root, "root").isNotNull();
		return root.resolve(RULES_DIRECTORY);
	}

	/**
	 * Renders a path trigger whose globs apply only within the project that {@code ${CAT_PROJECT_ROOT}} names.
	 *
	 * @param rule the rule path, absolute or containing an installation directive
	 * @param globs the project-relative path patterns
	 * @return the one-sentence lazy-loading instruction
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if {@code rule} is blank
	 */
	public static String pathScopedInstruction(String rule, List<String> globs)
	{
		requireThat(rule, "rule").isNotBlank();
		requireThat(globs, "globs").isNotNull();
		return "Load `" + rule + "` only when you read or edit a file inside the project `" + PROJECT_ROOT_REFERENCE +
			"` whose path relative to it matches " + globs + ".";
	}

	/**
	 * Renders an explicitly selected installed rule as a complete body with absolute plugin references.
	 *
	 * @param pluginRoot a non-null CAT installation directory
	 * @param rule a non-null rule path relative to the installation's {@code rules} directory
	 * @return the frontmatter-free rule body with direct includes expanded
	 * @throws NullPointerException if either argument is null
	 * @throws IllegalArgumentException if {@code rule} is absolute
	 * @throws IOException if the rule leaves the rule directory, is unavailable, or has invalid content
	 */
	public String loadRule(Path pluginRoot, Path rule) throws IOException
	{
		requireThat(pluginRoot, "pluginRoot").isNotNull();
		requireThat(rule, "rule").isNotNull();
		requireThat(rule.isAbsolute(), "rule.isAbsolute()").isFalse();
		Path absoluteRoot = pluginRoot.toAbsolutePath().normalize();
		Path ruleRoot = absoluteRoot.resolve("rules");
		Path file = ruleRoot.resolve(rule).normalize();
		if (!file.startsWith(ruleRoot))
			throw new IOException("CAT rule leaves its installed rule directory: " + file);
		String content = readRule(absoluteRoot, file, RuleRoot.PLUGIN);
		Frontmatter frontmatter = frontmatter(content, file.toString());
		return renderBody(absoluteRoot, file, ruleRoot, RuleRoot.PLUGIN, frontmatter, content).
			replace("${CAT_PLUGIN_ROOT}", absoluteRoot.toString());
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
		return load(pluginRoot, null, projectRoot, harness, RuleAudience.primary());
	}

	/**
	 * Loads all direct rules addressed to a harness's primary agent and their declared prerequisites.
	 *
	 * @param pluginRoot a non-null CAT installation directory
	 * @param userRulesRoot the optional non-repository rule directory owned by the current user
	 * @param projectRoot a non-null directory whose {@code .cat/rules} subdirectories are loaded
	 * @param harness a non-blank lowercase harness identifier
	 * @return the generated context
	 * @throws NullPointerException if {@code pluginRoot}, {@code projectRoot}, or {@code harness} are null
	 * @throws IllegalArgumentException if {@code harness} is blank or invalid
	 * @throws IOException if a rule directory or file is not a real directory or regular file, or its content is invalid
	 */
	public String loadForMainAgent(Path pluginRoot, Path userRulesRoot, Path projectRoot, String harness)
		throws IOException
	{
		return load(pluginRoot, userRulesRoot, projectRoot, harness, RuleAudience.primary());
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
		return load(pluginRoot, null, projectRoot, harness, RuleAudience.subagent(agentType));
	}

	/**
	 * Loads all direct rules addressed to one harness subagent and their declared prerequisites.
	 *
	 * @param pluginRoot a non-null CAT installation directory
	 * @param userRulesRoot the optional non-repository rule directory owned by the current user
	 * @param projectRoot a non-null directory whose {@code .cat/rules} subdirectories are loaded
	 * @param harness a non-blank lowercase harness identifier
	 * @param agentType the harness subagent type, or blank when the host did not provide one
	 * @return the generated context
	 * @throws NullPointerException if {@code pluginRoot}, {@code projectRoot}, {@code harness}, or {@code agentType}
	 *         are null
	 * @throws IllegalArgumentException if {@code harness} is blank or invalid
	 * @throws IOException if a rule directory or file is not a real directory or regular file, or its content is invalid
	 */
	public String loadForSubagent(Path pluginRoot, Path userRulesRoot, Path projectRoot, String harness,
		String agentType) throws IOException
	{
		requireThat(agentType, "agentType").isNotNull();
		return load(pluginRoot, userRulesRoot, projectRoot, harness, RuleAudience.subagent(agentType));
	}

	/**
	 * Loads all direct rules addressed to one recipient and their declared prerequisites.
	 *
	 * @param pluginRoot a non-null CAT installation directory
	 * @param userRulesRoot the optional non-repository rule directory owned by the current user
	 * @param projectRoot a non-null directory whose {@code .cat/rules} subdirectories are loaded
	 * @param harness a non-blank lowercase harness identifier
	 * @param audience the rule recipient
	 * @return the generated context
	 * @throws IOException if a rule directory or file is not a real directory or regular file, or its content is invalid
	 */
	private String load(Path pluginRoot, Path userRulesRoot, Path projectRoot, String harness, RuleAudience audience)
		throws IOException
	{
		requireThat(harness, "harness").isNotBlank();
		if (!harness.matches("[a-z][a-z0-9-]*"))
			throw new IllegalArgumentException("Invalid CAT harness: " + harness);
		List<Rule> rules = new ArrayList<>();
		Path pluginRules = pluginRoot.resolve("rules");
		loadDirectory(pluginRoot, pluginRules.resolve("common"), pluginRules, RuleRoot.PLUGIN, audience,
			rules);
		loadDirectory(pluginRoot, pluginRules.resolve(harness), pluginRules, RuleRoot.PLUGIN,
			audience, rules);
		if (userRulesRoot != null)
		{
			loadDirectory(userRulesRoot, userRulesRoot.resolve("common"), userRulesRoot, RuleRoot.USER,
				audience, rules);
			loadDirectory(userRulesRoot, userRulesRoot.resolve(harness), userRulesRoot, RuleRoot.USER,
				audience, rules);
		}
		Path projectRules = rulesDirectory(projectRoot);
		loadDirectory(projectRoot, projectRules.resolve("common"), projectRules, RuleRoot.PROJECT,
			audience, rules);
		loadDirectory(projectRoot, projectRules.resolve(harness), projectRules, RuleRoot.PROJECT,
			audience, rules);
		List<Rule> ordered = ordered(rules);
		List<String> leading = List.of("rules/" + harness + "/" + ENVIRONMENT_RULE,
			"rules/" + harness + "/" + READ_FILE_RULE);
		ordered.sort(Comparator.comparingInt(rule -> leadingRank(rule, leading)));
		List<String> context = new ArrayList<>(ordered.stream().map(Rule::context).toList());
		if (userRulesRoot != null)
		{
			String userLazyDependencies = lazyDependencies(userRulesRoot, userRulesRoot, RuleRoot.USER, audience);
			if (!userLazyDependencies.isBlank())
				context.add(userLazyDependencies);
		}
		String lazyDependencies = lazyDependencies(projectRoot, projectRules, RuleRoot.PROJECT, audience);
		if (!lazyDependencies.isBlank())
			context.add(lazyDependencies);
		return String.join("\n\n---\n", context);
	}

	/**
	 * Returns where a rule goes among the rules that the loader injects first.
	 *
	 * @param rule    the rule
	 * @param leading the plugin rule paths that go first, in their injection order
	 * @return the index of the rule's path in {@code leading} when the rule is a plugin rule, otherwise the size of
	 *         {@code leading}, which places the rule after them
	 */
	private static int leadingRank(Rule rule, List<String> leading)
	{
		if (rule.root() != RuleRoot.PLUGIN)
			return leading.size();
		int index = leading.indexOf(rule.path());
		if (index == -1)
			return leading.size();
		return index;
	}

	/**
	 * Renders dependency-first lazy-loading instructions for one non-bundled rule source's included rules.
	 *
	 * @param ownerRoot the directory that owns the source
	 * @param ruleRoot the root that bounds the source's rule tree
	 * @param root the source kind
	 * @param audience the recipient whose routes are loaded
	 * @return direct context for lazy dependency routes, or an empty string when none exist
	 * @throws IOException if declared lazy-rule metadata is invalid or names an unavailable file
	 */
	private String lazyDependencies(Path ownerRoot, Path ruleRoot, RuleRoot root, RuleAudience audience)
		throws IOException
	{
		Path include = ruleRoot.resolve("include");
		if (!Files.isDirectory(include, NOFOLLOW_LINKS))
			return "";
		List<String> routes = new ArrayList<>();
		try (Stream<Path> files = Files.walk(include))
		{
			for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".md")).sorted().toList())
			{
				String path = rulePath(ownerRoot, ruleRoot, root, file);
				Frontmatter frontmatter = frontmatter(Files.readString(file, UTF_8), path);
				if (!matches(frontmatter, path, audience))
					continue;
				List<String> dependencies = dependencies(frontmatter, path);
				List<String> lazy = new ArrayList<>();
				for (String dependency : dependencies)
				{
					Path resolved = file.getParent().resolve(dependency).normalize();
					if (!Files.isRegularFile(resolved, NOFOLLOW_LINKS))
						throw new IOException("Lazy rule dependency is not available: " + displayPath(ownerRoot, root,
							file) + " depends on " + dependency);
					if (resolved.startsWith(include))
						lazy.add(displayPath(ownerRoot, root, resolved));
				}
				if (!lazy.isEmpty())
					routes.add("When lazy loading `" + displayPath(ownerRoot, root, file) +
						"`, first lazy load `" + String.join("` and `", lazy) + "`.");
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
	 * @param ownerRoot a non-null rule-source owner directory
	 * @param directory a non-null rule directory
	 * @param ruleRoot the source root that bounds included rules
	 * @param root the source kind that contains {@code directory}
	 * @param audience the recipient whose rules are loaded
	 * @param rules a non-null list of discovered rules
	 * @throws IOException if the directory is not a real directory or one of its rules is invalid
	 */
	private void loadDirectory(Path ownerRoot, Path directory, Path ruleRoot, RuleRoot root,
		RuleAudience audience, List<Rule> rules)
		throws IOException
	{
		if (!Files.exists(directory, NOFOLLOW_LINKS))
			return;
		if (Files.isSymbolicLink(directory))
		{
			throw new IOException("CAT rule directory is a symbolic link: " + displayPath(ownerRoot, root, directory) +
				". Replace the symbolic link with a real directory inside the rule root.");
		}
		if (!Files.isDirectory(directory, NOFOLLOW_LINKS))
		{
			throw new IOException("CAT rule directory is not a directory: " + displayPath(ownerRoot, root, directory) +
				". Replace it with a directory containing CAT rule files inside the rule root.");
		}
		try (Stream<Path> entries = Files.list(directory))
		{
			for (Path file : entries.filter(path -> path.getFileName().toString().endsWith(".md")).sorted(
				Comparator.comparing(this::ruleOrder).thenComparing(path -> path.getFileName().toString())).toList())
			{
				Rule rule = render(ownerRoot, file, ruleRoot, root, audience);
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
	 * @param ownerRoot a non-null rule-source owner directory. For a bundled rule, it is the CAT installation directory
	 *   that replaces each {@link #PLUGIN_ROOT_DIRECTIVE} in the rule's context.
	 * @param file a non-null rule file
	 * @param ruleRoot the source root that bounds included rules
	 * @param root the source kind that contains {@code file}
	 * @param audience the recipient whose rules are loaded
	 * @return the rule's dependency metadata and context block
	 * @throws IOException if the rule is not a regular file, is too large, or has invalid content
	 */
	private Rule render(Path ownerRoot, Path file, Path ruleRoot, RuleRoot root, RuleAudience audience)
		throws IOException
	{
		String content = readRule(ownerRoot, file, root);
		String path = rulePath(ownerRoot, ruleRoot, root, file);
		Frontmatter frontmatter = frontmatter(content, path);
		if (!matches(frontmatter, path, audience))
			return null;
		JsonNode paths = null;
		if (frontmatter != null)
			paths = frontmatter.metadata().get("paths");
		if (paths == null)
		{
			String injected = renderBody(ownerRoot, file, ruleRoot, root, frontmatter, content);
			return new Rule(root, path, displayPath(ownerRoot, root, file), true, dependencies(frontmatter, path),
				injected);
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
		return new Rule(root, path, displayPath(ownerRoot, root, file), false, List.of(), pathScopedInstruction(
			file.toAbsolutePath().normalize().toString(), globs));
	}

	/**
	 * Reads a bounded regular rule file.
	 *
	 * @param ownerRoot the rule-source owner directory
	 * @param file the rule file
	 * @param root the source kind
	 * @return the UTF-8 rule content
	 * @throws IOException if the file is unavailable, symbolic, or too large
	 */
	private static String readRule(Path ownerRoot, Path file, RuleRoot root) throws IOException
	{
		if (Files.isSymbolicLink(file))
		{
			throw new IOException("CAT rule must be a regular file, not a symbolic link: " + displayPath(ownerRoot, root,
				file) +
				". Replace the symbolic link with a regular Markdown rule file inside the rule root.");
		}
		if (!Files.isRegularFile(file, NOFOLLOW_LINKS))
		{
			throw new IOException("CAT rule must be a regular file: " + displayPath(ownerRoot, root, file) +
				". Replace it with a regular Markdown rule file inside the rule root.");
		}
		if (Files.size(file) > MAXIMUM_RULE_BYTES)
			throw new IOException("CAT rule is too large: " + displayPath(ownerRoot, root, file));
		return Files.readString(file, UTF_8);
	}

	/**
	 * Renders a rule's body using the same includes and references as injected session context.
	 *
	 * @param ownerRoot the rule-source owner directory
	 * @param file the rule file
	 * @param ruleRoot the source root that bounds included rules
	 * @param root the source kind
	 * @param frontmatter the optional parsed metadata
	 * @param content the complete source text
	 * @return the rendered rule body
	 * @throws IOException if direct includes cannot be expanded
	 */
	private String renderBody(Path ownerRoot, Path file, Path ruleRoot, RuleRoot root, Frontmatter frontmatter,
		String content) throws IOException
	{
		String body = content;
		if (frontmatter != null)
			body = frontmatter.body();
		String injected = injectedBody(null, expandDirectIncludes(body, file, ruleRoot, root, new HashSet<>()),
			file, ruleRoot, root);
		if (root == RuleRoot.PLUGIN)
			injected = injected.replace(PLUGIN_ROOT_DIRECTIVE, ownerRoot.toAbsolutePath().normalize().toString());
		return injected;
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
			throw new IOException("Cyclic rule dependency: " + rule.displayPath());
		for (String dependency : rule.dependencies())
		{
			String dependencyPath = resolveDependency(rule, dependency);
			Rule prerequisite = byPath.get(dependencyPath);
			if (prerequisite == null)
			{
				throw new IOException("Rule dependency is not available: " + rule.displayPath() + " depends on " +
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
			throw new IOException("Rule dependency must be relative: " + rule.displayPath() + " depends on " + dependency);
		Path resolvedPath = Path.of(rule.path()).getParent().resolve(declaredPath).normalize();
		String resolved = resolvedPath.toString().replace(resolvedPath.getFileSystem().getSeparator(), "/");
		if (!resolved.equals(rule.root().path()) && !resolved.startsWith(rule.root().path() + "/"))
		{
			throw new IOException("Rule dependency leaves its source root: " + rule.displayPath() + " depends on " +
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
	 * @param root the source kind of {@code ruleRoot}
	 * @return the rule content to inject
	 */
	private static String injectedBody(Frontmatter frontmatter, String content, Path file, Path ruleRoot,
		RuleRoot root)
	{
		String body = content;
		if (frontmatter != null)
			body = frontmatter.body();
		String withoutDesignGoals = DESIGN_GOALS.matcher(body).replaceFirst("");
		int end = withoutDesignGoals.length();
		while (end > 0 && (withoutDesignGoals.charAt(end - 1) == '\n' || withoutDesignGoals.charAt(end - 1) == '\r'))
			--end;
		return resolveLazyRuleReferences(withoutDesignGoals.substring(0, end), file, ruleRoot, root);
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
	 * @param root the source kind of {@code ruleRoot}
	 * @param expanding files in the active include chain
	 * @return the expanded rule body
	 * @throws IOException if an include is ambiguous, unsafe, cyclic, or unreadable
	 */
	private String expandDirectIncludes(String content, Path file, Path ruleRoot, RuleRoot root, Set<Path> expanding)
		throws IOException
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
			String expanded = expandDirectIncludes(includedContent, included, ruleRoot, root, expanding);
			expanded = injectedBody(null, expanded, included, ruleRoot, root);
			matcher.appendReplacement(result, Matcher.quoteReplacement(expanded));
		}
		matcher.appendTail(result);
		expanding.remove(normalized);
		return result.toString();
	}

	/**
	 * Replaces a direct rule's references to included rules with paths that remain usable after its source location is
	 * removed from injected context.
	 * <p>
	 * A bundled rule's reference is rendered below {@code ${CAT_PLUGIN_ROOT}/rules}, because the injected skill
	 * environment states the plugin directory once; other references are rendered as absolute paths.
	 *
	 * @param content the injected direct-rule content
	 * @param file the direct rule's source file
	 * @param ruleRoot the rule tree that bounds available lazy rules
	 * @param root the source kind of {@code ruleRoot}
	 * @return context with resolvable lazy-rule paths
	 */
	private static String resolveLazyRuleReferences(String content, Path file, Path ruleRoot, RuleRoot root)
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
			matcher.appendReplacement(result, Matcher.quoteReplacement("`" + lazyRuleReference(resolved, ruleRoot, root) +
				"`"));
		}
		matcher.appendTail(result);
		return result.toString();
	}

	/**
	 * Renders the path of an included rule for injected context.
	 *
	 * @param rule the normalized included rule
	 * @param ruleRoot the rule tree that contains {@code rule}
	 * @param root the source kind of {@code ruleRoot}
	 * @return {@code ${CAT_PLUGIN_ROOT}/rules/} followed by the rule's slash-separated path below {@code ruleRoot} for a
	 *   bundled rule, otherwise the rule's absolute path
	 */
	private static String lazyRuleReference(Path rule, Path ruleRoot, RuleRoot root)
	{
		if (root != RuleRoot.PLUGIN)
			return rule.toString();
		StringJoiner reference = new StringJoiner("/", "${CAT_PLUGIN_ROOT}/rules/", "");
		for (Path name : ruleRoot.normalize().relativize(rule))
			reference.add(name.toString());
		return reference.toString();
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
			for (Map.Entry<String, JsonNode> property : result.properties())
			{
				if (!FRONTMATTER_KEYS.contains(property.getKey()))
				{
					throw new IOException("Unknown frontmatter key " + property.getKey() + " in " + path +
						"; rule frontmatter supports agents, depends-on and paths");
				}
			}
			return new Frontmatter(result, content.substring(end + delimiter.length()));
		}
		catch (IllegalArgumentException e)
		{
			throw new IOException("Invalid frontmatter: " + path, e);
		}
	}

	/**
	 * Returns a slash-separated path relative to one source root.
	 *
	 * @param root a non-null source root
	 * @param path a non-null path below the source root
	 * @return the source-root-relative path
	 */
	private static String relative(Path root, Path path)
	{
		return root.relativize(path).toString().replace(path.getFileSystem().getSeparator(), "/");
	}

	/**
	 * Returns the internal source-root-relative identity of a rule.
	 *
	 * @param ownerRoot the directory that owns this rule source
	 * @param ruleRoot the root that bounds this source's rule tree
	 * @param root the source kind
	 * @param file the rule file
	 * @return a source-unique rule identity
	 */
	private static String rulePath(Path ownerRoot, Path ruleRoot, RuleRoot root, Path file)
	{
		assert ownerRoot != null;
		assert ruleRoot != null;
		assert root != null;
		assert file != null;
		if (root == RuleRoot.USER)
			return root.path() + "/" + relative(ruleRoot, file);
		return relative(ownerRoot, file);
	}

	/**
	 * Returns the path a recipient can use to read a rule.
	 *
	 * @param ownerRoot the directory that owns this rule source
	 * @param root the source kind
	 * @param file the rule file
	 * @return a reader-visible path
	 */
	private static String displayPath(Path ownerRoot, RuleRoot root, Path file)
	{
		assert ownerRoot != null;
		assert root != null;
		assert file != null;
		if (root == RuleRoot.USER)
			return file.toAbsolutePath().normalize().toString();
		return relative(ownerRoot, file);
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
		USER("user-rules"),
		PROJECT(RULES_DIRECTORY);

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

	private record Rule(RuleRoot root, String path, String displayPath, boolean direct, List<String> dependencies,
		String context)
	{
	}
}
