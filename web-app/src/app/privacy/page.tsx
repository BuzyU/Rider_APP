import React from 'react';
import Link from 'next/link';

export default function PrivacyPolicy() {
  return (
    <div className="container" style={{ paddingTop: '6rem', paddingBottom: '6rem', maxWidth: '840px' }}>
      <Link href="/" className="btn btn-secondary" style={{ marginBottom: '2.5rem' }}>
        &larr; Back to Home
      </Link>
      
      <h1 style={{ fontSize: 'clamp(2rem, 4vw, 3rem)', marginBottom: '1rem', letterSpacing: '-0.03em' }}>
        Privacy Policy
      </h1>
      <p style={{ color: 'var(--text-secondary)', marginBottom: '3rem', fontSize: '1rem' }}>
        Last updated: October 2026
      </p>

      <div style={{ color: 'var(--text-primary)', lineHeight: '1.8', display: 'flex', flexDirection: 'column', gap: '2rem' }}>
        <section>
          <h2 style={{ fontSize: '1.5rem', marginBottom: '0.75rem', fontWeight: 600 }}>1. Introduction</h2>
          <p style={{ color: 'var(--text-secondary)' }}>
            RiderVoice (&quot;we&quot;, &quot;our&quot;, or &quot;us&quot;) is committed to protecting your privacy while delivering reliable group voice comms and ride telemetry. This Privacy Policy outlines what information we collect, how it is used, and how your data is safeguarded when using our website and mobile application.
          </p>
        </section>

        <section>
          <h2 style={{ fontSize: '1.5rem', marginBottom: '0.75rem', fontWeight: 600 }}>2. Voice Intercom Privacy</h2>
          <p style={{ color: 'var(--text-secondary)' }}>
            Your voice communications are streamed in real time between connected riders in a room. <strong>We do not record, transcribe, or store your voice conversations on our servers.</strong> Voice channels are encrypted in transit using industry-standard WebRTC protocols.
          </p>
        </section>

        <section>
          <h2 style={{ fontSize: '1.5rem', marginBottom: '0.75rem', fontWeight: 600 }}>3. Information We Collect</h2>
          <ul style={{ color: 'var(--text-secondary)', paddingLeft: '1.5rem', display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
            <li><strong>Account &amp; Waitlist Data:</strong> Email addresses and names provided when joining our waitlist, applying for beta testing, or creating an account.</li>
            <li><strong>Real-Time Location:</strong> With your permission, live GPS coordinates are processed exclusively during active ride sessions to display group position telemetry and incident alerts.</li>
            <li><strong>Device &amp; Telemetry Data:</strong> Hardware model and OS version to diagnose audio compatibility and Bluetooth headset latency.</li>
          </ul>
        </section>

        <section>
          <h2 style={{ fontSize: '1.5rem', marginBottom: '0.75rem', fontWeight: 600 }}>4. How We Use Information</h2>
          <p style={{ color: 'var(--text-secondary)' }}>
            We use your data solely to operate and improve RiderVoice comms services, maintain squad audio sessions, broadcast emergency alerts to group members, and send relevant beta rollout updates. <strong>We never sell your personal information or location history to advertisers.</strong>
          </p>
        </section>

        <section>
          <h2 style={{ fontSize: '1.5rem', marginBottom: '0.75rem', fontWeight: 600 }}>5. Data Security &amp; Retention</h2>
          <p style={{ color: 'var(--text-secondary)' }}>
            We implement administrative and technical security measures to protect your data. You may request account deletion or data removal at any time by contacting us directly.
          </p>
        </section>

        <section>
          <h2 style={{ fontSize: '1.5rem', marginBottom: '0.75rem', fontWeight: 600 }}>6. Contact Us</h2>
          <p style={{ color: 'var(--text-secondary)' }}>
            If you have questions regarding this Privacy Policy, reach out to our team at <a href="mailto:a07631356@gmail.com" style={{ color: 'var(--safety-orange)', textDecoration: 'underline' }}>a07631356@gmail.com</a>.
          </p>
        </section>
      </div>
    </div>
  );
}
