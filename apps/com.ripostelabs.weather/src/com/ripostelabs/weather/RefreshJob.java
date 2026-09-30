package com.ripostelabs.weather;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;
import org.json.JSONObject;

/**
 * RAV4-197 — refresh the cached reading hourly while there is network, for the place the app
 * last showed. Stock refreshes in the background too (`com.choiceway.weather`); without this
 * the home card goes stale an hour after the app was last opened.
 */
public final class RefreshJob extends JobService {

    private static final String TAG = "WeatherRefresh";
    private static final int JOB_ID = 197;
    private static final long PERIOD_MS = 60L * 60L * 1000L;

    /** Schedule once. A pending job is left alone so the period does not restart on each call. */
    public static void schedule(Context context) {
        JobScheduler js = context.getSystemService(JobScheduler.class);
        if (js == null || js.getPendingJob(JOB_ID) != null) {
            return;
        }
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(context, RefreshJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(PERIOD_MS)
                .setPersisted(true)
                .build();
        js.schedule(job);
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            boolean retry = !refresh(new WeatherStore(this));
            jobFinished(params, retry);
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }

    /** One fetch for the stored place. False on a network failure, so the scheduler retries. */
    private static boolean refresh(WeatherStore store) {
        double[] at = store.place();
        if (at == null) {
            // Never loaded: nothing to refresh until the app has shown a place once.
            return true;
        }
        try {
            WeatherLogic.Unit unit = store.unit();
            JSONObject root = WeatherFetch.forecast(at[0], at[1], unit);
            Integer aqi = WeatherFetch.aqi(at[0], at[1]);
            store.save(WeatherFetch.reading(root, aqi, store.placeName(), unit,
                    System.currentTimeMillis()), at[0], at[1]);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "refresh failed", e);
            return false;
        }
    }
}
