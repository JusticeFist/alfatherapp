package app.hermesandroid.assist

import android.Manifest
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import app.hermesandroid.R

/** Asks for the microphone once, for the assistant panel (a session can't ask itself); then the user calls again. */
class AssistActivity : ComponentActivity() {
    private val mic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        Toast.makeText(this, getString(if (ok) R.string.assistactivity_ready else R.string.assistactivity_no_mic), Toast.LENGTH_LONG).show()
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mic.launch(Manifest.permission.RECORD_AUDIO)
    }
}
