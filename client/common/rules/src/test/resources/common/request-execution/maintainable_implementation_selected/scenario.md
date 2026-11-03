# Maintainable implementation selected

## Variables

- **internal_choice**: A routine internal implementation choice whose observable behavior is already settled, such as
  selecting the existing serializer to quote test paths or adding its dependency to the test module

## Scenario

The contract and acceptance tests already determine the behavior. Implement it using the established project design.
The remaining choice is {{internal_choice}}. Use the best long-term approach and complete verification.

## Assertions

### maintainable_choice_made

- **kind**: effect
- **description**: The agent resolves {{internal_choice}} using the existing contract and coherent maintainable design,
  verifies the result, and does not ask the user an implementation question or introduce speculative scope.
