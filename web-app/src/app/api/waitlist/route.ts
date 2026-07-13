import { NextResponse } from 'next/server';

export async function POST(request: Request) {
  try {
    const { email } = await request.json();

    if (!email || !email.includes('@')) {
      return NextResponse.json(
        { error: 'Valid email is required' },
        { status: 400 }
      );
    }

    // Mocking the database insertion with a delay
    // TODO: Connect this to Supabase later
    await new Promise((resolve) => setTimeout(resolve, 1000));

    // Simulate successful insertion
    console.log('Mock: Inserted into waitlist ->', email);

    return NextResponse.json({ success: true, message: 'Added to waitlist' });
  } catch (error) {
    console.error('Waitlist API Error:', error);
    return NextResponse.json(
      { error: 'Internal server error' },
      { status: 500 }
    );
  }
}
