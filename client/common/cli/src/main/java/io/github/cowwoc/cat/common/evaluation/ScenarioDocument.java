package io.github.cowwoc.cat.common.evaluation;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.commonmark.node.Heading;
import org.commonmark.node.Node;
import org.commonmark.node.SourceSpan;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.text.TextContentRenderer;

/**
 * Parses the constrained Markdown structure used by one prompt-evaluation scenario.
 */
public final class ScenarioDocument
{
	private static final Parser PARSER = Parser.builder().includeSourceSpans(IncludeSourceSpans.BLOCKS).build();
	private static final TextContentRenderer TEXT = TextContentRenderer.builder().build();

	/**
	 * Prevents construction of scenario documents outside their parser.
	 */
	private ScenarioDocument()
	{
	}

	/**
	 * Parses one scenario document into its Runner task and evaluator assertions.
	 *
	 * @param contents the complete scenario document
	 * @param source the document used in diagnostics
	 * @return the parsed task and assertions
	 * @throws IOException if the document does not use the required heading structure
	 */
	public static Parsed parse(String contents, Path source) throws IOException
	{
		Node document = PARSER.parse(contents);
		Node scenario = document.getFirstChild();
		if (!(scenario instanceof Heading scenarioHeading) || scenarioHeading.getLevel() != 2 ||
			!headingText(scenarioHeading).equals("Scenario"))
		{
			throw invalid(source);
		}
		Node assertions = scenario.getNext();
		while (assertions != null && !(assertions instanceof Heading heading && heading.getLevel() == 2 &&
			headingText(heading).equals("Assertions")))
		{
			assertions = assertions.getNext();
		}
		if (!(assertions instanceof Heading assertionsHeading))
			throw invalid(source);
		String task = contentBetween(contents, scenarioHeading, assertionsHeading).strip();
		if (task.isBlank())
			throw invalid(source);
		List<Assertion> assertionsResult = assertionsAfter(contents, assertionsHeading, source);
		return new Parsed(task, assertionsResult);
	}

	/**
	 * Parses the assertion headings after the required Assertions heading.
	 *
	 * @param contents the complete scenario document
	 * @param assertionsHeading the Assertions heading
	 * @param source the document used in diagnostics
	 * @return the parsed assertion declarations
	 * @throws IOException if an assertion heading or proposition is invalid
	 */
	private static List<Assertion> assertionsAfter(String contents, Heading assertionsHeading, Path source)
		throws IOException
	{
		List<Heading> headings = new ArrayList<>();
		for (Node node = assertionsHeading.getNext(); node != null; node = node.getNext())
			if (node instanceof Heading heading)
			{
				if (heading.getLevel() != 3)
					throw invalid(source);
				headings.add(heading);
			}
		List<Assertion> result = new ArrayList<>();
		for (int index = 0; index < headings.size(); ++index)
		{
			Heading heading = headings.get(index);
			String id = headingText(heading);
			int end = contents.length();
			if (index + 1 < headings.size())
				end = sourceStart(headings.get(index + 1));
			String proposition = contents.substring(sourceEnd(heading), end).strip();
			if (!id.matches("[a-z][a-z0-9-]*") || proposition.isBlank() || proposition.contains("Pass only if") ||
				proposition.endsWith("?"))
			{
				throw new IOException("Scenario has an invalid evaluator assertion: " + source);
			}
			result.add(new Assertion(id, proposition));
		}
		return List.copyOf(result);
	}

	/**
	 * Returns the source text between two block nodes.
	 *
	 * @param contents the complete scenario document
	 * @param first the preceding block node
	 * @param second the following block node
	 * @return the intervening source text
	 */
	private static String contentBetween(String contents, Node first, Node second)
	{
		return contents.substring(sourceEnd(first), sourceStart(second));
	}

	/**
	 * Returns one heading's rendered text without inline Markdown markup.
	 *
	 * @param heading the heading to render
	 * @return the normalized heading text
	 */
	private static String headingText(Heading heading)
	{
		return TEXT.render(heading).strip();
	}

	/**
	 * Returns the first character index occupied by one parsed node.
	 *
	 * @param node the node whose source position is required
	 * @return the first input index occupied by the node
	 */
	private static int sourceStart(Node node)
	{
		return node.getSourceSpans().getFirst().getInputIndex();
	}

	/**
	 * Returns the first character index after one parsed node.
	 *
	 * @param node the node whose source extent is required
	 * @return the exclusive input index after the node
	 */
	private static int sourceEnd(Node node)
	{
		SourceSpan span = node.getSourceSpans().getLast();
		return span.getInputIndex() + span.getLength();
	}

	/**
	 * Creates the common diagnostic for an invalid scenario structure.
	 *
	 * @param source the invalid document
	 * @return the exception describing the malformed document
	 */
	private static IOException invalid(Path source)
	{
		return new IOException("Scenario must contain ## Scenario followed by ## Assertions: " + source);
	}

	/**
	 * One evaluator-owned semantic assertion parsed from a scenario document.
	 *
	 * @param id the stable assertion identifier
	 * @param proposition the direct evidence proposition
	 */
	public record Assertion(String id, String proposition)
	{
	}

	/**
	 * The Runner task and evaluator assertions parsed from one scenario document.
	 *
	 * @param task the Runner-visible task text
	 * @param assertions the evaluator-owned semantic assertions
	 */
	public record Parsed(String task, List<Assertion> assertions)
	{
	}
}
