package com.example

import android.app.Application
import com.example.core.repository.JobRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Rehydrates durable client work after process recreation. */
class IsmApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch {
            runCatching { JobRepository(this@IsmApplication).recoverPendingJobs() }
        }
    }
}
