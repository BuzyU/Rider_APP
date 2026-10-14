export interface TesterEmailData {
  name: string;
  email: string;
  version: string;
  fileName: string;
  downloadUrl: string;
  releasePageUrl: string;
  sha256: string;
  fileSize?: string;
}

export function buildTesterWelcomeEmail(data: TesterEmailData): { subject: string; html: string; text: string } {
  const firstName = data.name.trim().split(' ')[0] || 'Rider';
  const subject = `Welcome to the Convoy! Your RiderVoice APK (${data.version}) is ready`;

  const html = `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>${subject}</title>
</head>
<body style="margin: 0; padding: 0; background-color: #f5f5f7; font-family: -apple-system, BlinkMacSystemFont, 'SF Pro Display', 'Inter', 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; -webkit-font-smoothing: antialiased; color: #1d1d1f; line-height: 1.6;">
  <table role="presentation" width="100%" cellspacing="0" cellpadding="0" border="0" style="background-color: #f5f5f7; padding: 40px 16px;">
    <tr>
      <td align="center">
        <!-- Main Container Card -->
        <table role="presentation" width="100%" cellspacing="0" cellpadding="0" border="0" style="max-width: 600px; background-color: #ffffff; border-radius: 24px; overflow: hidden; border: 1px solid #e5e5ea; box-shadow: 0 10px 40px rgba(0,0,0,0.04);">
          
          <!-- Top Accent Bar -->
          <tr>
            <td style="background-color: #ff5500; height: 6px; line-height: 6px; font-size: 6px;">&nbsp;</td>
          </tr>

          <!-- Header -->
          <tr>
            <td style="padding: 40px 40px 24px 40px; text-align: left;">
              <div style="display: inline-block; background-color: rgba(255, 85, 0, 0.1); color: #ff5500; font-size: 11px; font-weight: 700; text-transform: uppercase; letter-spacing: 1px; padding: 6px 14px; border-radius: 9999px; margin-bottom: 20px;">
                ● Closed Beta Approved
              </div>
              <h1 style="margin: 0 0 8px 0; font-size: 28px; font-weight: 700; color: #1d1d1f; letter-spacing: -0.5px;">
                RIDERVOICE
              </h1>
              <p style="margin: 0; font-size: 14px; color: #86868b; text-transform: uppercase; letter-spacing: 0.5px; font-weight: 500;">
                Tactical Comms. No Compromise.
              </p>
            </td>
          </tr>

          <!-- Divider -->
          <tr>
            <td style="padding: 0 40px;">
              <hr style="border: none; border-top: 1px solid #f0f0f2; margin: 0;">
            </td>
          </tr>

          <!-- Body Content -->
          <tr>
            <td style="padding: 32px 40px;">
              <h2 style="margin: 0 0 16px 0; font-size: 22px; font-weight: 600; color: #1d1d1f; letter-spacing: -0.3px;">
                Welcome to the convoy, ${firstName}!
              </h2>
              <p style="margin: 0 0 24px 0; font-size: 15px; color: #4b4b50; line-height: 1.6;">
                Thank you for applying to test RiderVoice! Your application has been approved, and your Android APK build is ready for download. Experience hands-free VOX audio, Bluetooth helmet integration, and low-latency group channels on your next ride.
              </p>

              <!-- Download Button Box -->
              <table role="presentation" width="100%" cellspacing="0" cellpadding="0" border="0" style="margin: 30px 0;">
                <tr>
                  <td align="center">
                    <a href="${data.downloadUrl}" target="_blank" style="display: inline-block; background-color: #ff5500; color: #ffffff; text-decoration: none; font-size: 16px; font-weight: 600; padding: 16px 36px; border-radius: 9999px; box-shadow: 0 6px 20px rgba(255,85,0,0.35);">
                      Download APK (${data.version})
                    </a>
                  </td>
                </tr>
                <tr>
                  <td align="center" style="padding-top: 12px;">
                    <a href="${data.releasePageUrl}" target="_blank" style="color: #86868b; text-decoration: underline; font-size: 13px;">
                      View release notes on GitHub →
                    </a>
                  </td>
                </tr>
              </table>

              <!-- Release Details Card -->
              <table role="presentation" width="100%" cellspacing="0" cellpadding="0" border="0" style="background-color: #fbfbfd; border: 1px solid #e5e5ea; border-radius: 16px; margin: 24px 0;">
                <tr>
                  <td style="padding: 20px;">
                    <div style="font-size: 12px; font-weight: 700; color: #86868b; text-transform: uppercase; letter-spacing: 0.5px; margin-bottom: 12px;">
                      Release Specifications
                    </div>
                    <table role="presentation" width="100%" cellspacing="0" cellpadding="0" border="0" style="font-size: 13px; color: #1d1d1f;">
                      <tr>
                        <td style="padding: 4px 0; color: #86868b; width: 110px;">Version:</td>
                        <td style="padding: 4px 0; font-weight: 600;">${data.version}</td>
                      </tr>
                      <tr>
                        <td style="padding: 4px 0; color: #86868b;">Package File:</td>
                        <td style="padding: 4px 0; font-family: monospace;">${data.fileName}</td>
                      </tr>
                      <tr>
                        <td style="padding: 4px 0; color: #86868b;">Target:</td>
                        <td style="padding: 4px 0;">Universal ARM (arm64-v8a / armeabi-v7a)</td>
                      </tr>
                      <tr>
                        <td style="padding: 4px 0; color: #86868b;">SHA-256:</td>
                        <td style="padding: 4px 0; font-family: monospace; font-size: 11px; word-break: break-all; color: #55555a;">
                          ${data.sha256}
                        </td>
                      </tr>
                    </table>
                  </td>
                </tr>
              </table>

              <!-- 3 Step Android Guide -->
              <div style="margin-top: 32px;">
                <h3 style="margin: 0 0 16px 0; font-size: 16px; font-weight: 600; color: #1d1d1f;">
                  Quick 3-Step Installation Guide
                </h3>
                <table role="presentation" width="100%" cellspacing="0" cellpadding="0" border="0">
                  <tr>
                    <td style="vertical-align: top; width: 28px; padding-bottom: 14px;">
                      <div style="width: 22px; height: 22px; border-radius: 50%; background-color: #1d1d1f; color: #ffffff; font-size: 11px; font-weight: 700; text-align: center; line-height: 22px;">1</div>
                    </td>
                    <td style="vertical-align: top; padding-bottom: 14px; font-size: 14px; color: #4b4b50;">
                      <strong>Download APK:</strong> Tap the orange download button above on your Android phone.
                    </td>
                  </tr>
                  <tr>
                    <td style="vertical-align: top; width: 28px; padding-bottom: 14px;">
                      <div style="width: 22px; height: 22px; border-radius: 50%; background-color: #1d1d1f; color: #ffffff; font-size: 11px; font-weight: 700; text-align: center; line-height: 22px;">2</div>
                    </td>
                    <td style="vertical-align: top; padding-bottom: 14px; font-size: 14px; color: #4b4b50;">
                      <strong>Allow Installation:</strong> Tap the downloaded file notification. If prompted, tap <em>Settings</em> and enable <em>"Allow from this source"</em>.
                    </td>
                  </tr>
                  <tr>
                    <td style="vertical-align: top; width: 28px;">
                      <div style="width: 22px; height: 22px; border-radius: 50%; background-color: #1d1d1f; color: #ffffff; font-size: 11px; font-weight: 700; text-align: center; line-height: 22px;">3</div>
                    </td>
                    <td style="vertical-align: top; font-size: 14px; color: #4b4b50;">
                      <strong>Connect & Ride:</strong> Open RiderVoice, start or join a ride room, and enjoy crystal-clear VOX tactical intercom!
                    </td>
                  </tr>
                </table>
              </div>

            </td>
          </tr>

          <!-- Footer -->
          <tr>
            <td style="background-color: #fafafc; padding: 28px 40px; border-top: 1px solid #f0f0f2; text-align: center; font-size: 12px; color: #86868b;">
              <p style="margin: 0 0 6px 0;">
                You received this email because you submitted an application to test the RiderVoice Android App.
              </p>
              <p style="margin: 0; color: #a1a1a6;">
                © 2026 RiderVoice. Built for riders, by riders.
              </p>
            </td>
          </tr>

        </table>
      </td>
    </tr>
  </table>
</body>
</html>`;

  const text = `Welcome to the Convoy, ${firstName}!

Thank you for applying to test RiderVoice! Your application has been approved and your Android APK build (${data.version}) is ready.

Download APK:
${data.downloadUrl}

GitHub Release:
${data.releasePageUrl}

Release Specifications:
- Version: ${data.version}
- File: ${data.fileName}
- Architecture: Universal ARM
- SHA-256: ${data.sha256}

Quick 3-Step Installation Guide:
1. Download the APK file on your Android phone using the link above.
2. If prompted by Android or Chrome, enable "Allow from this source".
3. Open RiderVoice, connect your squad, and ride connected!

Need help or want to report bugs? Reach out on GitHub:
https://github.com/BuzyU/Rider_APP/releases

© 2026 RiderVoice. Built for riders, by riders.`;

  return { subject, html, text };
}

export function buildWaitlistWelcomeEmail(email: string): { subject: string; html: string; text: string } {
  const subject = `You're on the list — RiderVoice Waitlist Confirmation`;

  const html = `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>${subject}</title>
</head>
<body style="margin: 0; padding: 0; background-color: #f5f5f7; font-family: -apple-system, BlinkMacSystemFont, 'SF Pro Display', 'Inter', 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; -webkit-font-smoothing: antialiased; color: #1d1d1f; line-height: 1.6;">
  <table role="presentation" width="100%" cellspacing="0" cellpadding="0" border="0" style="background-color: #f5f5f7; padding: 40px 16px;">
    <tr>
      <td align="center">
        <table role="presentation" width="100%" cellspacing="0" cellpadding="0" border="0" style="max-width: 600px; background-color: #ffffff; border-radius: 24px; overflow: hidden; border: 1px solid #e5e5ea; box-shadow: 0 10px 40px rgba(0,0,0,0.04);">
          <tr>
            <td style="background-color: #ff5500; height: 6px; line-height: 6px; font-size: 6px;">&nbsp;</td>
          </tr>
          <tr>
            <td style="padding: 40px 40px 24px 40px; text-align: left;">
              <div style="display: inline-block; background-color: rgba(255, 85, 0, 0.1); color: #ff5500; font-size: 11px; font-weight: 700; text-transform: uppercase; letter-spacing: 1px; padding: 6px 14px; border-radius: 9999px; margin-bottom: 20px;">
                ● Early Access Waitlist
              </div>
              <h1 style="margin: 0 0 8px 0; font-size: 28px; font-weight: 700; color: #1d1d1f; letter-spacing: -0.5px;">
                RIDERVOICE
              </h1>
              <p style="margin: 0; font-size: 14px; color: #86868b; text-transform: uppercase; letter-spacing: 0.5px; font-weight: 500;">
                Tactical Comms. No Compromise.
              </p>
            </td>
          </tr>
          <tr>
            <td style="padding: 0 40px;">
              <hr style="border: none; border-top: 1px solid #f0f0f2; margin: 0;">
            </td>
          </tr>
          <tr>
            <td style="padding: 32px 40px;">
              <h2 style="margin: 0 0 16px 0; font-size: 22px; font-weight: 600; color: #1d1d1f; letter-spacing: -0.3px;">
                You&apos;re in. Keep an eye on your inbox.
              </h2>
              <p style="margin: 0 0 20px 0; font-size: 15px; color: #4b4b50; line-height: 1.6;">
                Thanks for joining the RiderVoice waitlist (${email}). We&apos;re preparing the next rollout wave of hands-free group communication for motorcycle riders.
              </p>
              <p style="margin: 0 0 28px 0; font-size: 15px; color: #4b4b50; line-height: 1.6;">
                Want to test the app right now? You can apply for closed beta access directly on our website to immediately download the Android APK build.
              </p>
              <table role="presentation" width="100%" cellspacing="0" cellpadding="0" border="0">
                <tr>
                  <td align="center">
                    <a href="https://github.com/BuzyU/Rider_APP/releases/latest" target="_blank" style="display: inline-block; background-color: #ff5500; color: #ffffff; text-decoration: none; font-size: 16px; font-weight: 600; padding: 15px 32px; border-radius: 9999px; box-shadow: 0 6px 20px rgba(255,85,0,0.3);">
                      Check Beta Release on GitHub →
                    </a>
                  </td>
                </tr>
              </table>
            </td>
          </tr>
          <tr>
            <td style="background-color: #fafafc; padding: 28px 40px; border-top: 1px solid #f0f0f2; text-align: center; font-size: 12px; color: #86868b;">
              <p style="margin: 0 0 6px 0;">
                No spam, ever. Just launch and beta access updates.
              </p>
              <p style="margin: 0; color: #a1a1a6;">
                © 2026 RiderVoice. Built for riders, by riders.
              </p>
            </td>
          </tr>
        </table>
      </td>
    </tr>
  </table>
</body>
</html>`;

  const text = `You're on the list — RiderVoice Waitlist Confirmation

Thanks for joining the RiderVoice waitlist (${email}). We're preparing the next rollout wave of hands-free group communication for motorcycle riders.

Want to test the app right now? Check our latest Android APK build on GitHub:
https://github.com/BuzyU/Rider_APP/releases/latest

No spam, ever. Just launch and beta access updates.

© 2026 RiderVoice. Built for riders, by riders.`;

  return { subject, html, text };
}

