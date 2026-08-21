package com.ailun.habitat.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.lifecycle.lifecycleScope
import com.ailun.habitat.R
import com.ailun.habitat.app.TriggerManager
import com.ailun.habitat.app.auth.AuthStateManager
import com.ailun.habitat.app.network.HabitatApiClient
import com.ailun.habitat.app.ui.HabitatSettingsActivity
import com.ailun.habitat.app.ui.ProfilePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Habitat 应用主入口。 */
class HabitatMainActivity : ComponentActivity() {

    companion object {
        const val PREFS_NAME = "habitat_prefs"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.isNavigationBarContrastEnforced = false

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // First-run → onboarding
        if (prefs.getBoolean("is_first_run", true)) {
            startActivity(Intent(this, HabitatOnboardingActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
            return
        }

        // Reset all workflow states on app restart
        TriggerManager.resetAll(this)

        // Initialize auth and API client on startup
        AuthStateManager.init(this)
        HabitatApiClient.init(ProfilePrefs.getServerUrl(this))

        // Normal launch → dashboard
        lifecycleScope.launch(Dispatchers.IO) {
            HabitatShellManager.proactiveConnect(this@HabitatMainActivity)
        }

        setContent {
            MaterialTheme(colorScheme = habitatColorScheme()) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    HabitatDashboard(this)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val mountedIds = com.ailun.habitat.WorkflowRepository.getFloatMountedWorkflowIds(this)
        if (mountedIds.isNotEmpty()) {
            HabitatFloatService.start(this)
        }
    }
}
