package app.hermesandroid.phone

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.provider.CalendarContract
import app.hermesandroid.graph
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

// Sharing with the app closed: PhoneSync and HealthSync otherwise pass only while the process lives (foreground,
// messages, pushes). JobScheduler (no WorkManager dependency) runs an hourly pass on any network, and a pass
// shortly after the calendar changes (a content-trigger job, re-armed after each run).

private const val HOURLY = 7101
private const val CALENDAR = 7102

object PhoneJobs {
    /** Arms both jobs while phone or health sharing is on; cancels them otherwise. */
    fun schedule(context: Context) {
        val js = context.getSystemService(JobScheduler::class.java)
        val graph = context.graph
        val phoneOn = graph.phone.prefs.enabled
        if (!phoneOn && !graph.health.prefs.enabled) { js.cancel(HOURLY); js.cancel(CALENDAR); return }
        val service = ComponentName(context, PhoneSyncJob::class.java)
        if (js.getPendingJob(HOURLY) == null) js.schedule(JobInfo.Builder(HOURLY, service)
            .setPeriodic(3600_000L, 15 * 60_000L).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).build())
        if (phoneOn && graph.phone.canReadCalendar()) armCalendar(context) else js.cancel(CALENDAR)
    }

    /** One-shot: fires 30 s to 2 min after a calendar edit (coalescing a burst of them). */
    fun armCalendar(context: Context) {
        context.getSystemService(JobScheduler::class.java).schedule(JobInfo.Builder(CALENDAR, ComponentName(context, PhoneSyncJob::class.java))
            .addTriggerContentUri(JobInfo.TriggerContentUri(CalendarContract.CONTENT_URI, JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
            .setTriggerContentUpdateDelay(30_000).setTriggerContentMaxDelay(120_000).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build())
    }
}

class PhoneSyncJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val graph = applicationContext.graph
        val calendar = params.jobId == CALENDAR
        graph.scope.launch {
            val passes = mutableListOf<Job>()
            graph.phone.sync(if (calendar) "calendar" else "background", force = true)?.let(passes::add)
            // Health Connect only answers a closed app with its "read in the background" permission.
            if (!calendar && graph.health.backgroundAllowed()) graph.health.sync("background")?.let(passes::add)
            passes.joinAll()
            android.util.Log.i("PhoneSyncJob", "job ${params.jobId} done: ${passes.size} pass(es)")
            if (calendar) PhoneJobs.armCalendar(applicationContext)
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}
