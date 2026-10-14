export interface TesterEmailData {
  name: string;
  email: string;
  version?: string;
}

export function buildTesterWelcomeEmail(data: TesterEmailData): { subject: string; html: string; text: string } {
  const firstName = data.name.trim().split(' ')[0] || 'Rider';
  const subject = `You're on the RiderVoice Priority Waiting List`;

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
          
          <!-- Top Brand Bar -->
          <tr>
            <td style="background-color: #ff5500; height: 6px; line-height: 6px; font-size: 6px;">&nbsp;</td>
          </tr>

          <!-- Header -->
          <tr>
            <td style="padding: 40px 40px 24px 40px; text-align: left;">
              <div style="display: inline-block; background-color: rgba(255, 85, 0, 0.1); color: #ff5500; font-size: 11px; font-weight: 700; text-transform: uppercase; letter-spacing: 1px; padding: 6px 14px; border-radius: 9999px; margin-bottom: 20px;">
                ● Priority Waiting List
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
              <p style="margin: 0 0 18px 0; font-size: 15px; color: #4b4b50; line-height: 1.6;">
                Thank you for applying to test RiderVoice. Your application has been received and your spot is confirmed on our priority waiting list.
              </p>
              <p style="margin: 0 0 24px 0; font-size: 15px; color: #4b4b50; line-height: 1.6;">
                We are testing hands-free VOX audio, Bluetooth helmet connectivity, and convoy telemetry with real riders. Access is being rolled out in sequential batches to ensure crystal-clear audio quality and low server latency across all devices.
              </p>

              <!-- Process Step Box -->
              <table role="presentation" width="100%" cellspacing="0" cellpadding="0" border="0" style="background-color: #fbfbfd; border: 1px solid #e5e5ea; border-radius: 16px; margin: 24px 0;">
                <tr>
                  <td style="padding: 24px;">
                    <div style="font-size: 12px; font-weight: 700; color: #ff5500; text-transform: uppercase; letter-spacing: 0.5px; margin-bottom: 16px;">
                      What happens next?
                    </div>

                    <table role="presentation" width="100%" cellspacing="0" cellpadding="0" border="0">
                      <tr>
                        <td style="vertical-align: top; width: 28px; padding-bottom: 12px;">
                          <div style="width: 20px; height: 20px; border-radius: 50%; background-color: #1d1d1f; color: #ffffff; font-size: 11px; font-weight: 700; text-align: center; line-height: 20px;">1</div>
                        </td>
                        <td style="vertical-align: top; padding-bottom: 12px; font-size: 14px; color: #4b4b50;">
                          <strong>Application Review:</strong> Our team reviews rider details and device profiles for current testing batches.
                        </td>
                      </tr>
                      <tr>
                        <td style="vertical-align: top; width: 28px; padding-bottom: 12px;">
                          <div style="width: 20px; height: 20px; border-radius: 50%; background-color: #1d1d1f; color: #ffffff; font-size: 11px; font-weight: 700; text-align: center; line-height: 20px;">2</div>
                        </td>
                        <td style="vertical-align: top; padding-bottom: 12px; font-size: 14px; color: #4b4b50;">
                          <strong>Batch Notification:</strong> Once your spot opens, you will receive an email notification directly with onboarding details.
                        </td>
                      </tr>
                      <tr>
                        <td style="vertical-align: top; width: 28px;">
                          <div style="width: 20px; height: 20px; border-radius: 50%; background-color: #1d1d1f; color: #ffffff; font-size: 11px; font-weight: 700; text-align: center; line-height: 20px;">3</div>
                        </td>
                        <td style="vertical-align: top; font-size: 14px; color: #4b4b50;">
                          <strong>Ride & Feedback:</strong> Connect with your squad on group rides and help refine tactical intercom features.
                        </td>
                      </tr>
                    </table>
                  </td>
                </tr>
              </table>

              <p style="margin: 24px 0 0 0; font-size: 14px; color: #6e6e73; line-height: 1.5;">
                Have questions or a large riding group? Feel free to reply directly to this email.
              </p>
            </td>
          </tr>

          <!-- Footer -->
          <tr>
            <td style="background-color: #fafafc; padding: 28px 40px; border-top: 1px solid #f0f0f2; text-align: center; font-size: 12px; color: #86868b;">
              <p style="margin: 0 0 6px 0;">
                No spam, ever. Just launch and priority access updates.
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

Thank you for applying to test RiderVoice. Your application has been received and your spot is confirmed on our priority waiting list.

We are testing hands-free VOX audio, Bluetooth helmet connectivity, and convoy telemetry with real riders. Access is being rolled out in sequential batches to ensure crystal-clear audio quality and low server latency across all devices.

What happens next?
1. Application Review: Our team reviews rider details and device profiles for current testing batches.
2. Batch Notification: Once your spot opens, you will receive an email notification directly with onboarding details.
3. Ride & Feedback: Connect with your squad on group rides and help refine tactical intercom features.

No spam, ever. Just launch and priority access updates.

© 2026 RiderVoice. Built for riders, by riders.`;

  return { subject, html, text };
}

export function buildWaitlistWelcomeEmail(email: string): { subject: string; html: string; text: string } {
  const subject = `You're on the list — RiderVoice Priority Waitlist`;

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
                ● Priority Waitlist Confirmed
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
                Thanks for joining the RiderVoice waitlist (${email}). Your spot is reserved on our priority rollout list.
              </p>
              <p style="margin: 0 0 24px 0; font-size: 15px; color: #4b4b50; line-height: 1.6;">
                We are actively testing group comms with selected rider convoys. As soon as the next wave of invitations opens, we will send an access notification directly to your inbox.
              </p>
            </td>
          </tr>
          <tr>
            <td style="background-color: #fafafc; padding: 28px 40px; border-top: 1px solid #f0f0f2; text-align: center; font-size: 12px; color: #86868b;">
              <p style="margin: 0 0 6px 0;">
                No spam, ever. Just launch and priority rollout updates.
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

  const text = `You're on the list — RiderVoice Priority Waitlist

Thanks for joining the RiderVoice waitlist (${email}). Your spot is reserved on our priority rollout list.

We are actively testing group comms with selected rider convoys. As soon as the next wave of invitations opens, we will send an access notification directly to your inbox.

No spam, ever. Just launch and priority rollout updates.

© 2026 RiderVoice. Built for riders, by riders.`;

  return { subject, html, text };
}
