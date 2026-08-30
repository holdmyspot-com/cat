package example;

public final class BillingService
{
	public void applyDiscount(int percentage)
	{
		if (percentage < 0)
			throw new IllegalArgumentException("percentage must not be negative");
	}
}
