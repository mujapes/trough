# Accessibility of the derived document

2026-10-29. DESIGN.md asks the scene to produce "accessibility/action observations", and
Android stays the accessibility host. `DocumentAccessibility.java` is the derived view's
`AccessibilityNodeProvider`.

## Nodes

- **Host** (`View.NO_ID`): an editable, multi-line, scrollable `android.widget.EditText`. Its
  children are the paragraphs the viewport shows. Scroll forward and backward move the view by
  4/5 of its height, through the render thread like a touch scroll.
- **Paragraph nodes**: virtual views named by paragraph id. Ids are stable across inserts and
  removals ([VIEW.md](VIEW.md) "Paragraph identity"), so an accessibility focus stays on its
  paragraph when others change. Each node holds:
  - the paragraph's text;
  - the selection while both ends of the document's selection lie in that paragraph;
  - movement granularities: character, word, line and paragraph;
  - bounds: full width, with the paragraph's viewport top and exact height from
    `deriveVisible`. That native read walks the same clean layouts the scene used, from the
    anchor.

## Behaviour

A paragraph node behaves as a TextView holding that paragraph:
- `ACTION_NEXT/PREVIOUS_AT_MOVEMENT_GRANULARITY` follows View.traverseAtGranularity. The
  segment iterators are ports of the platform's AccessibilityIterators: character and word
  over `java.text.BreakIterator`, with the word iterator's letter-or-digit boundary rules; line
  over TextLayout's lines, using LineTextSegmentIterator's edge rule per paragraph direction;
  paragraph by newline rules. Extending keeps the selection start. The selection is the
  document's (TextView.setAccessibilitySelection).
- `ACTION_SET_SELECTION` follows TextView: start = end = -1 clears; a valid range selects; the
  current selection again, or an invalid range, returns false.
- Click places the caret at the paragraph's end. Accessibility focus is tracked per id.

Events, sent only while accessibility is enabled:
- `TYPE_VIEW_TEXT_CHANGED` from the edited paragraph's node, with TextView's fields: from
  index, removed and added counts, before text and the text after. TalkBack echoes typing
  from these. `DocumentText.lastChange` records each apply that stays inside one paragraph.
  Splits, merges and multi-range edits are reported as subtree changes instead. The node's
  id comes from the order tree (`deriveId`), because the edited paragraph's layout is not
  clean yet;
- `TYPE_VIEW_TEXT_SELECTION_CHANGED` with each selection report to the IME;
- `TYPE_VIEW_SCROLLED` after scroll frames;
- `TYPE_WINDOW_CONTENT_CHANGED` (subtree, plus text after edits) after scroll or edit frames.

## Evidence

`android.access` (`AccessOracle.java`, offscreen, UI thread) scrolls 37 px into the anchor
paragraph and checks:
- the host's children are the shown paragraphs, in order;
- each node's text, bounds (against a flat StaticLayout height walk) and granularities.

It then checks text-change events for an insertion, a deletion and a replacement inside one
paragraph (fields as TextView sets them, and the node id unchanged), and that a newline split
sends none.

Then, for each shown paragraph, an EditText holding the same text with the same width, text
size and line breaking is the reference. The oracle first checks that its lines equal
TextLayout's. Both get the same 60 random actions: selections, including invalid and cleared
ones, and moves at all four granularities, forward or back, extending or not. Return values and
the resulting selection must match after each action.

## Not yet built

- Paragraph nodes for text outside the viewport, and traversal that continues into the next
  paragraph (TalkBack moves to the next node).
- Text-editing actions on nodes (`ACTION_SET_TEXT`, cut/copy/paste) and `ACTION_IME_ENTER`.
