# Intent Policy Evaluation v1

Profile: `v1`
Classifier: `deepseek-v4-flash`
Prompt / feature: `v1` / `intent-features-v1`
Frozen policy: accept `1.60`, clarify `0.80`, gap `0.50`, classifier cap `6`, clarification cap `3`

## Calibration (deterministic fixture self-consistency)

These metrics are **structural/deterministic self-consistency (v1)**, produced by
`IntentPolicyEvaluationSupport.evaluate` using a `FixtureClassifier` that returns
the fixture-annotated outcome directly — **not a live model evaluation**.

- Cases: 150
- Route accuracy: 1.0000
- Known-leaf macro F1: 1.0000
- Source Need Accuracy: 1.0000
- Ambiguity recall: 1.0000
- High-risk wrong automatic executions: 0

## Sealed Holdout (deterministic fixture self-consistency)

These metrics are **structural/deterministic self-consistency (v1)**, produced by
the same `FixtureClassifier`. They verify that the engine, candidate generator,
policy evaluator, and frozen profile thresholds produce the expected typed
outcomes for every fixture case — **not live model accuracy**.

- Cases: 150
- Route accuracy: 1.0000
- Known-leaf macro F1: 1.0000
- General/OOD false business-route rate: 0.0000
- Ambiguity recall: 1.0000
- Unnecessary clarification rate: 0.0000
- Clarification recovery accuracy: 1.0000
- New-topic release accuracy: 1.0000
- Multi-intent F1: 1.0000
- Source Need Accuracy: 1.0000
- Coverage / abstention: 0.5333 / 0.4667
- High-risk wrong automatic executions: 0

## Live-model holdout (ATC-AC-030): NOT EXECUTED

Three independent live-model holdout runs (`LiveIntentPolicyEvaluationTest`)
have not been executed. Per user decision (2026-07-11), `ATC-AC-030` is deferred
to a follow-up sub-gate. Phase 3 (`ATC-AC-028`) remains blocked until the live
gate truly passes. The deterministic metrics above do not substitute for live
model accuracy.

Reports contain aggregate metrics, enum slices, hashes, reason codes, and safe case IDs only.
