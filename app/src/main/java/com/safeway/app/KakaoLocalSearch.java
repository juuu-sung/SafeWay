package com.safeway.app;

import android.net.Uri;

import com.kakao.vectormap.LatLng;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class KakaoLocalSearch {
    private static final String KEYWORD_SEARCH_URL = "https://dapi.kakao.com/v2/local/search/keyword.json";
    private static final String ADDRESS_SEARCH_URL = "https://dapi.kakao.com/v2/local/search/address.json";
    private static final String COORD_TO_ADDRESS_URL = "https://dapi.kakao.com/v2/local/geo/coord2address.json";

    private KakaoLocalSearch() {
    }

    static List<Result> search(String restApiKey, String query, LatLng center) throws Exception {
        List<Result> keywordResults = request(restApiKey, KEYWORD_SEARCH_URL, query, center, true);
        if (!keywordResults.isEmpty()) {
            return keywordResults;
        }
        return request(restApiKey, ADDRESS_SEARCH_URL, query, center, false);
    }

    static String reverseGeocode(String restApiKey, LatLng point) throws Exception {
        if (point == null) {
            return "";
        }
        Uri uri = Uri.parse(COORD_TO_ADDRESS_URL).buildUpon()
                .appendQueryParameter("x", String.valueOf(point.longitude))
                .appendQueryParameter("y", String.valueOf(point.latitude))
                .appendQueryParameter("input_coord", "WGS84")
                .build();
        return parseAddress(requestJson(restApiKey, uri));
    }

    private static List<Result> request(String restApiKey, String endpoint, String query, LatLng center, boolean keyword) throws Exception {
        Uri.Builder builder = Uri.parse(endpoint).buildUpon()
                .appendQueryParameter("query", query)
                .appendQueryParameter("size", "10");
        if (keyword && center != null) {
            builder.appendQueryParameter("x", String.valueOf(center.longitude))
                    .appendQueryParameter("y", String.valueOf(center.latitude))
                    .appendQueryParameter("sort", "distance");
        }

        return parseResults(requestJson(restApiKey, builder.build()), keyword);
    }

    private static JSONObject requestJson(String restApiKey, Uri uri) throws Exception {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(uri.toString());
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(7000);
            connection.setReadTimeout(9000);
            connection.setRequestProperty("Authorization", "KakaoAK " + restApiKey);
            connection.setRequestProperty("Accept", "application/json");

            int status = connection.getResponseCode();
            String response = readResponse(connection, status);
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("Kakao Local API 응답 오류 " + status + ": " + response);
            }
            return new JSONObject(response);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String parseAddress(JSONObject json) {
        JSONArray documents = json.optJSONArray("documents");
        if (documents == null || documents.length() == 0) {
            return "";
        }
        JSONObject item = documents.optJSONObject(0);
        if (item == null) {
            return "";
        }
        return firstNonEmpty(nestedAddress(item, "road_address"), nestedAddress(item, "address"), "");
    }

    private static List<Result> parseResults(JSONObject json, boolean keyword) {
        List<Result> results = new ArrayList<>();
        JSONArray documents = json.optJSONArray("documents");
        if (documents == null) {
            return results;
        }
        for (int i = 0; i < documents.length(); i++) {
            JSONObject item = documents.optJSONObject(i);
            if (item == null) {
                continue;
            }
            double longitude = item.optDouble("x", Double.NaN);
            double latitude = item.optDouble("y", Double.NaN);
            if (!Double.isFinite(latitude) || !Double.isFinite(longitude)) {
                continue;
            }

            String name = keyword ? item.optString("place_name", "") : "";
            String roadAddress = keyword ? item.optString("road_address_name", "") : nestedAddress(item, "road_address");
            String address = item.optString("address_name", "");
            String label = firstNonEmpty(roadAddress, address, name);
            String display = name.isEmpty() || name.equals(label) ? label : name + "\n" + label;
            if (label.isEmpty()) {
                label = String.format(java.util.Locale.US, "%.7f,%.7f", latitude, longitude);
                display = label;
            }
            results.add(new Result(label, display, LatLng.from(latitude, longitude)));
        }
        return results;
    }

    private static String nestedAddress(JSONObject item, String key) {
        JSONObject nested = item.optJSONObject(key);
        return nested == null ? "" : nested.optString("address_name", "");
    }

    private static String firstNonEmpty(String first, String second, String third) {
        if (first != null && !first.trim().isEmpty()) {
            return first.trim();
        }
        if (second != null && !second.trim().isEmpty()) {
            return second.trim();
        }
        return third == null ? "" : third.trim();
    }

    private static String readResponse(HttpURLConnection connection, int status) throws Exception {
        InputStream stream = status >= 200 && status < 300
                ? connection.getInputStream()
                : connection.getErrorStream();
        if (stream == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
        }
        return builder.toString();
    }

    static final class Result {
        final String label;
        final String display;
        final LatLng latLng;

        Result(String label, String display, LatLng latLng) {
            this.label = label;
            this.display = display;
            this.latLng = latLng;
        }
    }
}
