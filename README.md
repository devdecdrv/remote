# Android MCP

An Android app that runs an MCP server on your phone, so an AI assistant (Claude, etc.) can see the screen and control the phone: taps, swipes, typing, apps, calls, SMS, notifications, files, power menu and more.

## Build the APK

The project was written without access to the Android SDK, so it has not been compiled yet. Pick one:

**GitHub (no install):** create a new GitHub repo, upload this folder, open the **Actions** tab, run **Build APK**, and download `android-mcp-debug-apk`. Any push also triggers a build.

**Android Studio:** open this folder, let Gradle sync, then Build > Build APK(s).

If the build shows a compile error, paste it back to Claude and it will fix it.

## Set up on the phone

1. Install the APK (allow "install unknown apps").
2. Open **Android MCP** and tap, in order:
   1. **Accessibility settings** and enable *Android MCP Control*. This is what lets it read the screen and tap. On some phones (Realme/Oppo) you may also need to allow restricted settings: App info > three dots > *Allow restricted settings*.
   2. **Notification access** and enable it.
   3. **Grant permissions** (calls, SMS, location, notifications).
   4. **All files access** (optional, only for shared storage; otherwise files live in the app's own folder).
   5. **Start server**. Also turn off battery optimisation for the app so it is not killed.
3. The app shows a URL (`http://<phone-ip>:8765/mcp`) and a token.

## Connect an AI client

The server speaks MCP over HTTP (JSON-RPC). Authenticate either with the header `Authorization: Bearer <token>` on `/mcp`, or by putting the token in the URL: `/mcp/<token>` (use this for claude.ai connectors, which only take a URL).

### Get an https link for Claude (Termux + Cloudflare quick tunnel)

1. Install **Termux** from F-Droid (the Play Store version is outdated).
2. In Termux run: `pkg update && pkg install cloudflared`
3. Start the Android MCP server in the app, then in Termux run: `cloudflared tunnel --url http://localhost:8765`
4. It prints a link like `https://random-words.trycloudflare.com`. Your connector URL is that link plus `/mcp/<token>`.
5. In claude.ai go to Settings > Connectors > Add custom connector and paste the URL. Keep Termux open (use `termux-wake-lock`), and the link changes each time you restart the tunnel.

- Same Wi-Fi: use `http://<phone-ip>:8765/mcp`.
- From anywhere: put the phone and your client on Tailscale, or expose the port with a tunnel. Do not expose it publicly without the token, and treat the token like a password: anyone holding it controls your phone.

Quick test from a computer:

```
curl -s -X POST http://<phone-ip>:8765/mcp \
  -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

## Tools (45)

| Area | Tools |
|---|---|
| Screen | get_screen_state, take_screenshot, find_nodes, wait, wait_for_text |
| Node actions | tap_node, long_click_node, scroll_node, set_node_text, type_text |
| Gestures | tap, double_tap, long_press, swipe, pinch, scroll |
| System / power | press_button (back, home, recents, notifications, quick_settings, power_dialog, lock_screen, take_screenshot), wake_screen, set_volume |
| Apps | list_apps, open_app, open_uri, send_intent |
| Calls / SMS | make_call, answer_call, end_call, call_state, set_speaker, set_mute, send_sms |
| Notifications | list_notifications, notification_open, notification_dismiss, notification_snooze, notification_action, notification_reply |
| Files | list_files, read_file, write_file, delete_file, download_file |
| Other | get_clipboard, set_clipboard, get_location, device_info |

## Limits you should know about

- **Calls:** the app can place, answer and end calls and toggle speaker and mute. It cannot feed an AI's voice into the call or listen to the other person: stock Android does not let ordinary apps access call audio.
- **Power button:** a real power-button press, shutdown or reboot is not possible without root or device-owner mode. `wake_screen` turns the screen on, `lock_screen` locks it, and `power_dialog` opens the power menu so you can tap Power off or Restart through the UI tree.
- **Secure screens** (banking apps, password fields) block screenshots and may hide their UI tree.
- **Clipboard reads** are restricted by Android 10+ when no app of ours is in the foreground.
- Camera, microphone recording and unlocking the PIN/pattern are not included.
- Calls and SMS are real and can cost money. Your AI client should ask you before using them.
