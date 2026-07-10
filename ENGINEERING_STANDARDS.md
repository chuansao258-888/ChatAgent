# ChatAgent Engineering Standards

This document is the canonical architecture, consistency, and AI-coding
standard for this repository. `AGENTS.md`, plans, implementation records, and
reviews reference these stable rule IDs instead of duplicating the policy.

## Precedence And Use

When instructions conflict, use this order:

1. Latest explicit user decision
2. Accepted plan and phase contract
3. Accepted ADR
4. This standard
5. Existing local convention
6. General preference

Every non-trivial phase must provide an `Architecture Contract`; implementation
must provide `Architecture Conformance Evidence`; review findings must cite the
violated rule IDs. Exceptions require an explicit reason, bounded scope, and
verification. Do not silently weaken the rule or change the plan after the fact.

## Architecture Rules

### ARC-01 Capability Cohesion And Change Locality

- Organize code around a clear business capability and change reason.
- Keep related state, rules, and behavior close enough to understand locally.
- A local requirement should change the smallest coherent module set.
- Do not split classes mechanically by size or scatter one capability across
  generic controller/service/repository/helper folders without a real boundary.

Evidence: owning module, touched modules, and why each changed surface is needed.

### ARC-02 State Ownership And Authoritative Rules

- Every important state and state transition has one explicit owner.
- Every business rule has one authoritative implementation.
- Other modules call the owner through behavior-oriented contracts; they do not
  reproduce the rule or mutate internal state directly.
- A replacement must remove or delegate the superseded implementation so two
  authorities cannot drift.

Evidence: owner, authoritative method/policy, callers, and stale-rule search.

### ARC-03 Dependency Direction And Acyclicity

- Follow the repository's established layer/module direction.
- Domain behavior must not depend on HTTP, MQ, database, provider SDK, or UI
  implementation details.
- Do not access another capability's internal repository or storage model.
- Do not introduce module, service, or package cycles. `@Lazy` is not an
  architecture fix unless the cycle is a documented framework-only constraint.

Evidence: existing and added dependency edges, forbidden edges, and cycle check.

### ARC-04 Module Boundaries And Information Hiding

- Collaborate through the smallest stable contract that expresses business
  intent.
- Default to the narrowest practical visibility and public API.
- Do not leak provider SDK models, persistence entities, internal prompts, or
  transport details across capability boundaries.
- Apply the least-knowledge principle without creating a service merely to hide
  one incidental getter chain.

Evidence: public surface changes and boundary model/adapter decisions.

### ARC-05 Call Chain And Data Flow Value

- Every layer and data conversion must add a real boundary value such as
  protocol translation, application orchestration, domain rules, permission,
  transaction ownership, or infrastructure isolation.
- Remove pass-through Manager/Handler/Processor/Helper layers and identical DTO
  conversions that add no semantics.
- Keep workflows readable end to end; do not hide control flow in generic
  pipelines or registries without a proven variation point.

Evidence: call/data path and the value contributed by each added hop or model.

### ARC-06 Transaction, Concurrency, And Idempotency Boundaries

- State which operations are atomic and which external effects are outside the
  local transaction.
- Do not hold a database transaction across slow HTTP, model, or MQ operations
  without an approved consistency reason.
- Do not rely on "normally once" or UI duplicate prevention. Use the simplest
  explicit mechanism that satisfies the real risk: constraints, versioning,
  idempotency keys, state transitions, locks, CAS, outbox, or compensation.
- Do not introduce distributed coordination patterns without demonstrated need.

Evidence: boundary, failure window, duplicate/concurrency behavior, and tests.

## Consistency Rules

### CONS-01 Local Convention And Domain Language

- Match the touched area's naming, package layout, construction/injection,
  DTO/type, exception, logging, null handling, test, import, and formatting
  conventions unless the existing pattern is itself the approved problem.
- Reuse canonical project terminology from `CONTEXT.md` and nearby code.
- Do not mix a style migration into feature work or reformat unrelated files.

Evidence: nearby examples followed and any intentional deviation.

### CONS-02 Contract Consistency Across Surfaces

- Keep API, DTO, event/MQ, SSE, database, prompt/tool, config, test, and docs
  consistent end to end.
- When configuration changes, update `application.yaml` and
  `docs/env_variables.txt` together without exposing secret values.
- Keep one term and one semantic meaning across producer, consumer, tests, and
  documentation.

Evidence: affected contract surfaces and round-trip/integration proof.

## Simplicity Rules

### SIMP-01 Minimal Correct Change

- Default to the smallest correct modification inside the accepted boundary.
- Prefer direct, readable control flow and local named logic.
- Do not broaden a feature into a refactor, migration, framework, or future
  roadmap implementation.
- Small duplication is preferable to a false shared abstraction.

Evidence: allowed diff, explicit non-goals, and avoided adjacent changes.

### SIMP-02 New Abstractions Must Earn Their Keep

Before adding an interface, factory, strategy, builder, resolver, provider,
adapter, bridge, registry, pipeline, context, template, base class, or shared
module, record:

1. The current problem it solves
2. The real variation or boundary
3. Existing implementations/callers
4. The concrete cost of a direct implementation
5. Why the abstraction is easier to understand and verify now

"It may be useful later" is not sufficient.

### SIMP-03 Avoid Generic Containers And Hidden Coupling

- Do not create God services, global contexts, generic business managers, or
  `CommonUtils`/`BusinessUtils` dumping grounds.
- Prefer composition over inheritance; avoid deep base-service hierarchies used
  only for reuse.
- Put code in shared/common/core only when the contract is stable and genuinely
  cross-capability.
- Treat high dependency count as a signal to inspect responsibility, not a
  mechanical reason to split a class.

Evidence: responsibility statement and dependency/consumer rationale.

## Error And Resilience Rules

### ERR-01 Preserve Outcome Semantics

Keep these outcomes distinguishable when relevant:

- legal empty result
- business rejection
- malformed or invalid data
- system/internal failure
- external dependency failure
- timeout
- concurrency conflict
- deliberate degradation

Do not collapse them into `null`, `false`, `Optional.empty()`, an empty list, or
a generic success response when their meanings differ.

### ERR-02 Resilience Must Be Deliberate

- Every fallback, retry, cache, default, compatibility layer, and degradation
  path needs explicit semantics, trigger, limit, observability, and tests.
- Do not catch broad exceptions and silently convert failure into empty success.
- Do not add resilience behavior merely because AI can imagine a failure.

Evidence: failure matrix, configuration/defaults, logs/metrics, and tests.

## Change And Evidence Rules

### CHANGE-01 Cleanup Is Part Of The Change

- Remove obsolete code, competing rules, dead branches, old config, stale tests,
  comments, TODOs, and compatibility paths replaced by the phase.
- Search for stale references before handoff.
- Preserve unrelated user changes and avoid unrelated formatting churn.

### EVID-01 Evidence Is Required At Every Gate

Plans record:

```text
Architecture Contract
- Applicable rule IDs
- Capability, behavior owner, state owner, authoritative rule
- Existing / allowed / forbidden dependency edges
- Public API, data, transaction, concurrency, and failure semantics
- Existing patterns, abstraction justification, locality, cleanup, proof
```

Implementations record:

```text
Architecture Conformance Evidence
- Rule IDs checked
- Actual ownership and authority
- Actual dependency/public/data changes
- Transaction/concurrency/failure behavior
- New abstractions and justification
- Complexity delta, cleanup, stale-reference search
- Deviations: None | approved decision
```

Reviews verify those claims against code and tests. A checkbox without file,
diff, command, test, or runtime evidence is not proof.

## Mandatory Decision Gates

Before editing, answer:

1. Where does this behavior belong, and who owns its state?
2. Does an authoritative rule or real abstraction already exist?
3. Which classes, branches, dependencies, states, transactions, and fallbacks
   does the change add?
4. What can be removed or implemented more directly?

Before handoff, verify:

- naming, placement, errors, data models, and tests match local conventions;
- ownership, authority, dependency direction, and public surface remain clear;
- failure and fallback semantics are explicit;
- the change is local and superseded code is gone;
- targeted and broader verification evidence is recorded.

Automate objective boundaries such as forbidden package dependencies, cycles,
contract round trips, formatting, and static analysis when stable. Do not turn
subjective quality signals such as class length or constructor parameter count
into hard gates without repository-specific evidence and approval.
