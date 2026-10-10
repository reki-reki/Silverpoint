# Security policy

## Supported versions

Silverpoint is in beta. Only the latest release (or, before the first release, the latest commit on `main`) is supported.

## Reporting a vulnerability

Please **do not** open a public issue for a security problem. Use GitHub's private reporting instead: open the repository's **Security** tab and choose **Report a vulnerability**.

Include what you found, how to reproduce it, and which version or commit is affected. You can expect an acknowledgement within a week. This is a volunteer project, so fix times are best-effort.

## Scope

Silverpoint asks only for the camera, vibration (haptics) and foreground-service permissions (to finish developing photos after you leave the app). It has no internet permission, no accounts and no analytics. Issues of interest include unsafe file handling when saving to MediaStore or Downloads, leaking location or device data in EXIF or DNG metadata, and crashes that can be triggered from outside the app.
