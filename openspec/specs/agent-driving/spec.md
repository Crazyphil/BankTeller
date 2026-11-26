# agent-driving Specification

## Purpose

The tested knowledge of driving BankTeller in a browser as an agent: how to reach the running app, read the Compose a11y overlay through the viewport shadow root, invoke actions by clicking overlay nodes, and the environment's verified quirks — maintained as a workspace skill so future agents inherit operational knowledge instead of re-deriving it.

## Requirements


### Requirement: Agent-driving skill exists and carries the tested method
The repository SHALL provide a workspace skill at `.agents/skills/agent-driving/SKILL.md` documenting the agent-driving method for BankTeller — the tested path to reading and driving the app in a browser. The skill SHALL be survivable knowledge: each claim verified in a session, not speculative.

#### Scenario: The skill documents the tested method
- **WHEN** the skill's contents are reviewed
- **THEN** they cover the dev loop and its URL, the ComposeViewport shadow-root path to the a11y overlay (`cmp_a11y_root`), reading exposed nodes (roles, aria-labels, geometry), invoking actions by clicking overlay nodes, and the environment's verified quirks (browser Tab traversal dead, CDP snapshots empty, HEAD requests 404, 0x0 geometry under a hidden viewport pane)

#### Scenario: An agent follows the skill and reaches the app
- **WHEN** an agent opens a fresh session, loads the skill, and follows it to drive the running app
- **THEN** it reaches the app's overlay, reads a node, and dispatches a click that executes a real app action — without rediscovering the shadow-root path, the click mechanism, or the quirks

#### Scenario: The skill stays current with the framework
- **WHEN** a Compose Multiplatform upgrade or bridge change alters the documented behavior (e.g. tab stops arriving, the container fix landing)
- **THEN** the change that includes the upgrade re-verifies the skill's claims and updates the skill — the skill is a living document tied to the upgrade cycle
