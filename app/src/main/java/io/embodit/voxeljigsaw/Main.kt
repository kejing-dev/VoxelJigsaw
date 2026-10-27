package io.embodit.voxeljigsaw

import com.pico.spatial.ui.foundation.dsl.DefaultWindowContainer
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import io.embodit.voxeljigsaw.ui.JigsawScreen
import io.embodit.voxeljigsaw.ui.theme.JigsawTheme

fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultWindowContainer {
            JigsawTheme {
                JigsawScreen()
            }
        }
    }
