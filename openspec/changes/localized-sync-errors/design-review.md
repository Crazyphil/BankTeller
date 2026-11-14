# Design Review: Localized Sync Errors

**Change**: localized-sync-errors
**Date**: 2026-11-13
**Reviewer**: Designer Checkpoint
**Scope**: Account/Connection degradation presentation and refresh affordances.

## Sign-off Verdict
- Account-card status slot: **APPROVED**
- Account-card scan signal: **APPROVED**
- Connection-card signal: **APPROVED**
- Prominent refresh action: **APPROVED WITH NOTES**

## Ruling on Deviations
1. **Prominent "Refresh authorization" (raw Button)**: **ACCEPT**. The shared components cannot host the required 14dp leading glyph. The implementation maintains the required visual hierarchy and gating.
2. **Tooltip content (binned copy + message + time)**: **ACCEPT**. Providing the raw API message for diagnostic honesty is appropriate.
3. **Icon mapping**: **ACCEPT**. These standard outlines provide the required clarity without alarmist filled icons.
4. **Tint strengths**: **ACCEPT**. The 6%/4% delta correctly emphasizes danger over brass.
5. **Redundant "Local copy" tag**: **REJECT**. The design language requires the canonical slot for degradation and the tag for archived status. Redundancy here violates the "single status line" rule. Must be removed from the canonical slot when the "Local copy" tag is present.
   - **SUPERSEDED (planning ruling, 2026-11-13)**: reversed — the BARE TAG is retired on dashboard cards (task 4.7, implemented; the canonical slot's "Local copy — not synchronized" line IS the card's single archived-record marker), and the tag survives only in the connections section's dense account rows (no status slot there). This checkpoint's original resolution (keep tag, drop slot line) is void; the deltas and task 4.7 are authoritative.
6. **Unused pure helpers**: **ACCEPT-WITH-NOTE**. Acceptable as an interim state for unit-test stability, but must be cleaned up in a follow-up task.
7. **Spec/Design violations**: None found.

## Findings
- **Note**: Ensure the "Local copy" cleanup is tracked.
- **Note**: The alignment of the status slot left-column placement appears correct (below metadata, above the fold).

## Overall Outcome: **APPROVED WITH NOTES**

> **Post-checkpoint rulings (planning, 2026-11-13/14)**: two user rulings post-date this checkpoint and supersede parts of the approved state: (1) the per-card re-authorization action is removed — the status slot is a pure status line (task 4.8); (2) the full re-tier — transient/rate-limited failures present brass everywhere, danger is consent-class only (task 4.6a). The sign-offs above describe the pre-ruling state; items 1–4, 6–7 remain valid.
