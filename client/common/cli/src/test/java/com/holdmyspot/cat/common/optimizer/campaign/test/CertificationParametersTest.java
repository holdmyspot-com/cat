package com.holdmyspot.cat.common.optimizer.campaign.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.campaign.CertificationParameter;
import com.holdmyspot.cat.common.optimizer.campaign.CertificationParameters;
import com.holdmyspot.cat.common.optimizer.statistics.SignificanceLevel;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests the comparison margin retained in a certification claim.
 */
public final class CertificationParametersTest
{
	/**
	 * Creates the test.
	 */
	public CertificationParametersTest()
	{
	}

	/**
	 * Provides comparison claims whose stored margin does or does not imply the requested margin.
	 *
	 * @return the cases
	 */
	@DataProvider
	public static Object[][] marginCases()
	{
		return new Object[][]
			{
				{new BigDecimal("0.90"), new BigDecimal("0.04"), new BigDecimal("0.05"), List.of()},
				{new BigDecimal("0.90"), new BigDecimal("0.05"), new BigDecimal("0.05"), List.of()},
				{new BigDecimal("0.90"), new BigDecimal("0.06"), new BigDecimal("0.05"),
					List.of(CertificationParameter.CONTROL_MARGIN)},
				{new BigDecimal("0.90"), new BigDecimal("0.050"), new BigDecimal("0.05"), List.of()},
				{new BigDecimal("0.95"), new BigDecimal("0.06"), new BigDecimal("0.05"), List.of()},
				{new BigDecimal("0.949"), new BigDecimal("0.06"), new BigDecimal("0.05"),
					List.of(CertificationParameter.CONTROL_MARGIN)}
			};
	}

	/**
	 * Verifies that implication compares the retained and requested margins without rounding their decimal values.
	 *
	 * @param minimum the minimum pass rate in both claims
	 * @param recordedMargin the margin retained by the certification
	 * @param requestedMargin the margin required by the current claim
	 * @param expected the parameters that the recorded claim does not imply
	 */
	@Test(dataProvider = "marginCases")
	public void marginDecidesImplication(BigDecimal minimum, BigDecimal recordedMargin, BigDecimal requestedMargin,
		List<CertificationParameter> expected)
	{
		// Requirement: R-M2
		SignificanceLevel level = new SignificanceLevel(BigInteger.TEN);
		CertificationParameters recorded = new CertificationParameters(minimum, recordedMargin, level);
		CertificationParameters requested = new CertificationParameters(minimum, requestedMargin, level);

		requireThat(recorded.notImplying(requested), "notImplying").isEqualTo(expected);
	}

	/**
	 * Verifies that multiple failed claim parameters remain in declaration order.
	 */
	@Test
	public void notImplyingKeepsDeclarationOrder()
	{
		// Requirement: R-M2
		CertificationParameters recorded = new CertificationParameters(new BigDecimal("0.89"), new BigDecimal("0.06"),
			new SignificanceLevel(BigInteger.valueOf(5)));
		CertificationParameters requested = new CertificationParameters(new BigDecimal("0.90"), new BigDecimal("0.05"),
			new SignificanceLevel(BigInteger.TEN));

		requireThat(recorded.notImplying(requested), "notImplying").isEqualTo(List.of(
			CertificationParameter.MINIMUM_PASS_RATE, CertificationParameter.CONTROL_MARGIN,
			CertificationParameter.CERTIFICATION_LEVEL));
	}

	/**
	 * Provides invalid comparison margins that are below 0 or at least 1.
	 *
	 * @return the cases
	 */
	@DataProvider
	public static Object[][] invalidMarginCases()
	{
		return new Object[][]
			{
				{new BigDecimal("-0.01")},
				{BigDecimal.ONE},
				{new BigDecimal("1.01")}
			};
	}

	/**
	 * Refuses a comparison margin outside [0, 1).
	 *
	 * @param margin the invalid margin
	 */
	@Test(dataProvider = "invalidMarginCases", expectedExceptions = IllegalArgumentException.class,
		expectedExceptionsMessageRegExp = ".*controlMargin.*")
	public void refusesInvalidMargin(BigDecimal margin)
	{
		// Requirement: R-M2
		new CertificationParameters(new BigDecimal("0.90"), margin, new SignificanceLevel(BigInteger.TEN));
	}

	/**
	 * Provides null as the comparison margin.
	 *
	 * @return the cases
	 */
	@DataProvider
	public static Object[][] nullMarginCases()
	{
		return new Object[][]
			{
				{null}
			};
	}

	/**
	 * Refuses a null comparison margin.
	 *
	 * @param margin the null margin
	 */
	@Test(dataProvider = "nullMarginCases", expectedExceptions = NullPointerException.class,
		expectedExceptionsMessageRegExp = ".*controlMargin.*")
	public void refusesNullMargin(BigDecimal margin)
	{
		// Requirement: R-M2
		new CertificationParameters(new BigDecimal("0.90"), margin, new SignificanceLevel(BigInteger.TEN));
	}

	/**
	 * Provides the inclusive lower boundary and a representative comparison margin.
	 *
	 * @return the cases
	 */
	@DataProvider
	public static Object[][] validMarginCases()
	{
		return new Object[][]
			{
				{BigDecimal.ZERO},
				{new BigDecimal("0.05")}
			};
	}

	/**
	 * Accepts margins in [0, 1).
	 *
	 * @param margin the accepted margin
	 */
	@Test(dataProvider = "validMarginCases")
	public void acceptsMargin(BigDecimal margin)
	{
		// Requirement: R-M2
		CertificationParameters parameters = new CertificationParameters(new BigDecimal("0.90"), margin,
			new SignificanceLevel(BigInteger.TEN));

		requireThat(parameters.controlMargin(), "controlMargin").isEqualTo(margin);
	}

	/**
	 * Provides claims whose pass rate does or does not imply comparisons at their stored margin.
	 *
	 * @return the cases
	 */
	@DataProvider
	public static Object[][] comparisonCases()
	{
		return new Object[][]
			{
				{new BigDecimal("0.90"), new BigDecimal("0.10"), true},
				{new BigDecimal("0.899"), new BigDecimal("0.10"), false},
				{new BigDecimal("0.95"), new BigDecimal("0.04"), false},
				{new BigDecimal("0.96"), new BigDecimal("0.04"), true}
			};
	}

	/**
	 * Verifies whether comparisons are implied using the margin retained in the claim.
	 *
	 * @param minimum the minimum pass rate
	 * @param margin the retained comparison margin
	 * @param expected whether comparisons are implied
	 */
	@Test(dataProvider = "comparisonCases")
	public void comparisonsUseTheRecordedMargin(BigDecimal minimum, BigDecimal margin, boolean expected)
	{
		// Requirement: R-M6
		CertificationParameters parameters = new CertificationParameters(minimum, margin,
			new SignificanceLevel(BigInteger.TEN));

		requireThat(parameters.comparisonsImplied(), "comparisonsImplied").isEqualTo(expected);
	}
}
