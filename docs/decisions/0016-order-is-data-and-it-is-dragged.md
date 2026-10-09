# 0016 — Order is data, and it is dragged

**Date:** 2026-09-02 · **Status:** accepted

## Context

`sortOrder` has been on accounts and categories since the first schema, and
every read path already ends in `ORDER BY sortOrder, name`. Nothing ever wrote
it. The values arrived with the seed data and stayed there, so the keypad's
category grid was in whatever order the seed happened to use — which is not the
order any particular household spends money in.

## Decision

**Drag to reorder, in Settings only.** Long press a row, drag it, let go. The
grid on the keypad, the budget list and the breakdown all pick the new order up
without being touched, because they were sorting by `sortOrder` already.

Settings only, because that is where a list is being *maintained*. Everywhere
else the same gesture would be in the way of the tap it shares a row with.

**No drag handle.** The rows already carry three icon buttons each; a fourth
grip would take width from the name to offer what a long press already offers.
Long press is also what keeps the reorder from competing with the tap that opens
the editor.

**Siblings only.** The roots of a kind reorder among themselves, the children of
one parent among themselves, the open accounts among themselves. Dragging across
those boundaries would be a reparent, and nesting is exactly one level deep by
construction. Archived accounts do not reorder at all — an archived account is
not offered when adding a transaction, so its position decides nothing.

**`sortOrder` is a position, not a rank.** It becomes the index, so there are no
gaps to run out of and no renumbering pass to schedule later. Every row in the
list is rewritten on every drop, because moving one shifts every position after
it anyway and the whole list is a handful of rows.

## Consequences

- Reordering sets `pending = 1` on every row it touches. Order is household
  state, the same as a name or a colour — a phone that reorders offline has to
  carry that to everyone else.
- Numbering restarts per sibling list, so an expense root and an income root can
  both be `sortOrder = 0`. Harmless: every query that cares filters by kind or
  groups before it sorts.
- `onReorder` fires once, when the finger lifts. Rows still swap under the
  finger — what is on screen during the drag is the order that will be saved —
  but a write per swap would put a dozen rows through Room and the sync queue
  for one gesture.
- `ReorderableColumn` keys its gesture detector on the item's identity, not its
  index. Keyed by index, every swap would restart the detector and drop the
  finger that was mid-drag.
- `ReorderableColumn` remembers the dragged order as a list of KEYS, and always
  renders the items it was last passed. Remembering the items themselves made
  every edit that did not change the set of ids invisible: a new subcategory
  never appeared, because adding a child does not change the list of root ids
  that the remembered copy was keyed on, so the root on screen kept the children
  it was built with. A rename, a recoloured icon and a moved balance were stale
  the same way. The override is dropped as soon as the database agrees with it,
  so an order arriving from another phone can still win.

## Addendum, 2026-10-09: the one query that did not group before it sorted

The consequence above says numbering restarts per sibling list — an expense root
and an income root can both be `sortOrder = 0` — and calls it harmless, because
"every query that cares filters by kind or groups before it sorts".

The ledger's category filter did neither. It took `categories()` as the table
returns it, `ORDER BY sortOrder, name`, and listed the roots: so the menu
alternated between the two kinds, reading Dom, Wypłata, Transport, Odsetki.
Neither list was in the order Settings shows, and nothing in the menu said where
spending stopped and earning began — the household had dragged an order that one
of its own screens then did not have.

So `filterableFamilies` groups by kind (expense first, as everywhere else in the
app), then sorts within the group, and the menu draws a rule and a heading above
each block. One menu rather than two chips: the question is "which category",
and splitting the control would mean choosing a kind in order to answer a
question that is not about kinds.

The general rule stands, and now has a name: **per-kind `sortOrder` is only
harmless where something groups by kind first.** Anything that lists both kinds
together has to do that grouping itself.
