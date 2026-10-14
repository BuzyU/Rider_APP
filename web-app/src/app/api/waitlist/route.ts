import { NextResponse } from 'next/server';
import { supabaseAdmin } from '@/utils/supabase/admin';
import { sendSmtpMail } from '@/lib/email/smtpClient';
import { buildWaitlistWelcomeEmail } from '@/lib/email/templates';

export async function POST(request: Request) {
  try {
    const { email } = await request.json();

    if (!email || typeof email !== 'string' || !email.includes('@')) {
      return NextResponse.json(
        { error: 'Valid email is required' },
        { status: 400 }
      );
    }

    const normalizedEmail = email.trim().toLowerCase();

    const { data: existing } = await supabaseAdmin
      .from('waitlist')
      .select('id')
      .eq('email', normalizedEmail)
      .maybeSingle();

    if (existing) {
      return NextResponse.json(
        { success: true, message: 'You are already on the waitlist!' },
        { status: 200 }
      );
    }

    const { error } = await supabaseAdmin
      .from('waitlist')
      .insert([{ email: normalizedEmail, createdAt: new Date().toISOString() }]);

    if (error) {
      if (error.code === '23505') {
        return NextResponse.json(
          { success: true, message: 'You are already on the waitlist!' },
          { status: 200 }
        );
      }
      return NextResponse.json({ error: error.message }, { status: 500 });
    }

    // Send confirmation email via Google SMTP
    try {
      const emailPayload = buildWaitlistWelcomeEmail(normalizedEmail);
      await sendSmtpMail({
        to: normalizedEmail,
        subject: emailPayload.subject,
        html: emailPayload.html,
        text: emailPayload.text,
      });
      console.log(`[SMTP] Successfully sent waitlist welcome email to ${normalizedEmail}`);
    } catch (mailError) {
      console.warn(`[SMTP Warning] Failed to send waitlist email to ${normalizedEmail}:`, mailError);
    }

    return NextResponse.json({ success: true, message: 'Added to waitlist' });
  } catch (error: unknown) {
    console.error('Waitlist API Error:', error);
    const message = error instanceof Error ? error.message : 'Internal server error';
    return NextResponse.json(
      { error: message },
      { status: 500 }
    );
  }
}
