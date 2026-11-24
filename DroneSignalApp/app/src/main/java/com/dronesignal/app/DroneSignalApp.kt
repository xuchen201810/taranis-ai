package com.dronesignal.app

import android.app.Application
import com.dronesignal.app.signal.SignalRepository

class DroneSignalApp : Application() {
    val repository by lazy { SignalRepository() }
}
