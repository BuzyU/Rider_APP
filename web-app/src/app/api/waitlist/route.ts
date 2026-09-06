import { NextResponse } from 'next/server';
import { supabaseAdmin } from '@/utils/supabase/admin';

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

    // Deduplicate against existing waitlist entries
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

    return NextResponse.json({ success: true, message: 'Added to waitlist' });
  } catch (error: any) {
    console.error('Waitlist API Error:', error);
    return NextResponse.json(
      { error: error?.message || 'Internal server error' },
      { status: 500 }
    );
  }
}

