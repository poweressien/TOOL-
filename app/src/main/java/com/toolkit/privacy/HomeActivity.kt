package com.toolkit.privacy

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface

/** Optional home screen: the organized app launcher. Disabled until the user turns it on from Assist > Home. */
class HomeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppTheme { Surface { OrganizerScreen(asHome = true) } } }
    }
}
