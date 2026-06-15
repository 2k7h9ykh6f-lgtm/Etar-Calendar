package com.android.calendar.alerts;

import android.app.NotificationChannel;
import android.content.Context;

import com.android.calendar.alerts.AlertService.NotificationWrapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Fake implementation of {@link NotificationMgr} that records all posted and cancelled
 * notifications for verification in tests.
 */
public class FakeNotificationMgr extends NotificationMgr {

    public final List<Integer> postedIds = new ArrayList<>();
    public final List<NotificationWrapper> postedNotifications = new ArrayList<>();
    public final List<Integer> cancelledIds = new ArrayList<>();
    public boolean cancelAllCalled = false;
    public int cancelAllFrom = -1;
    public int cancelAllTo = -1;

    @Override
    public void notify(Context context, int id, NotificationWrapper notification) {
        postedIds.add(id);
        postedNotifications.add(notification);
    }

    @Override
    public void cancel(int id) {
        cancelledIds.add(id);
    }

    @Override
    public void createNotificationChannel(NotificationChannel channel) {
        // No-op in tests
    }

    @Override
    public void cancelAll() {
        cancelAllCalled = true;
        super.cancelAll();
    }

    @Override
    public void cancelAllBetween(int from, int to) {
        cancelAllFrom = from;
        cancelAllTo = to;
        super.cancelAllBetween(from, to);
    }

    public boolean wasNotificationPosted(int id) {
        return postedIds.contains(id);
    }

    public boolean wasNotificationCancelled(int id) {
        return cancelledIds.contains(id);
    }

    public void reset() {
        postedIds.clear();
        postedNotifications.clear();
        cancelledIds.clear();
        cancelAllCalled = false;
        cancelAllFrom = -1;
        cancelAllTo = -1;
    }
}
