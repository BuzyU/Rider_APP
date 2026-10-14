import { NextResponse } from 'next/server';
import { supabaseAdmin } from '@/utils/supabase/admin';
import { sendSmtpMail } from '@/lib/email/smtpClient';
import { buildTesterWelcomeEmail } from '@/lib/email/templates';

// Fallback release metadata matching latest GitHub release
const DEFAULT_RELEASE = {
  version: 'v0.0.3.7',
  releaseName: 'RiderVoice v0.0.3.7',
  fileName: 'Rider_APP-v0.0.3.7.apk',
  downloadUrl: 'https://github.com/BuzyU/Rider_APP/releases/download/v0.0.3.7/Rider_APP-v0.0.3.7.apk',
  releasePageUrl: 'https://github.com/BuzyU/Rider_APP/releases/tag/v0.0.3.7',
  sha256: '202a9b0c461dc405625ba6195aa3ad01878028a1cc85876794fe96279e6d8825',
  fileSize: '~50 MB',
  targetArch: 'Universal ARM (arm64-v8a, armeabi-v7a)',
  highlights: 'fix ~2s VOX cut with WebRTC ADM sample relay, dead code purge, UI/UX cleanup, and battery optimization'
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
    });
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

export async function POST(request: Request) {
  try {
    const body = await request.json();
    const { name, email, phone, deviceModel, motorcycleModel, experienceNotes } = body;

    if (!name || typeof name !== 'string' || name.trim().length === 0) {
      return NextResponse.json(
        { error: 'Please enter your name.' },
        { status: 400 }
      );
    }

    if (!email || typeof email !== 'string' || !email.includes('@')) {
      return NextResponse.json(
        { error: 'Please enter a valid email address.' },
        { status: 400 }
      );
    }

    const normalizedEmail = email.trim().toLowerCase();
    const normalizedName = name.trim();
    const release = await getLatestRelease();

    // Store in Supabase table "tester_applications"
    try {
      const { data: existing, error: selectErr } = await supabaseAdmin
        .from('tester_applications')
        .select('id, download_count')
        .eq('email', normalizedEmail)
        .maybeSingle();

      if (existing) {
        await supabaseAdmin
          .from('tester_applications')
          .update({
            name: normalizedName,
            phone: phone?.trim() || null,
            device_model: deviceModel?.trim() || null,
            motorcycle_model: motorcycleModel?.trim() || null,
            experience_notes: experienceNotes?.trim() || null,
            apk_version: release.version,
            download_count: (existing.download_count || 1) + 1,
            updated_at: new Date().toISOString()
          })
          .eq('id', existing.id);
      } else if (!selectErr) {
        await supabaseAdmin
          .from('tester_applications')
          .insert([
            {
              name: normalizedName,
              email: normalizedEmail,
              phone: phone?.trim() || null,
              device_model: deviceModel?.trim() || null,
              motorcycle_model: motorcycleModel?.trim() || null,
              experience_notes: experienceNotes?.trim() || null,
              apk_version: release.version,
              download_count: 1,
              created_at: new Date().toISOString(),
              updated_at: new Date().toISOString()
            }
          ]);
      }
    } catch (dbError) {
      console.warn('Warning: Supabase write failed (allowing APK download anyway):', dbError);
    }

    // Send confirmation and download instructions email via Google SMTP
    try {
      const emailPayload = buildTesterWelcomeEmail({
        name: normalizedName,
        email: normalizedEmail,
        version: release.version,
        fileName: release.fileName,
        downloadUrl: release.downloadUrl,
        releasePageUrl: release.releasePageUrl,
        sha256: release.sha256,
        fileSize: release.fileSize,
      });

      await sendSmtpMail({
        to: normalizedEmail,
        subject: emailPayload.subject,
        html: emailPayload.html,
        text: emailPayload.text,
      });
      console.log(`[SMTP] Successfully sent confirmation email to ${normalizedEmail}`);
    } catch (mailError) {
      console.warn(`[SMTP Warning] Failed to send confirmation email to ${normalizedEmail}:`, mailError);
    }

    return NextResponse.json({
      success: true,
      message: 'Application approved! Your APK download is ready.',
      release
    });
  } catch (error: unknown) {
    console.error('Tester application API error:', error);
    const message = error instanceof Error ? error.message : 'Internal server error';
    return NextResponse.json(
      { error: message },
      { status: 500 }
    );
  }
}

export async function GET() {
  const release = await getLatestRelease();
  return NextResponse.json({ release });
}

