import React from 'react';
import Link from 'next/link';

export default function TermsOfService() {
  return (
    <div className="container" style={{ paddingTop: '6rem', paddingBottom: '6rem', maxWidth: '840px' }}>
      <Link href="/" className="btn btn-secondary" style={{ marginBottom: '2.5rem' }}>
        &larr; Back to Home
      </Link>
      
      <h1 style={{ fontSize: 'clamp(2rem, 4vw, 3rem)', marginBottom: '1rem', letterSpacing: '-0.03em' }}>
        Terms of Service
      </h1>
      <p style={{ color: 'var(--text-secondary)', marginBottom: '3rem', fontSize: '1rem' }}>
        Last updated: October 2026
      </p>

      <div style={{ color: 'var(--text-primary)', lineHeight: '1.8', display: 'flex', flexDirection: 'column', gap: '2rem' }}>
        <section>
          <h2 style={{ fontSize: '1.5rem', marginBottom: '0.75rem', fontWeight: 600 }}>1. Agreement to Terms</h2>
          <p style={{ color: 'var(--text-secondary)' }}>
            By accessing or using the RiderVoice website and applications, you agree to be bound by these Terms of Service. If you do not agree to these terms, please do not use our services.
          </p>
        </section>

        <section>
          <h2 style={{ fontSize: '1.5rem', marginBottom: '0.75rem', fontWeight: 600, color: 'var(--safety-orange)' }}>
            2. Rider Safety &amp; Road Responsibility (Important)
          </h2>
          <p style={{ color: 'var(--text-secondary)' }}>
            <strong>Your primary responsibility is the safe operation of your vehicle at all times.</strong> RiderVoice is designed for hands-free audio communication. You agree to:
          </p>
          <ul style={{ color: 'var(--text-secondary)', paddingLeft: '1.5rem', display: 'flex', flexDirection: 'column', gap: '0.5rem', marginTop: '0.5rem' }}>
            <li>Never manipulate your phone or configure app settings while riding. Set up your room and audio before mounting.</li>
            <li>Obey all applicable helmet laws, traffic regulations, and speed limits.</li>
            <li>Keep audio volume at a safe level that allows you to hear ambient emergency sirens, horns, and road traffic.</li>
          </ul>
        </section>

        <section>
          <h2 style={{ fontSize: '1.5rem', marginBottom: '0.75rem', fontWeight: 600 }}>3. Beta Testing &amp; Service Availability</h2>
          <p style={{ color: 'var(--text-secondary)' }}>
            Certain versions of RiderVoice are released as beta builds for field testing. Features, audio stability, and telemetry depend on cellular carrier coverage and GPS satellite reception. We do not guarantee uninterrupted connectivity in remote areas or dead zones.
          </p>
        </section>

        <section>
          <h2 style={{ fontSize: '1.5rem', marginBottom: '0.75rem', fontWeight: 600 }}>4. Acceptable Use</h2>
          <p style={{ color: 'var(--text-secondary)' }}>
            You agree not to misuse RiderVoice communication channels to transmit abusive, hateful, or unlawful content, disrupt convoy voice rooms, or attempt to reverse-engineer our proprietary audio protocols.
          </p>
        </section>

        <section>
          <h2 style={{ fontSize: '1.5rem', marginBottom: '0.75rem', fontWeight: 600 }}>5. Limitation of Liability</h2>
          <p style={{ color: 'var(--text-secondary)' }}>
            To the maximum extent permitted by law, RiderVoice and its contributors shall not be liable for any direct, indirect, incidental, or consequential damages resulting from vehicle operation, connectivity interruptions, or reliance on real-time location telemetry.
          </p>
        </section>

        <section>
          <h2 style={{ fontSize: '1.5rem', marginBottom: '0.75rem', fontWeight: 600 }}>6. Contact &amp; Inquiries</h2>
          <p style={{ color: 'var(--text-secondary)' }}>
            For inquiries regarding these Terms of Service, contact us at <a href="mailto:a07631356@gmail.com" style={{ color: 'var(--safety-orange)', textDecoration: 'underline' }}>a07631356@gmail.com</a>.
          </p>
        </section>
      </div>
    </div>
  );
}
