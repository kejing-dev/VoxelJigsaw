package io.embodit.voxeljigsaw.platform

import android.app.Application
import com.pico.spatial.ui.foundation.dsl.launch
import io.embodit.voxeljigsaw.mainApp

class JigsawApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        launch(::mainApp)
    }
}
