import { NextResponse } from 'next/server';
import { supabaseAdmin } from '@/utils/supabase/admin';
import { sendSmtpMail } from '@/lib/email/smtpClient';
import { buildTesterWelcomeEmail } from '@/lib/email/templates';
import { rateLimit, clientIp } from '@/lib/rateLimit';

const DEFAULT_RELEASE = {
  version: 'v0.0.3.19',
  releaseName: 'RiderVoice v0.0.3.19',
  fileName: 'Rider_APP-v0.0.3.19.apk',
  downloadUrl: 'https://github.com/BuzyU/Rider_APP/releases/download/v0.0.3.19/Rider_APP-v0.0.3.19.apk',
  releasePageUrl: 'https://github.com/BuzyU/Rider_APP/releases/tag/v0.0.3.19',
  sha256: 'Pending release verification',
  fileSize: '~50 MB',
  targetArch: 'Universal ARM (arm64-v8a, armeabi-v7a)',
  highlights: 'Staged Room Identity Migration, Durable Create Idempotency, Race Mitigation & Webhook Defense'
};

async function getLatestRelease() {
  try {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 3000);
    const res = await fetch('https://api.github.com/repos/BuzyU/Rider_APP/releases/latest', {
      headers: {
        'Accept': 'application/vnd.github.v3+json',
        'User-Agent': 'RiderApp-Website'
      },
      next: { revalidate: 3600 },
      signal: controller.signal
    } as RequestInit & { next?: { revalidate: number } });
    clearTimeout(timeout);

    if (res.ok) {
      const data = await res.json();
      type ReleaseAsset = { name?: string; browser_download_url?: string; size?: number };
      const apkAsset = data.assets?.find((a: ReleaseAsset) => a.name?.endsWith('.apk'));
      if (apkAsset) {
        return {
          version: data.tag_name || DEFAULT_RELEASE.version,
          releaseName: data.name || data.tag_name || DEFAULT_RELEASE.releaseName,
          fileName: apkAsset.name || DEFAULT_RELEASE.fileName,
          downloadUrl: apkAsset.browser_download_url || DEFAULT_RELEASE.downloadUrl,
          releasePageUrl: data.html_url || DEFAULT_RELEASE.releasePageUrl,
          sha256: DEFAULT_RELEASE.sha256,
          fileSize: apkAsset.size ? `${(apkAsset.size / (1024 * 1024)).toFixed(1)} MB` : DEFAULT_RELEASE.fileSize,
          targetArch: DEFAULT_RELEASE.targetArch,
          highlights: data.body ? data.body.slice(0, 300) : DEFAULT_RELEASE.highlights
        };
      }
    }
  } catch (err) {
    console.warn('GitHub releases API lookup timed out or failed, using fallback:', err);
  }
  return DEFAULT_RELEASE;
}


const MAX = { name: 100, email: 254, phone: 30, deviceModel: 100, motorcycleModel: 100, experienceNotes: 2000 };
const EMAIL_RE = /^[a-zA-Z0-9_%+-]+(?:\.[a-zA-Z0-9_%+-]+)*@(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?\.)+[a-zA-Z]{2,}$/;

function optionalText(value: unknown, max: number): string | null | undefined {
  // undefined => invalid; null => absent; string => trimmed value
  if (value === undefined || value === null || value === '') return null;
  if (typeof value !== 'string' || value.length > max) return undefined;
  return value.trim() || null;
}

const fail = (status: number, error: string) => NextResponse.json({ error }, { status });

export async function POST(request: Request) {
  try {
    if (!rateLimit(`tester:ip:${clientIp(request)}`, 5, 10 * 60_000)) {
      return fail(429, 'Too many requests. Please try again later.');
    }

    let body: Record<string, unknown>;
    try {
      body = await request.json();
    } catch {
      return fail(400, 'Invalid request body.');
    }
    if (!body || typeof body !== 'object') return fail(400, 'Invalid request body.');
    const { name, email, phone, deviceModel, motorcycleModel, experienceNotes } = body;

    if (typeof name !== 'string' || name.trim().length === 0 || name.length > MAX.name) {
      return fail(400, 'Please enter your name.');
    }
    if (typeof email !== 'string' || email.length > MAX.email || !EMAIL_RE.test(email.trim())) {
      return fail(400, 'Please enter a valid email address.');
    }

    const phoneV = optionalText(phone, MAX.phone);
    const deviceV = optionalText(deviceModel, MAX.deviceModel);
    const motoV = optionalText(motorcycleModel, MAX.motorcycleModel);
    const notesV = optionalText(experienceNotes, MAX.experienceNotes);
    if ([phoneV, deviceV, motoV, notesV].includes(undefined)) {
      return fail(400, 'One or more fields are invalid or too long.');
    }

    const normalizedEmail = email.trim().toLowerCase();
    const normalizedName = name.trim();

    if (!rateLimit(`tester:email:${normalizedEmail}`, 3, 60 * 60_000)) {
      return fail(429, 'Too many requests. Please try again later.');
    }

    const release = await getLatestRelease();
    const now = new Date().toISOString();
    const fields = {
      name: normalizedName,
      phone: phoneV,
      device_model: deviceV,
      motorcycle_model: motoV,
      experience_notes: notesV,
      apk_version: release.version,
      updated_at: now,
    };

    const { data: existing, error: selectErr } = await supabaseAdmin
      .from('tester_applications')
      .select('id')
      .eq('email', normalizedEmail)
      .maybeSingle();

    if (selectErr) {
      console.error('Tester application select failed:', selectErr);
      return fail(503, 'Service temporarily unavailable. Please try again.');
    }

    let alreadyApplied = false;
    if (existing) {
      const { error: updateErr } = await supabaseAdmin
        .from('tester_applications')
        .update(fields)
        .eq('id', existing.id);
      if (updateErr) {
        console.error('Tester application update failed:', updateErr);
        return fail(503, 'Service temporarily unavailable. Please try again.');
      }
      alreadyApplied = true;
    } else {
      const { error: insertErr } = await supabaseAdmin
        .from('tester_applications')
        .insert([{ ...fields, email: normalizedEmail, status: 'PENDING', download_count: 1, created_at: now }]);
      if (insertErr?.code === '23505') {
        alreadyApplied = true; // concurrent duplicate: idempotent success, no second email
      } else if (insertErr) {
        console.error('Tester application insert failed:', insertErr);
        return fail(503, 'Service temporarily unavailable. Please try again.');
      }
    }

    let emailSent = false;
    let emailMessageId: string | undefined;
    if (!alreadyApplied) {
      try {
        const emailPayload = buildTesterWelcomeEmail({
          name: normalizedName,
          email: normalizedEmail,
          version: release.version,
        });
        const res = await sendSmtpMail({
          to: normalizedEmail,
          subject: emailPayload.subject,
          html: emailPayload.html,
          text: emailPayload.text,
        });
        emailSent = res.success;
        emailMessageId = res.messageId;
      } catch (mailError) {
        console.warn('[SMTP Warning] Failed to send confirmation email:', mailError);
      }
    }

    return NextResponse.json({
      success: true,
      message: 'Application received! You are on our priority waitlist.',
      release,
      emailSent,
      emailMessageId,
    });
  } catch (error: unknown) {
    console.error('Tester application API error:', error);
    return fail(500, 'Internal server error');
  }
}

export async function GET() {
  const release = await getLatestRelease();
  return NextResponse.json({ release });
}
