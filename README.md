# BOOX Ink Sample

A minimal Android sample for low-latency sketching on BOOX e-ink tablets.

It intentionally has one feature: draw on a full-screen canvas. The point is to show the safe
`TouchHelper` pattern that keeps the BOOX firmware pen path fast without trapping touch input,
navigation, or the Android UI.

## Design

The app keeps two independent layers:

1. `SurfaceView` is given to BOOX `TouchHelper`. The firmware draws its temporary, near-zero-latency
   preview directly to the panel.
2. `SketchView` owns a normal Android bitmap. On pen-up, it commits the collected points into that
   bitmap, which is the durable drawing source of truth.

The raw firmware path is restricted to the canvas and explicitly excludes Android system bars.
Finger input is disabled in `TouchHelper`, leaving it to regular Android navigation. On pause and
destroy, both raw capture and raw rendering are disabled; the helper is closed on destroy.

After a short idle delay following pen-up, the sample briefly disables only the firmware render
passthrough, then restores it. This is the BOOX panel "unfreeze" step: it lets the panel and system
gestures settle without interrupting active handwriting.

The implementation follows the field-tested BOOX approach in ForestNote, but is purposely inline in
one activity rather than being a reusable ink architecture.

## Why this does not lag or trap the device

The important idea is that BOOX ink has **two different owners**. They must not be conflated.

1. `TouchHelper` owns only the live stylus preview. It renders directly to the e-ink panel through
   `SurfaceView`, so it is near-zero latency, but that preview is temporary.
2. `SketchView` owns the actual drawing. The raw callbacks collect pen points and, on pen-up, commit
   them into an ordinary Android bitmap. That bitmap is the durable source of truth.

This split is the reason the app can be both fast and safe: firmware handles the time-critical
preview; the app owns pixels that must persist through a refresh, tool switch, or lifecycle event.

### Firmware input and rendering are separate switches

`TouchHelper` exposes two independent controls:

- `setRawDrawingEnabled(...)` controls raw stylus capture and callbacks.
- `setRawDrawingRenderEnabled(...)` controls firmware's direct-to-panel preview.

Do not assume that turning off capture also turns off rendering. On BOOX firmware they can remain
independent: preview can still draw even after callbacks stop. This sample always drives both
switches together from the active tool state. It also reapplies that state after `openRawDrawing()`
and `setStroke*()` calls because those SDK calls may silently re-enable the raw engine.

### UI belongs to Android, never the raw ink engine

The raw engine is pen-only (`enableFingerTouch(false)`). System bars and the PEN / ERASE toolbar are
excluded from its drawing rectangle, leaving those regions to normal Android input. That means a
finger can always operate UI and navigation, while a pen tap on a control cannot turn into a stroke.

### The eraser deliberately takes the safe path

Selecting ERASE first disables both raw firmware switches. Erasing then uses ordinary Android stylus
events to clear the committed bitmap. It is not the low-latency preview path, but it prevents the
classic failure where firmware ink keeps drawing underneath a menu or non-pen tool. Selecting PEN
reconfigures the raw session and restores low-latency preview.

### What commonly causes broken BOOX sketch apps

- Letting raw firmware drawing cover the entire screen, including controls or system navigation.
- Updating a menu/tool without disabling both raw capture and raw rendering.
- Toggling the raw engine during an active stroke, or fully restarting it after every pen-up.
- Treating the temporary firmware preview as the persistent drawing instead of committing to an
  app-owned bitmap.

## Requirements

- A BOOX Android e-ink tablet with pen support
- Android 11 / API 30 or newer
- Android SDK installed locally

## Build and install

```sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Important BOOX rules demonstrated here

- Use `TouchHelper` for stylus input and direct-to-panel preview.
- Keep committed pixels in your own Android canvas/bitmap.
- Do not let firmware raw drawing own fingers or navigation regions.
- Drive `setRawDrawingEnabled` and `setRawDrawingRenderEnabled` together whenever input becomes
  unavailable.
- Never toggle raw capture after every stroke. Defer the render-only release until the writer pauses.
- Close raw drawing during lifecycle teardown.

## License

Apache-2.0. BOOX's SDK remains subject to BOOX's own terms.
