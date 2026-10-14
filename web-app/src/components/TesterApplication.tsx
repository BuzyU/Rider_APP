'use client';

import React, { useState, useEffect, useRef } from 'react';
import {
  Download,
  CheckCircle2,
  ArrowRight,
  Smartphone,
  Sparkles,
  ShieldCheck,
  Copy,
  Check,
  ExternalLink,
  Bike,
  Volume2
} from 'lucide-react';
import styles from './TesterApplication.module.css';

interface ReleaseInfo {
  version: string;
  releaseName: string;
  fileName: string;
  downloadUrl: string;
  releasePageUrl: string;
  sha256: string;
  fileSize: string;
  targetArch?: string;
}

const FALLBACK_RELEASE: ReleaseInfo = {
  version: 'v0.0.3.7',
  releaseName: 'RiderVoice v0.0.3.7',
  fileName: 'Rider_APP-v0.0.3.7.apk',
  downloadUrl: 'https://github.com/BuzyU/Rider_APP/releases/download/v0.0.3.7/Rider_APP-v0.0.3.7.apk',
  releasePageUrl: 'https://github.com/BuzyU/Rider_APP/releases/tag/v0.0.3.7',
  sha256: '202a9b0c461dc405625ba6195aa3ad01878028a1cc85876794fe96279e6d8825',
  fileSize: '~50 MB',
  targetArch: 'Universal ARM'
};

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
  const [releaseInfo, setReleaseInfo] = useState<ReleaseInfo>(FALLBACK_RELEASE);
  const [countdown, setCountdown] = useState<number>(2);
  const [hasCopiedHash, setHasCopiedHash] = useState(false);
  const [autoDownloadStarted, setAutoDownloadStarted] = useState(false);
  
  const autoDownloadTriggered = useRef(false);

  // Trigger download helper
  const triggerDownload = (url: string, filename: string) => {
    try {
      const link = document.createElement('a');
      link.href = url;
      link.setAttribute('download', filename);
      link.setAttribute('target', '_blank');
      link.setAttribute('rel', 'noopener noreferrer');
      document.body.appendChild(link);
      link.click();
      document.body.removeChild(link);
    } catch {
      window.location.assign(url);
    }
  };

  // Auto-download countdown upon reaching 'success' state
  useEffect(() => {
    if (viewState !== 'success') return;
    autoDownloadTriggered.current = false;
    setCountdown(2);
    setAutoDownloadStarted(false);

    const interval = setInterval(() => {
      setCountdown((prev) => {
        if (prev <= 1) {
          clearInterval(interval);
          if (!autoDownloadTriggered.current) {
            autoDownloadTriggered.current = true;
            triggerDownload(releaseInfo.downloadUrl, releaseInfo.fileName);
            setAutoDownloadStarted(true);
          }
          return 0;
        }
        return prev - 1;
      });
    }, 1000);

    return () => clearInterval(interval);
  }, [viewState, releaseInfo]);

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

      if (data.release) {
        setReleaseInfo(data.release);
      }

      setViewState('success');
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'Something went wrong. Please try again.';
      setErrorMsg(msg);
    } finally {
      setIsSubmitting(false);
    }
  };

  const copyChecksum = () => {
    navigator.clipboard.writeText(releaseInfo.sha256);
    setHasCopiedHash(true);
    setTimeout(() => setHasCopiedHash(false), 2500);
  };

  return (
    <div className={styles.container}>
      <div className={styles.card}>
        
        {/* VIEW 1: PROMPT / INITIAL OPTION TO APPLY */}
        {viewState === 'prompt' && (
          <div className={styles.promptView}>
            <span className={styles.badge}>
              <Sparkles size={14} /> Android Closed Beta • {releaseInfo.version} Ready
            </span>

            <h3 className={styles.promptTitle}>
              Test RiderVoice with Your Squad
            </h3>

            <p className={styles.promptDesc}>
              Our high-reliability Android APK is built and ready for closed field testing. 
              Apply below to test hands-free VOX comms, mesh audio relay, and real-time convoy telemetry on your bike.
            </p>

            <div className={styles.featurePills}>
              <span className={styles.pill}><Volume2 size={15} /> VOX Hands-Free</span>
              <span className={styles.pill}><Bike size={15} /> Universal Bluetooth Intercom</span>
              <span className={styles.pill}><Smartphone size={15} /> Android Universal APK</span>
              <span className={styles.pill}><ShieldCheck size={15} /> Zero Cloud Lock-In</span>
            </div>

            <button
              type="button"
              className={`btn btn-primary ${styles.ctaBtn}`}
              onClick={() => setViewState('form')}
            >
              Apply for Test & Download APK
              <ArrowRight size={18} />
            </button>
          </div>
        )}

        {/* VIEW 2: TESTER APPLICATION FORM */}
        {viewState === 'form' && (
          <div>
            <div className={styles.formHeader}>
              <span className={styles.badge}>
                <Sparkles size={14} /> Tester Application Form
              </span>
              <h3 className={styles.formTitle}>Apply for Tester Access</h3>
              <p className={styles.formDesc}>
                Fill out the quick form below. Upon submission, your download of <strong>{releaseInfo.fileName}</strong> will start immediately.
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
                  {isSubmitting ? 'Registering...' : 'Submit & Download APK'}
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

        {/* VIEW 3: SUCCESS & DOWNLOAD LAYER */}
        {viewState === 'success' && (
          <div className={styles.successLayer}>
            <div className={styles.successIconWrapper}>
              <CheckCircle2 size={42} />
            </div>

            <h3 className={styles.successTitle}>
              Application Complete! Your APK is Ready
            </h3>

            <p className={styles.successDesc}>
              Thank you, <strong>{name}</strong>! You have been granted access to the RiderVoice tester squad. 
              Install the APK build below and hit the road with your group.
            </p>

            <div className={styles.autoDownloadNotice}>
              <Download size={16} />
              {countdown > 0 ? (
                <span>Auto-download starting in <strong>{countdown}s</strong>...</span>
              ) : (
                <span>{autoDownloadStarted ? 'Auto-download triggered!' : 'Download started!'} If it didn&apos;t start, click below.</span>
              )}
            </div>

            <div className={styles.downloadActions}>
              <a
                href={releaseInfo.downloadUrl}
                download={releaseInfo.fileName}
                className={styles.primaryDownloadBtn}
                onClick={() => triggerDownload(releaseInfo.downloadUrl, releaseInfo.fileName)}
              >
                <Download size={20} />
                Download APK ({releaseInfo.version})
              </a>

              <a
                href={releaseInfo.releasePageUrl}
                target="_blank"
                rel="noopener noreferrer"
                className={styles.secondaryReleaseBtn}
              >
                <ExternalLink size={18} />
                View on GitHub Releases
              </a>
            </div>

            {/* Android Install Instructions Card */}
            <div className={styles.installStepsCard}>
              <div className={styles.installTitle}>
                <Smartphone size={18} style={{ color: 'var(--safety-orange)' }} />
                <span>3-Step Android Installation Guide</span>
              </div>
              <div className={styles.stepList}>
                <div className={styles.stepItem}>
                  <div className={styles.stepBadge}>1</div>
                  <div>
                    <strong>Download APK:</strong> Save <code>{releaseInfo.fileName}</code> ({releaseInfo.fileSize}) to your Android device.
                  </div>
                </div>
                <div className={styles.stepItem}>
                  <div className={styles.stepBadge}>2</div>
                  <div>
                    <strong>Allow Unknown Sources:</strong> Tap the downloaded file notification. If prompted, tap <em>Settings</em> and enable <em>&quot;Allow from this source&quot;</em>.
                  </div>
                </div>
                <div className={styles.stepItem}>
                  <div className={styles.stepBadge}>3</div>
                  <div>
                    <strong>Connect & Ride:</strong> Open RiderVoice, create or join a ride room, and enjoy crystal-clear VOX tactical intercom!
                  </div>
                </div>
              </div>
            </div>

            {/* Checksum Card */}
            <div className={styles.checksumCard}>
              <span className={styles.checksumText} title={releaseInfo.sha256}>
                SHA-256: {releaseInfo.sha256}
              </span>
              <button
                type="button"
                className={styles.copyBtn}
                onClick={copyChecksum}
              >
                {hasCopiedHash ? <Check size={14} style={{ color: '#10b981' }} /> : <Copy size={14} />}
                {hasCopiedHash ? 'Copied' : 'Copy'}
              </button>
            </div>

            <button
              type="button"
              className={styles.resetBtn}
              onClick={() => setViewState('prompt')}
            >
              Submit another tester application
            </button>
          </div>
        )}

      </div>
    </div>
  );
}

