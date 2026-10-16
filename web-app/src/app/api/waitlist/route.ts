import { NextResponse } from 'next/server';
import { supabaseAdmin } from '@/utils/supabase/admin';
import { sendSmtpMail } from '@/lib/email/smtpClient';
import { buildWaitlistWelcomeEmail } from '@/lib/email/templates';

export async function POST(request: Request) {
  try {
    const { email } = await request.json();

    if (!email || typeof email !== 'string' || !email.includes('@')) {
      return NextResponse.json(
        { error: 'Please enter a valid email address.' },
        { status: 400 }
      );
    }

    const normalizedEmail = email.trim().toLowerCase();

    try {
      const { data: existing } = await supabaseAdmin
        .from('waitlist')
        .select('id')
        .eq('email', normalizedEmail)
        .maybeSingle();

      if (existing) {
        return NextResponse.json(
          { success: true, message: "You are already on our priority waitlist! We'll keep you posted." },
          { status: 200 }
        );
      }

      const { error: insertError } = await supabaseAdmin
        .from('waitlist')
        .insert([{ email: normalizedEmail, createdAt: new Date().toISOString() }]);

      if (insertError && insertError.code === '23505') {
        return NextResponse.json(
          { success: true, message: "You are already on our priority waitlist! We'll keep you posted." },
          { status: 200 }
        );
      }
    } catch (dbErr) {
      console.warn('[Waitlist DB Warning] Database write failed (allowing signup to succeed):', dbErr);
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
      console.warn(`[SMTP Warning] Failed to send waitlist email to ${normalizedEmail}:`, mailError);
    }

    return NextResponse.json({
      success: true,
      message: "You're on the priority waitlist! Keep an eye on your inbox for rollout updates.",
      emailSent,
      emailMessageId
    });
  } catch (error: unknown) {
    console.error('Waitlist API Error:', error);
    const message = error instanceof Error ? error.message : 'Internal server error';
    return NextResponse.json(
      { error: message },
      { status: 500 }
    );
  }
}

export async function GET() {
  try {
    let waitlistCount = 0;
    let testerCount = 0;
    let userCount = 0;

    try {
      const { count: wCount } = await supabaseAdmin
        .from('waitlist')
        .select('*', { count: 'exact', head: true });
      if (typeof wCount === 'number') waitlistCount = wCount;
    } catch {}

    try {
      const { count: tCount } = await supabaseAdmin
        .from('tester_applications')
        .select('*', { count: 'exact', head: true });
      if (typeof tCount === 'number') testerCount = tCount;
    } catch {}

    try {
      const { count: uCount } = await supabaseAdmin
        .from('User')
        .select('*', { count: 'exact', head: true });
      if (typeof uCount === 'number') userCount = uCount;
    } catch {}

    const total = waitlistCount + testerCount + userCount;
    return NextResponse.json({ count: total });
  } catch {
    return NextResponse.json({ count: 0 });
  }
}
