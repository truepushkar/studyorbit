package com.studyorbit.app

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

internal enum class TimerBackground(@StringRes val label: Int, @DrawableRes val artwork: Int) {
    OBSERVATORY(R.string.background_observatory, R.drawable.atlas_focus),
    FOREST(R.string.background_forest, R.drawable.focus_forest),
    DUSK(R.string.background_dusk, R.drawable.focus_dusk),
    OCEAN(R.string.background_ocean, R.drawable.focus_ocean),
    PHOTO(R.string.background_photo, R.drawable.atlas_focus)
}
