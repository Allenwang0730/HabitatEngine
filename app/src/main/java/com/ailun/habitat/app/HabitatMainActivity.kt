package com.ailun.habitat.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.ailun.habitat.WorkflowRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Habitat 应用主入口。
 * 进程级初始化（鉴权、API 客户端、触发器恢复）在 [HabitatApp] 中完成，这里不再重置任何状态。
 */
class HabitatMainActivity : ComponentActivity() {

    companion object {
        const val PREFS_NAME = "habitat_prefs"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // First-run → onboarding
        if (prefs.getBoolean("is_first_run", true)) {
            startActivity(Intent(this, HabitatOnboardingActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            HabitatShellManager.proactiveConnect(this@HabitatMainActivity)
        }

        setContent {
            HabitatTheme { HabitatDashboard(this) }
        }
    }

    override fun onStart() {
        super.onStart()
        if (isFinishing) return
        val mountedIds = WorkflowRepository.getFloatMountedWorkflowIds(this)
        if (mountedIds.isNotEmpty()) {
            HabitatFloatService.start(this)
        }
    }
}
