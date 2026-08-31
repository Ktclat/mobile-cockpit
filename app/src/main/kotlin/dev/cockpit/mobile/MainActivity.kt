package dev.cockpit.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.cockpit.presentation.CockpitRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val component = (application as CockpitApplication).processComponent
        setContent {
            CockpitRoot(
                appName = component.shellAppName,
                agentApplicationPortHandle = component.agentApplicationPortHandle,
                conversationApplicationPortHandle = component.conversationApplicationPortHandle,
                agentConversationQueryPortHandle = component.agentConversationQueryPortHandle,
            )
        }
    }
}
