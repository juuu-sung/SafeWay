package com.safeway.app;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.List;
import java.util.Locale;

public class ReturnRecordActivity extends AppCompatActivity {
    private static final String FILTER_ALL = "all";
    private static final String FILTER_COMPLETE = "complete";
    private static final String FILTER_DELAYED = "delayed";

    private AppDatabase db;
    private LinearLayout recordList;
    private TextView recordCountText;
    private TextView averageDurationText;
    private TextView routeCountText;
    private TextView filterAllButton;
    private TextView filterCompleteButton;
    private TextView filterDelayedButton;
    private String currentFilter = FILTER_ALL;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SafeWayTheme.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_records);
        BottomNav.bind(this, ReturnRecordActivity.class);
        db = new AppDatabase(this);
        recordList = findViewById(R.id.recordList);
        recordCountText = findViewById(R.id.recordCountText);
        averageDurationText = findViewById(R.id.averageDurationText);
        routeCountText = findViewById(R.id.routeCountText);
        filterAllButton = findViewById(R.id.filterAllButton);
        filterCompleteButton = findViewById(R.id.filterCompleteButton);
        filterDelayedButton = findViewById(R.id.filterDelayedButton);
        filterAllButton.setOnClickListener(v -> selectFilter(FILTER_ALL));
        filterCompleteButton.setOnClickListener(v -> selectFilter(FILTER_COMPLETE));
        filterDelayedButton.setOnClickListener(v -> selectFilter(FILTER_DELAYED));
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderRecords();
    }

    private void selectFilter(String filter) {
        currentFilter = filter;
        renderRecords();
    }

    private void renderRecords() {
        List<AppDatabase.ReturnRecord> records = db.getReturnRecords();
        recordList.removeAllViews();
        styleFilterButtons();

        int totalDuration = 0;
        int totalExpected = 0;
        int expectedCount = 0;
        int visibleCount = 0;
        for (AppDatabase.ReturnRecord record : records) {
            totalDuration += record.durationMinutes;
            if (record.expectedMinutes > 0) {
                totalExpected += record.expectedMinutes;
                expectedCount++;
            }
            if (!matchesFilter(record)) {
                continue;
            }
            recordList.addView(createRecordCard(record));
            visibleCount++;
        }

        int average = records.isEmpty() ? 0 : totalDuration / records.size();
        int expectedAverage = expectedCount == 0 ? 0 : totalExpected / expectedCount;
        recordCountText.setText("이번 주\n" + records.size() + "회");
        averageDurationText.setText("평균 소요\n" + average + "분");
        routeCountText.setText(expectedAverage > 0
                ? "평균 예상\n" + expectedAverage + "분"
                : "경로 기록\n" + records.size() + "개");

        if (records.isEmpty() || visibleCount == 0) {
            TextView empty = new TextView(this);
            empty.setText(records.isEmpty()
                    ? "아직 귀가 기록이 없습니다.\n메인 화면에서 안심귀가를 시작하고 완료해보세요."
                    : "이 조건에 맞는 귀가 기록이 없습니다.");
            empty.setTextColor(getColor(R.color.safeway_muted));
            empty.setTextSize(14);
            empty.setBackgroundResource(R.drawable.bg_card);
            empty.setPadding(dp(20), dp(20), dp(20), dp(20));
            recordList.addView(empty);
        }
    }

    private void styleFilterButtons() {
        styleFilterButton(filterAllButton, FILTER_ALL.equals(currentFilter));
        styleFilterButton(filterCompleteButton, FILTER_COMPLETE.equals(currentFilter));
        styleFilterButton(filterDelayedButton, FILTER_DELAYED.equals(currentFilter));
    }

    private void styleFilterButton(TextView button, boolean selected) {
        button.setBackgroundResource(selected ? R.drawable.bg_primary : android.R.color.transparent);
        button.setTextColor(getColor(selected ? R.color.white : R.color.safeway_muted));
    }

    private boolean matchesFilter(AppDatabase.ReturnRecord record) {
        if (FILTER_DELAYED.equals(currentFilter)) {
            return record.expectedMinutes > 0 && record.durationMinutes > record.expectedMinutes;
        }
        if (FILTER_COMPLETE.equals(currentFilter)) {
            return "완료".equals(record.status);
        }
        return true;
    }

    private LinearLayout createRecordCard(AppDatabase.ReturnRecord record) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_record_row);
        card.setPadding(dp(22), dp(18), dp(22), dp(18));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 0, 0, dp(20));
        card.setLayoutParams(params);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setOrientation(LinearLayout.HORIZONTAL);
        card.addView(header);

        TextView date = new TextView(this);
        date.setText(record.createdDate == null || record.createdDate.trim().isEmpty()
                ? "날짜 없음"
                : record.createdDate);
        date.setTextColor(getColor(R.color.safeway_ink));
        date.setTextSize(18);
        date.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams dateParams = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        );
        date.setLayoutParams(dateParams);
        header.addView(date);

        TextView status = new TextView(this);
        status.setText(record.status == null || record.status.trim().isEmpty() ? "완료" : record.status);
        status.setTextColor(getColor(R.color.safeway_teal));
        status.setTextSize(11);
        status.setTypeface(null, Typeface.BOLD);
        status.setGravity(Gravity.CENTER);
        status.setBackgroundResource(R.drawable.bg_teal_soft);
        status.setPadding(dp(14), 0, dp(14), 0);
        header.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(30)
        ));

        TextView time = new TextView(this);
        time.setText(String.format(Locale.KOREA, "%s → %s · %d분",
                record.startTime, record.endTime, record.durationMinutes));
        time.setTextColor(getColor(R.color.safeway_muted));
        time.setTextSize(15);
        time.setPadding(0, dp(14), 0, 0);
        card.addView(time);

        TextView route = new TextView(this);
        route.setText("경로   현재 위치 → " + getDestinationLabel(record));
        route.setTextColor(getColor(R.color.safeway_muted));
        route.setTextSize(11);
        route.setTypeface(null, Typeface.BOLD);
        route.setPadding(0, dp(13), 0, 0);
        card.addView(route);

        LinearLayout metaRow = new LinearLayout(this);
        metaRow.setOrientation(LinearLayout.HORIZONTAL);
        metaRow.setGravity(Gravity.CENTER_VERTICAL);
        metaRow.setPadding(0, dp(10), 0, 0);
        card.addView(metaRow);

        metaRow.addView(createChip(getExpectedText(record), getColor(R.color.safeway_primary), R.drawable.bg_primary_soft, 1f));
        metaRow.addView(createChip(getResultText(record), getColor(R.color.safeway_teal), R.drawable.bg_teal_soft, 1f));

        if (record.usedAiCall) {
            TextView aiSummary = new TextView(this);
            aiSummary.setText("AI 통화 요약\n" + getAiSummaryText(record));
            aiSummary.setTextColor(getColor(R.color.safeway_voice));
            aiSummary.setTextSize(12);
            aiSummary.setLineSpacing(dp(2), 1f);
            aiSummary.setTypeface(null, Typeface.BOLD);
            aiSummary.setBackgroundResource(R.drawable.bg_primary_soft);
            aiSummary.setPadding(dp(14), dp(12), dp(14), dp(12));
            LinearLayout.LayoutParams aiParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            aiParams.setMargins(0, dp(12), 0, 0);
            card.addView(aiSummary, aiParams);

            if (record.aiTranscript != null && !record.aiTranscript.trim().isEmpty()) {
                TextView transcriptLink = new TextView(this);
                transcriptLink.setText("전체 대화 보기");
                transcriptLink.setTextColor(getColor(R.color.safeway_primary));
                transcriptLink.setTextSize(11);
                transcriptLink.setTypeface(null, Typeface.BOLD);
                transcriptLink.setPadding(0, dp(10), 0, 0);
                transcriptLink.setOnClickListener(v -> showAiTranscriptDialog(record));
                card.addView(transcriptLink);
            }
        }

        if (hasActualRoutePoints(record) || (record.routeLink != null && !record.routeLink.trim().isEmpty())) {
            TextView link = new TextView(this);
            link.setText(hasActualRoutePoints(record) ? "실제 경로 보기" : "경로 보기");
            link.setTextColor(getColor(R.color.safeway_primary));
            link.setTextSize(11);
            link.setTypeface(null, Typeface.BOLD);
            link.setPadding(0, dp(10), 0, 0);
            link.setOnClickListener(v -> openRecordRoute(record));
            card.addView(link);
        }
        return card;
    }

    private void openRecordRoute(AppDatabase.ReturnRecord record) {
        if (hasActualRoutePoints(record)) {
            Intent intent = new Intent(this, ReturnRouteActivity.class);
            intent.putExtra(ReturnRouteActivity.EXTRA_RECORD_ID, record.id);
            startActivity(intent);
            return;
        }

        String routeLink = record.routeLink;
        if (routeLink == null || routeLink.trim().isEmpty()) {
            Toast.makeText(this, "저장된 경로 링크가 없습니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        Uri sourceUri = Uri.parse(routeLink.trim());
        Uri kakaoMapUri = toKakaoMapAppUri(sourceUri);
        if (kakaoMapUri != null && openUri(kakaoMapUri, true)) {
            return;
        }
        openUri(toHttpsFallbackUri(sourceUri), false);
    }

    private Uri toKakaoMapAppUri(Uri sourceUri) {
        if (sourceUri == null) {
            return null;
        }
        String scheme = sourceUri.getScheme();
        if ("kakaomap".equalsIgnoreCase(scheme)) {
            return sourceUri;
        }
        String path = sourceUri.getPath();
        if (path == null) {
            return null;
        }
        if (path.contains("/scheme/route")) {
            String sp = sourceUri.getQueryParameter("sp");
            String ep = sourceUri.getQueryParameter("ep");
            if (isEmpty(sp) || isEmpty(ep)) {
                return null;
            }
            return Uri.parse("kakaomap://route").buildUpon()
                    .appendQueryParameter("sp", sp)
                    .appendQueryParameter("ep", ep)
                    .appendQueryParameter("by", normalizeRouteType(sourceUri.getQueryParameter("by")))
                    .build();
        }
        if (path.contains("/scheme/look")) {
            String point = sourceUri.getQueryParameter("p");
            if (isEmpty(point)) {
                return null;
            }
            return Uri.parse("kakaomap://look").buildUpon()
                    .appendQueryParameter("p", point)
                    .build();
        }
        if (path.contains("/scheme/search")) {
            String query = sourceUri.getQueryParameter("q");
            if (isEmpty(query)) {
                return null;
            }
            return Uri.parse("kakaomap://search").buildUpon()
                    .appendQueryParameter("q", query)
                    .build();
        }
        return null;
    }

    private Uri toHttpsFallbackUri(Uri uri) {
        if (uri == null) {
            return Uri.parse("https://map.kakao.com");
        }
        String value = uri.toString();
        if (value.startsWith("http://m.map.kakao.com")) {
            return Uri.parse("https://m.map.kakao.com" + value.substring("http://m.map.kakao.com".length()));
        }
        return uri;
    }

    private String normalizeRouteType(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "FOOT";
        }
        String normalized = value.trim().toUpperCase(Locale.US);
        if ("FOOT".equals(normalized) || "CAR".equals(normalized) || "PUBLICTRANSIT".equals(normalized)) {
            return normalized;
        }
        if ("WALK".equals(normalized) || "WALKING".equals(normalized)) {
            return "FOOT";
        }
        return "FOOT";
    }

    private boolean openUri(Uri uri, boolean preferKakaoMapApp) {
        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
        if (preferKakaoMapApp) {
            intent.setPackage("net.daum.android.map");
        }
        try {
            startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException firstError) {
            if (preferKakaoMapApp) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                    return true;
                } catch (ActivityNotFoundException | SecurityException ignored) {
                    return false;
                }
            }
            Toast.makeText(this, "카카오맵 또는 브라우저 앱을 열 수 없습니다.", Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private boolean isEmpty(String value) {
        return value == null || value.trim().isEmpty();
    }

    private boolean hasActualRoutePoints(AppDatabase.ReturnRecord record) {
        if (record == null || record.actualRoutePoints == null || record.actualRoutePoints.trim().isEmpty()) {
            return false;
        }
        int pointCount = 0;
        String[] pairs = record.actualRoutePoints.split(";");
        for (String pair : pairs) {
            if (pair == null || pair.trim().isEmpty()) {
                continue;
            }
            String[] coordinates = pair.split(",");
            if (coordinates.length == 2) {
                pointCount++;
            }
            if (pointCount >= 2) {
                return true;
            }
        }
        return false;
    }

    private void showAiTranscriptDialog(AppDatabase.ReturnRecord record) {
        TextView content = new TextView(this);
        content.setText(record.aiTranscript == null || record.aiTranscript.trim().isEmpty()
                ? getAiSummaryText(record)
                : record.aiTranscript.trim());
        content.setTextColor(getColor(R.color.safeway_ink));
        content.setTextSize(14);
        content.setLineSpacing(dp(4), 1f);
        content.setPadding(dp(22), dp(18), dp(22), dp(18));

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(content);

        new AlertDialog.Builder(this)
                .setTitle("AI 통화 전체 대화")
                .setView(scrollView)
                .setPositiveButton("닫기", null)
                .show();
    }

    private TextView createChip(String text, int color, int background, float weight) {
        TextView chip = new TextView(this);
        chip.setText(text);
        chip.setTextColor(color);
        chip.setTextSize(10);
        chip.setTypeface(null, Typeface.BOLD);
        chip.setGravity(Gravity.CENTER);
        chip.setBackgroundResource(background);
        chip.setPadding(dp(8), 0, dp(8), 0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0,
                dp(34),
                weight
        );
        params.setMargins(0, 0, dp(8), 0);
        chip.setLayoutParams(params);
        return chip;
    }

    private String getDestinationLabel(AppDatabase.ReturnRecord record) {
        if (record.routeDestination == null || record.routeDestination.trim().isEmpty()) {
            return "도착지 미정";
        }
        return record.routeDestination.trim();
    }

    private String getExpectedText(AppDatabase.ReturnRecord record) {
        if (record.expectedMinutes <= 0) {
            return "예상 시간 없음";
        }
        return "예상 " + record.expectedMinutes + "분";
    }

    private String getResultText(AppDatabase.ReturnRecord record) {
        if (record.expectedMinutes <= 0) {
            return "소요 " + record.durationMinutes + "분";
        }
        int diff = record.durationMinutes - record.expectedMinutes;
        if (diff == 0) {
            return "예상 시간과 같음";
        }
        if (diff < 0) {
            return "예상보다 " + Math.abs(diff) + "분 빠름";
        }
        return "예상보다 " + diff + "분 늦음";
    }

    private String getAiSummaryText(AppDatabase.ReturnRecord record) {
        if (record.aiSummary == null || record.aiSummary.trim().isEmpty()) {
            return "귀가 중 AI 안심 동행 통화를 사용함";
        }
        return record.aiSummary.trim();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
