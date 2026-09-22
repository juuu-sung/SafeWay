package com.safeway.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.location.Location;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class AppDatabase extends SQLiteOpenHelper {
    private static final String DB_NAME = "safeway.db";
    private static final int DB_VERSION = 6;
    private static final float DANGER_MEMO_DUPLICATE_RADIUS_METERS = 30f;

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
        return saveDangerMemo(
                -1,
                placeName,
                reason,
                memo,
                createdAt,
                latitude,
                longitude,
                locationAddress
        ).id;
    }

    DangerMemoWriteResult saveDangerMemo(int editingMemoId, String placeName, String reason,
                                         String memo, String createdAt, String latitude,
                                         String longitude, String locationAddress) {
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            DangerMemo editingMemo = editingMemoId > 0 ? getDangerMemo(database, editingMemoId) : null;
            DangerMemo duplicate = findDuplicateDangerMemo(
                    database,
                    editingMemoId,
                    placeName,
                    reason,
                    latitude,
                    longitude
            );

            if (duplicate != null) {
                DangerMemo incoming = new DangerMemo(
                        editingMemoId,
                        placeName,
                        reason,
                        memo,
                        latitude,
                        longitude,
                        locationAddress,
                        createdAt
                );
                DangerMemo merged = mergeDangerMemos(duplicate, incoming);
                database.update(
                        "danger_memos",
                        dangerMemoValues(merged),
                        "id = ?",
                        new String[]{String.valueOf(duplicate.id)}
                );
                if (editingMemo != null && editingMemo.id != duplicate.id) {
                    database.delete(
                            "danger_memos",
                            "id = ?",
                            new String[]{String.valueOf(editingMemo.id)}
                    );
                }
                database.setTransactionSuccessful();
                return new DangerMemoWriteResult(duplicate.id, DangerMemoWriteType.MERGED);
            }

            ContentValues values = dangerMemoValues(
                    new DangerMemo(
                            editingMemoId,
                            placeName,
                            reason,
                            memo,
                            latitude,
                            longitude,
                            locationAddress,
                            createdAt
                    )
            );
            if (editingMemo != null) {
                int updated = database.update(
                        "danger_memos",
                        values,
                        "id = ?",
                        new String[]{String.valueOf(editingMemo.id)}
                );
                database.setTransactionSuccessful();
                return new DangerMemoWriteResult(
                        updated > 0 ? editingMemo.id : -1,
                        DangerMemoWriteType.UPDATED
                );
            }

            long id = database.insert("danger_memos", null, values);
            database.setTransactionSuccessful();
            return new DangerMemoWriteResult(id, DangerMemoWriteType.INSERTED);
        } finally {
            database.endTransaction();
        }
    }

    List<DangerMemo> getDangerMemos() {
        return getDangerMemos(getReadableDatabase());
    }

    boolean deleteDangerMemo(int id) {
        return getWritableDatabase().delete(
                "danger_memos",
                "id = ?",
                new String[]{String.valueOf(id)}
        ) > 0;
    }

    int countDuplicateDangerMemos() {
        List<DangerMemo> memos = getDangerMemos();
        ArrayList<DangerMemo> uniqueMemos = new ArrayList<>();
        int duplicateCount = 0;
        for (DangerMemo memo : memos) {
            if (findDuplicateIndex(uniqueMemos, memo) >= 0) {
                duplicateCount++;
            } else {
                uniqueMemos.add(memo);
            }
        }
        return duplicateCount;
    }

    int mergeDuplicateDangerMemos() {
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            List<DangerMemo> memos = getDangerMemos(database);
            ArrayList<DangerMemo> uniqueMemos = new ArrayList<>();
            int mergedCount = 0;

            for (DangerMemo memo : memos) {
                int duplicateIndex = findDuplicateIndex(uniqueMemos, memo);
                if (duplicateIndex < 0) {
                    uniqueMemos.add(memo);
                    continue;
                }

                DangerMemo survivor = uniqueMemos.get(duplicateIndex);
                DangerMemo merged = mergeDangerMemos(survivor, memo);
                database.update(
                        "danger_memos",
                        dangerMemoValues(merged),
                        "id = ?",
                        new String[]{String.valueOf(survivor.id)}
                );
                database.delete(
                        "danger_memos",
                        "id = ?",
                        new String[]{String.valueOf(memo.id)}
                );
                uniqueMemos.set(duplicateIndex, merged);
                mergedCount++;
            }

            database.setTransactionSuccessful();
            return mergedCount;
        } finally {
            database.endTransaction();
        }
    }

    private List<DangerMemo> getDangerMemos(SQLiteDatabase database) {
        ArrayList<DangerMemo> memos = new ArrayList<>();
        Cursor cursor = database.query(
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

    private DangerMemo getDangerMemo(SQLiteDatabase database, int id) {
        Cursor cursor = database.query(
                "danger_memos",
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
            return dangerMemoFromCursor(cursor);
        } finally {
            cursor.close();
        }
    }

    private DangerMemo findDuplicateDangerMemo(SQLiteDatabase database, int excludedId,
                                                String placeName, String reason,
                                                String latitude, String longitude) {
        DangerMemo candidate = new DangerMemo(
                excludedId,
                placeName,
                reason,
                "",
                latitude,
                longitude,
                "",
                ""
        );
        for (DangerMemo memo : getDangerMemos(database)) {
            if (memo.id != excludedId && areDuplicateDangerMemos(memo, candidate)) {
                return memo;
            }
        }
        return null;
    }

    private int findDuplicateIndex(List<DangerMemo> memos, DangerMemo candidate) {
        for (int index = 0; index < memos.size(); index++) {
            if (areDuplicateDangerMemos(memos.get(index), candidate)) {
                return index;
            }
        }
        return -1;
    }

    private boolean areDuplicateDangerMemos(DangerMemo first, DangerMemo second) {
        String firstReason = normalizeDangerMemoText(first.reason);
        String secondReason = normalizeDangerMemoText(second.reason);
        if (firstReason.isEmpty() || !firstReason.equals(secondReason)) {
            return false;
        }

        String firstPlace = normalizeDangerMemoText(first.placeName);
        String secondPlace = normalizeDangerMemoText(second.placeName);
        if (!firstPlace.isEmpty() && firstPlace.equals(secondPlace)) {
            return true;
        }

        double[] firstPoint = parseDangerMemoPoint(first.latitude, first.longitude);
        double[] secondPoint = parseDangerMemoPoint(second.latitude, second.longitude);
        if (firstPoint == null || secondPoint == null) {
            return false;
        }
        float[] distance = new float[1];
        Location.distanceBetween(
                firstPoint[0],
                firstPoint[1],
                secondPoint[0],
                secondPoint[1],
                distance
        );
        return distance[0] <= DANGER_MEMO_DUPLICATE_RADIUS_METERS;
    }

    private String normalizeDangerMemoText(String value) {
        if (value == null) {
            return "";
        }
        return value.trim()
                .toLowerCase(Locale.KOREA)
                .replaceAll("[\\s\\p{Punct}·ㆍ]+", "");
    }

    private double[] parseDangerMemoPoint(String latitude, String longitude) {
        if (latitude == null || longitude == null
                || latitude.trim().isEmpty() || longitude.trim().isEmpty()) {
            return null;
        }
        try {
            return new double[]{
                    Double.parseDouble(latitude.trim()),
                    Double.parseDouble(longitude.trim())
            };
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private DangerMemo mergeDangerMemos(DangerMemo primary, DangerMemo secondary) {
        return new DangerMemo(
                primary.id,
                preferNonEmpty(primary.placeName, secondary.placeName),
                preferNonEmpty(primary.reason, secondary.reason),
                mergeMemoDetails(primary.memo, secondary.memo),
                preferNonEmpty(primary.latitude, secondary.latitude),
                preferNonEmpty(primary.longitude, secondary.longitude),
                preferNonEmpty(primary.locationAddress, secondary.locationAddress),
                preferNonEmpty(primary.createdAt, secondary.createdAt)
        );
    }

    private String mergeMemoDetails(String primary, String secondary) {
        String first = primary == null ? "" : primary.trim();
        String second = secondary == null ? "" : secondary.trim();
        if (first.isEmpty()) {
            return second;
        }
        if (second.isEmpty() || first.equals(second) || first.contains(second)) {
            return first;
        }
        if (second.contains(first)) {
            return second;
        }
        return first + " · " + second;
    }

    private String preferNonEmpty(String primary, String secondary) {
        if (primary != null && !primary.trim().isEmpty()) {
            return primary.trim();
        }
        return secondary == null ? "" : secondary.trim();
    }

    private ContentValues dangerMemoValues(DangerMemo memo) {
        ContentValues values = new ContentValues();
        values.put("place_name", memo.placeName);
        values.put("reason", memo.reason);
        values.put("memo", memo.memo);
        values.put("latitude", memo.latitude);
        values.put("longitude", memo.longitude);
        values.put("location_address", memo.locationAddress);
        values.put("created_at", memo.createdAt);
        return values;
    }

    private DangerMemo dangerMemoFromCursor(Cursor cursor) {
        return new DangerMemo(
                cursor.getInt(cursor.getColumnIndexOrThrow("id")),
                cursor.getString(cursor.getColumnIndexOrThrow("place_name")),
                cursor.getString(cursor.getColumnIndexOrThrow("reason")),
                cursor.getString(cursor.getColumnIndexOrThrow("memo")),
                getOptionalString(cursor, "latitude"),
                getOptionalString(cursor, "longitude"),
                getOptionalString(cursor, "location_address"),
                cursor.getString(cursor.getColumnIndexOrThrow("created_at"))
        );
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

    enum DangerMemoWriteType {
        INSERTED,
        UPDATED,
        MERGED
    }

    static class DangerMemoWriteResult {
        final long id;
        final DangerMemoWriteType type;

        DangerMemoWriteResult(long id, DangerMemoWriteType type) {
            this.id = id;
            this.type = type;
        }

        boolean isSuccessful() {
            return id > 0;
        }
    }
}
