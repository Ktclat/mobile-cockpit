package dev.cockpit.mobile

import dev.cockpit.mobile.debug.DeterministicDemoAgent
import dev.cockpit.platform.android.ConversationTextResponder

internal object ResponderBinding {
    val responder: ConversationTextResponder = DeterministicDemoAgent()
}
