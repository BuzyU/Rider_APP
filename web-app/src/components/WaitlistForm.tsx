'use client';

import { useState, useEffect } from 'react';
import { ArrowRight, CheckCircle2 } from 'lucide-react';
import styles from './WaitlistForm.module.css';

export default function WaitlistForm({ id = 'waitlist-form' }: { id?: string }) {
  const [email, setEmail] = useState('');
  const [status, setStatus] = useState<'idle' | 'loading' | 'success' | 'error'>('idle');
  const [message, setMessage] = useState('');
  const [registeredCount, setRegisteredCount] = useState<number | null>(null);

  useEffect(() => {
    let mounted = true;
    fetch('/api/waitlist')
      .then((res) => res.json())
      .then((data) => {
        if (mounted && typeof data.count === 'number' && data.count > 0) {
          setRegisteredCount(data.count);
        }
      })
      .catch(() => {});
    return () => {
      mounted = false;
    };
  }, []);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!email) return;

    setStatus('loading');
    
    try {
      const response = await fetch('/api/waitlist', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email }),
      });
      
      const data = await response.json();
      
      if (!response.ok) {
        throw new Error(data.error || 'Something went wrong');
      }
      
      setStatus('success');
      setMessage(data.message || "You're on the priority waitlist! Keep an eye on your inbox.");
      setEmail('');
      if (registeredCount !== null) {
        setRegisteredCount(registeredCount + 1);
      }
    } catch (err: unknown) {
      setStatus('error');
      const msg = err instanceof Error ? err.message : 'Failed to join waitlist. Please try again.';
      setMessage(msg);
    }
  };

  if (status === 'success') {
    return (
      <div className={styles.successMessage}>
        <CheckCircle2 className={styles.successIcon} />
        <p style={{ margin: 0 }}>{message}</p>
      </div>
    );
  }

  return (
    <form id={id} onSubmit={handleSubmit} className={styles.formContainer}>
      <div className={styles.inputWrapper}>
        <input
          type="email"
          placeholder="Enter your email"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          required
          className="input"
          disabled={status === 'loading'}
          style={{ flexGrow: 1 }}
        />
        <button 
          type="submit" 
          className="btn btn-primary"
          disabled={status === 'loading'}
          style={{ flexShrink: 0 }}
        >
          {status === 'loading' ? 'Joining...' : 'Get Early Access'}
          {status !== 'loading' && <ArrowRight className={styles.icon} size={20} />}
        </button>
      </div>
      {status === 'error' && <p className={styles.errorMessage} style={{ margin: 0 }}>{message}</p>}
      <p className={styles.trustText}>
        {registeredCount !== null && registeredCount > 0
          ? `No spam. Just launch updates. ${registeredCount} ${registeredCount === 1 ? 'rider' : 'riders'} already on the list.`
          : 'No spam. Just priority rollout updates.'}
      </p>
    </form>
  );
}
