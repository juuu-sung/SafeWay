package com.safeway.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

public class AppDatabase extends SQLiteOpenHelper {
    private static final String DB_NAME = "safeway.db";
    private static final int DB_VERSION = 6;

    public AppDatabase(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE return_records (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "start_time TEXT NOT NULL," +
                "end_time TEXT NOT NULL," +
                "duration_minutes INTEGER NOT NULL," +
                "status TEXT NOT NULL," +
                "used_ai_call INTEGER NOT NULL," +
                "ai_summary TEXT," +
                "ai_transcript TEXT," +
                "route_destination TEXT," +
                "route_link TEXT," +
                "actual_route_points TEXT," +
                "expected_minutes INTEGER NOT NULL DEFAULT 0," +
                "created_date TEXT NOT NULL)");

        db.execSQL("CREATE TABLE danger_memos (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "place_name TEXT NOT NULL," +
                "reason TEXT NOT NULL," +
                "memo TEXT," +
                "latitude TEXT," +
                "longitude TEXT," +
                "location_address TEXT," +
                "created_at TEXT NOT NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE danger_memos ADD COLUMN latitude TEXT");
            db.execSQL("ALTER TABLE danger_memos ADD COLUMN longitude TEXT");
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE return_records ADD COLUMN route_destination TEXT");
            db.execSQL("ALTER TABLE return_records ADD COLUMN route_link TEXT");
            db.execSQL("ALTER TABLE return_records ADD COLUMN expected_minutes INTEGER NOT NULL DEFAULT 0");
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE return_records ADD COLUMN ai_transcript TEXT");
        }
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE danger_memos ADD COLUMN location_address TEXT");
        }
        if (oldVersion < 6) {
            db.execSQL("ALTER TABLE return_records ADD COLUMN actual_route_points TEXT");
        }
    }

    long insertReturnRecord(String startTime, String endTime, int durationMinutes, int expectedMinutes,
                            boolean usedAiCall, String aiSummary, String aiTranscript,
                            String routeDestination, String routeLink, String actualRoutePoints, String createdDate) {
        ContentValues values = new ContentValues();
        values.put("start_time", startTime);
        values.put("end_time", endTime);
        values.put("duration_minutes", durationMinutes);
        values.put("status", "완료");
        values.put("used_ai_call", usedAiCall ? 1 : 0);
        values.put("ai_summary", aiSummary == null ? "" : aiSummary);
        values.put("ai_transcript", aiTranscript == null ? "" : aiTranscript);
        values.put("route_destination", routeDestination == null ? "" : routeDestination);
        values.put("route_link", routeLink == null ? "" : routeLink);
        values.put("actual_route_points", actualRoutePoints == null ? "" : actualRoutePoints);
        values.put("expected_minutes", expectedMinutes);
        values.put("created_date", createdDate);
        return getWritableDatabase().insert("return_records", null, values);
    }

    List<ReturnRecord> getReturnRecords() {
        ArrayList<ReturnRecord> records = new ArrayList<>();
        Cursor cursor = getReadableDatabase().query(
                "return_records",
                null,
                null,
                null,
                null,
                null,
                "id DESC"
        );
        try {
            while (cursor.moveToNext()) {
                records.add(new ReturnRecord(
                        cursor.getInt(cursor.getColumnIndexOrThrow("id")),
                        cursor.getString(cursor.getColumnIndexOrThrow("start_time")),
                        cursor.getString(cursor.getColumnIndexOrThrow("end_time")),
                        cursor.getInt(cursor.getColumnIndexOrThrow("duration_minutes")),
                        cursor.getString(cursor.getColumnIndexOrThrow("status")),
                        cursor.getInt(cursor.getColumnIndexOrThrow("used_ai_call")) == 1,
                        cursor.getString(cursor.getColumnIndexOrThrow("ai_summary")),
                        getOptionalString(cursor, "ai_transcript"),
                        getOptionalString(cursor, "route_destination"),
                        getOptionalString(cursor, "route_link"),
                        getOptionalString(cursor, "actual_route_points"),
                        getOptionalInt(cursor, "expected_minutes"),
                        cursor.getString(cursor.getColumnIndexOrThrow("created_date"))
                ));
            }
        } finally {
            cursor.close();
        }
        return records;
    }

    ReturnRecord getReturnRecord(int id) {
        Cursor cursor = getReadableDatabase().query(
                "return_records",
                null,
                "id = ?",
                new String[]{String.valueOf(id)},
                null,
                null,
                null,
                "1"
        );
        try {
            if (!cursor.moveToFirst()) {
                return null;
            }
            return new ReturnRecord(
                    cursor.getInt(cursor.getColumnIndexOrThrow("id")),
                    cursor.getString(cursor.getColumnIndexOrThrow("start_time")),
                    cursor.getString(cursor.getColumnIndexOrThrow("end_time")),
                    cursor.getInt(cursor.getColumnIndexOrThrow("duration_minutes")),
                    cursor.getString(cursor.getColumnIndexOrThrow("status")),
                    cursor.getInt(cursor.getColumnIndexOrThrow("used_ai_call")) == 1,
                    cursor.getString(cursor.getColumnIndexOrThrow("ai_summary")),
                    getOptionalString(cursor, "ai_transcript"),
                    getOptionalString(cursor, "route_destination"),
                    getOptionalString(cursor, "route_link"),
                    getOptionalString(cursor, "actual_route_points"),
                    getOptionalInt(cursor, "expected_minutes"),
                    cursor.getString(cursor.getColumnIndexOrThrow("created_date"))
            );
        } finally {
            cursor.close();
        }
    }

    long insertDangerMemo(String placeName, String reason, String memo, String createdAt) {
        return insertDangerMemo(placeName, reason, memo, createdAt, "", "", "");
    }

    long insertDangerMemo(String placeName, String reason, String memo, String createdAt,
                          String latitude, String longitude) {
        return insertDangerMemo(placeName, reason, memo, createdAt, latitude, longitude, "");
    }

    long insertDangerMemo(String placeName, String reason, String memo, String createdAt,
                          String latitude, String longitude, String locationAddress) {
        ContentValues values = new ContentValues();
        values.put("place_name", placeName);
        values.put("reason", reason);
        values.put("memo", memo == null ? "" : memo);
        values.put("latitude", latitude == null ? "" : latitude);
        values.put("longitude", longitude == null ? "" : longitude);
        values.put("location_address", locationAddress == null ? "" : locationAddress);
        values.put("created_at", createdAt);
        return getWritableDatabase().insert("danger_memos", null, values);
    }

    List<DangerMemo> getDangerMemos() {
        ArrayList<DangerMemo> memos = new ArrayList<>();
        Cursor cursor = getReadableDatabase().query(
                "danger_memos",
                null,
                null,
                null,
                null,
                null,
                "id DESC"
        );
        try {
            while (cursor.moveToNext()) {
                memos.add(new DangerMemo(
                        cursor.getInt(cursor.getColumnIndexOrThrow("id")),
                        cursor.getString(cursor.getColumnIndexOrThrow("place_name")),
                        cursor.getString(cursor.getColumnIndexOrThrow("reason")),
                        cursor.getString(cursor.getColumnIndexOrThrow("memo")),
                        getOptionalString(cursor, "latitude"),
                        getOptionalString(cursor, "longitude"),
                        getOptionalString(cursor, "location_address"),
                        cursor.getString(cursor.getColumnIndexOrThrow("created_at"))
                ));
            }
        } finally {
            cursor.close();
        }
        return memos;
    }

    private String getOptionalString(Cursor cursor, String columnName) {
        int index = cursor.getColumnIndex(columnName);
        if (index < 0) {
            return "";
        }
        String value = cursor.getString(index);
        return value == null ? "" : value;
    }

    private int getOptionalInt(Cursor cursor, String columnName) {
        int index = cursor.getColumnIndex(columnName);
        if (index < 0 || cursor.isNull(index)) {
            return 0;
        }
        return cursor.getInt(index);
    }

    void seedDefaultMemosIfEmpty() {
        if (!getDangerMemos().isEmpty()) {
            return;
        }
        insertDangerMemo("학교 후문 골목", "가로등이 어둡고 사람이 적음", "밤 10시 이후에는 큰길 이용", "2026-05-18");
        insertDangerMemo("버스정류장 뒤편", "밤에 사람이 거의 없음", "큰길 정류장 이용 추천", "2026-05-17");
    }

    static class ReturnRecord {
        final int id;
        final String startTime;
        final String endTime;
        final int durationMinutes;
        final String status;
        final boolean usedAiCall;
        final String aiSummary;
        final String aiTranscript;
        final String routeDestination;
        final String routeLink;
        final String actualRoutePoints;
        final int expectedMinutes;
        final String createdDate;

        ReturnRecord(int id, String startTime, String endTime, int durationMinutes,
                     String status, boolean usedAiCall, String aiSummary, String aiTranscript,
                     String routeDestination, String routeLink, String actualRoutePoints,
                     int expectedMinutes, String createdDate) {
            this.id = id;
            this.startTime = startTime;
            this.endTime = endTime;
            this.durationMinutes = durationMinutes;
            this.status = status;
            this.usedAiCall = usedAiCall;
            this.aiSummary = aiSummary == null ? "" : aiSummary;
            this.aiTranscript = aiTranscript == null ? "" : aiTranscript;
            this.routeDestination = routeDestination == null ? "" : routeDestination;
            this.routeLink = routeLink == null ? "" : routeLink;
            this.actualRoutePoints = actualRoutePoints == null ? "" : actualRoutePoints;
            this.expectedMinutes = expectedMinutes;
            this.createdDate = createdDate;
        }
    }

    static class DangerMemo {
        final int id;
        final String placeName;
        final String reason;
        final String memo;
        final String latitude;
        final String longitude;
        final String locationAddress;
        final String createdAt;

        DangerMemo(int id, String placeName, String reason, String memo,
                   String latitude, String longitude, String locationAddress, String createdAt) {
            this.id = id;
            this.placeName = placeName;
            this.reason = reason;
            this.memo = memo == null ? "" : memo;
            this.latitude = latitude == null ? "" : latitude;
            this.longitude = longitude == null ? "" : longitude;
            this.locationAddress = locationAddress == null ? "" : locationAddress;
            this.createdAt = createdAt;
        }
    }
}
