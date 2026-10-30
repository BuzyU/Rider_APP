import { NextResponse } from 'next/server';
import { supabaseAdmin } from '@/utils/supabase/admin';
import { sendSmtpMail } from '@/lib/email/smtpClient';
import { buildWaitlistWelcomeEmail } from '@/lib/email/templates';
import { rateLimit, clientIp } from '@/lib/rateLimit';

const EMAIL_RE = /^[a-zA-Z0-9_%+-]+(?:\.[a-zA-Z0-9_%+-]+)*@(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?\.)+[a-zA-Z]{2,}$/;
const fail = (status: number, error: string) => NextResponse.json({ error }, { status });
const ALREADY = "You are already on our priority waitlist! We'll keep you posted.";

export async function POST(request: Request) {
  try {
    if (!rateLimit(`waitlist:ip:${clientIp(request)}`, 5, 10 * 60_000)) {
      return fail(429, 'Too many requests. Please try again later.');
    }

    let body: { email?: unknown } | null;
    try {
      body = await request.json();
    } catch {
      return fail(400, 'Invalid request body.');
    }
    const email = body?.email;

    if (typeof email !== 'string' || email.length > 254 || !EMAIL_RE.test(email.trim())) {
      return fail(400, 'Please enter a valid email address.');
    }

    const normalizedEmail = email.trim().toLowerCase();
    if (!rateLimit(`waitlist:email:${normalizedEmail}`, 3, 60 * 60_000)) {
      return fail(429, 'Too many requests. Please try again later.');
    }

    const { data: existing, error: selectErr } = await supabaseAdmin
      .from('waitlist')
      .select('id')
      .eq('email', normalizedEmail)
      .maybeSingle();

    if (selectErr) {
      console.error('[Waitlist] select failed:', selectErr);
      return fail(503, 'Service temporarily unavailable. Please try again.');
    }
    if (existing) {
      return NextResponse.json({ success: true, message: ALREADY }, { status: 200 });
    }

    const { error: insertError } = await supabaseAdmin
      .from('waitlist')
      .insert([{ email: normalizedEmail, createdAt: new Date().toISOString() }]);

    if (insertError?.code === '23505') {
      return NextResponse.json({ success: true, message: ALREADY }, { status: 200 });
    }
    if (insertError) {
      console.error('[Waitlist] insert failed:', insertError);
      return fail(503, 'Service temporarily unavailable. Please try again.');
    }

    let emailSent = false;
    let emailMessageId: string | undefined;
    try {
      const emailPayload = buildWaitlistWelcomeEmail(normalizedEmail);
      const res = await sendSmtpMail({
        to: normalizedEmail,
        subject: emailPayload.subject,
        html: emailPayload.html,
        text: emailPayload.text,
      });
      emailSent = res.success;
      emailMessageId = res.messageId;
    } catch (mailError) {
      console.warn('[SMTP Warning] Failed to send waitlist email:', mailError);
    }

    return NextResponse.json({
      success: true,
      message: "You're on the priority waitlist! Keep an eye on your inbox for rollout updates.",
      emailSent,
      emailMessageId,
    });
  } catch (error: unknown) {
    console.error('Waitlist API Error:', error);
    return fail(500, 'Internal server error');
  }
}

/** Public count of waitlist signups only (tester applications and users are not included). */
export async function GET() {
  try {
    const { count, error } = await supabaseAdmin
      .from('waitlist')
      .select('*', { count: 'exact', head: true });
    if (error || typeof count !== 'number') {
      console.error('[Waitlist] count failed:', error);
      return fail(503, 'Service temporarily unavailable.');
    }
    return NextResponse.json({ count, waitlistCount: count });
  } catch (err) {
    console.error('[Waitlist] count error:', err);
    return fail(503, 'Service temporarily unavailable.');
  }
}
