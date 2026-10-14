'use client';

import React, { useState } from 'react';
import {
  CheckCircle2,
  ArrowRight,
  Smartphone,
  Sparkles,
  ShieldCheck,
  Bike,
  Volume2,
  MailCheck
} from 'lucide-react';
import styles from './TesterApplication.module.css';

export default function TesterApplication() {
  const [viewState, setViewState] = useState<'prompt' | 'form' | 'success'>('prompt');
  
  // Form fields
  const [name, setName] = useState('');
  const [email, setEmail] = useState('');
  const [phone, setPhone] = useState('');
  const [deviceModel, setDeviceModel] = useState('');
  const [motorcycleModel, setMotorcycleModel] = useState('');
  const [experienceNotes, setExperienceNotes] = useState('');
  
  // State
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [errorMsg, setErrorMsg] = useState('');

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!name.trim() || !email.trim()) {
      setErrorMsg('Please enter both your name and email address.');
      return;
    }

    setIsSubmitting(true);
    setErrorMsg('');

    try {
      const res = await fetch('/api/tester-application', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          name: name.trim(),
          email: email.trim(),
          phone: phone.trim() || undefined,
          deviceModel: deviceModel.trim() || undefined,
          motorcycleModel: motorcycleModel.trim() || undefined,
          experienceNotes: experienceNotes.trim() || undefined,
        }),
      });

      const data = await res.json();

      if (!res.ok) {
        throw new Error(data.error || 'Failed to submit application. Please try again.');
      }

      setViewState('success');
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'Something went wrong. Please try again.';
      setErrorMsg(msg);
    } finally {
      setIsSubmitting(false);
    }
  };

  const resetForm = () => {
    setName('');
    setEmail('');
    setPhone('');
    setDeviceModel('');
    setMotorcycleModel('');
    setExperienceNotes('');
    setErrorMsg('');
    setViewState('prompt');
  };

  return (
    <div className={styles.container}>
      <div className={styles.card}>
        
        {/* VIEW 1: PROMPT / INITIAL OPTION TO APPLY */}
        {viewState === 'prompt' && (
          <div className={styles.promptView}>
            <span className={styles.badge}>
              <Sparkles size={14} /> Closed Beta • Priority Waiting List
            </span>

            <h3 className={styles.promptTitle}>
              Apply for Priority Access
            </h3>

            <p className={styles.promptDesc}>
              Join fellow riders testing hands-free VOX audio, Bluetooth helmet integration, and low-latency group channels in real riding conditions.
            </p>

            <div className={styles.featurePills}>
              <span className={styles.pill}><Volume2 size={15} /> Hands-Free VOX</span>
              <span className={styles.pill}><Bike size={15} /> Bluetooth Intercom Bridge</span>
              <span className={styles.pill}><Smartphone size={15} /> Android Universal Build</span>
              <span className={styles.pill}><ShieldCheck size={15} /> Low-Latency Audio</span>
            </div>

            <button
              type="button"
              className={`btn btn-primary ${styles.ctaBtn}`}
              onClick={() => setViewState('form')}
            >
              Apply for Priority Access
              <ArrowRight size={18} />
            </button>
          </div>
        )}

        {/* VIEW 2: TESTER APPLICATION FORM */}
        {viewState === 'form' && (
          <div>
            <div className={styles.formHeader}>
              <span className={styles.badge}>
                <Sparkles size={14} /> Tester Application
              </span>
              <h3 className={styles.formTitle}>Apply for Priority Beta Access</h3>
              <p className={styles.formDesc}>
                Submit your details below to join the priority waiting list. Invitation waves are scheduled sequentially for testing squads.
              </p>
            </div>

            <form onSubmit={handleSubmit} className={styles.formGrid}>
              <div className={styles.twoCol}>
                <div className={styles.fieldGroup}>
                  <label htmlFor="tester-name" className={styles.label}>
                    Full Name <span style={{ color: 'var(--safety-orange)' }}>*</span>
                  </label>
                  <input
                    id="tester-name"
                    type="text"
                    required
                    placeholder="e.g. Alex Turner"
                    value={name}
                    onChange={(e) => setName(e.target.value)}
                    className={styles.formInput}
                    disabled={isSubmitting}
                  />
                </div>

                <div className={styles.fieldGroup}>
                  <label htmlFor="tester-email" className={styles.label}>
                    Email Address <span style={{ color: 'var(--safety-orange)' }}>*</span>
                  </label>
                  <input
                    id="tester-email"
                    type="email"
                    required
                    placeholder="e.g. alex@example.com"
                    value={email}
                    onChange={(e) => setEmail(e.target.value)}
                    className={styles.formInput}
                    disabled={isSubmitting}
                  />
                </div>
              </div>

              <div className={styles.twoCol}>
                <div className={styles.fieldGroup}>
                  <label htmlFor="tester-phone" className={styles.label}>
                    Phone / WhatsApp <span className={styles.optionalTag}>Optional</span>
                  </label>
                  <input
                    id="tester-phone"
                    type="tel"
                    placeholder="e.g. +1 555-0199 (for convoy test alerts)"
                    value={phone}
                    onChange={(e) => setPhone(e.target.value)}
                    className={styles.formInput}
                    disabled={isSubmitting}
                  />
                </div>

                <div className={styles.fieldGroup}>
                  <label htmlFor="tester-device" className={styles.label}>
                    Android Device Model <span className={styles.optionalTag}>Optional</span>
                  </label>
                  <input
                    id="tester-device"
                    type="text"
                    placeholder="e.g. Samsung S23, Pixel 8, OnePlus 11"
                    value={deviceModel}
                    onChange={(e) => setDeviceModel(e.target.value)}
                    className={styles.formInput}
                    disabled={isSubmitting}
                  />
                </div>
              </div>

              <div className={styles.fieldGroup}>
                <label htmlFor="tester-bike" className={styles.label}>
                  Motorcycle & Helmet Intercom <span className={styles.optionalTag}>Optional</span>
                </label>
                <input
                  id="tester-bike"
                  type="text"
                  placeholder="e.g. Yamaha MT-09 / Sena 50S, Cardo Packtalk, or earbuds"
                  value={motorcycleModel}
                  onChange={(e) => setMotorcycleModel(e.target.value)}
                  className={styles.formInput}
                  disabled={isSubmitting}
                />
              </div>

              <div className={styles.fieldGroup}>
                <label htmlFor="tester-notes" className={styles.label}>
                  Feedback & Test Notes <span className={styles.optionalTag}>Optional</span>
                </label>
                <textarea
                  id="tester-notes"
                  placeholder="Any routes you ride, squad size, or specific features you'd like to test?"
                  value={experienceNotes}
                  onChange={(e) => setExperienceNotes(e.target.value)}
                  className={`${styles.formInput} ${styles.textarea}`}
                  disabled={isSubmitting}
                />
              </div>

              {errorMsg && (
                <div className={styles.errorBanner}>
                  {errorMsg}
                </div>
              )}

              <div className={styles.formActions}>
                <button
                  type="submit"
                  disabled={isSubmitting}
                  className="btn btn-primary"
                  style={{ minWidth: '220px' }}
                >
                  {isSubmitting ? 'Submitting...' : 'Submit Application'}
                  {!isSubmitting && <ArrowRight size={18} style={{ marginLeft: '8px' }} />}
                </button>
                <button
                  type="button"
                  disabled={isSubmitting}
                  className={styles.cancelBtn}
                  onClick={() => setViewState('prompt')}
                >
                  Back
                </button>
              </div>
            </form>
          </div>
        )}

        {/* VIEW 3: SUCCESS STATE - PRIORITY WAITLIST CONFIRMED */}
        {viewState === 'success' && (
          <div className={styles.successLayer}>
            <div className={styles.successIconWrapper}>
              <CheckCircle2 size={44} />
            </div>

            <h3 className={styles.successTitle}>
              Application Received!
            </h3>

            <p className={styles.successDesc}>
              Thank you, <strong>{name}</strong>! Your application has been logged and your spot is confirmed on our priority waiting list. 
              A confirmation email has been dispatched to <strong>{email}</strong>.
            </p>

            <div className={styles.confirmationBadge}>
              <MailCheck size={18} />
              <span>Priority Waitlist Spot Confirmed</span>
            </div>

            {/* Next Steps Information Card */}
            <div className={styles.installStepsCard}>
              <div className={styles.installTitle}>
                <Sparkles size={18} style={{ color: 'var(--safety-orange)' }} />
                <span>What Happens Next?</span>
              </div>
              <div className={styles.stepList}>
                <div className={styles.stepItem}>
                  <div className={styles.stepBadge}>1</div>
                  <div>
                    <strong>Rider Profile Queued:</strong> Your hardware specifications and convoy notes have been added to our wave scheduler.
                  </div>
                </div>
                <div className={styles.stepItem}>
                  <div className={styles.stepBadge}>2</div>
                  <div>
                    <strong>Sequential Wave Rollout:</strong> To preserve intercom voice clarity and maintain &lt; 50ms voice latency, we onboard riders in staged batches.
                  </div>
                </div>
                <div className={styles.stepItem}>
                  <div className={styles.stepBadge}>3</div>
                  <div>
                    <strong>Direct Access Notification:</strong> As soon as your squad&apos;s testing wave is unlocked, you will receive an invitation email with full setup instructions.
                  </div>
                </div>
              </div>
            </div>

            <button
              type="button"
              className={styles.resetBtn}
              onClick={resetForm}
            >
              Submit another application
            </button>
          </div>
        )}

      </div>
    </div>
  );
}

