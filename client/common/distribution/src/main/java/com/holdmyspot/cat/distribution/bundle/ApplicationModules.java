package com.holdmyspot.cat.distribution.bundle;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.lang.module.Configuration;
import java.lang.module.FindException;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.lang.module.ResolutionException;
import java.lang.module.ResolvedModule;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The modules that one Java application's module graph resolves to: the application's own modular JARs and the
 * system modules of the JDK that runs the build.
 * <p>
 * The graph is resolved without service binding, as {@code jlink} resolves an image's modules: the application ships
 * the JARs that its roots require, and needs the system modules that those JARs require. A JAR or system module that
 * only service binding reaches, such as another harness's module or an optional JDK provider, is left out.
 *
 * @param jars          the application's modular JARs that the graph resolves to, sorted by file name
 * @param systemModules the names of the system modules that the graph resolves to
 */
public record ApplicationModules(List<Path> jars, SortedSet<String> systemModules)
{
	/**
	 * The version of the runtime requirement's format.
	 */
	private static final int REQUIREMENT_FORMAT = 1;

	/**
	 * Creates the modules of an application.
	 *
	 * @param jars          the application's modular JARs that the graph resolves to, sorted by file name
	 * @param systemModules the names of the system modules that the graph resolves to
	 * @throws NullPointerException     if any argument is null
	 * @throws IllegalArgumentException if {@code systemModules} is empty
	 */
	public ApplicationModules
	{
		requireThat(jars, "jars").isNotNull();
		requireThat(systemModules, "systemModules").isNotEmpty();
		jars = List.copyOf(jars);
		systemModules = Collections.unmodifiableSortedSet(new TreeSet<>(systemModules));
	}

	/**
	 * Resolves an application's module graph.
	 *
	 * @param candidates the modular JARs that the application may use; a JAR that the application's roots do not
	 *                   require is left out
	 * @param roots      the modules that the application starts from
	 * @return the modules that the graph resolves to
	 * @throws NullPointerException     if any argument is null
	 * @throws IllegalArgumentException if {@code roots} is empty
	 * @throws IOException              if the graph cannot be resolved, for example because a module that it requires
	 *                                  is missing
	 */
	public static ApplicationModules resolve(List<Path> candidates, Set<String> roots) throws IOException
	{
		requireThat(candidates, "candidates").isNotNull();
		requireThat(roots, "roots").isNotEmpty();
		ModuleFinder candidateFinder = ModuleFinder.of(candidates.toArray(Path[]::new));
		Configuration graph;
		try
		{
			graph = Configuration.empty().resolve(candidateFinder, ModuleFinder.ofSystem(), roots);
		}
		catch (FindException | ResolutionException e)
		{
			throw new IOException("Cannot resolve the modules of " + roots + ": " + e.getMessage(), e);
		}
		List<Path> jars = new ArrayList<>();
		SortedSet<String> systemModules = new TreeSet<>();
		for (ResolvedModule module : graph.modules())
		{
			Optional<ModuleReference> found = candidateFinder.find(module.name());
			if (found.isEmpty())
			{
				systemModules.add(module.name());
				continue;
			}
			URI location = found.get().location().orElseThrow(() ->
				new IOException("The module " + module.name() + " has no location"));
			jars.add(Path.of(location));
		}
		jars.sort((left, right) -> left.getFileName().toString().compareTo(right.getFileName().toString()));
		return new ApplicationModules(jars, systemModules);
	}

	/**
	 * Writes the runtime requirement of the application: the Java feature release of the JDK that runs the build, whose
	 * class files the application's JARs hold, and the system modules that the application's graph resolves to. A
	 * runtime satisfies the requirement when its feature release is at least the required one and it holds every
	 * required module.
	 *
	 * @param file the file that receives the requirement
	 * @param json the JSON writer
	 * @throws NullPointerException if any argument is null
	 * @throws IOException          if the file cannot be written
	 */
	public void writeRequirement(Path file, JsonMapper json) throws IOException
	{
		requireThat(file, "file").isNotNull();
		requireThat(json, "json").isNotNull();
		ObjectNode requirement = json.createObjectNode();
		requirement.put("format", REQUIREMENT_FORMAT);
		requirement.put("java_feature", Runtime.version().feature());
		ArrayNode modules = requirement.putArray("modules");
		systemModules.forEach(modules::add);
		Files.writeString(file, json.writeValueAsString(requirement) + "\n", UTF_8);
	}
}
