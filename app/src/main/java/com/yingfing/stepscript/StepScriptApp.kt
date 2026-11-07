package com.yingfing.stepscript

import android.app.Application
import androidx.room.Room

class StepScriptApp : Application() {

    lateinit var db: AppDb
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        db = AppDb(
            Room.databaseBuilder(this, com.yingfing.stepscript.data.AppDatabase::class.java, "stepscript.db")
                .fallbackToDestructiveMigration()
                .build()
        )
        prefs = getSharedPreferences("settings", MODE_PRIVATE)
    }

    companion object {
        lateinit var instance: StepScriptApp
            private set
        lateinit var db: AppDb
            private set
        lateinit var prefs: android.content.SharedPreferences
            private set
    }
}

/** 轻量包装，方便注入。 */
class AppDb(val raw: com.yingfing.stepscript.data.AppDatabase) {
    val scripts by lazy { raw.scriptDao() }
    val versions by lazy { raw.versionDao() }
    val failures by lazy { raw.failureDao() }
}
