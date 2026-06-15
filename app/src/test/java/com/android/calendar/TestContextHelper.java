package com.android.calendar;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.test.mock.MockContentProvider;
import android.test.mock.MockContentResolver;
import android.test.mock.MockContext;

import java.util.HashMap;
import java.util.Map;

/**
 * Helper for building test Context instances with programmable ContentResolver
 * and SharedPreferences. Supports registering cursor responses matched by URI substring.
 */
public class TestContextHelper {

    private final TestContext mContext;
    private final MockContentResolver mContentResolver;
    private final ProgrammableContentProvider mContentProvider;
    private final FakeSharedPreferences mSharedPreferences;

    public TestContextHelper() {
        mContentResolver = new MockContentResolver();
        mContentProvider = new ProgrammableContentProvider();
        mSharedPreferences = new FakeSharedPreferences();
        mContext = new TestContext(mContentResolver, mSharedPreferences);

        // Register the programmable provider for all authorities
        mContentResolver.addProvider("com.android.calendar", mContentProvider);
        mContentResolver.addProvider("settings", mContentProvider);
    }

    public Context getContext() {
        return mContext;
    }

    public MockContentResolver getContentResolver() {
        return mContentResolver;
    }

    public ProgrammableContentProvider getContentProvider() {
        return mContentProvider;
    }

    public FakeSharedPreferences getSharedPreferences() {
        return mSharedPreferences;
    }

    /**
     * Register a cursor to be returned when a query URI contains the given substring.
     */
    public void addQueryResult(String uriSubstring, Cursor cursor) {
        mContentProvider.addQueryResult(uriSubstring, cursor);
    }

    /**
     * A MockContext that provides our fake ContentResolver and SharedPreferences.
     */
    public static class TestContext extends MockContext {
        private final ContentResolver mContentResolver;
        private final SharedPreferences mSharedPreferences;

        public TestContext(ContentResolver contentResolver, SharedPreferences sharedPreferences) {
            this.mContentResolver = contentResolver;
            this.mSharedPreferences = sharedPreferences;
        }

        @Override
        public ContentResolver getContentResolver() {
            return mContentResolver;
        }

        @Override
        public SharedPreferences getSharedPreferences(String name, int mode) {
            return mSharedPreferences;
        }

        @Override
        public String getPackageName() {
            return "com.android.calendar.test";
        }

        @Override
        public Object getSystemService(String name) {
            return null;
        }

        @Override
        public int checkPermission(String permission, int pid, int uid) {
            return PackageManager.PERMISSION_GRANTED;
        }

        @Override
        public Resources getResources() {
            return null;
        }
    }

    /**
     * A MockContentProvider that returns pre-programmed cursors based on URI substring matching.
     */
    public static class ProgrammableContentProvider extends MockContentProvider {
        private final Map<String, Cursor> mQueryResults = new HashMap<>();

        public ProgrammableContentProvider() {
            super(null);
        }

        public void addQueryResult(String uriSubstring, Cursor cursor) {
            mQueryResults.put(uriSubstring, cursor);
        }

        @Override
        public Cursor query(Uri uri, String[] projection, String selection,
                String[] selectionArgs, String sortOrder) {
            String uriStr = uri.toString();
            for (Map.Entry<String, Cursor> entry : mQueryResults.entrySet()) {
                if (uriStr.contains(entry.getKey())) {
                    return entry.getValue();
                }
            }
            // Return an empty cursor if no match found
            return new TypedFakeCursor(new String[0], new java.util.ArrayList<>());
        }

        @Override
        public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
            return 1;
        }

        @Override
        public Bundle call(String method, String request, Bundle args) {
            return null;
        }

        @Override
        public String getType(Uri uri) {
            return null;
        }

        @Override
        public boolean onCreate() {
            return false;
        }
    }
}
