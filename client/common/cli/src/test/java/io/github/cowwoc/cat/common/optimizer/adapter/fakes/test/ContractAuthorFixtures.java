package io.github.cowwoc.cat.common.optimizer.adapter.fakes.test;

import io.github.cowwoc.cat.common.PortablePaths;
import io.github.cowwoc.cat.common.optimizer.contract.CapabilityRanking;
import io.github.cowwoc.cat.common.optimizer.contract.ProfileCatalog;
import io.github.cowwoc.cat.common.optimizer.contract.TrialProfile;
import io.github.cowwoc.cat.common.optimizer.proposal.ContractAuthorFiles;
import io.github.cowwoc.cat.common.optimizer.proposal.ContractContext;
import io.github.cowwoc.cat.common.optimizer.proposal.DraftDirectory;
import io.github.cowwoc.cat.common.optimizer.proposal.OutputFile;
import io.github.cowwoc.cat.common.optimizer.proposal.ProfileEvidence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import tools.jackson.databind.json.JsonMapper;

/**
 * The files that tests give the workspace of a contract author session.
 */
public final class ContractAuthorFixtures
{
	/**
	 * The files that {@link #files(JsonMapper)} writes, relative to the directory that it writes them to, sorted: the
	 * context file of the contract tools and the scenario of the draft's one prompt test.
	 */
	public static final List<String> FILES = List.of(ContractContext.NAME,
		DraftDirectory.NAME + "/.cat/tests/notes/scenario.md");

	/**
	 * Prevents instantiation.
	 */
	private ContractAuthorFixtures()
	{
	}

	/**
	 * Returns the files of a contract author session whose draft directory holds one recorded prompt test.
	 *
	 * @param json the JSON mapper that writes the context file
	 * @return the files
	 * @throws NullPointerException if {@code json} is null
	 */
	public static ContractAuthorFiles files(JsonMapper json)
	{
		TrialProfile profile = new TrialProfile("model", "low");
		ProfileEvidence profiles = new ProfileEvidence(new ProfileCatalog("one profile", List.of(profile)),
			new CapabilityRanking(List.of(CapabilityRanking.family(profile.model())), "one family"), Map.of());
		ContractContext context = new ContractContext(OutputFile.NAME, DraftDirectory.NAME, Path.of("CLAUDE.md"),
			true, profile, profiles, Map.of(profile, 40_000L), List.of(), Optional.empty());
		return new ContractAuthorFiles(json, DraftDirectory.NAME, draft ->
		{
			Path scenario = draft.resolve(".cat/tests/notes/scenario.md");
			Files.createDirectories(scenario.getParent());
			Files.writeString(scenario, "# Notes\n");
		}, ContractContext.NAME, context);
	}

	/**
	 * Returns the regular files below a directory.
	 *
	 * @param directory the directory
	 * @return each file's path relative to {@code directory}, in the form that {@link PortablePaths#text(Path)}
	 *   returns, sorted
	 * @throws NullPointerException if {@code directory} is null
	 * @throws IOException          if the directory cannot be listed
	 */
	public static List<String> regularFiles(Path directory) throws IOException
	{
		List<String> files = new ArrayList<>();
		try (Stream<Path> paths = Files.walk(directory))
		{
			for (Path path : (Iterable<Path>) paths::iterator)
			{
				if (Files.isRegularFile(path))
					files.add(PortablePaths.text(directory.relativize(path)));
			}
		}
		files.sort(null);
		return files;
	}
}
