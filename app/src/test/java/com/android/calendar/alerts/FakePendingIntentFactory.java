package com.android.calendar.alerts;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import java.util.ArrayList;
import java.util.List;

/**
 * Fake implementation of {@link AlarmScheduler.PendingIntentFactory} that records
 * all created Intents for verification in tests. Returns null PendingIntent since
 * tests use {@link FakeAlarmManager} which doesn't need real PendingIntents.
 */
public class FakePendingIntentFactory implements AlarmScheduler.PendingIntentFactory {

    public final List<Intent> createdIntents = new ArrayList<>();
    public final List<Integer> createdFlags = new ArrayList<>();

    @Override
    public PendingIntent getBroadcast(Context context, int requestCode, Intent intent, int flags) {
        createdIntents.add(intent);
        createdFlags.add(flags);
        // Return null - FakeAlarmManager doesn't inspect the PendingIntent
        return null;
    }

    public int getCallCount() {
        return createdIntents.size();
    }
}
