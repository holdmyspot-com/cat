package io.github.cowwoc.cat.common.git.replay;

import io.github.cowwoc.cat.common.git.Git;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Captures raw additions, deletions, and Git-reported renames from replay source transitions.
 */
final class PathTransitionFacts
{
	private final Git git;

	/**
	 * Creates the Git path-transition reader.
	 *
	 * @param git the repository Git boundary
	 */
	PathTransitionFacts(Git git)
	{
		requireThat(git, "git").isNotNull();
		this.git = git;
	}

	/**
	 * Reads the raw transition facts for every captured source.
	 *
	 * @param manifest the replay manifest
	 * @return raw additions, deletions, and Git-reported renames
	 * @throws IOException if Git cannot inspect a source transition
	 */
	List<PathTransitionFact> read(BatchManifest manifest) throws IOException
	{
		Map<String, String> outputs = manifest.outputs().stream().filter(output -> output.source() != null).
			collect(Collectors.toMap(Output::source, Output::id));
		List<PathTransitionFact> facts = new ArrayList<>();
		for (SourceSnapshot source : manifest.sources())
		{
			int index = 0;
			for (String line : git.lines("diff-tree", "--root", "--no-commit-id", "--name-status", "-r", "-M",
				source.hash()))
			{
				index += 1;
				String[] fields = line.split("\\t");
				String status = fields[0];
				if (status.startsWith("R") && fields.length == 3)
					facts.add(new PathTransitionFact(source.hash() + ":transition:" + index, source.hash(),
						outputs.get(source.hash()), status, fields[1], fields[2]));
				else if ((status.equals("A") || status.equals("D")) && fields.length == 2)
					facts.add(new PathTransitionFact(source.hash() + ":transition:" + index, source.hash(),
						outputs.get(source.hash()), status, fields[1], null));
			}
		}
		return facts;
	}
}
