package com.android.calendar;

import android.database.Cursor;
import android.test.mock.MockCursor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A cursor implementation that supports typed column access (getInt, getLong, getString)
 * for use in unit tests. Each row is a Map of column name to value.
 */
public class TypedFakeCursor extends MockCursor {

    private final String[] mColumnNames;
    private final List<Map<String, Object>> mRows;
    private int mPosition = -1;
    private boolean mClosed = false;

    /**
     * Creates a cursor with the given column names and row data.
     *
     * @param columnNames the column names for this cursor
     * @param rows a list of row maps, each mapping column name to value.
     *             Values can be Integer, Long, String, or null.
     */
    public TypedFakeCursor(String[] columnNames, List<Map<String, Object>> rows) {
        this.mColumnNames = columnNames;
        this.mRows = rows;
    }

    @Override
    public int getCount() {
        return mRows.size();
    }

    @Override
    public boolean moveToFirst() {
        if (mRows.isEmpty()) {
            return false;
        }
        mPosition = 0;
        return true;
    }

    @Override
    public boolean moveToNext() {
        mPosition++;
        return mPosition < mRows.size();
    }

    @Override
    public boolean moveToPosition(int position) {
        if (position >= -1 && position < mRows.size()) {
            mPosition = position;
            return position >= 0;
        }
        return false;
    }

    @Override
    public boolean isAfterLast() {
        return mPosition >= mRows.size();
    }

    @Override
    public boolean isBeforeFirst() {
        return mPosition < 0;
    }

    @Override
    public int getPosition() {
        return mPosition;
    }

    @Override
    public int getColumnIndex(String columnName) {
        for (int i = 0; i < mColumnNames.length; i++) {
            if (mColumnNames[i].equals(columnName)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public int getColumnIndexOrThrow(String columnName) {
        int index = getColumnIndex(columnName);
        if (index < 0) {
            throw new IllegalArgumentException("Column '" + columnName + "' not found");
        }
        return index;
    }

    @Override
    public String getColumnName(int columnIndex) {
        return mColumnNames[columnIndex];
    }

    @Override
    public String[] getColumnNames() {
        return mColumnNames.clone();
    }

    @Override
    public int getColumnCount() {
        return mColumnNames.length;
    }

    @Override
    public String getString(int columnIndex) {
        Object val = getValueAt(columnIndex);
        return val != null ? val.toString() : null;
    }

    @Override
    public int getInt(int columnIndex) {
        Object val = getValueAt(columnIndex);
        if (val instanceof Integer) return (Integer) val;
        if (val instanceof Long) return ((Long) val).intValue();
        if (val instanceof String) return Integer.parseInt((String) val);
        return 0;
    }

    @Override
    public long getLong(int columnIndex) {
        Object val = getValueAt(columnIndex);
        if (val instanceof Long) return (Long) val;
        if (val instanceof Integer) return ((Integer) val).longValue();
        if (val instanceof String) return Long.parseLong((String) val);
        return 0L;
    }

    @Override
    public boolean isNull(int columnIndex) {
        return getValueAt(columnIndex) == null;
    }

    @Override
    public void close() {
        mClosed = true;
    }

    public boolean isClosed() {
        return mClosed;
    }

    private Object getValueAt(int columnIndex) {
        if (mPosition < 0 || mPosition >= mRows.size()) {
            throw new IllegalStateException("Cursor not positioned at a valid row: " + mPosition);
        }
        String colName = mColumnNames[columnIndex];
        return mRows.get(mPosition).get(colName);
    }

    // ---- Builder for convenient cursor construction ----

    /**
     * Builder for constructing TypedFakeCursor instances with a fluent API.
     */
    public static class Builder {
        private final String[] mColumnNames;
        private final List<Map<String, Object>> mRows = new java.util.ArrayList<>();

        public Builder(String[] columnNames) {
            this.mColumnNames = columnNames;
        }

        public Builder addRow(Object... values) {
            if (values.length != mColumnNames.length) {
                throw new IllegalArgumentException(
                        "Expected " + mColumnNames.length + " values, got " + values.length);
            }
            Map<String, Object> row = new HashMap<>();
            for (int i = 0; i < mColumnNames.length; i++) {
                row.put(mColumnNames[i], values[i]);
            }
            mRows.add(row);
            return this;
        }

        public TypedFakeCursor build() {
            return new TypedFakeCursor(mColumnNames, mRows);
        }
    }
}
