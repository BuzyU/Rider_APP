import React from 'react';
import Link from 'next/link';

export default function PrivacyPolicy() {
  return (
    <div className="container" style={{ paddingTop: '6rem', paddingBottom: '6rem' }}>
      <Link href="/" className="btn btn-secondary" style={{ marginBottom: '2rem' }}>
        &larr; Back to Home
      </Link>
      <h1 style={{ fontSize: '3rem', marginBottom: '2rem' }}>Privacy Policy</h1>
      <div style={{ color: 'var(--text-secondary)', lineHeight: '1.8' }}>
        <p>This is a placeholder for the Privacy Policy.</p>
        <p>Last updated: {new Date().toLocaleDateString()}</p>
      </div>
    </div>
  );
}
