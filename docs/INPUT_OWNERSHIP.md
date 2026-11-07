# Input ownership

App controls must not inject commands into or overwrite the AI prompt to implement app fields.
Music has its own search EditText. The shared native keyboard targets the focused EditText and
pins that destination for the gesture. App text stays in the app field; AI drafts remain intact.
Pending prompt autosend defers while editing an app field and rearms on return to the prompt/draft tab.
Keep text, selection and focus across reloads. App draft persistence is independent of prompt storage.

Fullscreen app geometry stays independent of the floating input overlay. The Trough navigation
bar owns Back, Home and recent native views; a slim readout strip opens trace controls. Android
system bars are transient and remain available by edge swipe. Verification must cover focus changes,
pause-to-send, both drafts, reload while editing, trace modes, navigation and overlay input shielding.
Compilation alone does not establish that live interaction or seamless reload is correct.
