package moe.pulse.scan

import android.app.Application
import moe.pulse.scan.core.data.PulseDatabase
import moe.pulse.scan.core.data.SettingsRepository
import org.opencv.android.OpenCVLoader

class PulseApp : Application() {

    lateinit var database: PulseDatabase
        private set
    lateinit var settings: SettingsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        // Bundled native OpenCV; no loader activity, no network.
        OpenCVLoader.initLocal()
        database = PulseDatabase.get(this)
        settings = SettingsRepository(this)
    }
}
