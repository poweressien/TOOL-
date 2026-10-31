package com.toolkit.privacy

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** Asks for fingerprint / face / screen lock before showing something sensitive. Falls back to "allowed" on phones with no lock set. */
object AuthGate {
    private fun authenticators(): Int =
        if (Build.VERSION.SDK_INT >= 30) BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        else BiometricManager.Authenticators.BIOMETRIC_WEAK

    fun confirm(ctx: Context, title: String, onOk: () -> Unit, onFail: (String) -> Unit = {}) {
        val act = ctx.findActivity()
        if (act == null) { onFail("No activity"); return }
        val can = BiometricManager.from(ctx).canAuthenticate(authenticators())
        if (can != BiometricManager.BIOMETRIC_SUCCESS) { onOk(); return }
        val b = BiometricPrompt.PromptInfo.Builder().setTitle(title).setAllowedAuthenticators(authenticators())
        if (Build.VERSION.SDK_INT < 30) b.setNegativeButtonText("Cancel")
        BiometricPrompt(act, ContextCompat.getMainExecutor(act), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { onOk() }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { onFail(errString.toString()) }
        }).authenticate(b.build())
    }
}
