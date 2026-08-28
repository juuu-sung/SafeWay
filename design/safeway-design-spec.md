# SafeWay Android UI Design Spec

Figma file: https://www.figma.com/design/gQUw6RNAly08leKiIzBqRm

Note: Figma MCP reached the Starter plan tool-call limit after creating the file, so the editable screen board is provided as `design/safeway-android-ui.svg`. Import this SVG into the Figma file when MCP access is available again.

## App Direction

SafeWay is an AI-assisted safe-return-home Android app for women and students. The app focuses on return-home status tracking, quick emergency contact, guardian contact, AI companion call, return records, and danger-location memos.

## Screens

1. Main / idle
   - Current state: before return
   - Expected return time chips
   - Start return CTA
   - 112 call, guardian call, AI companion call
   - Entry points for records, danger memos, guardian settings

2. Main / active return
   - Elapsed timer
   - Start time and expected return time
   - Progress bar
   - Return complete CTA
   - AI companion call, 112 call, guardian call
   - Nearby caution memo preview

3. AI companion call
   - Mode selector: guardian, friend, calm guide
   - Voice conversation transcript
   - Risk keyword alert area
   - 112, guardian, end call controls
   - Large microphone action

4. Guardian settings
   - Current guardian summary
   - Name, phone, relationship inputs
   - Relationship chips
   - Save CTA

5. Return records
   - Weekly summary metrics
   - Filter tabs
   - Record cards with start/end/duration/status
   - AI call summary when used

6. Danger memo
   - Existing danger-location memo cards
   - Quick add form
   - Place, reason, detailed memo
   - Save CTA

## Visual Tokens

Use these as `colors.xml` or Compose color tokens.

```xml
<color name="safeway_bg">#F6F8FB</color>
<color name="safeway_surface">#FFFFFF</color>
<color name="safeway_ink">#152137</color>
<color name="safeway_muted">#5E6A7D</color>
<color name="safeway_border">#DCE4EE</color>
<color name="safeway_primary">#1F6FEB</color>
<color name="safeway_teal">#0C9488</color>
<color name="safeway_danger">#E5484D</color>
<color name="safeway_success">#26A269</color>
<color name="safeway_warning">#C77800</color>
<color name="safeway_soft_blue">#EAF2FF</color>
<color name="safeway_soft_teal">#E6F7F4</color>
<color name="safeway_soft_red">#FFECEE</color>
```

## Android Mapping

Recommended Activity structure:

- `MainActivity`: idle/active return state, timer, emergency actions
- `AiCallActivity`: SpeechRecognizer, AI API response, TextToSpeech, risk keyword highlight
- `GuardianActivity`: guardian SharedPreferences form
- `ReturnRecordActivity`: Room DB records with RecyclerView
- `DangerMemoActivity`: memo list and quick add or navigation to add screen
- `AddDangerMemoActivity`: optional separate full memo registration screen

Recommended UI constants:

- Phone frame target: 390 x 844 in design; Android can map this to full-screen portrait layouts.
- Card radius: 8dp
- Button radius: 8dp
- Horizontal page padding: 24dp
- Card padding: 20dp
- Section gap: 18dp to 24dp
- Primary button height: 52dp
- Input height: 52dp

## Content Rules

- Avoid calling the AI feature a fake call or impersonation feature.
- Use labels like `AI 안심 동행 통화`, `보호자 모드`, `친구 모드`, `차분한 안내 모드`.
- Use `ACTION_DIAL` for 112 and guardian calls to avoid direct-call permission burden.
- For the first implementation, detect risk keywords locally with string matching before adding AI-side risk classification.
