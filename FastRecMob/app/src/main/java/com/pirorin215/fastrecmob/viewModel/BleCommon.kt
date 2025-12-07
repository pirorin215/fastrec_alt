package com.pirorin215.fastrecmob.viewModel

sealed class NavigationEvent {
    object NavigateBack : NavigationEvent()
}

enum class BleOperation {
    IDLE,
    FETCHING_DEVICE_INFO,
    FETCHING_FILE_LIST,
    FETCHING_SETTINGS,
    DOWNLOADING_FILE,
    SENDING_SETTINGS,
    DELETING_FILE,
    SENDING_TIME
}
