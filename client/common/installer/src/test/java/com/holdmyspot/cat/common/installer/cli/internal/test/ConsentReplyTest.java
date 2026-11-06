package com.holdmyspot.cat.common.installer.cli.internal.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.installer.cli.internal.ConsentReply;
import java.util.Optional;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests how the installer reads the user's answer to the question that install asks in the terminal.
 */
public final class ConsentReplyTest
{
	/**
	 * Creates the test.
	 */
	public ConsentReplyTest()
	{
	}

	/**
	 * Supplies each answer and the reply it makes.
	 *
	 * @return the answer and the reply
	 */
	@DataProvider
	public Object[][] answers()
	{
		return new Object[][]
			{
				{Optional.of("y"), ConsentReply.ALLOWED},
				{Optional.of("Y"), ConsentReply.ALLOWED},
				{Optional.of(" YES "), ConsentReply.ALLOWED},
				{Optional.of("yes"), ConsentReply.ALLOWED},
				{Optional.of("n"), ConsentReply.DECLINED},
				{Optional.of(""), ConsentReply.DECLINED},
				{Optional.of("sure"), ConsentReply.DECLINED},
				{Optional.of("yes please"), ConsentReply.DECLINED},
				{Optional.empty(), ConsentReply.UNASKED}
			};
	}

	/**
	 * Only yes allows the change; an empty answer takes the default, no; a terminal that did not ask withholds it.
	 *
	 * @param answer the terminal's answer
	 * @param reply  the reply
	 */
	@Test(dataProvider = "answers")
	public void onlyYesAllows(Optional<String> answer, ConsentReply reply)
	{
		requireThat(ConsentReply.of(answer), "reply").isEqualTo(reply);
	}
}
