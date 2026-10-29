package com.keisha.tusur.timetable.ui.update

import androidx.annotation.StringRes
import com.keisha.tusur.timetable.R

@StringRes
fun UpdateError.messageRes(): Int = when (this) {
    UpdateError.NETWORK -> R.string.error_update_network
    UpdateError.SERVER -> R.string.error_update_server
    UpdateError.PARSE -> R.string.error_update_parse
    UpdateError.VERIFICATION -> R.string.error_update_verification
    UpdateError.INSTALL -> R.string.error_update_install
    UpdateError.UNKNOWN -> R.string.error_unknown
}
