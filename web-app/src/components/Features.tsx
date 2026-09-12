'use client';

import { motion } from 'framer-motion';
import { Smartphone, Mic, User, Share } from 'lucide-react';
import styles from './Features.module.css';

export default function Features() {
  return (
    <>
      <section className="container">
        <div className={styles.bentoContainer}>
          <motion.div 
            className={`${styles.bentoCard} ${styles.cardWide}`}
            initial={{ opacity: 0, y: 20 }}
            whileInView={{ opacity: 1, y: 0 }}
            viewport={{ once: true, margin: "-50px" }}
            transition={{ duration: 0.6, ease: [0.16, 1, 0.3, 1] }}
          >
            <div className={styles.bentoText}>
              <h2 className={styles.bentoTitle}>Infinite Range VoIP.</h2>
              <p className={styles.bentoDesc}>
                Leave Bluetooth mesh limits behind. Our app uses cellular data to keep your convoy connected in crystal clear audio, whether they are 50 feet or 50 miles away.
              </p>
            </div>
            
            <div className={styles.microUI}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '2rem' }}>
                <div style={{ textAlign: 'center' }}>
                  <div style={{ width: 60, height: 60, borderRadius: '50%', background: 'var(--background)', border: '1px solid var(--border-color)', display: 'flex', alignItems: 'center', justifyContent: 'center', marginBottom: '10px' }}>
                    <User size={24} />
                  </div>
                  <span style={{ fontSize: '0.8rem', fontWeight: 600 }}>You</span>
                </div>
                
                <div style={{ display: 'flex', alignItems: 'center', color: 'var(--safety-orange)' }}>
                  <div style={{ width: 10, height: 10, borderRadius: '50%', background: 'currentColor' }}></div>
                  <div style={{ height: 2, width: 80, background: 'linear-gradient(90deg, var(--safety-orange), transparent)' }}></div>
                </div>

                <div style={{ textAlign: 'center' }}>
                  <div style={{ width: 60, height: 60, borderRadius: '50%', background: 'var(--background)', border: '1px solid var(--border-color)', display: 'flex', alignItems: 'center', justifyContent: 'center', marginBottom: '10px' }}>
                    <User size={24} />
                  </div>
                  <span style={{ fontSize: '0.8rem', fontWeight: 600 }}>Alex</span>
                  <div style={{ fontSize: '0.7rem', color: 'var(--text-secondary)' }}>50 miles</div>
                </div>
              </div>
            </div>
          </motion.div>

          <motion.div 
            className={styles.bentoCard}
            initial={{ opacity: 0, y: 20 }}
            whileInView={{ opacity: 1, y: 0 }}
            viewport={{ once: true, margin: "-50px" }}
            transition={{ duration: 0.6, delay: 0.1, ease: [0.16, 1, 0.3, 1] }}
          >
            <div className={styles.bentoText}>
              <h2 className={styles.bentoTitle}>The Universal Bridge.</h2>
              <p className={styles.bentoDesc}>
                Cardo, Sena, or AirPods. Connect them via Bluetooth, and we bridge the gap.
              </p>
            </div>
            <div className={styles.microUI}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '1rem', background: 'var(--background)', padding: '1rem', borderRadius: '16px', border: '1px solid var(--border-color)' }}>
                <Smartphone size={32} />
                <div style={{ display: 'flex', flexDirection: 'column' }}>
                  <span style={{ fontWeight: 600, fontSize: '0.9rem' }}>AirPods Pro</span>
                  <span style={{ color: 'var(--safety-orange)', fontSize: '0.8rem', fontWeight: 600 }}>Connected</span>
                </div>
              </div>
            </div>
          </motion.div>

          <motion.div 
            className={styles.bentoCard}
            initial={{ opacity: 0, y: 20 }}
            whileInView={{ opacity: 1, y: 0 }}
            viewport={{ once: true, margin: "-50px" }}
            transition={{ duration: 0.6, delay: 0.2, ease: [0.16, 1, 0.3, 1] }}
          >
            <div className={styles.bentoText}>
              <h2 className={styles.bentoTitle}>One-Tap Invites.</h2>
              <p className={styles.bentoDesc}>
                Drop a link in your group chat. iOS or Android, they tap and they&apos;re instantly in the ride.
              </p>
            </div>
            <div className={styles.microUI}>
              <div className={styles.shareSheet}>
                <div className={styles.shareAvatar}><Share size={24} /></div>
                <span style={{ fontWeight: 600, fontSize: '1rem' }}>Invite to Convoy</span>
                <span style={{ color: 'var(--text-secondary)', fontSize: '0.8rem', marginTop: '4px' }}>rider.app/join/xyz123</span>
                <div className={styles.shareBtn}>Copy Link</div>
              </div>
            </div>
          </motion.div>

          <motion.div 
            className={`${styles.bentoCard} ${styles.cardWide}`}
            initial={{ opacity: 0, y: 20 }}
            whileInView={{ opacity: 1, y: 0 }}
            viewport={{ once: true, margin: "-50px" }}
            transition={{ duration: 0.6, delay: 0.3, ease: [0.16, 1, 0.3, 1] }}
          >
            <div className={styles.bentoText}>
              <h2 className={styles.bentoTitle}>Lock and Ride.</h2>
              <p className={styles.bentoDesc}>
                The app runs silently in your pocket. Optimized for extreme background efficiency, it preserves your battery for the map and keeps you connected all day.
              </p>
            </div>
            <div className={styles.microUI}>
              <div className={styles.lockScreen}>
                <div className={styles.timeClock}>10:41</div>
                
                <div className={styles.liveActivity}>
                  <div className={styles.activityIcon}>
                    <Mic size={20} />
                  </div>
                  <div className={styles.activityText}>
                    <div className={styles.activityTitle}>Sunday Ride</div>
                    <div className={styles.activitySub}>3 Riders Active</div>
                  </div>
                  <div className={styles.activityWave}>
                    <div className={styles.waveBar}></div>
                    <div className={styles.waveBar}></div>
                    <div className={styles.waveBar}></div>
                    <div className={styles.waveBar}></div>
                  </div>
                </div>
              </div>
            </div>
          </motion.div>
        </div>
      </section>

      <section className={styles.secondaryFeatures}>
        <div className="container">
          <div className={styles.secondaryHeader}>
            <h2 className="section-title">And so much more.</h2>
          </div>
        </div>
      </section>
    </>
  );
}
