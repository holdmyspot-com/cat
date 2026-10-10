# No representation choice

## Variables

| Name | Description |
|---|---|
| sample | Three or more forecasts, at least two of them with equal probabilities, written as `("region", probability)` pairs, such as `("south", 0.2), ("east", 0.7) and ("north", 0.7)` |

## Scenario

A Java service has this record:

```java
public record Forecast(String region, double rainProbability)
{
}
```

Add a static method `List<Forecast> wettestFirst(List<Forecast> forecasts)` that returns a new list of the forecasts
ordered from the highest rain probability to the lowest, ordering forecasts with equal probabilities by region name.
Show the complete record and the list that the method returns for the forecasts {{sample}}.

## Assertions

### representation_unchanged

kind: guard

The response keeps `rainProbability` as a `double` and the record's components unchanged. It orders the forecasts by
comparing the stored `double` values, for example with `Comparator.comparingDouble` or `Double.compare`, and does not
convert them to `BigDecimal` or another exact type.

### order_matches

kind: guard

For the forecasts {{sample}}, the method returns them from the highest probability to the lowest,
ordering equal probabilities by region name, and leaves the input list unchanged.
