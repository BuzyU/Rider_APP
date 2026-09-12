'use client';

import { motion } from 'framer-motion';
import { Mic, MicOff, PhoneOff, User } from 'lucide-react';
import WaitlistForm from './WaitlistForm';
import styles from './Hero.module.css';

export default function Hero() {
  return (
    <section className={styles.heroSection}>
      <div className="container">
        <div className={styles.heroGrid}>
          
          <div className={styles.content}>
            <motion.h1 
              className={`hero-title ${styles.title}`}
              initial={{ opacity: 0, y: 20 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ duration: 1, ease: [0.16, 1, 0.3, 1] }}
            >
              Tactical comms.<br />
              No compromise.
            </motion.h1>
            
            <motion.p 
              className={styles.subtitle}
              initial={{ opacity: 0, y: 20 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ duration: 1, delay: 0.2, ease: [0.16, 1, 0.3, 1] }}
            >
              Ride together. Talk without limits. No phone holding, no app-locking, no brand walls.
            </motion.p>

            <motion.div
              initial={{ opacity: 0, y: 20 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ duration: 1, delay: 0.4, ease: [0.16, 1, 0.3, 1] }}
              className={styles.formWrapper}
            >
              <WaitlistForm id="hero-waitlist" />
            </motion.div>
          </div>

          <motion.div 
            className={styles.visualColumn}
            initial={{ opacity: 0, scale: 0.95 }}
            animate={{ opacity: 1, scale: 1 }}
            transition={{ duration: 1, delay: 0.3, ease: [0.16, 1, 0.3, 1] }}
          >
            <div className={styles.phoneMockup}>
              <div className={styles.dynamicIsland}></div>
              
              <div className={styles.appUI}>
                <div className={styles.appHeader}>
                  <span className={styles.time}>9:41</span>
                  <div className={styles.statusIcons}>
                    <div className={styles.signalIcon}></div>
                    <div className={styles.batteryIcon}></div>
                  </div>
                </div>

                <div className={styles.callContainer}>
                  <div className={styles.groupName}>Sunday Ride Convoy</div>
                  <div className={styles.callDuration}>01:24:10</div>

                  <div className={styles.avatarGrid}>
                    <div className={`${styles.avatarContainer} ${styles.speaking}`}>
                      <div className={styles.avatar}><User size={32} /></div>
                      <div className={styles.nameBadge}>You</div>
                    </div>
                    <div className={styles.avatarContainer}>
                      <div className={styles.avatar}><User size={32} /></div>
                      <div className={styles.nameBadge}>Alex</div>
                    </div>
                    <div className={styles.avatarContainer}>
                      <div className={styles.avatar}><User size={32} /></div>
                      <div className={styles.nameBadge}>Sam</div>
                      <div className={styles.mutedBadge}><MicOff size={12} /></div>
                    </div>
                    <div className={`${styles.avatarContainer} ${styles.speaking}`}>
                      <div className={styles.avatar}><User size={32} /></div>
                      <div className={styles.nameBadge}>Jordan</div>
                    </div>
                  </div>

                  <div className={styles.callControls}>
                    <div className={styles.controlBtn}><Mic size={24} /></div>
                    <div className={`${styles.controlBtn} ${styles.endCall}`}><PhoneOff size={24} /></div>
                  </div>
                </div>
              </div>
            </div>
          </motion.div>

        </div>
      </div>
    </section>
  );
}
