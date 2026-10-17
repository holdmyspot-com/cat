package io.github.cowwoc.cat.common.optimizer.proposal;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.secrets.PromptTemplateAccess;
import io.github.cowwoc.cat.common.secrets.PromptTemplateSecrets;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * A session's prompt text, with {@code {{name}}} placeholders for the values that CAT inserts.
 * <p>
 * Filling replaces each placeholder whose name has a value in one pass, so a value that itself contains
 * {@code {{name}}} text is inserted unchanged. A placeholder without a value stays as written, because a prompt may
 * show the placeholder syntax that a contract's turns use.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 */
public final class PromptTemplate
{
	static
	{
		PromptTemplateSecrets.setAccess(new PromptTemplateAccess()
		{
			@Override
			public String readResource(String resource)
			{
				return PromptTemplate.readResource(resource);
			}
		});
	}

	private final String text;

	/**
	 * Creates a template.
	 *
	 * @param text non-blank prompt text
	 * @throws NullPointerException if {@code text} is null
	 * @throws IllegalArgumentException if {@code text} is blank
	 */
	public PromptTemplate(String text)
	{
		requireThat(text, "text").isNotBlank();
		this.text = text;
	}

	/**
	 * Loads a template that CAT ships.
	 *
	 * @param resource the absolute classpath location of the prompt file
	 * @return the template
	 * @throws NullPointerException if {@code resource} is null
	 * @throws UncheckedIOException if the packaged prompt file is missing or cannot be read
	 */
	public static PromptTemplate load(String resource)
	{
		return new PromptTemplate(readResource(resource));
	}

	/**
	 * Returns the text of a file that CAT ships.
	 *
	 * @param resource the absolute classpath location of the file
	 * @return the text
	 * @throws NullPointerException if {@code resource} is null
	 * @throws UncheckedIOException if the packaged file is missing or cannot be read
	 */
	static String readResource(String resource)
	{
		requireThat(resource, "resource").isNotNull();
		try (InputStream in = PromptTemplate.class.getResourceAsStream(resource))
		{
			if (in == null)
				throw new IOException("The packaged resource " + resource + " is missing");
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * Returns the prompt text with its placeholders unfilled.
	 *
	 * @return the text
	 */
	public String text()
	{
		return text;
	}

	/**
	 * Returns the prompt with the supplied placeholder values inserted once.
	 *
	 * @param values the text that replaces each {@code {{name}}} placeholder, by name
	 * @return the prompt
	 * @throws NullPointerException if {@code values} is null or contains a null key or value
	 * @throws IllegalArgumentException if the text has no placeholder for one of the values, which means that the
	 *   prompt would silently omit that value
	 */
	public String fill(Map<String, String> values)
	{
		requireThat(values, "values").isNotNull();
		for (Map.Entry<String, String> entry : values.entrySet())
		{
			requireThat(entry.getKey(), "values.key").isNotNull();
			requireThat(entry.getValue(), "values.get(name)").withContext(entry.getKey(), "name").isNotNull();
			requireThat(text, "text").contains(placeholder(entry.getKey()));
		}
		StringBuilder prompt = new StringBuilder(text.length());
		int start = 0;
		while (true)
		{
			int open = text.indexOf("{{", start);
			if (open == -1)
				break;
			int close = text.indexOf("}}", open + 2);
			if (close == -1)
				break;
			String value = values.get(text.substring(open + 2, close));
			if (value == null)
			{
				// Not a placeholder that this fill supplies, so keep the opening braces and continue after them
				prompt.append(text, start, open + 2);
				start = open + 2;
				continue;
			}
			prompt.append(text, start, open).append(value);
			start = close + 2;
		}
		prompt.append(text, start, text.length());
		return prompt.toString();
	}

	/**
	 * Returns the placeholder of a name.
	 *
	 * @param name the name
	 * @return {@code {{name}}}
	 */
	private static String placeholder(String name)
	{
		return "{{" + name + "}}";
	}
}
