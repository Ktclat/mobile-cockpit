# Slice 07 conversation evidence

Captured on 2026-08-31 from the real Android application on emulator-5560
(Cockpit_API_36, API 36), debug build variant, at 1080 × 2400 pixels.

The capture harness is
ConversationContinuityDeviceTest.switchesTwoConversationsWithoutLeakage.
It drives the visible application controls, waits on Compose semantics rather
than sleeping, and runs device screencap only after each state is observable.
The connected command was:

    .\gradlew.bat :presentation:connectedDebugAndroidTest :app:connectedDebugAndroidTest

The strengthened unchanged command passed three times during review-fix
verification, with a forced `--rerun-tasks` execution that ran all 169 Gradle
actions. The first six PNGs below were pulled after the final exact connected
run.

State setup:

- empty.png: fresh install, empty Room projection.
- one-agent.png: Agent Ada created, before any Conversation.
- multiple-conversations.png: two active Conversations for Ada, with the
  current Conversation identified and the switcher open.
- long-message.png: persisted long user Message and deterministic debug Agent
  Message rendered in the keyed scrollable timeline.
- dark-theme.png: the persisted long-message state after device night mode
  was enabled and the Activity recreated.
- font-scale-200.png: the same persisted state after device font scale was
  set to 2.0 and the Activity recreated.
- release-provider-unavailable.png: the fresh unsigned release APK was locally
  signed only for emulator installation, installed fresh, and used to create
  Agent ReleaseReviewAda and one Conversation. The user Message
  `release_review_message_persists` is visible as `You · accepted` beside
  `Configure a model provider`; no synthetic Agent Message is present. The
  app was force-stopped with PID 18455, had no PID while stopped, cold-launched
  in 879 ms with PID 18688, and recovered this same Room-backed state without
  clearing data.

After the six debug captures, the same device test leaves an unsent
exact-destination Draft in Conversation A, archives A, and recreates the
Activity before either consuming the Draft or restoring A. It first proves A
is still archived while B remains active and contains neither A's Draft nor A's
Messages. Only then does it explicitly restore A and prove the owning
Conversation recovers that Draft and history; switching back to B proves the
isolation still holds.

The harness restores device night mode and font scale to their normal values in
finally.

| File | Bytes | SHA-256 |
| --- | ---: | --- |
| empty.png | 36,646 | C1333EF275C0C4FFC8D29AD6CAA880CE5E6793E090ABA709F40972C07518F49E |
| one-agent.png | 43,798 | 2B65E8F7B97E4FCDFB4E31A239560D098E85B80890192FCDBFC7940CBA257FCE |
| multiple-conversations.png | 266,879 | D00896110B3226F948A5E5782E302D54F79783E77D7D88912150897742CAC609 |
| long-message.png | 258,757 | 46B8E906CF2625DA09144E75FE01EF9C91477173601D592873A15EAA5CCC377D |
| dark-theme.png | 270,408 | 5E7EB86A729B62B359EAF21F0FF6719B65D49FADFCCF9E19D75866D8F5165CCA |
| font-scale-200.png | 254,991 | B7F8EF3078B21102C6611657F761715D58728841A98BBC6A088AD504DEC2F75D |
| release-provider-unavailable.png | 90,198 | 73A2D9FF7E43F026287C7319A54DA2BCA15475831BFF584DFDE89C4409C0081A |

All seven files have the PNG signature 89504E470D0A1A0A, decode successfully at
1080 x 2400, have non-zero length, and have distinct SHA-256 values. The six
required debug states and the release state were visually inspected together as
a contact sheet at original aspect ratio. Agent identity precedes provider
status; the multiple-Conversation state identifies the current and second
Conversation; the long timeline remains readable and scrollable; the dark
capture uses a dark surface with light text; and the 200% capture keeps the
configuration, archive, back, draft, and send actions ordered and reachable
without overlap.
