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

## Not yet built

- A host View: `onCreateInputConnection` with `EditorInfo` (input type, initial selection,
  surrounding text) on the eventual native surface view.
- `InputMethodManager.updateSelection` and cursor-anchor notifications after edits that did
  not come from the IME (agent merges and rebases change `external` in the queue).
- `sendKeyEvent` for hardware keys, plus clipboard, extracted text and accessibility. Bidi
  and grapheme-aware cursor movement belong to the text layout layer.
