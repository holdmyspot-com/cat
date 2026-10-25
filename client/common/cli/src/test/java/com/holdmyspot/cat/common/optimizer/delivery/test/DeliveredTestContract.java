package com.holdmyspot.cat.common.optimizer.delivery.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.optimizer.contract.PromptTestFiles;
import com.holdmyspot.cat.common.optimizer.contract.Scenario;
import com.holdmyspot.cat.common.optimizer.contract.ScenarioFormatException;
import com.holdmyspot.cat.common.optimizer.contract.ValuesFile;
import com.holdmyspot.cat.common.optimizer.contract.WorkspaceFile;
import com.holdmyspot.cat.common.optimizer.delivery.DeliveredTest;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;

import tools.jackson.databind.json.JsonMapper;

/**
 * Checks the actual scenario, values and workspace files of a delivered prompt test.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe if the borrowed mapper supports concurrent reads.
 */
public final class DeliveredTestContract
{
	private final Path base;
	private final JsonMapper json;

	/**
	 * Creates checks for delivered files in a base worktree.
	 *
	 * @param base the base worktree
	 * @param json the mapper that reads values files
	 * @throws NullPointerException if any argument is null
	 */
	public DeliveredTestContract(Path base, JsonMapper json)
	{
		requireThat(base, "base").isNotNull();
		requireThat(json, "json").isNotNull();
		this.base = base;
		this.json = json;
	}

	/**
	 * Requires the scenario, values and workspace files to hold the delivered test's recorded contents.
	 *
	 * @param test the delivered prompt test
	 * @throws NullPointerException if {@code test} is null
	 * @throws IOException if a file cannot be read
	 * @throws ScenarioFormatException if a scenario cannot be parsed
	 */
	public void delivered(DeliveredTest test) throws IOException, ScenarioFormatException
	{
		requireThat(test, "test").isNotNull();
		Path directory = base.resolve(test.location().directory());
		requireThat(Scenario.parse(Files.readString(directory.resolve(PromptTestFiles.SCENARIO), UTF_8)), "scenario").
			withContext(test.id(), "id").isEqualTo(test.scenario());
		try (Reader in = Files.newBufferedReader(directory.resolve(ValuesFile.NAME), UTF_8))
		{
			requireThat(ValuesFile.read(json, in, ValuesFile.NAME), "testCases").withContext(test.id(), "id").
				isEqualTo(test.testCases());
		}
		for (WorkspaceFile file : test.workspaceFiles())
		{
			requireThat(Files.readString(directory.resolve(PromptTestFiles.WORKSPACE).resolve(file.path()), UTF_8),
				"fixture").withContext(test.id(), "id").isEqualTo(file.content());
		}
	}
}
