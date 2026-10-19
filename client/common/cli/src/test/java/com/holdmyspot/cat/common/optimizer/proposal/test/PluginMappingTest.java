package com.holdmyspot.cat.common.optimizer.proposal.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.optimizer.contract.TrialPlugin;
import com.holdmyspot.cat.common.optimizer.inventory.FileEntry;
import com.holdmyspot.cat.common.optimizer.inventory.FileInventory;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;
import com.holdmyspot.cat.common.optimizer.proposal.PluginMapping;

import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.testng.annotations.Test;

/**
 * Tests for {@link PluginMapping}.
 */
public final class PluginMappingTest
{
	/**
	 * A project rule.
	 */
	private static final Path READ = Path.of("source/rules/read.md");
	/**
	 * Another project rule in the same directory.
	 */
	private static final Path SEARCH = Path.of("source/rules/search.md");

	/**
	 * Verifies that a project directory maps to the base directory that holds a file at each of its files' relative
	 * paths.
	 */
	@Test
	public void mapsDirectory()
	{
		// Requirement: R-W12
		FileInventory base = inventory("/base", Map.of(
			"rules/codex/read.md", "Read the whole file.",
			"rules/codex/search.md", "Search first.",
			"skills/plan.md", "Plan."));

		List<TrialPlugin.Mapping> mappings = PluginMapping.map(project(), Set.of(READ, SEARCH), Set.of(), base);

		requireThat(mappings, "mappings").isEqualTo(List.of(
			new TrialPlugin.Mapping(Path.of("source/rules"), Path.of("rules/codex"))));
	}

	/**
	 * Verifies that among the base directories that hold every relative path, the one with the most identical files
	 * is chosen.
	 */
	@Test
	public void choosesMostIdenticalDirectory()
	{
		// Requirement: R-W12
		FileInventory base = inventory("/base", Map.of(
			"rules/claude/read.md", "Read the whole file.",
			"rules/claude/search.md", "Search with the Grep tool.",
			"rules/codex/read.md", "Read the whole file.",
			"rules/codex/search.md", "Search first."));

		List<TrialPlugin.Mapping> mappings = PluginMapping.map(project(), Set.of(READ, SEARCH), Set.of(), base);

		requireThat(mappings, "mappings").isEqualTo(List.of(
			new TrialPlugin.Mapping(Path.of("source/rules"), Path.of("rules/codex"))));
	}

	/**
	 * Verifies that when no base directory holds all of a project directory's files, each prompt file maps on its own
	 * to the base path that holds an identical copy.
	 */
	@Test
	public void mapsFilesSingly()
	{
		// Requirement: R-W12
		FileInventory base = inventory("/base", Map.of(
			"read/rules.md", "Read the whole file.",
			"search/rules.md", "Search first."));

		List<TrialPlugin.Mapping> mappings = PluginMapping.map(project(), Set.of(READ, SEARCH), Set.of(), base);

		requireThat(mappings, "mappings").isEqualTo(List.of(
			new TrialPlugin.Mapping(READ, Path.of("read/rules.md")),
			new TrialPlugin.Mapping(SEARCH, Path.of("search/rules.md"))));
	}

	/**
	 * Verifies that a prompt file that does not load from the project and that no mapping covers is listed, and that
	 * one that loads from the project is not.
	 */
	@Test
	public void listsUncoveredFile()
	{
		// Requirement: R-W12
		TrialPlugin plugin = built("cat", List.of(new TrialPlugin.Mapping(READ, Path.of("rules/read.md"))));
		FileInventory base = inventory("/base", Map.of("rules/read.md", "Read the whole file."));

		PluginMapping.Coverage coverage = PluginMapping.check(project(), Set.of(READ, SEARCH, Path.of("AGENTS.md")),
			Set.of(Path.of("AGENTS.md")), List.of(plugin), Map.of("cat", base));

		requireThat(coverage.uncovered(), "uncovered").isEqualTo(List.of(SEARCH));
		requireThat(coverage.differing(), "differing").isEmpty();
	}

	/**
	 * Verifies that a prompt file that two project-built plugins ship maps into both, and that each plugin's base is
	 * compared on its own. The other file of its directory lies in neither base, so the file maps on its own.
	 */
	@Test
	public void mapsFileIntoTwoPlugins()
	{
		// Requirement: R-W12
		FileInventory catBase = inventory("/cat", Map.of("rules/read.md", "Read the whole file."));
		FileInventory docsBase = inventory("/docs", Map.of("guides/read.md", "Read the file."));
		List<TrialPlugin.Mapping> catMappings = PluginMapping.map(project(), Set.of(READ), Set.of(), catBase);
		List<TrialPlugin.Mapping> docsMappings = PluginMapping.map(project(), Set.of(READ), Set.of(), docsBase);
		TrialPlugin cat = built("cat", catMappings);
		TrialPlugin docs = built("docs", List.of(new TrialPlugin.Mapping(READ, Path.of("guides/read.md"))));

		PluginMapping.Coverage coverage = PluginMapping.check(project(), Set.of(READ), Set.of(), List.of(cat, docs),
			Map.of("cat", catBase, "docs", docsBase));

		requireThat(catMappings, "catMappings").isEqualTo(List.of(
			new TrialPlugin.Mapping(READ, Path.of("rules/read.md"))));
		requireThat(docsMappings, "docsMappings").isEmpty();
		requireThat(cat.mappedPath(READ), "cat").isEqualTo(Optional.of(Path.of("rules/read.md")));
		requireThat(docs.mappedPath(READ), "docs").isEqualTo(Optional.of(Path.of("guides/read.md")));
		requireThat(coverage.uncovered(), "uncovered").isEmpty();
		requireThat(coverage.differing(), "differing").isEqualTo(List.of(
			new PluginMapping.Difference("docs", READ, Path.of("guides/read.md"))));
	}

	/**
	 * Verifies that each mapped file whose content differs from the base's file at its mapped path is warned of, and
	 * that a plugin that the project does not build is not compared.
	 */
	@Test
	public void warnsOfDifferingFile()
	{
		// Requirement: R-W12
		FileInventory base = inventory("/base", Map.of(
			"rules/codex/read.md", "Read the whole file.",
			"rules/codex/search.md", "Search with the Grep tool."));
		TrialPlugin plugin = built("cat", List.of(new TrialPlugin.Mapping(Path.of("source/rules"),
			Path.of("rules/codex"))));
		TrialPlugin installed = new TrialPlugin("tools@market", "b".repeat(64), new TrialPlugin.Installed(),
			List.of());

		PluginMapping.Coverage coverage = PluginMapping.check(project(), Set.of(READ, SEARCH), Set.of(),
			List.of(plugin, installed), Map.of("cat", base));

		requireThat(coverage.differing(), "differing").isEqualTo(List.of(
			new PluginMapping.Difference("cat", SEARCH, Path.of("rules/codex/search.md"))));
		requireThat(coverage.uncovered(), "uncovered").isEmpty();
	}

	/**
	 * Verifies that a target that does not load in trials is refused, and that a covered target is not.
	 */
	@Test
	public void refusesUnloadableTarget()
	{
		// Requirement: R-W12
		PluginMapping.Coverage coverage = new PluginMapping.Coverage(List.of(SEARCH), List.of());

		requireThat(coverage.targetRefusal(SEARCH).orElseThrow(), "refusal").
			contains("Trials would not load the target " + SEARCH);
		requireThat(coverage.targetRefusal(READ), "covered").isEmpty();
	}

	/**
	 * Returns the project: two rules in one directory and the harness's instruction file.
	 *
	 * @return the project's files
	 */
	private static FileInventory project()
	{
		return inventory("/project", Map.of(
			"source/rules/read.md", "Read the whole file.",
			"source/rules/search.md", "Search first.",
			"AGENTS.md", "Be brief."));
	}

	/**
	 * Returns a trial plugin that the project builds.
	 *
	 * @param name the plugin's name
	 * @param mappings the plugin's mappings
	 * @return the plugin
	 */
	private static TrialPlugin built(String name, List<TrialPlugin.Mapping> mappings)
	{
		return new TrialPlugin(name, "a".repeat(64), new TrialPlugin.Build(List.of("build"), Path.of("out")),
			mappings);
	}

	/**
	 * Returns an inventory of regular files.
	 *
	 * @param root the inventory's root
	 * @param files the content of each file, by path relative to the root
	 * @return the inventory
	 */
	private static FileInventory inventory(String root, Map<String, String> files)
	{
		Map<Path, FileEntry> entries = new HashMap<>();
		for (Map.Entry<String, String> file : files.entrySet())
		{
			entries.put(Path.of(file.getKey()), new FileEntry.RegularFile(Sha256.of(file.getValue().getBytes(UTF_8)),
				PosixFilePermissions.fromString("rw-r--r--")));
		}
		return new FileInventory(Path.of(root).toAbsolutePath(), entries);
	}
}
