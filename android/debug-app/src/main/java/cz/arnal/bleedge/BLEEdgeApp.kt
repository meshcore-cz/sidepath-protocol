package cz.arnal.bleedge

import android.app.Application
import android.util.Log

class BLEEdgeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.i("BLEEdgeApp", "Application started")
    }
}
