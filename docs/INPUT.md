# Text input adapter

2026-10-28. `hot/src/com/trough/hot/TextInput.java` is the Android IME boundary DESIGN.md asks
for: "an Android text-input adapter preserves IME composition, selection, bidi, clipboard and
accessibility contracts even when the text itself is rendered by Trough". It subclasses
`BaseInputConnection` in full-editor mode and maps each IME operation to one atomic batch on
the native draft queue ([DRAFTS.md](DRAFTS.md)), through the in-process bionic library
([BIONIC.md](BIONIC.md)). The text exists only in the queue; Java keeps no mirror.

## Operations

| IME call | Batch |
| --- | --- |
| `commitText`, `setComposingText` (also the `TextAttribute` overloads) | replace the composing region, else the selection; place the cursor; composing text becomes the new region unless empty |
| `setComposingRegion` | no edits; the clamped, ordered region (empty clears it) |
| `finishComposingText` | no edits; clears the region |
| `deleteSurroundingText` | up to two deletions around the selection widened by the composing region; positions mapped; an emptied region is dropped |
| `setSelection` | no edits; out-of-range requests ignored, as Android does |
| `getTextBeforeCursor`, `getTextAfterCursor`, `getSelectedText` | slices of an O(1) snapshot |

The cursor rule follows Android's `replaceText`. The requested position is computed against
the old text, then moved like an Editable selection point. An insertion at the point pushes it
forward. Inside a replaced range it moves to the end of the new text. Exception: a pure
insertion with `newCursorPosition` 0 keeps the cursor before the inserted text. Negative
`deleteSurroundingText` lengths delete nothing on that side and return true; the IME framework,
not the connection, rejects them.

## Evidence

`android.ime` (`ImeOracle.java`) runs on the UI thread, where IMEs call in. Each seed drives
TextInput and Android's own `BaseInputConnection` over an `Editable` with the same random
sequence: commits and composing text (including surrogate pairs and newlines), arbitrary
`newCursorPosition`, regions, reversed or out-of-range selections, and deletions with negative
lengths. After every operation it compares text, selection anchor and caret, and composing
region; every seventh operation it also compares the three cursor queries.

The first runs found two real rule differences: the `newCursorPosition` 0 insertion and the
negative lengths. They also showed that the fallback mode (`fullEditor=false`) is not a
reference, because it clears its Editable. Pixel bundle `4103cb0d5100...` (SDK 37): 40 seeds,
16000 operations and 6960 queries, all identical.

## Host view and change notification

`EditorView.java` hosts one draft:
- `onCheckIsTextEditor` is true.
- `onCreateInputConnection` fills `EditorInfo`: multi-line text with sentence caps, no
  fullscreen, the initial selection, and up to 1024 units of surrounding text on each side. It
  returns a `TextInput`.
- Changes made outside the IME are pushed, not polled. The keeper writes an eventfd
  (`tk_notify_fd`) when it merges or conflicts an external revision, or when a save fails.
- `EditorView` registers the fd with the main Looper (`addOnFileDescriptorEventListener`). On
  an event it reads the queue's external count and calls `InputMethodManager.restartInput`
  only if the text moved, so a failure notification does not disturb the IME.
- `android.editor` (Pixel bundle `ba63cde56abd...`): `EditorInfo` class, initial selection and
  surrounding text are correct. An agent edit merged on the keeper thread produced exactly one
  event and one restart, with the merged text right and the caret kept.

## Document

2026-10-29. The IME rules above are written against `TextInput.Backend` (state, one atomic
apply, slice), so the same code drives two text stores: `Draft` (the native draft queue) and
`DocumentText` (the derived view's paragraphs, [VIEW.md](VIEW.md) "Paragraph identity").

- The IME sees one text: the paragraphs joined by `'\n'`. Offsets map to (paragraph, offset)
  with `deriveLocate`, clamped; `deriveSlice` and `deriveDocState` read the joined text and
  the selection cells on the owner thread.
- A replacement of `[start, end)` rejoins the touched paragraphs with the new text and splits
  the result on `'\n'`. The first piece rewrites the first paragraph (`deriveText`); the other
  touched paragraphs are removed (`deriveRemove`) and the remaining pieces inserted
  (`deriveInsert`). Typing Enter splits a paragraph; deleting a newline merges two. Paragraphs
  outside the range keep their ids, so their layout is reused.
- The selection lives in the deriver's selection cells (caret and anchor), so the drawn caret
  and highlight follow IME edits. The composing region is kept in Java in joined offsets.
- The walks are O(paragraphs) per call until the order cells become a rope.

Evidence: `android.docime` (`DocImeOracle.java`) runs android.ime's random operations, with
newline-heavy pieces, against `BaseInputConnection` over an `Editable` holding the joined
text. After every operation the joined document, selection and composing region must match;
every seventh, the cursor queries too. Each seed ends by rendering: the derived scene of the
final document must equal the flat reference scene of its paragraphs.

## Cursor anchor

2026-10-29. `DocumentText.cursorAnchorInfo(matrix, w, h)` follows Editor.updateCursorAnchorInfo:
- selection range: anchor and caret in the joined text;
- the view-to-screen matrix;
- composing text, when the region is non-empty;
- the insertion marker: `deriveCursorAnchor` reads it on the owner from settled nodes. The
  overlay's caret rectangle gives the paragraph's viewport y. The caret node (now 20 bytes:
  x, top, bottom, baseline, flags) gives the line box, the baseline and the direction
  (TextLayout.rtlAt = Layout.isRtlCharAt). Visible and invisible flags test the top and bottom
  points as TextView.isPositionVisible does, against the view's w x h.

Not covered yet:
- a marker for a range selection (Editor puts it at the selection start);
- a marker for a caret in a paragraph the overlay does not walk (above the anchor or below
  the viewport); the IME gets none;
- character bounds for the composing text (`addCharacterBounds`).

Evidence: `android.anchor` (`AnchorOracle.java`) scrolls the viewport into its anchor
paragraph. It places 80 carets, including first and last lines clipped by the edges and RTL
characters, and compares the marker, flags and selection against Editor's rules on
StaticLayout. Every fifth caret it also checks a composing region across paragraphs.

## Not yet built

- The view becomes the native presenter's surface view. Its touch handling, focus and
  `showSoftInput` belong to the interaction layer.
- `sendKeyEvent` for hardware keys, plus clipboard, extracted text and accessibility. Bidi
  and grapheme-aware cursor movement belong to the text layout layer.
